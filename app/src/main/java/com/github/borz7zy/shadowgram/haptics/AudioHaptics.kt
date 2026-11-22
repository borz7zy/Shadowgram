/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.borz7zy.rustcore.RustHaptics
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.notifications.NotificationSoundChannels
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AudioHaptics(private val app: ShadowgramApplication) : SharedPreferences.OnSharedPreferenceChangeListener {
    val context: Context get() = app
    var currentMedia: (suspend () -> HapticAudioSource?)? = null
    val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)
    val databaseFile = File(app.noBackupFilesDir, "shadowgram_haptics.db")
    val storage by lazy { RustHaptics(databaseFile) }
    val vibrator by lazy { HapticVibrator(app) }
    private val manager by lazy { app.getSystemService(NotificationManager::class.java) }
    private val workers by lazy { WorkManager.getInstance(app) }
    private val prepareLock = Mutex()
    private val notificationLock = Mutex()
    private val notificationChannels by lazy { NotificationHapticChannels(app, manager) }
    private val notificationRefreshes = ConcurrentHashMap<String, Long>()

    fun enabled(key: String) = preferences.getBoolean(ENABLED, false) && preferences.getBoolean(key, true)
    fun profile() = preferences.getString(PROFILE, "1")?.toIntOrNull()?.coerceIn(0, 2) ?: 1
    fun intensity() = preferences.getInt(INTENSITY, 75).coerceIn(0, 100)
    fun offset() = preferences.getInt(OFFSET, 200).coerceIn(0, 400) - 200
    fun limit() = preferences.getString(LIMIT, "32")?.toIntOrNull()?.coerceIn(4, 256) ?: 32

    fun init() {
        preferences.registerOnSharedPreferenceChangeListener(this)
        app.contentResolver.registerContentObserver(
            Settings.System.DEFAULT_NOTIFICATION_URI, false,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { index() }
            },
        )
        index(checkSources = true)
        app.backgroundScope.launch(Dispatchers.IO) {
            while (true) {
                delay(60_000)
                if (preferences.getBoolean(ENABLED, false)) safely("update haptic cache usage") { storage.flushUsage() }
            }
        }
    }

    fun index(checkSources: Boolean = false) = app.backgroundScope.launch(Dispatchers.IO) {
        safely("index") {
            if (preferences.getBoolean(ENABLED, false)) {
                refreshNotifications(enqueue = true)
                val version = storage.version()
                val upgrade = preferences.getInt(INDEX_VERSION, 0) != version
                if (checkSources || upgrade) reindexSources(force = upgrade)
                if (upgrade) preferences.edit().putInt(INDEX_VERSION, version).apply()
            }
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        when (key) {
            ENABLED, NOTIFICATIONS, AUTOMATIC -> index()
            PROFILE, INTENSITY, OFFSET -> app.backgroundScope.launch(Dispatchers.IO) {
                safely("refresh notification haptics") { refreshNotifications(enqueue = false) }
            }
            LIMIT -> app.backgroundScope.launch(Dispatchers.IO) {
                safely("apply haptic storage limit") { storage.clean(limit(), false) }
            }
        }
    }

    suspend fun request(source: HapticAudioSource, pinned: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        prepareLock.withLock {
            val revision = source.revision(app) ?: return@withLock false
            val input = workDataOf(
                "key" to source.key, "uri" to source.uri.toString(), "revision" to revision,
            )
            if (storage.register(source.key, revision, source.uri.toString(), pinned) == READY) return@withLock true
            val request = OneTimeWorkRequestBuilder<HapticGenerationWorker>()
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()
            workers.enqueueUniqueWork(workName(source, revision), ExistingWorkPolicy.KEEP, request).result.await()
            true
        }
    }

    suspend fun stopPreparing(source: HapticAudioSource) = withContext(Dispatchers.IO) {
        val revision = source.revision(app) ?: return@withContext
        storage.cancel(source.key, revision)
        workers.cancelUniqueWork(workName(source, revision))
    }

    private fun sourceForChannel(channel: NotificationChannel): HapticAudioSource? {
        val sound = channel.sound ?: return null
        if (channel.id == NotificationSoundChannels.DEFAULT) return HapticAudioSource.notification(app, sound)
        if (!channel.id.startsWith("telegram_sound_")) return null
        val file = NotificationSoundChannels.fileFor(app, channel.id)
        // A user may have selected a different sound for the channel in Android settings.
        return if (sound.authority == "${app.packageName}.files" && sound.lastPathSegment == file.name) {
            HapticAudioSource.telegram(channel.id, file)
        } else {
            val actual = HapticAudioSource.notification(app, sound)?.uri ?: return null
            HapticAudioSource("telegram-sound:${channel.id}", actual)
        }
    }

    fun defaultNotificationSource(): HapticAudioSource? {
        val channel = manager.getNotificationChannel(NotificationSoundChannels.DEFAULT) ?: run {
            NotificationSoundChannels.ensureDefaultChannel(app)
            manager.getNotificationChannel(NotificationSoundChannels.DEFAULT)
        }
        return channel?.let(::sourceForChannel)
    }

    fun notificationStatus(): Int {
        if (!enabled(NOTIFICATIONS)) return 0
        val source = defaultNotificationSource() ?: return 0
        val revision = source.revision(app) ?: return 0
        return storage.status(source.key, revision)
    }

    fun scheduleNotificationSound(channelId: String) {
        if (!enabled(NOTIFICATIONS)) return
        val now = SystemClock.elapsedRealtime()
        var refresh = false
        notificationRefreshes.compute(channelId) { _, previous ->
            refresh = previous == null || now - previous >= 10_000
            if (refresh) now else previous
        }
        if (!refresh) return
        app.backgroundScope.launch(Dispatchers.IO) {
            safely("prepare notification haptics") {
                val channel = manager.getNotificationChannel(channelId) ?: return@safely
                updateNotification(channel, enqueue = true)
            }
        }
    }

    private suspend fun refreshNotifications(enqueue: Boolean) {
        if (!enabled(NOTIFICATIONS)) {
            notificationChannels.clear()
            return
        }
        NotificationSoundChannels.ensureDefaultChannel(app)
        for (channel in manager.notificationChannels) {
            if (channel.id == NotificationSoundChannels.DEFAULT || channel.id.startsWith("telegram_sound_")) {
                updateNotification(channel, enqueue)
            }
        }
    }

    private suspend fun updateNotification(channel: NotificationChannel, enqueue: Boolean) = notificationLock.withLock {
        if (!enabled(NOTIFICATIONS) || !notificationChannels.canAlert(channel)) return@withLock
        val source = sourceForChannel(channel)
        val revision = source?.revision(app)
        val useBinary = useBinaryNotification()
        val templateRevision = revision?.let { "${storage.version()}:$useBinary:$it" }
        val profile = profile()
        val intensity = intensity()
        val offset = offset()
        val settings = "$profile:$intensity:$offset"
        val automatic = enqueue && preferences.getBoolean(AUTOMATIC, true)
        if (source != null && templateRevision != null && notificationChannels.hasPrepared(channel, settings, templateRevision)) {
            if (automatic) request(source, pinned = true)
            return@withLock
        }
        val frames = if (source != null && revision != null) storage.lookup(source.key, revision, profile) else null
        if (intensity > 0 && vibrator.available) {
            val binary = frames?.let { RustHaptics.notificationPlan(it, offset, intensity, basic = true) }
            val amplitude = if (!useBinary && frames != null) {
                RustHaptics.notificationPlan(frames, offset, intensity, basic = false)
            } else null
            val effect = amplitude?.let(vibrator::effect)
            val timings = binary?.let(HapticVibrator::onOff)
            val variant = "notification-waveform-v3:${(amplitude ?: binary).contentHashCode()}:${timings.contentHashCode()}"
            notificationChannels.publish(channel, settings, variant, effect, timings, templateRevision.takeIf { frames != null })
        }
        if (automatic && source != null && revision != null &&
            (frames != null || storage.status(source.key, revision) == 0)
        ) {
            request(source, pinned = true)
        }
    }

    fun notificationChannel(baseId: String): String {
        if (!enabled(NOTIFICATIONS) || intensity() == 0 || !vibrator.available) return baseId
        val base = manager.getNotificationChannel(baseId) ?: return baseId
        return try {
            notificationChannels.select(base, notificationSettings()) { scheduleNotificationSound(baseId) }
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to configure notification vibration", error)
            baseId
        }
    }

    /** An explicit preview may wait for preparation before it starts audio. */
    suspend fun previewEffect(source: HapticAudioSource, onPreparing: () -> Unit): VibrationEffect? {
        if (!enabled(NOTIFICATIONS) || intensity() == 0 || !vibrator.available) return null
        val profile = profile()
        var effect: VibrationEffect? = null
        safely("prepare notification sound preview") {
            val frames = withContext(Dispatchers.IO) {
                val revision = source.revision(app) ?: return@withContext null
                storage.lookup(source.key, revision, profile)?.let { return@withContext it }
                withContext(Dispatchers.Main) { onPreparing() }
                if (!request(source)) return@withContext null
                withTimeoutOrNull(8_000) {
                    workers.getWorkInfosForUniqueWorkFlow(workName(source, revision))
                        .first { work -> work.all { it.state.isFinished } }
                }
                if (source.revision(app) == revision) storage.lookup(source.key, revision, profile) else null
            }
            if (frames != null) {
                effect = withContext(Dispatchers.Default) {
                    val plan = RustHaptics.notificationPlan(frames, offset(), intensity(), useBinaryNotification())
                    vibrator.effect(plan)
                }
                app.backgroundScope.launch(Dispatchers.IO) {
                    safely("publish previewed notification haptics") { onGenerated(source) }
                }
            }
        }
        val intensity = intensity()
        if (!enabled(NOTIFICATIONS) || intensity == 0) return null
        return effect ?: vibrator.effect(intArrayOf(65, intensity * 255 / 100))
    }

    private fun notificationSettings() = "${profile()}:${intensity()}:${offset()}"

    private fun useBinaryNotification() = Build.VERSION.SDK_INT < 35 || vibrator.basic

    suspend fun onGenerated(source: HapticAudioSource) {
        val channel = when {
            source.key == HapticAudioSource.DEFAULT_NOTIFICATION -> NotificationSoundChannels.DEFAULT
            source.key.startsWith("telegram-sound:") -> source.key.removePrefix("telegram-sound:")
            else -> return
        }
        manager.getNotificationChannel(channel)?.let { updateNotification(it, enqueue = false) }
    }

    private suspend fun reindexSources(force: Boolean) {
        for ((key, uri, pinned, _) in storage.sources().toList().chunked(4)) {
            val source = when {
                key == HapticAudioSource.DEFAULT_NOTIFICATION ->
                    manager.getNotificationChannel(NotificationSoundChannels.DEFAULT)?.let(::sourceForChannel)
                key.startsWith("telegram-sound:") ->
                    manager.getNotificationChannel(key.removePrefix("telegram-sound:"))?.let(::sourceForChannel)
                        ?: HapticAudioSource(key, uri.toUri())
                else -> HapticAudioSource(key, uri.toUri())
            }
            if (source != null) {
                val revision = source.revision(app) ?: continue
                val status = storage.status(key, revision)
                if (force || status in 0..5) request(source, pinned == "1")
            } else if (pinned == "1") storage.unpin(key)
        }
    }

    suspend fun rebuild() = withContext(Dispatchers.IO) {
        prepareLock.withLock {
            workers.cancelAllWorkByTag(WORK_TAG).result.await()
            try {
                storage.invalidate()
            } catch (_: SQLiteDatabaseCorruptException) {
                storage.recover()
            }
        }
        notificationLock.withLock { notificationChannels.invalidatePreparedRevisions() }
        reindexSources(force = true)
        refreshNotifications(enqueue = true)
    }

    suspend fun safely(operation: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Unable to $operation", error)
        }
    }

    private fun workName(source: HapticAudioSource, revision: String) =
        "haptics:${digest("${source.key}|$revision")}"

    companion object {
        const val ENABLED = "audio_haptics_enabled"
        const val NOTIFICATIONS = "audio_haptics_notifications"
        const val MEDIA = "audio_haptics_media"
        const val PROFILE = "audio_haptics_profile"
        const val INTENSITY = "audio_haptics_intensity"
        const val OFFSET = "audio_haptics_offset"
        const val AUTOMATIC = "audio_haptics_automatic"
        const val LIMIT = "audio_haptics_limit"
        const val READY = 6
        private const val INDEX_VERSION = "audio_haptics_index_version"
        private const val WORK_TAG = "shadowgram-audio-haptics"
        private const val TAG = "AudioHaptics"

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}

/** WorkManager persists the scheduling request. Rust persists the job state and owns the PCM. */
class HapticGenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        decoderLock.withLock { generate() }
    }

    private suspend fun generate(): Result {
        val app = applicationContext as ShadowgramApplication
        val haptics = app.audioHaptics
        val key = inputData.getString("key") ?: return Result.failure()
        val uri = inputData.getString("uri")?.toUri() ?: return Result.failure()
        val revision = inputData.getString("revision") ?: return Result.failure()
        val source = HapticAudioSource(key, uri)
        val token = id.toString()
        val storage = haptics.storage
        var handle = 0L
        try {
            if (!storage.claim(key, revision, token, runAttemptCount)) return Result.success()
            if (source.revision(app) != revision) {
                storage.state(key, revision, token, 8)
                return Result.failure()
            }
            var currentFormat: Triple<Int, Int, Int>? = null
            HapticPcmDecoder.decode(app, uri, { rate, channels, encoding ->
                val actual = Triple(rate, channels, encoding)
                if (currentFormat != actual) {
                    if (handle != 0L) throw IOException("PCM format changed during decode")
                    handle = storage.begin(rate, channels, encoding)
                    if (handle == 0L) throw IOException("Unsupported PCM format")
                    currentFormat = actual
                    storage.state(key, revision, token, 3)
                }
            }, { buffer, offset, size ->
                if (handle == 0L || !storage.feed(handle, buffer, offset, size)) {
                    throw IOException("Invalid or oversized PCM stream")
                }
            })
            if (source.revision(app) != revision) {
                storage.state(key, revision, token, 8)
                return Result.failure()
            }
            if (!storage.save(handle, key, revision, token, haptics.limit())) {
                storage.state(key, revision, token, 7)
                return Result.failure()
            }
            haptics.safely("activate prepared notification haptics") { haptics.onGenerated(source) }
            return Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager may restart stopped work. Explicit cancellation already updates SQLite.
            runCatching { storage.state(key, revision, token, 1) }
            throw cancelled
        } catch (error: Exception) {
            Log.w("HapticGeneration", "Could not prepare vibration", error)
            val retry = error is IOException && runAttemptCount < 2
            runCatching { storage.state(key, revision, token, if (retry) 1 else 7) }
            return if (retry) Result.retry() else Result.failure()
        } finally {
            if (handle != 0L) storage.release(handle)
        }
    }

    companion object {
        private val decoderLock = Mutex()
    }
}

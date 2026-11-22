/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import com.github.borz7zy.shadowgram.R
import java.security.MessageDigest

/** Android owns both alert outputs. Channel selection never opens an audio file or the pattern DB. */
internal class NotificationHapticChannels(
    private val context: Context,
    private val manager: NotificationManager,
) {
    private val preferences = context.getSharedPreferences("audio_haptics_channels", Context.MODE_PRIVATE)
    private val fallbacks = mutableMapOf<String, Selection>()

    private data class Selection(val id: String, val configuration: String)

    fun canAlert(base: NotificationChannel) =
        base.importance >= NotificationManager.IMPORTANCE_DEFAULT && base.sound != null

    @Synchronized
    fun select(base: NotificationChannel, settings: String, onMissing: () -> Unit = {}): String {
        if (!canAlert(base)) return base.id
        userSelection(base)?.let { return it.id }
        val configuration = configuration(base, settings)
        // Android keeps the prepared channel across process restarts, including cold FCM starts.
        preparedChannel(base, configuration)?.let { return it }
        onMissing()
        if (base.shouldVibrate()) return base.id
        fallbacks[base.id]?.takeIf { it.configuration == configuration }?.let { selection ->
            if (manager.getNotificationChannel(selection.id) != null) return selection.id
        }
        return create(base, FALLBACK, null, null).also { id ->
            remember(base, id, null)
            fallbacks[base.id] = Selection(id, configuration)
        }
    }

    @Synchronized
    fun hasPrepared(base: NotificationChannel, settings: String, sourceRevision: String? = null): Boolean {
        if (sourceRevision != null && preferences.getString("${base.id}:prepared_revision", null) != sourceRevision) {
            return false
        }
        return preparedChannel(base, configuration(base, settings)) != null
    }

    private fun preparedChannel(base: NotificationChannel, configuration: String): String? {
        if (preferences.getString("${base.id}:prepared_config", null) != configuration) return null
        val id = preferences.getString("${base.id}:prepared_id", null) ?: return null
        if (id == base.id) return id.takeIf { base.shouldVibrate() }
        return id.takeIf { manager.getNotificationChannel(it)?.let(::hasWaveform) == true }
    }

    @Synchronized
    fun clear() = fallbacks.clear()

    @Synchronized
    fun invalidatePreparedRevisions() {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.endsWith(":prepared_revision") }.forEach { editor.remove(it) }
        editor.apply()
    }

    @Synchronized
    fun publish(
        base: NotificationChannel, settings: String, variant: String, effect: VibrationEffect?, timings: LongArray?,
        sourceRevision: String? = null,
    ) {
        if (!canAlert(base) || userSelection(base) != null) return
        val hasPulse = timings?.hasPulse() == true
        val configuration = configuration(base, settings)
        // Some versions discard unsupported effects. Keep generated timings as a compatible variant.
        val waveform = createWaveformChannel(base, "$configuration:$variant", effect, timings?.takeIf { hasPulse })
        // A decoded sound without usable pulses still has a final channel for this revision.
        val prepared = waveform != null || sourceRevision != null
        val id = waveform ?: if (base.shouldVibrate()) base.id else create(base, FALLBACK, null, null)
        remember(base, id, configuration.takeIf { prepared }, sourceRevision, clearPrepared = !prepared)
        if (!prepared && id != base.id) fallbacks[base.id] = Selection(id, configuration)
        else fallbacks.remove(base.id)
    }

    private fun configuration(base: NotificationChannel, settings: String): String {
        val sound = base.sound
        val actualSound = if (sound != null && RingtoneManager.isDefault(sound)) {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        } else sound
        return "$settings:${base.importance}:$sound:$actualSound"
    }

    private fun userSelection(base: NotificationChannel): NotificationChannel? {
        val id = preferences.getString(base.id, null) ?: return null
        val previous = manager.getNotificationChannel(id) ?: return null
        return previous.takeIf {
            it.shouldVibrate() != preferences.getBoolean("${base.id}:vibrates", true) ||
                it.hasUserSetSound() && it.sound != base.sound ||
                it.importance != preferences.getInt("${base.id}:importance", base.importance)
        }
    }

    private fun remember(
        base: NotificationChannel, id: String, prepared: String?, sourceRevision: String? = null,
        clearPrepared: Boolean = false,
    ) {
        val editor = preferences.edit()
        if (id == base.id) {
            editor.remove(base.id).remove("${base.id}:vibrates").remove("${base.id}:importance")
        } else {
            editor.putString(base.id, id).putBoolean("${base.id}:vibrates", true)
                .putInt("${base.id}:importance", base.importance)
        }
        if (prepared != null) {
            editor.putString("${base.id}:prepared_id", id).putString("${base.id}:prepared_config", prepared)
                .putString("${base.id}:prepared_revision", sourceRevision)
        } else if (clearPrepared) {
            editor.remove("${base.id}:prepared_id").remove("${base.id}:prepared_config")
                .remove("${base.id}:prepared_revision")
        }
        editor.apply()
    }

    private fun hasWaveform(channel: NotificationChannel): Boolean {
        if (!channel.shouldVibrate()) return false
        if (channel.vibrationPattern?.hasPulse() == true) return true
        return try {
            Build.VERSION.SDK_INT >= 35 && channel.vibrationEffect != null
        } catch (_: NoSuchMethodError) {
            false
        }
    }

    private fun LongArray.hasPulse() = (1 until size step 2).any { this[it] > 0L }

    private fun createWaveformChannel(
        base: NotificationChannel, variant: String, effect: VibrationEffect?, timings: LongArray?,
    ): String? {
        if (Build.VERSION.SDK_INT >= 35 && effect != null) {
            try {
                val candidate = create(base, "$variant:amplitude", effect, null)
                if (manager.getNotificationChannel(candidate)?.vibrationEffect == effect) return candidate
            } catch (_: NoSuchMethodError) {
                // The effect API can be absent on firmware where it is disabled.
            }
        }
        if (timings == null) return null
        val candidate = create(base, "$variant:timings", null, timings)
        return candidate.takeIf { id -> timings.contentEquals(manager.getNotificationChannel(id)?.vibrationPattern) }
    }

    private fun create(
        base: NotificationChannel, variant: String, effect: VibrationEffect?, timings: LongArray?,
    ): String {
        val key = "${base.sound}:${base.importance}:$variant"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val id = "ah_${base.id}_$digest"
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(
                id, context.getString(R.string.haptics_notification_channel, base.name), base.importance,
            ).apply {
                group = base.group
                setSound(base.sound, base.audioAttributes)
                setBypassDnd(base.canBypassDnd())
                setShowBadge(base.canShowBadge())
                lockscreenVisibility = base.lockscreenVisibility
                if (Build.VERSION.SDK_INT >= 35 && effect != null) {
                    vibrationEffect = effect
                } else {
                    vibrationPattern = timings ?: longArrayOf(0, 65)
                }
                enableVibration(true)
            })
        }
        return id
    }

    private companion object {
        private const val FALLBACK = "notification-fallback-v2"
    }
}

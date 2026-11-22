/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.core.content.FileProvider
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.haptics.HapticAudioSource
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

internal class NotificationSoundChannels(private val context: Context, private val manager: NotificationManager) {
    fun readyChannel(accountId: Int, id: Long): String? {
        if (id == -1L || id == 0L) return DEFAULT
        return manager.getNotificationChannel(channelId(accountId, id))?.id
    }

    suspend fun prepareChannel(accountId: Int, id: Long, client: TelegramClient): String? {
        readyChannel(accountId, id)?.let { return it }
        // Android fixes the sound when a channel is created, so give each TDLib sound its own channel.
        val channelId = channelId(accountId, id)
        return withTimeoutOrNull(15_000) {
            val sound = client.send(TdApi.GetSavedNotificationSound(id)).getOrNull()
                ?: return@withTimeoutOrNull null
            val file = materialize(channelId, sound, client) ?: return@withTimeoutOrNull null
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val label = sound.title.ifBlank { sound.data }
                manager.createNotificationChannel(NotificationChannel(
                    channelId,
                    label.ifBlank { context.getString(R.string.notifications_sound_unknown, id) },
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                })
                channelId
            } catch (_: SecurityException) {
                null
            }
        }
    }

    /** Preview and delivery share the same file, so the same prepared pattern works in both. */
    suspend fun previewSource(accountId: Int, sound: TdApi.NotificationSound, client: TelegramClient): HapticAudioSource? {
        val channelId = channelId(accountId, sound.id)
        return withTimeoutOrNull(15_000) {
            materialize(channelId, sound, client)?.let { HapticAudioSource.telegram(channelId, it) }
        }
    }

    private suspend fun materialize(channelId: String, sound: TdApi.NotificationSound, client: TelegramClient): File? {
        val file = fileFor(context, channelId)
        if (file.isFile && file.length() > 0) return file
        val downloaded = client.send(TdApi.DownloadFile(sound.sound.id, 16, 0, 0, true)).getOrNull()
        val local = downloaded?.local?.takeIf { it.isDownloadingCompleted && it.path.isNotBlank() } ?: return null

        return publishLock.withLock {
            if (file.isFile && file.length() > 0) return@withLock file
            var temporary: File? = null
            try {
                val root = requireNotNull(file.parentFile)
                root.mkdirs()
                temporary = File.createTempFile("sound-", ".tmp", root)
                File(local.path).copyTo(temporary, overwrite = true)
                Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                file
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } finally {
                temporary?.delete()
            }
        }
    }

    companion object {
        private val publishLock = Mutex()
        const val DEFAULT = "telegram_messages"

        private fun channelId(accountId: Int, soundId: Long) = "telegram_sound_${accountId}_$soundId"

        fun fileFor(context: Context, channelId: String) =
            File(File(context.filesDir, "notification_sounds"), "$channelId.mp3")

        fun ensureDefaultChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                DEFAULT, context.getString(R.string.notification_channel_messages), NotificationManager.IMPORTANCE_HIGH,
            ))
        }
    }
}

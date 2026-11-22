/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal data class HapticAudioSource(val key: String, val uri: Uri) {
    // Called only on a worker thread. A URI on its own cannot identify a version.
    fun revision(context: Context): String? {
        return try {
            val version = when (uri.scheme) {
                "file" -> Files.readAttributes(uri.toFile().toPath(), BasicFileAttributes::class.java)
                    .takeIf { it.isRegularFile && it.size() in 1..MAX_BYTES }
                    ?.let { "${it.fileKey()}:${it.size()}:${it.lastModifiedTime()}" }
                "content" -> if (isNotification) {
                    notificationMetadata(context) ?: notificationDescriptorVersion(context)
                } else contentVersion(context)
                "android.resource" -> if (isNotification) notificationDescriptorVersion(context) else null
                else -> null
            } ?: return null
            "$uri|$version".takeIf { it.length <= 1024 }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private val isNotification get() = key == DEFAULT_NOTIFICATION || key.startsWith("telegram-sound:")

    private fun notificationMetadata(context: Context): String? = try {
        contentVersion(context)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    // System and OEM sound providers can expose the audio without MediaStore timestamps.
    private fun notificationDescriptorVersion(context: Context): String? = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            val stat = Os.fstat(asset.fileDescriptor)
            val size = asset.length
            if (!OsConstants.S_ISREG(stat.st_mode) || size !in 1..MAX_BYTES) return@use null
            "descriptor:${stat.st_dev}:${stat.st_ino}:${stat.st_mtime}:${asset.startOffset}:$size"
        }
    } catch (_: ErrnoException) {
        null
    }

    private fun contentVersion(context: Context): String? {
        val isMedia = uri.authority == MediaStore.AUTHORITY
        val isDocument = DocumentsContract.isDocumentUri(context, uri)
        val changedColumn = when {
            isMedia -> MediaStore.MediaColumns.DATE_MODIFIED
            isDocument -> DocumentsContract.Document.COLUMN_LAST_MODIFIED
            else -> return null
        }
        val generation = isMedia && Build.VERSION.SDK_INT >= 30
        val columns = listOf(changedColumn, MediaStore.MediaColumns.SIZE) +
            if (generation) listOf(MediaStore.MediaColumns.GENERATION_MODIFIED) else emptyList()
        return context.contentResolver.query(uri, columns.toTypedArray(), null, null, null)?.use {
            if (!it.moveToFirst() || it.getLong(1) !in 1..MAX_BYTES) return@use null
            val modified = it.getLong(0)
            val generationModified = if (generation) it.getLong(2) else 0L
            if (modified <= 0 && generationModified <= 0) return@use null
            val volume = if (generation) MediaStore.getVersion(context, MediaStore.getVolumeName(uri)) else ""
            "$volume:$modified:${it.getLong(1)}:$generationModified"
        }
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024 * 1024

        fun media(context: Context, uri: Uri): HapticAudioSource {
            val canonical = if (uri.scheme == "content" && uri.authority != MediaStore.AUTHORITY) {
                // SAF pickers may return a document URI for a track that ExoPlayer opens via MediaStore.
                runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: uri
            } else uri
            return HapticAudioSource(canonical.toString(), uri)
        }

        fun telegram(channel: String, file: File) =
            HapticAudioSource("telegram-sound:$channel", Uri.fromFile(file))

        fun notification(context: Context, sound: Uri): HapticAudioSource? {
            val actual = if (RingtoneManager.isDefault(sound)) {
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
            } else sound
            return actual?.let { HapticAudioSource(DEFAULT_NOTIFICATION, it) }
        }

        const val DEFAULT_NOTIFICATION = "telegram-default-notification"
    }
}

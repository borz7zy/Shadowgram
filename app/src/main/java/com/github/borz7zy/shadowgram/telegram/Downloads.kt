/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.models.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Files of messages: downloaded on a tap, opened in other apps, copied to the shared Downloads
 * folder. Saving runs on its own, the screen that asked may be gone by then.
 */
object Downloads {
    /**
     * @param name What it's called in Downloads
     */
    data class Saveable(val file: TdApi.File, val name: String, val mimeType: String)

    private const val LOG_TAG = "Downloads"
    /** Asked for by a tap, before the pictures on screen. */
    private const val PRIORITY = 32

    private val scope = MainScope()

    /**
     * What a tap on [document] does, from the screen showing it: open it once downloaded, else
     * cancel or start its download through [toggleDownload].
     */
    fun onTap(context: Context, document: MessageItem.Document, toggleDownload: () -> Unit) =
        when (document.path) {
            null -> toggleDownload()
            else -> open(context, document)
        }

    /** Cancel [document]'s download if it's running, else start it. */
    fun toggleDownload(client: TelegramClient, document: MessageItem.Document) = when {
        document.isDownloading -> client.send(TdApi.CancelDownloadFile(document.fileId, false)) {}
        else -> client.send(TdApi.DownloadFile(document.fileId, PRIORITY, 0, 0, false)) {}
    }

    /**
     * A downloaded file: the system asks what to do with it every time, the apps that open it
     * and the ones it can be sent to, never a remembered default app.
     *
     * @param context An activity's, the chooser starts from it
     */
    fun open(context: Context, document: MessageItem.Document) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.files", File(document.path ?: return)
        )
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(File(document.name).extension.lowercase())
            ?: "*/*"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            document.name,
        )
        // A protected chat's file is only viewed
        if (document.canBeSaved) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(send))
        }
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.document_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** [document] as the bubbles and rows show it, [file] the latest state of its file. */
    fun documentOf(document: TdApi.Document, file: TdApi.File, canBeSaved: Boolean) = MessageItem.Document(
        fileId = file.id,
        name = document.fileName,
        size = file.size.takeIf { it > 0 } ?: file.expectedSize,
        downloadedSize = file.local.downloadedSize,
        isDownloading = file.local.isDownloadingActive,
        path = file.local.path.takeIf { file.local.isDownloadingCompleted },
        canBeSaved = canBeSaved,
    )

    /** The file of [message] that can be saved, null for messages without one or protected. */
    fun saveableOf(message: TdApi.Message): Saveable? {
        if (!message.canBeSaved) return null
        val id = message.id
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document.let {
                Saveable(it.document, it.fileName.ifBlank { "file_$id" }, it.mimeType)
            }

            is TdApi.MessageAudio -> content.audio.let {
                Saveable(it.audio, it.fileName.ifBlank { "audio_$id.mp3" }, it.mimeType)
            }

            is TdApi.MessageVideo -> content.video.let {
                Saveable(it.video, it.fileName.ifBlank { "video_$id.mp4" }, it.mimeType)
            }

            is TdApi.MessageAnimation -> content.animation.let {
                Saveable(it.animation, it.fileName.ifBlank { "animation_$id.mp4" }, it.mimeType)
            }

            is TdApi.MessageVoiceNote -> content.voiceNote.let {
                Saveable(it.voice, "voice_$id.ogg", it.mimeType)
            }

            is TdApi.MessageVideoNote -> Saveable(
                content.videoNote.video, "video_message_$id.mp4", "video/mp4"
            )

            is TdApi.MessagePhoto -> content.photo.sizes.maxByOrNull { it.width }?.let {
                Saveable(it.photo, "photo_$id.jpg", "image/jpeg")
            }

            else -> null
        }
    }

    /** Download [saveable] if needed and copy it to Downloads, telling how it went. */
    fun save(context: Context, client: TelegramClient, saveable: Saveable) {
        val context = context.applicationContext
        scope.launch {
            val file = client.send(TdApi.DownloadFile(saveable.file.id, PRIORITY, 0, 0, true))
            val path = (file as? Result.Success)?.data?.local
                ?.takeIf { it.isDownloadingCompleted }?.path
            val isSaved = path != null && withContext(Dispatchers.IO) {
                copy(context, File(path), saveable)
            }
            Toast.makeText(
                context,
                if (isSaved) R.string.downloads_saved else R.string.downloads_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun copy(context: Context, source: File, saveable: Saveable): Boolean {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, saveable.name)
                put(MediaStore.Downloads.MIME_TYPE, saveable.mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: return false

        return try {
            resolver.openOutputStream(uri)!!.use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            resolver.update(
                uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null
            )
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Can't save ${saveable.name}", e)
            resolver.delete(uri, null, null)
            false
        }
    }
}

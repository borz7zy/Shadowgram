/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * The photos and videos a viewer pages through, in order: an album's, a profile's media, or just
 * the one opened.
 */
class PhotoViewerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val chatId = savedStateHandle.get<Long>(ARG_CHAT_ID) ?: 0L
    val messageId = savedStateHandle.get<Long>(ARG_MESSAGE_ID) ?: 0L

    private var readyClient: com.github.borz7zy.shadowgram.telegram.TelegramClient? = null

    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient().also { readyClient = it }
    }

    /** The opened photo's picture and place in the chat, handed over by it. */
    class Anchor : ViewModel() {
        var drawable: Drawable? = null
        var frame: Frame? = null

        /** Where the bubble picture of a message is now on screen, null if it isn't. */
        var locate: ((Long) -> Frame?)? = null
    }

    /**
     * A bubble picture on screen.
     *
     * @param corners Its radii where it meets the bubble's corners: top left, top right, bottom
     *   right, bottom left
     */
    class Frame(val bounds: Rect, val corners: FloatArray)

    /**
     * A photo, or the still of a [video].
     *
     * @param fileId The biggest size, a video's thumbnail; 0 for none
     * @param path It, once downloaded
     */
    data class Photo(
        val messageId: Long,
        val fileId: Int,
        val path: String?,
        val width: Int,
        val height: Int,
        val minithumbnail: ByteArray?,
        val caption: String?,
        val date: Int,
        val senderId: TdApi.MessageSender,
        val video: Video? = null,
        /** False in protected chats: no saving to the gallery. */
        val canBeSaved: Boolean = false,
    )

    /** @param duration Seconds */
    data class Video(val fileId: Int, val duration: Int)

    /**
     * @param photos In the album's order
     * @param total How many, 0 while loading
     * @param firstNumber The 1-based number of the first of [photos]
     */
    data class State(
        val photos: List<Photo> = listOf(),
        val total: Int = 0,
        val firstNumber: Int = 1,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** The photos of the message it was opened from: an album's, or the one. */
    private val messageIds = savedStateHandle.get<LongArray>(ARG_MESSAGE_IDS) ?: longArrayOf(messageId)

    init {
        viewModelScope.launch {
            val client = client.await()
            val messages = (client.send(TdApi.GetMessages(chatId, messageIds)) as? Result.Success)
                ?.data?.messages.orEmpty().filterNotNull()
            // In the order given: an album's, or a profile's newest first like its grid
            val order = messageIds.withIndex().associate { (index, id) -> id to index }
            val photos = messages.mapNotNull { it.toPhoto() }.sortedBy { order[it.messageId] }
            _state.value = State(photos = photos, total = photos.size)
        }
    }

    /** The photo's file, downloaded, null if it can't be. */
    suspend fun download(photo: Photo): String? = photo.path ?: download(photo.fileId)

    /** A file, downloaded whole, null if it can't be. */
    suspend fun download(fileId: Int): String? {
        if (fileId == 0) {
            return null
        }
        val file = client.await().send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, true))
        return (file as? Result.Success)?.data?.local?.takeIf { it.isDownloadingCompleted }?.path
    }

    /** Who sent it, for the top bar. */
    fun senderName(sender: TdApi.MessageSender): String? = readyClient?.chats?.sender(sender)?.name

    private fun TdApi.Message.toPhoto(): Photo? {
        val content = content
        if (content is TdApi.MessageVideo) {
            val video = content.video
            // Only pictures, an MP4 thumbnail is left to the minithumbnail
            val thumbnail = video.thumbnail?.takeIf {
                it.format is TdApi.ThumbnailFormatJpeg || it.format is TdApi.ThumbnailFormatWebp
                        || it.format is TdApi.ThumbnailFormatPng
            }
            return Photo(
                messageId = id,
                fileId = thumbnail?.file?.id ?: 0,
                path = thumbnail?.file?.local?.takeIf { it.isDownloadingCompleted }?.path,
                width = video.width.takeIf { it > 0 } ?: thumbnail?.width ?: 0,
                height = video.height.takeIf { it > 0 } ?: thumbnail?.height ?: 0,
                minithumbnail = video.minithumbnail?.data,
                caption = content.caption.text.takeIf { it.isNotBlank() },
                date = date,
                senderId = senderId,
                video = Video(video.video.id, video.duration),
                canBeSaved = canBeSaved,
            )
        }
        if (content !is TdApi.MessagePhoto) {
            return null
        }
        val size = content.photo.sizes.maxByOrNull { it.width } ?: return null
        return Photo(
            messageId = id,
            fileId = size.photo.id,
            path = size.photo.local.takeIf { it.isDownloadingCompleted }?.path,
            width = size.width,
            height = size.height,
            minithumbnail = content.photo.minithumbnail?.data,
            caption = content.caption.text.takeIf { it.isNotBlank() },
            date = date,
            senderId = senderId,
            canBeSaved = canBeSaved,
        )
    }

    companion object {
        const val ARG_CHAT_ID = "chat_id"
        const val ARG_MESSAGE_ID = "message_id"
        const val ARG_MESSAGE_IDS = "message_ids"
        private const val DOWNLOAD_PRIORITY = 32
    }
}

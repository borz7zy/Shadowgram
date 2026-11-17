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

        var restore: (() -> android.view.View?)? = null
    }

    /**
     * A bubble picture on screen.
     *
     * @param corners Its radii where it meets the bubble's corners: top left, top right, bottom
     *   right, bottom left
     * @param viewport Visible list area in screen coordinates; bounds retain the full image size
     */
    class Frame(val bounds: Rect, val corners: FloatArray, val viewport: Rect? = null)

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
        /** A GIF: saved to the downloads or to the account's GIFs. */
        val isGif: Boolean = false,
    )

    /**
     * @param duration Seconds
     * @param isAnimation A GIF or an animated profile photo: looped silently, without controls
     */
    data class Video(val fileId: Int, val duration: Int, val isAnimation: Boolean = false)

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

    /** The chat's profile photos rather than messages'. */
    private val isProfile = savedStateHandle.get<Boolean>(ARG_PROFILE) ?: false

    init {
        viewModelScope.launch {
            val client = client.await()
            if (isProfile) {
                val photos = profilePhotos(client)
                _state.value = State(photos = photos, total = photos.size)
                return@launch
            }
            val messages = (client.send(TdApi.GetMessages(chatId, messageIds)) as? Result.Success)
                ?.data?.messages.orEmpty().filterNotNull()
            // In the order given: an album's, or a profile's newest first like its grid
            val order = messageIds.withIndex().associate { (index, id) -> id to index }
            val photos = messages.mapNotNull { it.toPhoto() }.sortedBy { order[it.messageId] }
            _state.value = State(photos = photos, total = photos.size)
        }
    }

    /**
     * A user's profile photos, or a group's or channel's: the current one, then the ones it had
     * before, which only show as "changed the photo" messages. Newest first.
     *
     * ponytail: the newest [PROFILE_PHOTOS_LIMIT] only, page if someone has more
     */
    private suspend fun profilePhotos(client: com.github.borz7zy.shadowgram.telegram.TelegramClient): List<Photo> {
        val chat = (client.send(TdApi.GetChat(chatId)) as? Result.Success)?.data ?: return listOf()
        suspend fun changes() = (client.send(
            TdApi.SearchChatMessages(
                chatId, null, "", null, 0, 0, PROFILE_PHOTOS_LIMIT, TdApi.SearchMessagesFilterChatPhoto()
            )
        ) as? Result.Success)?.data?.messages.orEmpty()
            .mapNotNull { (it.content as? TdApi.MessageChatChangePhoto)?.photo }

        val photos = when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> userPhotos(client, type.userId)
            is TdApi.ChatTypeSecret -> userPhotos(client, type.userId)
            is TdApi.ChatTypeSupergroup -> listOfNotNull(
                (client.send(TdApi.GetSupergroupFullInfo(type.supergroupId)) as? Result.Success)?.data?.photo
            ) + changes()

            is TdApi.ChatTypeBasicGroup -> listOfNotNull(
                (client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) as? Result.Success)?.data?.photo
            ) + changes()

            else -> listOf()
        }
        val sender = TdApi.MessageSenderChat(chatId)
        return photos.distinctBy { it.id }.mapIndexedNotNull { index, photo ->
            val size = photo.sizes.maxByOrNull { it.width } ?: return@mapIndexedNotNull null
            Photo(
                // The avatar shows the current one, the first: the viewer opens and closes there
                messageId = if (index == 0) CURRENT_PROFILE_PHOTO_ID else photo.id,
                fileId = size.photo.id,
                path = size.photo.local.takeIf { it.isDownloadingCompleted }?.path,
                width = size.width,
                height = size.height,
                minithumbnail = photo.minithumbnail?.data,
                caption = null,
                date = photo.addedDate,
                senderId = sender,
                video = photo.animation?.let { Video(it.file.id, 0, isAnimation = true) },
                canBeSaved = !chat.hasProtectedContent,
            )
        }
    }

    private suspend fun userPhotos(client: com.github.borz7zy.shadowgram.telegram.TelegramClient, userId: Long) =
        (client.send(TdApi.GetUserProfilePhotos(userId, 0, PROFILE_PHOTOS_LIMIT)) as? Result.Success)
            ?.data?.photos.orEmpty().toList()

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

    /** Add a GIF to the account's saved ones, false if Telegram refused. */
    suspend fun saveGif(fileId: Int) =
        client.await().send(TdApi.AddSavedAnimation(TdApi.InputFileId(fileId))) is Result.Success

    /** Who sent it, for the top bar. */
    fun senderName(sender: TdApi.MessageSender): String? = readyClient?.chats?.sender(sender)?.name

    private fun TdApi.Message.toPhoto(): Photo? {
        val content = content
        if (content is TdApi.MessageAnimation) {
            val animation = content.animation
            val thumbnail = animation.thumbnail?.takeIf {
                it.format is TdApi.ThumbnailFormatJpeg || it.format is TdApi.ThumbnailFormatWebp
                        || it.format is TdApi.ThumbnailFormatPng
            }
            return Photo(
                messageId = id,
                fileId = thumbnail?.file?.id ?: 0,
                path = thumbnail?.file?.local?.takeIf { it.isDownloadingCompleted }?.path,
                width = animation.width.takeIf { it > 0 } ?: thumbnail?.width ?: 0,
                height = animation.height.takeIf { it > 0 } ?: thumbnail?.height ?: 0,
                minithumbnail = animation.minithumbnail?.data,
                caption = content.caption.text.takeIf { it.isNotBlank() },
                date = date,
                senderId = senderId,
                video = Video(animation.animation.id, animation.duration, isAnimation = true),
                canBeSaved = canBeSaved,
                isGif = true,
            )
        }
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
        const val ARG_PROFILE = "profile"
        private const val PROFILE_PHOTOS_LIMIT = 100

        /** The [Photo.messageId] of a profile's current photo. */
        const val CURRENT_PROFILE_PHOTO_ID = -1L
        private const val DOWNLOAD_PRIORITY = 32
    }
}

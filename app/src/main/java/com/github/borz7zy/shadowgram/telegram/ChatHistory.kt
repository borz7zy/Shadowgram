/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import android.util.Log
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.ui.text.CustomEmojiRange
import com.github.borz7zy.shadowgram.videonote.VideoNoteGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.TreeMap

/**
 * Messages of an open chat, or of one forum topic when [topicId] isn't 0, kept up to date from
 * TDLib updates.
 *
 * A chat with unread messages opens around the first of them and pages both ways, otherwise it
 * opens at the newest message and pages backwards. Pictures of the loaded messages and avatars of
 * their senders are downloaded as they show up.
 *
 * Same scheme as [ChatsStore]: updates are applied on the TDLib thread under [lock], every change
 * bumps [version] and collectors build snapshots on [Dispatchers.Default].
 */
class ChatHistory(
    private val client: TelegramClient,
    val chatId: Long,
    val topicId: Int,
) {
    /**
     * @param messages Oldest first
     * @param isGroup Whether sender names and avatars are shown
     * @param isLoaded Whether TDLib answered at least once, so an empty chat really is empty
     * @param isNewestLoaded Whether the newest message is loaded, new ones then show up live
     * @param unreadAfterId Messages after this one were unread when the chat was opened
     * @param newMessageCount Messages that arrived since the chat was opened
     */
    data class Snapshot(
        val messages: List<MessageItem>,
        val isGroup: Boolean,
        val isLoaded: Boolean,
        val isNewestLoaded: Boolean,
        val unreadAfterId: Long?,
        val newMessageCount: Int,
    )

    private val lock = Any()

    // Guarded by lock
    private val messages = TreeMap<Long, TdApi.Message>()
    private var lastReadOutboxMessageId = 0L
    private var unreadAfterId: Long? = null
    private var isGroup = false
    private var isLoaded = false
    private var isLoadingOlder = false
    private var isLoadingNewer = false
    private var isOldestLoaded = false
    private var isNewestLoaded = true
    private var newMessageCount = 0

    /** Latest state of the files this history asked TDLib to download, by file id. */
    private val files = HashMap<Int, TdApi.File>()

    /** Latest state of the documents' files, downloaded when asked to, by file id. Guarded by lock. */
    private val documents = HashMap<Int, TdApi.File>()

    /** Latest state of the files of messages being sent, by file id. Guarded by lock. */
    private val uploads = HashMap<Int, TdApi.File>()

    private val version = MutableStateFlow(0L)

    private val onUpdate: (TdApi.Object) -> Unit = { update ->
        if (synchronized(lock) { apply(update) }) {
            version.update { it + 1 }
        }
    }

    /**
     * The messages, starts listening and loading when collected.
     */
    fun snapshots(): Flow<Snapshot> = callbackFlow {
        // Listen before loading, so that nothing sent meanwhile is missed
        client.addUpdateListener(onUpdate)

        val readState = client.chats.readState(chatId, topicId)
        val opensAtUnread = readState.unreadCount > 0 && readState.lastReadInboxMessageId > 0
        synchronized(lock) {
            isGroup = readState.isGroup
            lastReadOutboxMessageId = readState.lastReadOutboxMessageId
            if (opensAtUnread) {
                unreadAfterId = readState.lastReadInboxMessageId
                isNewestLoaded = false
            }
        }

        when (opensAtUnread) {
            true -> loadAround(readState.lastReadInboxMessageId)
            false -> loadOlder()
        }

        launch {
            combine(version, client.videoNotes.progress) { _, processing -> processing }
                .map { snapshot(it) }
                .collect { send(it) }
        }

        awaitClose { client.removeUpdateListener(onUpdate) }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * Ask TDLib for the page before the oldest loaded message. Does nothing while a request is
     * running or once the start of the chat is reached.
     */
    fun loadOlder() {
        val fromMessageId = synchronized(lock) {
            if (isLoadingOlder || isOldestLoaded) {
                return
            }
            isLoadingOlder = true
            messages.keys.firstOrNull() ?: 0L
        }

        request(fromMessageId, 0) { page, isAnswer ->
            val needsMore = synchronized(lock) {
                isLoadingOlder = false
                isOldestLoaded = isAnswer && page.none { it.id < fromMessageId || fromMessageId == 0L }
                // The first answer usually only has what TDLib had locally, often a single
                // message, keep going until the screen can be filled
                !isOldestLoaded && isAnswer && messages.size < PAGE_SIZE
            }
            if (needsMore) {
                loadOlder()
            }
        }
    }

    /**
     * Ask TDLib for the page after the newest loaded message, when the chat was opened in the
     * middle. Does nothing while a request is running or once the newest message is loaded.
     */
    fun loadNewer() {
        val fromMessageId = synchronized(lock) {
            if (isLoadingNewer || isNewestLoaded) {
                return
            }
            isLoadingNewer = true
            messages.keys.lastOrNull() ?: return
        }

        request(fromMessageId, -(PAGE_SIZE - 1)) { page, isAnswer ->
            synchronized(lock) {
                isLoadingNewer = false
                isNewestLoaded = isAnswer && page.none { it.id > fromMessageId }
            }
        }
    }

    fun sendText(text: String) =
        send(TdApi.InputMessageText(TdApi.FormattedText(text, arrayOf()), null, true))

    /**
     * Send a video note right away, its [raw] recording is transcoded by
     * [TelegramClient.videoNotes] meanwhile.
     *
     * @param raw The square recording
     * @param duration In seconds
     * @param length The side of the square, up to 640
     * @param thumbnail A local square JPEG and its side, up to 320
     */
    fun sendVideoNote(raw: File, duration: Int, length: Int, thumbnail: Pair<String, Int>?) =
        send(
            TdApi.InputMessageVideoNote(
                TdApi.InputVideoNote(
                    VideoNoteGenerator.inputFile(raw),
                    thumbnail?.let { (path, side) ->
                        TdApi.InputThumbnail(TdApi.InputFileLocal(path), side, side)
                    },
                    duration,
                    length,
                ),
                null,
            )
        )

    /** Delete a message for everyone; a message still being sent is cancelled. */
    fun delete(messageId: Long) {
        client.send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true)) {}
    }

    /**
     * Send [content], at [scheduleAt] (unix time) or right away when null.
     */
    fun send(content: TdApi.InputMessageContent, scheduleAt: Int? = null) {
        jumpToNewest()

        client.send(
            TdApi.SendMessage(chatId, topic(), takeReplyTo(), options(scheduleAt), null, content)
        ) {
            if (it is TdApi.Error) {
                Log.e(LOG_TAG, "SendMessage failed: ${it.code} ${it.message}")
            }
        }
    }

    /** Send [contents] grouped into one album. */
    fun sendAlbum(contents: List<TdApi.InputMessageContent>, scheduleAt: Int? = null) {
        jumpToNewest()

        client.send(
            TdApi.SendMessageAlbum(
                chatId, topic(), takeReplyTo(), options(scheduleAt), contents.toTypedArray()
            )
        ) {
            if (it is TdApi.Error) {
                Log.e(LOG_TAG, "SendMessageAlbum failed: ${it.code} ${it.message}")
            }
        }
    }

    /**
     * Send a result of an inline bot query, a GIF found through @gif.
     */
    fun sendInlineResult(queryId: Long, resultId: String, scheduleAt: Int? = null) {
        jumpToNewest()

        client.send(
            TdApi.SendInlineQueryResultMessage(
                chatId, topic(), takeReplyTo(), options(scheduleAt), queryId, resultId, true
            )
        ) {
            if (it is TdApi.Error) {
                Log.e(LOG_TAG, "SendInlineQueryResultMessage failed: ${it.code} ${it.message}")
            }
        }
    }

    /** The message the next one replies to, 0 for none. Cleared once sent. */
    @Volatile
    var replyToMessageId = 0L

    private fun takeReplyTo() = replyToMessageId.takeIf { it != 0L }?.let {
        replyToMessageId = 0L
        TdApi.InputMessageReplyToMessage(it, null, 0, "")
    }

    private fun topic() = topicId.takeIf { it != 0 }?.let { TdApi.MessageTopicForum(it) }

    private fun options(scheduleAt: Int?) = scheduleAt?.let {
        TdApi.MessageSendOptions().apply {
            schedulingState = TdApi.MessageSchedulingStateSendAtDate(it, 0)
        }
    }

    /** A message sent while reading old ones lands after a gap, jump to the newest first. */
    private fun jumpToNewest() {
        val mustReload = synchronized(lock) {
            if (isNewestLoaded) {
                false
            } else {
                messages.clear()
                isNewestLoaded = true
                isOldestLoaded = false
                true
            }
        }
        if (mustReload) {
            version.update { it + 1 }
            loadOlder()
        }
    }

    /**
     * Mark messages as read, TDLib ignores the ones that already are.
     */
    fun view(messageIds: LongArray) {
        client.send(TdApi.ViewMessages(chatId, messageIds, null, false)) {}
    }

    private fun loadAround(messageId: Long) {
        synchronized(lock) {
            isLoadingOlder = true
            isLoadingNewer = true
        }

        request(messageId, -AROUND_NEWER) { page, isAnswer ->
            val needsOlder = synchronized(lock) {
                isLoadingOlder = false
                isLoadingNewer = false
                // Fewer newer messages than asked for: the newest one is in
                isNewestLoaded = !isAnswer || page.count { it.id > messageId } < AROUND_NEWER
                isAnswer && page.count { it.id <= messageId } < PAGE_SIZE - AROUND_NEWER
            }
            if (needsOlder) {
                loadOlder()
            }
        }
    }

    /**
     * Get a page of history from [fromMessageId], [offset] negative for newer messages.
     * [onPage] runs on the TDLib thread once the page is stored, with whether TDLib answered
     * with messages at all.
     */
    private fun request(
        fromMessageId: Long,
        offset: Int,
        onPage: (List<TdApi.Message>, Boolean) -> Unit,
    ) {
        val request = when (topicId) {
            0 -> TdApi.GetChatHistory(chatId, fromMessageId, offset, PAGE_SIZE, false)
            else -> TdApi.GetForumTopicHistory(chatId, topicId, fromMessageId, offset, PAGE_SIZE)
        }

        client.send(request) { result ->
            val page = (result as? TdApi.Messages)?.messages.orEmpty().toList()
            synchronized(lock) {
                page.forEach { messages[it.id] = it }
                isLoaded = true
            }
            onPage(page, result is TdApi.Messages)
            version.update { it + 1 }
        }
    }

    private fun belongs(message: TdApi.Message) = message.chatId == chatId && (topicId == 0
            || (message.topicId as? TdApi.MessageTopicForum)?.forumTopicId == topicId)

    private fun apply(update: TdApi.Object): Boolean {
        when (update) {
            is TdApi.UpdateNewMessage -> {
                if (!belongs(update.message)) {
                    return false
                }
                newMessageCount++
                // Reading old messages: it gets loaded with the page it belongs to
                if (isNewestLoaded) {
                    messages[update.message.id] = update.message
                }
            }

            is TdApi.UpdateMessageSendSucceeded -> {
                if (!belongs(update.message)) {
                    return false
                }
                val old = messages.remove(update.oldMessageId) ?: return false
                old.content.uploadFile()?.let { uploads.remove(it.id) }
                messages[update.message.id] = update.message
            }

            is TdApi.UpdateMessageSendFailed -> {
                if (!belongs(update.message)) {
                    return false
                }
                val old = messages.remove(update.oldMessageId) ?: return false
                old.content.uploadFile()?.let { uploads.remove(it.id) }
                messages[update.message.id] = update.message
            }

            is TdApi.UpdateDeleteMessages -> {
                if (update.chatId != chatId || !update.isPermanent) {
                    return false
                }
                update.messageIds.forEach { messages.remove(it) }
            }

            is TdApi.UpdateMessageContent -> {
                if (update.chatId != chatId) {
                    return false
                }
                messages[update.messageId]?.content = update.newContent
            }

            is TdApi.UpdateMessageInteractionInfo -> {
                if (update.chatId != chatId) {
                    return false
                }
                val message = messages[update.messageId] ?: return false
                message.interactionInfo = update.interactionInfo
            }

            is TdApi.UpdateMessageEdited -> {
                if (update.chatId != chatId) {
                    return false
                }
                messages[update.messageId]?.editDate = update.editDate
            }

            is TdApi.UpdateChatReadOutbox -> {
                if (update.chatId != chatId || topicId != 0) {
                    return false
                }
                lastReadOutboxMessageId = update.lastReadOutboxMessageId
            }

            is TdApi.UpdateForumTopic -> {
                if (update.chatId != chatId || update.forumTopicId != topicId) {
                    return false
                }
                lastReadOutboxMessageId = update.lastReadOutboxMessageId
            }

            is TdApi.UpdateFile -> {
                val file = update.file
                when {
                    // ponytail: scans the loaded messages per upload tick, index pending files by
                    // id if chats with thousands of loaded messages make this show up
                    messages.values.any {
                        it.sendingState is TdApi.MessageSendingStatePending
                                && it.content.uploadFile()?.id == file.id
                    } -> {
                        // Chunks are small, only redraw when what's shown changes
                        val old = uploads.put(file.id, file)
                        if (old != null && old.local.isDownloadingCompleted == file.local.isDownloadingCompleted
                            && old.uploadPercent() == file.uploadPercent()
                        ) {
                            return false
                        }
                    }

                    // A document shows its download's progress
                    file.id in documents -> {
                        val old = documents.put(file.id, file)!!
                        if (old.local.isDownloadingActive == file.local.isDownloadingActive
                            && old.local.isDownloadingCompleted == file.local.isDownloadingCompleted
                            && old.downloadPercent() == file.downloadPercent()
                        ) {
                            return false
                        }
                    }

                    // Only finished downloads change what's shown, not their progress
                    file.id in files && file.local.isDownloadingCompleted -> files[file.id] = file

                    else -> return false
                }
            }

            else -> return false
        }

        return true
    }

    /**
     * @param processing Percent transcoded of the video notes being generated, by path
     */
    private fun snapshot(processing: Map<String, Int>): Snapshot {
        val messages: List<TdApi.Message>
        val lastRead: Long
        val snapshot: Snapshot
        synchronized(lock) {
            messages = this.messages.values.toList()
            lastRead = lastReadOutboxMessageId
            snapshot = Snapshot(
                listOf(), isGroup, isLoaded, isNewestLoaded, unreadAfterId, newMessageCount
            )
        }

        // Senders take the chats store lock, never hold ours meanwhile
        return snapshot.copy(
            messages = messages.map { it.toItem(snapshot.isGroup, lastRead, processing) }
        )
    }

    /**
     * The local path of [file] once downloaded, asking TDLib for it the first time it's seen.
     */
    private fun pathOf(file: TdApi.File?, priority: Int): String? {
        file ?: return null
        val latest = synchronized(lock) {
            files[file.id] ?: run {
                files[file.id] = file
                null
            }
        }
        val current = latest ?: file

        if (current.local.isDownloadingCompleted) {
            return current.local.path
        }
        if (latest == null && current.local.canBeDownloaded) {
            // ponytail: everything loaded gets downloaded, download on bind if pages of big
            // photos ever cost too much
            client.send(TdApi.DownloadFile(current.id, priority, 0, 0, false)) {}
        }
        return null
    }

    /** The latest state of a document's [file], without downloading it. */
    private fun documentFileOf(file: TdApi.File) = synchronized(lock) {
        documents.getOrPut(file.id) { file }
    }

    private fun TdApi.File.downloadPercent() = when {
        size > 0 -> local.downloadedSize * 100 / size
        else -> 0L
    }

    private fun TdApi.Message.toItem(
        isGroup: Boolean,
        lastReadOutbox: Long,
        processing: Map<String, Int>,
    ): MessageItem {
        // Being sent: the file is generated first (transcoded), then uploaded
        val file = content.uploadFile()
            ?.takeIf { sendingState is TdApi.MessageSendingStatePending }
            ?.let { synchronized(lock) { uploads[it.id] } ?: it }
        val isProcessing = file != null && !file.local.isDownloadingCompleted

        val sender = when {
            isGroup && !isOutgoing -> client.chats.sender(senderId)
            else -> null
        }
        val (kind, previewText) = content.toPreviewContent()
        // The file's own view shows its name or song, the bubble only the caption
        val text = when (content) {
            is TdApi.MessageDocument, is TdApi.MessageAudio ->
                content.formattedText()?.text?.takeIf { it.isNotBlank() }

            else -> previewText
        }

        return MessageItem(
            id = id,
            isOutgoing = isOutgoing,
            senderId = when (val sender = senderId) {
                is TdApi.MessageSenderUser -> sender.userId
                is TdApi.MessageSenderChat -> sender.chatId
                else -> 0L
            },
            senderName = sender?.name,
            senderAccentColorId = sender?.accentColorId ?: 0,
            senderPhotoPath = pathOf(sender?.photo, AVATAR_PRIORITY),
            kind = kind,
            text = text,
            date = date,
            state = when {
                sendingState is TdApi.MessageSendingStatePending -> MessageItem.State.PENDING
                sendingState is TdApi.MessageSendingStateFailed -> MessageItem.State.FAILED
                isOutgoing && id <= lastReadOutbox -> MessageItem.State.READ
                else -> MessageItem.State.SENT
            },
            media = content.toMedia(),
            customEmoji = content.formattedText()?.entities.orEmpty().mapNotNull {
                (it.type as? TdApi.TextEntityTypeCustomEmoji)?.let { type ->
                    CustomEmojiRange(it.offset, it.length, type.customEmojiId)
                }
            },
            mediaAlbumId = mediaAlbumId,
            senderEmojiStatusId = sender?.emojiStatusId ?: 0L,
            senderIsPremium = sender?.isPremium == true,
            reactions = interactionInfo?.reactions?.reactions.orEmpty().map {
                MessageItem.Reaction(
                    emoji = (it.type as? TdApi.ReactionTypeEmoji)?.emoji,
                    customEmojiId = (it.type as? TdApi.ReactionTypeCustomEmoji)?.customEmojiId ?: 0L,
                    count = it.totalCount,
                    isChosen = it.isChosen,
                )
            },
            playable = when (val content = content) {
                is TdApi.MessageVoiceNote -> MessageItem.Playable(
                    content.voiceNote.voice.id, content.voiceNote.duration, false
                )

                is TdApi.MessageVideoNote -> MessageItem.Playable(
                    content.videoNote.video.id, content.videoNote.duration, true
                )

                is TdApi.MessageAudio -> content.audio.let {
                    MessageItem.Playable(
                        it.audio.id,
                        it.duration,
                        isVideo = false,
                        isMusic = true,
                        title = it.title.ifBlank { it.fileName },
                        performer = it.performer,
                        artworkPath = pathOf(it.albumCoverThumbnail?.file, MEDIA_PRIORITY),
                    )
                }

                else -> null
            },
            document = (content as? TdApi.MessageDocument)?.document?.let {
                Downloads.documentOf(it, documentFileOf(it.document))
            },
            uploadProgress = when {
                file == null -> null
                isProcessing -> processing[file.local.path] ?: 0
                else -> file.uploadPercent()
            },
            isProcessing = isProcessing,
        )
    }

    /** The text or caption, with its entities. */
    private fun TdApi.MessageContent.formattedText(): TdApi.FormattedText? = when (this) {
        is TdApi.MessageText -> text
        is TdApi.MessagePhoto -> caption
        is TdApi.MessageVideo -> caption
        is TdApi.MessageAnimation -> caption
        is TdApi.MessageDocument -> caption
        is TdApi.MessageAudio -> caption
        is TdApi.MessageVoiceNote -> caption
        else -> null
    }

    private fun TdApi.File.uploadPercent(): Int {
        val total = expectedSize.takeIf { it > 0 } ?: size
        return when {
            remote.isUploadingCompleted -> 100
            total > 0 -> (remote.uploadedSize * 100 / total).toInt().coerceIn(0, 100)
            else -> 0
        }
    }

    /** The file uploaded when the message is sent. */
    private fun TdApi.MessageContent.uploadFile(): TdApi.File? = when (this) {
        is TdApi.MessagePhoto -> photo.sizes.maxByOrNull { it.width }?.photo
        is TdApi.MessageVideo -> video.video
        is TdApi.MessageVideoNote -> videoNote.video
        is TdApi.MessageVoiceNote -> voiceNote.voice
        is TdApi.MessageAudio -> audio.audio
        is TdApi.MessageDocument -> document.document
        is TdApi.MessageAnimation -> animation.animation
        else -> null
    }

    private fun TdApi.MessageContent.toMedia(): MessageItem.Media? = when (this) {
        is TdApi.MessagePhoto -> {
            // The biggest size that still fits a bubble, or the smallest one there is
            val size = photo.sizes.filter { it.width <= PHOTO_MAX_SIZE }.maxByOrNull { it.width }
                ?: photo.sizes.minByOrNull { it.width }
            size?.let {
                MessageItem.Media(
                    MessageItem.Media.Type.PHOTO,
                    pathOf(it.photo, MEDIA_PRIORITY),
                    photo.minithumbnail?.data,
                    it.width,
                    it.height,
                )
            }
        }

        is TdApi.MessageSticker -> when (sticker.format) {
            is TdApi.StickerFormatWebp -> MessageItem.Media(
                MessageItem.Media.Type.STICKER,
                pathOf(sticker.sticker, MEDIA_PRIORITY),
                null,
                sticker.width,
                sticker.height,
            )

            is TdApi.StickerFormatWebm, is TdApi.StickerFormatTgs -> MessageItem.Media(
                MessageItem.Media.Type.ANIMATED_STICKER,
                pathOf(sticker.sticker, MEDIA_PRIORITY),
                null,
                sticker.width,
                sticker.height,
            )

            else -> null
        }

        is TdApi.MessageVideoNote -> videoNote.thumbnail
            .toStill(videoNote.minithumbnail, videoNote.length, videoNote.length)
            ?.copy(type = MessageItem.Media.Type.VIDEO_NOTE)

        // Videos and GIFs are MP4 for the platform decoder: show their still thumbnail
        is TdApi.MessageVideo ->
            (video.thumbnail.toStill(video.minithumbnail, video.width, video.height)
                ?: MessageItem.Media(MessageItem.Media.Type.PHOTO, null, null, video.width, video.height))
                .copy(
                    type = MessageItem.Media.Type.VIDEO,
                    videoFileId = video.video.id,
                    duration = video.duration,
                )
        is TdApi.MessageAnimation ->
            (animation.thumbnail.toStill(animation.minithumbnail, animation.width, animation.height)
                ?: MessageItem.Media(
                    MessageItem.Media.Type.PHOTO, null, null, animation.width, animation.height
                ))
                .let { still ->
                    when (animation.mimeType) {
                        // ponytail: real GIFs (rare) stay still, play them with an animated
                        // image decoder if they show up
                        "image/gif" -> still
                        else -> still.copy(
                            type = MessageItem.Media.Type.ANIMATION,
                            videoPath = pathOf(animation.animation, MEDIA_PRIORITY),
                        )
                    }
                }

        else -> null
    }

    private fun TdApi.Thumbnail?.toStill(
        minithumbnail: TdApi.Minithumbnail?,
        width: Int,
        height: Int,
    ): MessageItem.Media? {
        val isPicture = this?.format is TdApi.ThumbnailFormatJpeg
                || this?.format is TdApi.ThumbnailFormatWebp
                || this?.format is TdApi.ThumbnailFormatPng
        if (!isPicture && minithumbnail == null) {
            return null
        }

        return MessageItem.Media(
            MessageItem.Media.Type.PHOTO,
            if (isPicture) pathOf(this.file, MEDIA_PRIORITY) else null,
            minithumbnail?.data,
            width.takeIf { it > 0 } ?: this?.width ?: 0,
            height.takeIf { it > 0 } ?: this?.height ?: 0,
        )
    }

    companion object {
        private const val LOG_TAG = "ChatHistory"

        private const val PAGE_SIZE = 50

        /** Newer messages asked for when opening at the first unread one. */
        private const val AROUND_NEWER = 30

        private const val PHOTO_MAX_SIZE = 1280
        private const val MEDIA_PRIORITY = 16
        private const val AVATAR_PRIORITY = 8
    }
}

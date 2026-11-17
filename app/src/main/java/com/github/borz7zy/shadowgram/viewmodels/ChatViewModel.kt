/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.ChatHeader
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.MessageRow
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.telegram.ChatHistory
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.InputContents
import com.github.borz7zy.shadowgram.telegram.StickersStore
import com.github.borz7zy.shadowgram.ui.text.CustomEmojiRange
import com.github.borz7zy.shadowgram.ui.text.MentionRange
import com.github.borz7zy.shadowgram.videonote.VideoNoteCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * An open chat or forum topic: its top bar, messages and composer. Kept across configuration
 * changes, so nothing is reloaded.
 */
class ChatViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val chatId = savedStateHandle.get<Long>(ARG_CHAT_ID) ?: 0L
    private val topicId = savedStateHandle.get<Int>(ARG_TOPIC_ID) ?: 0

    /** The chat's scheduled messages rather than its history. */
    val isScheduled = savedStateHandle.get<Boolean>(ARG_SCHEDULED) ?: false

    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient()
    }

    private val history = viewModelScope.async { ChatHistory(client.await(), chatId, topicId, isScheduled) }

    private val snapshots = flow { emitAll(history.await().snapshots()) }
        .shareIn(viewModelScope, SharingStarted.Eagerly, 1)

    /**
     * @param rows Newest first, for a reversed list
     * @param isNewestLoaded Whether the bottom of the list is the newest message
     */
    data class Rows(val rows: List<MessageRow>, val isNewestLoaded: Boolean)

    val rows = snapshots
        .map<_, FlowResult<Rows, Nothing>> {
            when (it.isLoaded) {
                true -> FlowResult.Success(
                    Rows(
                        MessageRow.build(it.messages, it.isGroup, it.unreadAfterId).asReversed(),
                        it.isNewestLoaded,
                    )
                )

                false -> FlowResult.Loading
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, FlowResult.Loading)

    /**
     * Whether the list was scrolled to where the chat opens (the first unread message), done
     * once per chat, not again after a configuration change.
     */
    var isPositioned = false

    /** Messages of the topic when it was opened. */
    private val topicMessageCount = MutableStateFlow<Int?>(null)

    val header = combine(
        flow { emitAll(client.await().chats.header(chatId, topicId)) },
        topicMessageCount,
        snapshots.map { it.newMessageCount }.distinctUntilChanged(),
    ) { header, count, newCount ->
        when {
            header != null && isScheduled -> header.copy(subtitle = ChatHeader.Subtitle.Scheduled)
            header != null && count != null && header.subtitle == ChatHeader.Subtitle.None ->
                header.copy(subtitle = ChatHeader.Subtitle.TopicMessages(count + newCount))

            else -> header
        }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        if (topicId != 0) {
            viewModelScope.launch { topicMessageCount.value = countTopicMessages() }
        }
    }

    /**
     * A topic is the thread of the message that created it, its reply count is what Telegram
     * shows. The General topic has no such message, a search counts it instead.
     */
    private suspend fun countTopicMessages(): Int? {
        val client = client.await()

        val thread = client.send(TdApi.GetMessageThread(chatId, topicId.toLong() shl 20))
        if (thread is Result.Success) {
            thread.data.replyInfo?.let { return it.replyCount }
        }

        val found = client.send(
            TdApi.SearchChatMessages(
                chatId, TdApi.MessageTopicForum(topicId), "", null, 0, 0, 1, null
            )
        )
        return (found as? Result.Success)?.data?.totalCount
    }

    /**
     * Tell TDLib whether the chat is on screen, it then sends the updates only open chats get
     * (typing, live views) and reads them as they are viewed.
     */
    fun setOpen(isOpen: Boolean) {
        // The chat's own screen is under this one and keeps it open
        if (isScheduled) {
            return
        }
        viewModelScope.launch {
            client.await().send(
                when (isOpen) {
                    true -> TdApi.OpenChat(chatId)
                    false -> TdApi.CloseChat(chatId)
                }
            )
        }
    }

    fun loadOlder() {
        viewModelScope.launch { history.await().loadOlder() }
    }

    fun loadNewer() {
        viewModelScope.launch { history.await().loadNewer() }
    }

    /** The reply goes with the next message, the composer stops showing it. */
    private fun clearReply() {
        replyTo.value = null
    }

    /** When the others were last told a message is being typed, 0 when they weren't. */
    private var typingSentAt = 0L

    /**
     * The composer's text changed: tell the others a message is being typed, again every few
     * seconds while it goes on (Telegram drops it after 6), or that it stopped once it's empty.
     */
    fun onTyping(isEmpty: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        val isTyping = when {
            isEmpty -> if (typingSentAt != 0L) false else return
            now - typingSentAt < TYPING_REPEAT_MS -> return
            else -> true
        }
        typingSentAt = if (isTyping) now else 0L
        viewModelScope.launch { history.await().setTyping(isTyping) }
    }

    /**
     * The message the next one replies to.
     *
     * @param name Who sent it
     * @param text What it says, or what it is; the quote when there's one
     * @param quote The part of its text quoted, null for the whole message
     * @param quotePosition Where [quote] starts in its text, UTF-16
     */
    data class Reply(
        val messageId: Long,
        val name: String,
        val text: String,
        val quote: String? = null,
        val quotePosition: Int = 0,
    )

    val replyTo = MutableStateFlow<Reply?>(null)

    fun setReply(reply: Reply?) {
        replyTo.value = reply
        viewModelScope.launch {
            history.await().setReplyTo(reply?.messageId ?: 0L, reply?.quote, reply?.quotePosition ?: 0)
        }
    }

    /** Add the reaction, or take it back if it's already there. */
    fun toggleReaction(messageId: Long, emoji: String?, customEmojiId: Long) {
        val message = (rows.value as? FlowResult.Success)?.data?.rows
            ?.firstNotNullOfOrNull { (it as? MessageRow.Bubble)?.message?.takeIf { m -> m.id == messageId } }
        val isChosen = message?.reactions?.any {
            it.isChosen && it.emoji == emoji && it.customEmojiId == customEmojiId
        } == true
        val type = when (emoji) {
            null -> TdApi.ReactionTypeCustomEmoji(customEmojiId)
            else -> TdApi.ReactionTypeEmoji(emoji)
        }
        viewModelScope.launch {
            val client = client.await()
            when (isChosen) {
                true -> client.send(TdApi.RemoveMessageReaction(chatId, messageId, type))
                false -> client.send(TdApi.AddMessageReaction(chatId, messageId, type, false, true))
            }
        }
    }

    /** Send a scheduled message right away. */
    fun sendNow(messageId: Long) {
        viewModelScope.launch {
            client.await().send(TdApi.EditMessageSchedulingState(chatId, messageId, null))
        }
    }

    /** Delete a message, for everyone when [revoke]. */
    fun deleteMessage(messageId: Long, revoke: Boolean) {
        viewModelScope.launch {
            client.await().send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), revoke))
        }
    }

    /**
     * A message TDLib refused, what of it goes back to the composer.
     *
     * @param error Why, null when a file couldn't be read
     * @param text The text or the caption
     * @param attachments The files not sent yet
     */
    data class Failed(
        val error: String?,
        val text: String? = null,
        val attachments: List<InputContents.Picked> = listOf(),
    )

    private val _sendFailures = Channel<Failed>(Channel.BUFFERED)

    /** Kept until the screen is back to show them. */
    val sendFailures = _sendFailures.receiveAsFlow()

    /** Send through [send], a refusal reported with nothing to restore. */
    private fun sendNow(send: suspend (ChatHistory) -> TdApi.Error?) {
        viewModelScope.launch {
            send(history.await())?.let { _sendFailures.send(Failed(it.message)) }
        }
    }

    /**
     * @param customEmoji Custom emoji in [text], sent as its entities
     * @param mentions Users mentioned by name in [text], sent as its entities
     * @param scheduling When it goes, null for right away
     */
    fun send(
        text: String,
        customEmoji: List<CustomEmojiRange> = listOf(),
        mentions: List<MentionRange> = listOf(),
        scheduling: TdApi.MessageSchedulingState? = null,
    ) {
        clearReply()
        // Sending ends the typing on Telegram's side
        typingSentAt = 0L
        val entities = (customEmoji.map {
            TdApi.TextEntity(it.offset, it.length, TdApi.TextEntityTypeCustomEmoji(it.customEmojiId))
        } + mentions.map {
            TdApi.TextEntity(it.offset, it.length, TdApi.TextEntityTypeMentionName(it.userId))
        }).sortedBy { it.offset }.toTypedArray()
        viewModelScope.launch {
            val error = history.await().send(
                TdApi.InputMessageText(TdApi.FormattedText(text, entities), null, true),
                scheduling,
            )
            // ponytail: custom emoji come back as plain text, keep the spans if that annoys
            error?.let { _sendFailures.send(Failed(it.message, text)) }
        }
    }

    /** Files picked for the next message, in the order they go. Outlives the view. */
    var attachments = listOf<InputContents.Picked>()

    /** Whether [attachments] go grouped into albums. */
    var isGrouped = true

    /**
     * Send picked files, [caption] on the first. Only now are they read and uploaded.
     *
     * @param grouped Into albums where Telegram allows, else one message each
     * @param scheduling When they go, null for right away
     */
    fun send(
        picked: List<InputContents.Picked>,
        grouped: Boolean,
        caption: String? = null,
        scheduling: TdApi.MessageSchedulingState? = null,
    ) {
        clearReply()
        typingSentAt = 0L
        viewModelScope.launch {
            // One unreadable file and nothing goes, rather than a message missing it
            val contents = picked.mapIndexed { index, it ->
                InputContents.fromUri(getApplication(), it.uri, it.asFile, caption.takeIf { index == 0 })
                    ?: run {
                        Log.w(LOG_TAG, "Can't read ${it.uri}")
                        _sendFailures.send(Failed(null, caption, picked))
                        return@launch
                    }
            }
            val history = history.await()
            val albums = if (grouped) InputContents.albums(contents) else contents.map(::listOf)
            // Albums keep the order, so the sent ones are the first picked
            var sent = 0
            albums.forEach { album ->
                val error = when (album.size) {
                    1 -> history.send(album.single(), scheduling)
                    else -> history.sendAlbum(album, scheduling)
                }
                if (error != null) {
                    _sendFailures.send(
                        Failed(error.message, caption.takeIf { sent == 0 }, picked.drop(sent))
                    )
                    return@launch
                }
                sent += album.size
            }
        }
    }

    fun sendSticker(sticker: StickersStore.Sticker) {
        clearReply()
        sendNow { history ->
            history.send(
                TdApi.InputMessageSticker(
                    TdApi.InputSticker(
                        TdApi.InputFileId(sticker.fileId), null, sticker.width, sticker.height
                    ),
                    sticker.emoji,
                ),
            )
        }
    }

    fun sendGif(gif: StickersStore.Gif) {
        clearReply()
        sendNow { history ->
            when (val resultId = gif.resultId) {
                null -> history.send(
                    TdApi.InputMessageAnimation(
                        TdApi.InputAnimation(
                            TdApi.InputFileId(gif.fileId), null, null,
                            gif.duration, gif.width, gif.height,
                        ),
                        null, false, false,
                    ),
                )

                else -> history.sendInlineResult(gif.queryId, resultId)
            }
        }
    }

    fun sendLocation(latitude: Double, longitude: Double, accuracy: Double) {
        clearReply()
        sendNow { history ->
            history.send(
                TdApi.InputMessageLocation(TdApi.Location(latitude, longitude, accuracy)),
            )
        }
    }

    fun sendContact(phoneNumber: String, name: String) {
        clearReply()
        sendNow { history ->
            history.send(
                TdApi.InputMessageContact(TdApi.Contact(phoneNumber, name, "", "", 0)),
            )
        }
    }

    /** Stickers and saved GIFs of the account, for the sticker keyboard. */
    val stickers = flow { emitAll(client.await().stickers.snapshots()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STICKERS_STOP_MS), null)

    /** What the GIF page searches for, empty for the saved GIFs. */
    val gifQuery = MutableStateFlow("")

    /** GIFs found for [gifQuery], null while it's empty. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val foundGifs = gifQuery
        .debounce(GIF_SEARCH_DEBOUNCE_MS)
        .mapLatest { query ->
            query.trim().takeIf { it.isNotEmpty() }
                ?.let { client.await().stickers.searchGifs(chatId, it) }
        }
        .combine(stickers) { gifs, snapshot ->
            // Their files download like the saved ones, paths come with the snapshots
            val paths = snapshot?.paths.orEmpty()
            gifs?.map { gif ->
                gif.copy(
                    path = gif.path ?: paths[gif.fileId],
                    thumbnailPath = gif.thumbnailPath ?: gif.thumbnailFileId?.let(paths::get),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STICKERS_STOP_MS), null)

    /** Download a sticker or GIF file the keyboard shows. */
    fun download(fileId: Int) {
        viewModelScope.launch { client.await().stickers.download(fileId) }
    }

    /**
     * Send a recorded square as a video note: the message shows up at once with the first frame,
     * the account's [com.github.borz7zy.shadowgram.videonote.VideoNoteGenerator] transcodes it.
     */
    fun sendVideoNote(raw: File) {
        replyTo.value = null
        viewModelScope.launch {
            // ponytail: thumbnails stay in the cache dir (tens of KB each), TDLib uploads from
            // them; delete them on updateMessageSendSucceeded if cache size starts to matter
            val thumbnail = File(raw.parentFile, raw.name.replace("_raw.mp4", ".jpg"))
            val (probe, thumbnailSide) = withContext(Dispatchers.IO) {
                val probe = runCatching { VideoNoteCamera.probe(raw) }.getOrNull()
                    ?: (VideoNoteCamera.MAX_LENGTH to VideoNoteCamera.MAX_DURATION_MS)
                val thumbnailSide = runCatching { VideoNoteCamera.thumbnail(raw, thumbnail) }
                    .onFailure { Log.w(LOG_TAG, "Video note thumbnail failed", it) }
                    .getOrNull()
                probe to thumbnailSide
            }
            val (side, durationMs) = probe
            history.await().sendVideoNote(
                raw,
                ((durationMs + 500) / 1000).toInt().coerceIn(1, 60),
                side,
                thumbnailSide?.let { thumbnail.absolutePath to it },
            )?.let { _sendFailures.send(Failed(it.message)) }
        }
    }

    /** Start or cancel downloading a document, its bubble shows the progress. */
    fun toggleDownload(document: MessageItem.Document) {
        viewModelScope.launch { Downloads.toggleDownload(client.await(), document) }
    }

    /** Stop sending a message whose file is still uploading. */
    fun cancelUpload(messageId: Long) {
        viewModelScope.launch { history.await().delete(messageId) }
    }

    /** The pinned messages, newest first; none for the scheduled ones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pinned = flow {
        if (!isScheduled) {
            emitAll(history.await().pinned())
        }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatHistory.PinnedMessages())

    fun setPinned(messageId: Long, isPinned: Boolean) {
        viewModelScope.launch { history.await().setPinned(messageId, isPinned) }
    }

    /**
     * A member to mention, offered while an @word is typed.
     *
     * @param username Mentioned as @username, by name when there's none
     */
    data class MentionSuggestion(
        val userId: Long,
        val name: String,
        val username: String?,
        val accentColorId: Int,
        val photoPath: String?,
    )

    /** The @word being typed without the @, empty right after it; null when there's none. */
    val mentionQuery = MutableStateFlow<String?>(null)

    /** Only groups have members to mention, not channels or private chats. */
    private val canMention = viewModelScope.async {
        when (val type = (client.await().send(TdApi.GetChat(chatId)) as? Result.Success)?.data?.type) {
            is TdApi.ChatTypeBasicGroup -> true
            is TdApi.ChatTypeSupergroup -> !type.isChannel
            else -> false
        }
    }

    private val myUserId = viewModelScope.async {
        (client.await().send(TdApi.GetMe()) as? Result.Success)?.data?.id ?: 0L
    }

    /**
     * The members matching [mentionQuery], all of them for an empty one, this account left out.
     *
     * ponytail: the first [MENTION_LIMIT] members TDLib finds, Telegram's limit per request
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val mentionSuggestions = mentionQuery
        .debounce { if (it.isNullOrEmpty()) 0L else MENTION_DEBOUNCE_MS }
        .mapLatest { query ->
            if (query == null || !canMention.await()) {
                return@mapLatest listOf()
            }
            val client = client.await()
            val members = (client.send(TdApi.SearchChatMembers(chatId, query, MENTION_LIMIT, null)) as? Result.Success)
                ?.data?.members.orEmpty()
            val myUserId = myUserId.await()
            members.mapNotNull { member ->
                val userId = (member.memberId as? TdApi.MessageSenderUser)?.userId
                    ?.takeIf { it != myUserId } ?: return@mapNotNull null
                val sender = client.chats.sender(member.memberId)
                MentionSuggestion(
                    userId = userId,
                    name = sender.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    username = client.chats.username(userId),
                    accentColorId = sender.accentColorId,
                    photoPath = sender.photo?.local?.takeIf { it.isDownloadingCompleted }?.path,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf())

    /** The chat or user an @username stands for, null if there's none. */
    suspend fun resolveUsername(username: String): Long? =
        (client.await().send(TdApi.SearchPublicChat(username)) as? Result.Success)?.data?.id

    /** Back to the newest messages, reloading them when reading old ones. */
    fun showNewest() {
        viewModelScope.launch { history.await().showNewest() }
    }

    /** The oldest unread mention, or message with unseen reactions; null for none. */
    suspend fun firstUnread(isReaction: Boolean) = history.await().firstUnread(isReaction)

    /** Load the history around [messageId] to show it, unless it's loaded already. */
    fun loadAt(messageId: Long) {
        viewModelScope.launch { history.await().loadAt(messageId) }
    }

    fun view(messageIds: LongArray) {
        viewModelScope.launch { history.await().view(messageIds) }
    }

    companion object {
        private const val LOG_TAG = "ChatViewModel"

        private const val STICKERS_STOP_MS = 5_000L
        private const val GIF_SEARCH_DEBOUNCE_MS = 400L
        private const val TYPING_REPEAT_MS = 5_000L
        private const val MENTION_LIMIT = 200
        private const val MENTION_DEBOUNCE_MS = 200L

        const val ARG_CHAT_ID = "chat_id"
        const val ARG_TOPIC_ID = "topic_id"
        const val ARG_SCHEDULED = "scheduled"
    }
}

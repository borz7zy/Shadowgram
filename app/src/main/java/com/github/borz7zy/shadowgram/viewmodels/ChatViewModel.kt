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
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.telegram.TelegramErrorText
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.ChatHeader
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.MessageRow
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.telegram.ChatHistory
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.InputContents
import com.github.borz7zy.shadowgram.telegram.MessageEdits
import com.github.borz7zy.shadowgram.telegram.StickersStore
import com.github.borz7zy.shadowgram.ui.text.CustomEmojiRange
import com.github.borz7zy.shadowgram.ui.text.MentionRange
import com.github.borz7zy.shadowgram.videonote.VideoNoteCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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
import kotlinx.coroutines.flow.transformLatest
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

    // Serialize the chat's commands while keeping history locks and JNI calls off main.
    private val historyDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val history = viewModelScope.async(historyDispatcher) {
        ChatHistory(client.await(), chatId, topicId, isScheduled)
    }

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

    val selectedMessages = MutableStateFlow<Map<Long, MessageItem>>(emptyMap())
    val selectionProperties = MutableStateFlow<Map<Long, TdApi.MessageProperties>>(emptyMap())
    @OptIn(ExperimentalCoroutinesApi::class)
    val editableSelectionId = combine(selectedMessages, selectionProperties) { selected, properties ->
        val id = selected.keys.singleOrNull() ?: return@combine null
        properties[id]?.takeIf { it.canBeEdited }?.let { id to it }
    }
        .distinctUntilChanged()
        .mapLatest { selection ->
            val (id, properties) = selection ?: return@mapLatest null
            val message = (client.await().send(TdApi.GetMessage(chatId, id)) as? Result.Success)?.data
            if (message != null && MessageEdits.target(message, properties) != null) id else null
        }
        .flowOn(historyDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    val selectionBusy = MutableStateFlow(false)
    private val actionErrorsChannel = Channel<String>(Channel.BUFFERED)
    val actionErrors = actionErrorsChannel.receiveAsFlow()

    fun toggleMessageSelection(message: MessageItem) {
        if (message.state == MessageItem.State.PENDING || message.state == MessageItem.State.FAILED) return
        if (message.id in selectedMessages.value) {
            selectedMessages.value = selectedMessages.value - message.id
            selectionProperties.value = selectionProperties.value - message.id
            return
        }
        if (selectedMessages.value.size >= 100) {
            actionErrorsChannel.trySend(getApplication<Application>().getString(R.string.selection_limit))
            return
        }
        selectedMessages.value = selectedMessages.value + (message.id to message)
        viewModelScope.launch {
            when (val result = client.await().send(TdApi.GetMessageProperties(chatId, message.id))) {
                is Result.Success -> if (message.id in selectedMessages.value) {
                    selectionProperties.value = selectionProperties.value + (message.id to result.data)
                }
                is Result.Failure -> actionErrorsChannel.send(TelegramErrorText.message(getApplication(), result.error))
            }
        }
    }

    fun clearMessageSelection() {
        selectedMessages.value = emptyMap()
        selectionProperties.value = emptyMap()
    }

    fun deleteSelectedMessages(revoke: Boolean) {
        val ids = selectedMessages.value.keys.toLongArray()
        if (ids.isEmpty() || selectionBusy.value) return
        selectionBusy.value = true
        viewModelScope.launch {
            try {
                val properties = ids.map { (client.await().send(TdApi.GetMessageProperties(chatId, it)) as? Result.Success)?.data }
                if (properties.any { it == null || if (revoke) !it.canBeDeletedForAllUsers else !it.canBeDeletedOnlyForSelf }) {
                    actionErrorsChannel.send(getApplication<Application>().getString(R.string.selection_error_unavailable))
                    return@launch
                }
                when (val result = client.await().send(TdApi.DeleteMessages(chatId, ids, revoke))) {
                    is Result.Success -> {
                        selectedMessages.value = selectedMessages.value - ids.toSet()
                        selectionProperties.value = selectionProperties.value - ids.toSet()
                    }
                    is Result.Failure -> actionErrorsChannel.send(TelegramErrorText.message(getApplication(), result.error))
                }
            } finally { selectionBusy.value = false }
        }
    }

    fun favoriteSelectedSticker() {
        val id = selectedMessages.value.keys.singleOrNull() ?: return
        if (selectionBusy.value) return
        selectionBusy.value = true
        viewModelScope.launch {
            try {
                val message = (client.await().send(TdApi.GetMessage(chatId, id)) as? Result.Success)?.data ?: return@launch
                val sticker = (message.content as? TdApi.MessageSticker)?.sticker ?: return@launch
                when (val result = client.await().send(TdApi.AddFavoriteSticker(TdApi.InputFileId(sticker.sticker.id)))) {
                    is Result.Success -> actionErrorsChannel.send(getApplication<Application>().getString(R.string.selection_sticker_added))
                    is Result.Failure -> actionErrorsChannel.send(TelegramErrorText.message(getApplication(), result.error))
                }
            } finally { selectionBusy.value = false }
        }
    }


    fun toggleMute() {
        viewModelScope.launch {
            val current = client.await()
            val chat = (current.send(TdApi.GetChat(chatId)) as? Result.Success)?.data ?: return@launch
            val settings = chat.notificationSettings.apply {
                useDefaultMuteFor = false
                muteFor = if (header.value?.chat?.isMuted == true) 0 else Int.MAX_VALUE
            }
            val result = current.send(TdApi.SetChatNotificationSettings(chatId, settings))
            if (result is Result.Failure) actionErrorsChannel.send(TelegramErrorText.message(getApplication(), result.error))
        }
    }

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
        viewModelScope.launch(historyDispatcher) { history.await().loadOlder() }
    }

    fun loadNewer() {
        viewModelScope.launch(historyDispatcher) { history.await().loadNewer() }
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
        viewModelScope.launch(historyDispatcher) { history.await().setTyping(isTyping) }
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
        viewModelScope.launch(historyDispatcher) {
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
        viewModelScope.launch(historyDispatcher) {
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
        viewModelScope.launch(historyDispatcher) {
            val entities = (customEmoji.map {
                TdApi.TextEntity(it.offset, it.length, TdApi.TextEntityTypeCustomEmoji(it.customEmojiId))
            } + mentions.map {
                TdApi.TextEntity(it.offset, it.length, TdApi.TextEntityTypeMentionName(it.userId))
            }).sortedBy { it.offset }.toTypedArray()
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

    data class ComposerDraft(val text: TdApi.FormattedText, val start: Int, val end: Int)

    var normalDraft: ComposerDraft? = null
    var editDraft: ComposerDraft? = null
    var editPickerMessageId: Long? = null
    var pendingPhoto: Pair<Uri, Long?>? = null

    data class Edit(
        val target: MessageEdits.Target,
        val replacement: InputContents.Picked? = null,
        val previewPath: String? = null,
        val isSaving: Boolean = false,
    )

    private val _edit = MutableStateFlow<Edit?>(null)
    val edit = _edit.asStateFlow()
    private var loadEdit: Job? = null
    private var loadEditPreview: Job? = null

    fun startEditing(messageId: Long) {
        if (_edit.value?.isSaving == true || _edit.value?.target?.message?.id == messageId) return
        loadEdit?.cancel()
        loadEdit = viewModelScope.launch {
            val target = withContext(historyDispatcher) { editableMessage(messageId) }
                ?: return@launch editError(R.string.message_edit_unavailable)
            if (_edit.value?.isSaving == true) return@launch

            loadEditPreview?.cancel()
            editDraft = null
            val thumbnail = target.attachment?.thumbnail
            val path = thumbnail?.local?.takeIf { it.isDownloadingCompleted }?.path
            _edit.value = Edit(target, previewPath = path)
            onTyping(isEmpty = true)
            if (path == null && thumbnail != null) {
                loadEditPreview = viewModelScope.launch {
                    val result = withContext(historyDispatcher) {
                        client.await().send(TdApi.DownloadFile(thumbnail.id, 16, 0, 0, true))
                    }
                    val local = (result as? Result.Success)?.data?.local
                    val current = _edit.value
                    if (current?.target === target && local?.isDownloadingCompleted == true) {
                        _edit.value = current.copy(previewPath = local.path)
                    }
                }
            }
        }
    }

    fun replaceEditedAttachment(messageId: Long, picked: InputContents.Picked?) {
        val current = _edit.value ?: return
        if (current.target.message.id == messageId && current.target.canReplaceMedia && !current.isSaving) {
            _edit.value = current.copy(replacement = picked)
        }
    }

    fun cancelEditing() {
        if (_edit.value?.isSaving == true) return
        finishEditing()
    }

    private fun finishEditing() {
        loadEdit?.cancel()
        loadEditPreview?.cancel()
        _edit.value = null
        editDraft = null
    }

    private suspend fun editableMessage(messageId: Long): MessageEdits.Target? {
        val client = client.await()
        val message = (client.send(TdApi.GetMessage(chatId, messageId)) as? Result.Success)?.data
            ?: return null
        val properties = (client.send(TdApi.GetMessageProperties(chatId, messageId)) as? Result.Success)?.data
            ?: return null
        return MessageEdits.target(message, properties)
    }

    fun saveEdit(text: TdApi.FormattedText) {
        val current = _edit.value ?: return
        if (current.isSaving || (!current.target.isCaption && current.replacement == null && text.text.isBlank())) {
            return
        }
        loadEdit?.cancel()
        _edit.value = current.copy(isSaving = true)
        viewModelScope.launch {
            try {
                if (withContext(historyDispatcher) { submitEdit(current, text) }) finishEditing()
            } finally {
                _edit.value?.let { _edit.value = it.copy(isSaving = false) }
            }
        }
    }

    private suspend fun submitEdit(edit: Edit, text: TdApi.FormattedText): Boolean {
        val original = edit.target.message
        val target = editableMessage(original.id)
        if (target == null || target.message.content.constructor != original.content.constructor ||
            (edit.replacement != null && !target.canReplaceMedia)
        ) {
            editError(R.string.message_edit_unavailable)
            return false
        }
        val replacement = edit.replacement?.let {
            val input = try {
                InputContents.fromUri(
                    getApplication(), it.uri, it.asFile, caption = null, preferAudio = target.prefersAudio,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Can't prepare edited attachment", error)
                null
            }
            if (input == null) {
                editError(R.string.chat_send_unreadable)
                return false
            }
            if (!target.accepts(input)) {
                editError(R.string.message_edit_album_type)
                return false
            }
            input
        }
        val caption = if (text.text.isBlank()) TdApi.FormattedText("", emptyArray()) else text
        return when (val result = client.await().send(target.request(caption, replacement))) {
            is Result.Success -> true
            is Result.Failure -> {
                if (result.error.message == "MESSAGE_NOT_MODIFIED") return true
                actionErrorsChannel.send(
                    getApplication<Application>().getString(
                        R.string.message_edit_failed, TelegramErrorText.message(getApplication(), result.error),
                    )
                )
                false
            }
        }
    }

    private fun editError(resource: Int) {
        actionErrorsChannel.trySend(getApplication<Application>().getString(resource))
    }

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
        viewModelScope.launch(historyDispatcher) {
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
        .flowOn(Dispatchers.Default)
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
        viewModelScope.launch(historyDispatcher) {
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
        viewModelScope.launch(historyDispatcher) { history.await().delete(messageId) }
    }

    /** The pinned messages, newest first; none for the scheduled ones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pinned = flow {
        if (!isScheduled) {
            emitAll(history.await().pinned())
        }
    }
        .flowOn(historyDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatHistory.PinnedMessages())

    fun setPinned(messageId: Long, isPinned: Boolean) {
        viewModelScope.launch(historyDispatcher) { history.await().setPinned(messageId, isPinned) }
    }

    /**
     * A row offered over the composer for what is typed: a member to mention, a bot command,
     * an inline bot result.
     *
     * @param key Unique among the rows
     * @param avatarId Whose letter and color the avatar falls back to
     */
    data class Suggestion(
        val key: String,
        val name: String,
        val text: String?,
        val avatarId: Long,
        val accentColorId: Int,
        val photoPath: String?,
        val action: Action,
    )

    sealed interface Action {
        /** @param username Mentioned as @username, by name when there's none */
        data class Mention(val userId: Long, val name: String, val username: String?) : Action

        /** Sent as is, /command or /command@bot */
        data class Command(val text: String) : Action

        data class InlineResult(val queryId: Long, val resultId: String) : Action
    }

    /** The @word being typed without the @, empty right after it; null when there's none. */
    val mentionQuery = MutableStateFlow<String?>(null)

    /** All of the composer's text, for a /command or an @bot inline query. */
    val composerText = MutableStateFlow("")

    private val chatType = viewModelScope.async {
        (client.await().send(TdApi.GetChat(chatId)) as? Result.Success)?.data?.type
    }

    /** Only groups have members to mention, not channels or private chats. */
    private val canMention = viewModelScope.async {
        when (val type = chatType.await()) {
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
    private val mentionSuggestions = mentionQuery
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
                val name = sender.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val username = client.chats.username(userId)
                Suggestion(
                    key = "u$userId",
                    name = name,
                    text = username?.let { "@$it" },
                    avatarId = userId,
                    accentColorId = sender.accentColorId,
                    photoPath = sender.photo?.local?.takeIf { it.isDownloadingCompleted }?.path,
                    action = Action.Mention(userId, name, username),
                )
            }
        }

    /** Every command of the bots here: the bot of a private chat, those added to a group. */
    private val botCommands = viewModelScope.async(Dispatchers.Default) {
        val client = client.await()
        val type = chatType.await()
        val bots = when (type) {
            is TdApi.ChatTypePrivate -> (client.send(TdApi.GetUserFullInfo(type.userId)) as? Result.Success)
                ?.data?.botInfo?.commands?.let { arrayOf(TdApi.BotCommands(type.userId, it)) }

            is TdApi.ChatTypeBasicGroup ->
                (client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) as? Result.Success)?.data?.botCommands

            is TdApi.ChatTypeSupergroup ->
                (client.send(TdApi.GetSupergroupFullInfo(type.supergroupId)) as? Result.Success)?.data?.botCommands

            else -> null
        }.orEmpty()
        bots.flatMap { bot ->
            val sender = client.chats.sender(TdApi.MessageSenderUser(bot.botUserId))
            // In a group the bot is named, so the command goes to it and not to another
            val suffix = client.chats.username(bot.botUserId)
                ?.takeIf { type !is TdApi.ChatTypePrivate }?.let { "@$it" }.orEmpty()
            bot.commands.map {
                Suggestion(
                    key = "c${bot.botUserId}/${it.command}",
                    name = "/${it.command}",
                    text = it.description.takeIf { it.isNotBlank() },
                    avatarId = bot.botUserId,
                    accentColorId = sender.accentColorId,
                    photoPath = sender.photo?.local?.takeIf { it.isDownloadingCompleted }?.path,
                    action = Action.Command("/${it.command}$suffix"),
                )
            }
        }
    }

    /** Whether the composer offers the bots' commands. */
    val hasBotCommands = flow { emit(botCommands.await().isNotEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The commands starting with the /word that is all of the text. */
    private val commandSuggestions = combine(composerText, flow { emit(botCommands.await()) }) { text, commands ->
        COMMAND_REGEX.matchEntire(text)?.let { match ->
            commands.filter { it.name.startsWith("/${match.groupValues[1]}", ignoreCase = true) }
        }.orEmpty()
    }

    /**
     * What an inline bot finds for "@bot query", the text starting with it. Shown at once, again
     * once the thumbnails are downloaded.
     *
     * ponytail: the first page only, the next with [TdApi.InlineQueryResults.nextOffset] when
     * scrolled to the end; no location for the bots that ask for it, no switch_pm button
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val inlineSuggestions = composerText
        .map { text -> INLINE_REGEX.matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] } }
        .distinctUntilChanged()
        .debounce { if (it == null) 0L else INLINE_DEBOUNCE_MS }
        .transformLatest { query ->
            val rows = query?.let { (username, text) -> inlineResults(username, text) }.orEmpty()
            emit(rows.map { it.first })
            val client = client.await()
            val downloaded = coroutineScope {
                rows.map { (row, file) ->
                    async {
                        val path = file?.takeIf { row.photoPath == null }?.let {
                            (client.send(TdApi.DownloadFile(it.id, INLINE_DOWNLOAD_PRIORITY, 0, 0, true)) as? Result.Success)
                                ?.data?.local?.takeIf { it.isDownloadingCompleted }?.path
                        }
                        path?.let { row.copy(photoPath = it) } ?: row
                    }
                }.awaitAll()
            }
            if (downloaded != rows.map { it.first }) {
                emit(downloaded)
            }
        }

    /** The rows for an inline query with their thumbnails, empty when @[username] isn't an inline bot. */
    private suspend fun inlineResults(username: String, query: String): List<Pair<Suggestion, TdApi.File?>> {
        val client = client.await()
        val chat = (client.send(TdApi.SearchPublicChat(username)) as? Result.Success)?.data
        val botUserId = (chat?.type as? TdApi.ChatTypePrivate)?.userId ?: return listOf()
        val user = (client.send(TdApi.GetUser(botUserId)) as? Result.Success)?.data
        if ((user?.type as? TdApi.UserTypeBot)?.isInline != true) {
            return listOf()
        }
        val results = (client.send(TdApi.GetInlineQueryResults(botUserId, chatId, null, query, "")) as? Result.Success)
            ?.data ?: return listOf()
        return results.results.mapNotNull { result ->
            val (id, title, text, thumbnail) = when (result) {
                is TdApi.InlineQueryResultArticle ->
                    InlineRow(result.id, result.title, result.description, result.thumbnail?.file)

                is TdApi.InlineQueryResultPhoto ->
                    InlineRow(result.id, result.title, result.description, result.photo.sizes.firstOrNull()?.photo)

                is TdApi.InlineQueryResultAnimation ->
                    InlineRow(result.id, result.title, null, result.animation.thumbnail?.file)

                is TdApi.InlineQueryResultVideo ->
                    InlineRow(result.id, result.title, result.description, result.video.thumbnail?.file)

                is TdApi.InlineQueryResultDocument ->
                    InlineRow(result.id, result.title, result.description, result.document.thumbnail?.file)

                is TdApi.InlineQueryResultAudio -> InlineRow(
                    result.id, result.audio.title, result.audio.performer, result.audio.albumCoverThumbnail?.file
                )

                is TdApi.InlineQueryResultVoiceNote -> InlineRow(result.id, result.title, null, null)
                is TdApi.InlineQueryResultSticker ->
                    InlineRow(result.id, result.sticker.emoji, null, result.sticker.thumbnail?.file)

                is TdApi.InlineQueryResultContact -> InlineRow(
                    result.id,
                    "${result.contact.firstName} ${result.contact.lastName}".trim(),
                    result.contact.phoneNumber,
                    result.thumbnail?.file,
                )

                is TdApi.InlineQueryResultLocation -> InlineRow(result.id, result.title, null, result.thumbnail?.file)
                is TdApi.InlineQueryResultVenue ->
                    InlineRow(result.id, result.venue.title, result.venue.address, result.thumbnail?.file)

                is TdApi.InlineQueryResultGame -> InlineRow(
                    result.id, result.game.title, result.game.description, result.game.photo.sizes.firstOrNull()?.photo
                )

                else -> return@mapNotNull null
            }
            Suggestion(
                key = "i$id",
                name = title?.takeIf { it.isNotBlank() } ?: "@$username",
                text = text?.takeIf { it.isNotBlank() },
                avatarId = botUserId,
                accentColorId = user.accentColorId,
                photoPath = thumbnail?.local?.takeIf { it.isDownloadingCompleted }?.path,
                action = Action.InlineResult(results.inlineQueryId, id),
            ) to thumbnail
        }
    }

    private data class InlineRow(val id: String, val title: String?, val text: String?, val thumbnail: TdApi.File?)

    /** The rows over the composer: an inline query's results, else commands, else members. */
    val suggestions = combine(
        inlineSuggestions, commandSuggestions, mentionSuggestions, mentionQuery,
    ) { inline, commands, mentions, mentionQuery ->
        when {
            inline.isNotEmpty() -> inline
            commands.isNotEmpty() -> commands
            mentionQuery != null -> mentions
            else -> listOf()
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf())

    /** Send the result picked from an inline bot's, "via @bot" shown on it. */
    fun sendInlineResult(result: Action.InlineResult) {
        clearReply()
        typingSentAt = 0L
        sendNow { it.sendInlineResult(result.queryId, result.resultId, hideViaBot = false) }
    }

    /** The chat or user an @username stands for, null if there's none. */
    suspend fun resolveUsername(username: String): Long? =
        (client.await().send(TdApi.SearchPublicChat(username)) as? Result.Success)?.data?.id

    /** Back to the newest messages, reloading them when reading old ones. */
    fun showNewest() {
        viewModelScope.launch(historyDispatcher) { history.await().showNewest() }
    }

    /** The oldest unread mention, or message with unseen reactions; null for none. */
    suspend fun firstUnread(isReaction: Boolean) = history.await().firstUnread(isReaction)

    /** Load the history around [messageId] to show it, unless it's loaded already. */
    fun loadAt(messageId: Long) {
        viewModelScope.launch(historyDispatcher) { history.await().loadAt(messageId) }
    }

    fun view(messageIds: LongArray) {
        viewModelScope.launch(historyDispatcher) { history.await().view(messageIds) }
    }

    companion object {
        private const val LOG_TAG = "ChatViewModel"

        private const val STICKERS_STOP_MS = 5_000L
        private const val GIF_SEARCH_DEBOUNCE_MS = 400L
        private const val TYPING_REPEAT_MS = 5_000L
        private const val MENTION_LIMIT = 200
        private const val MENTION_DEBOUNCE_MS = 200L
        private const val INLINE_DEBOUNCE_MS = 400L
        private const val INLINE_DOWNLOAD_PRIORITY = 16

        /** A /word that is all of the text, the start of a command. */
        private val COMMAND_REGEX = Regex("/(\\w*)")

        /** "@bot query": an inline query, from the space after the username on. */
        private val INLINE_REGEX = Regex("@(\\w{3,}) ([\\s\\S]*)")

        const val ARG_CHAT_ID = "chat_id"
        const val ARG_TOPIC_ID = "topic_id"
        const val ARG_SCHEDULED = "scheduled"
    }
}

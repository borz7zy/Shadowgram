/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.ChatListKey
import com.github.borz7zy.shadowgram.models.ChatRow
import com.github.borz7zy.shadowgram.models.ChatBulkAction
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.TelegramErrorText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.github.borz7zy.shadowgram.models.FlowResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Chats of a single chat list and their selection. Kept across configuration changes, so nothing
 * is reloaded and the selection survives.
 */
class ChatListViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val chatListKey = ChatListKey.of(
        savedStateHandle.get<Long>(ARG_CHAT_LIST_STABLE_ID) ?: ChatListKey.Main.stableId
    )

    // Signed in first: TDLib can't load chats before, opened from another app's "Share" the
    // launcher didn't wait for it
    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient().apply {
            authorizationState.first { it is TdApi.AuthorizationStateReady }
        }
    }
    private val chatsStore = viewModelScope.async { client.await().chats }

    private val chats = flow { emitAll(chatsStore.await().chatList(chatListKey)) }
        .map<_, FlowResult<List<ChatItem>, Nothing>> {
            when (it.isLoaded) {
                true -> FlowResult.Success(it.items)
                false -> FlowResult.Loading
            }
        }

    private val selectedChatIds = MutableStateFlow(setOf<Long>())
    val selectionBusy = MutableStateFlow(false)
    private val errorsChannel = Channel<String>(Channel.BUFFERED)
    val errors = errorsChannel.receiveAsFlow()
    val selectedChats = combine(chats, selectedChatIds) { result, ids ->
        (result as? FlowResult.Success)?.data.orEmpty().filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The forum of this list whose topics are shown, null for none. */
    private val forumChatId = MutableStateFlow<Long?>(null)

    val rows = combine(chats, selectedChatIds, forumChatId) { chats, selectedChatIds, forumChatId ->
        when (chats) {
            is FlowResult.Success -> FlowResult.Success(
                chats.data.map {
                    ChatRow(
                        it,
                        it.id in selectedChatIds,
                        selectedChatIds.isNotEmpty(),
                        isOpenForum = it.id == forumChatId,
                    )
                }
            )

            is FlowResult.Loading -> chats
            is FlowResult.Failure -> chats
        }
    }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FlowResult.Loading,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val topicRows = forumChatId
        .flatMapLatest { chatId ->
            when (chatId) {
                null -> flowOf(FlowResult.Loading)
                else -> flow { emitAll(chatsStore.await().forumTopics(chatId)) }
                    .map<_, FlowResult<List<ChatRow>, Nothing>> { snapshot ->
                        when (snapshot.isLoaded) {
                            true -> FlowResult.Success(
                                snapshot.items.map { ChatRow(it, false, false) }
                            )

                            false -> FlowResult.Loading
                        }
                    }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FlowResult.Loading,
        )

    fun setForum(chatId: Long?) {
        forumChatId.value = chatId
    }

    val isSelectionMode = selectedChatIds
        .map { it.isNotEmpty() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            false,
        )

    fun toggleSelection(chatId: Long) {
        if (selectionBusy.value) return
        selectedChatIds.update {
            when (chatId in it) {
                true -> it - chatId
                false -> it + chatId
            }
        }
    }

    fun clearSelection() {
        if (selectionBusy.value) return
        selectedChatIds.value = setOf()
    }

    fun performSelection(action: ChatBulkAction) {
        val selected = selectedChats.value.toList()
        if (selectionBusy.value || selected.isEmpty()) return
        if (action == ChatBulkAction.CLEAR && com.github.borz7zy.shadowgram.models.chatHistoryClearAction(selected) == null) return
        if (action == ChatBulkAction.REMOVE_FOLDER) {
            val folder = chatListKey as? ChatListKey.Folder ?: return
            editFolder(folder.id, remove = true)
            return
        }
        selectionBusy.value = true
        viewModelScope.launch {
            val current = client.await()
            val failed = mutableSetOf<Long>()
            val mute = selected.any { !it.isMuted }
            val pin = selected.any { !it.isPinned }
            try {
                for (chat in selected) {
                    val result: Result<out TdApi.Object, TdApi.Error> = when (action) {
                        ChatBulkAction.ARCHIVE -> current.send(TdApi.AddChatToList(chat.id,
                            if (chatListKey == ChatListKey.Archive) TdApi.ChatListMain() else TdApi.ChatListArchive()))
                        ChatBulkAction.PIN -> current.send(TdApi.ToggleChatIsPinned(chatListKey.toTdApi(), chat.id, pin))
                        ChatBulkAction.UNREAD -> current.send(TdApi.ToggleChatIsMarkedAsUnread(chat.id, true))
                        ChatBulkAction.MUTE -> {
                            when (val response = current.send(TdApi.GetChat(chat.id))) {
                                is Result.Failure -> response
                                is Result.Success -> current.send(TdApi.SetChatNotificationSettings(chat.id, response.data.notificationSettings.apply {
                                    useDefaultMuteFor = false
                                    muteFor = if (mute) Int.MAX_VALUE else 0
                                }))
                            }
                        }
                        ChatBulkAction.DELETE -> {
                            when (val response = current.send(TdApi.GetChat(chat.id))) {
                                is Result.Failure -> response
                                is Result.Success -> when (response.data.type) {
                                    is TdApi.ChatTypeSupergroup -> current.send(TdApi.LeaveChat(chat.id))
                                    is TdApi.ChatTypeBasicGroup -> {
                                        when (val left = current.send(TdApi.LeaveChat(chat.id))) {
                                            is Result.Failure -> left
                                            is Result.Success -> current.send(TdApi.DeleteChatHistory(chat.id, true, false))
                                        }
                                    }
                                    else -> current.send(TdApi.DeleteChatHistory(chat.id, true, false))
                                }
                            }
                        }
                        ChatBulkAction.CLEAR -> if (chat.clearsCacheOnly) current.send(TdApi.OptimizeStorage(
                            0, 0, 0, 0, null, longArrayOf(chat.id), null, true, 0
                        )) else current.send(TdApi.DeleteChatHistory(chat.id, false, false))
                        ChatBulkAction.REMOVE_FOLDER -> error("Handled above")
                    }
                    if (result is Result.Failure) {
                        failed += chat.id
                        errorsChannel.send(TelegramErrorText.message(getApplication(), result.error))
                    }
                }
                selectedChatIds.value = failed
            } finally { selectionBusy.value = false }
        }
    }

    fun addSelectionToFolder(folderId: Int) = editFolder(folderId, remove = false)

    private fun editFolder(folderId: Int, remove: Boolean) {
        if (selectionBusy.value) return
        val ids = selectedChats.value.map { it.id }.toSet()
        if (ids.isEmpty()) return
        selectionBusy.value = true
        viewModelScope.launch {
            try {
                val current = client.await()
                val result = when (val response = current.send(TdApi.GetChatFolder(folderId))) {
                    is Result.Failure -> response
                    is Result.Success -> {
                        val folder = response.data
                        if (remove) {
                            folder.pinnedChatIds = folder.pinnedChatIds.filterNot { it in ids }.toLongArray()
                            folder.includedChatIds = folder.includedChatIds.filterNot { it in ids }.toLongArray()
                            folder.excludedChatIds = (folder.excludedChatIds.toList() + ids).distinct().toLongArray()
                        } else {
                            folder.excludedChatIds = folder.excludedChatIds.filterNot { it in ids }.toLongArray()
                            folder.includedChatIds = (folder.includedChatIds.toList() + ids.filterNot { it in folder.pinnedChatIds }).distinct().toLongArray()
                        }
                        current.send(TdApi.EditChatFolder(folderId, folder))
                    }
                }
                when (result) {
                    is Result.Success -> selectedChatIds.value = emptySet()
                    is Result.Failure -> errorsChannel.send(TelegramErrorText.message(getApplication(), result.error))
                }
            } finally { selectionBusy.value = false }
        }
    }

    /**
     * @see com.github.borz7zy.shadowgram.telegram.ChatsStore.setPinnedChats
     */
    suspend fun setPinnedChats(chatIds: List<Long>) =
        chatsStore.await().setPinnedChats(chatListKey, chatIds)

    fun loadMore() {
        viewModelScope.launch {
            chatsStore.await().loadMore(chatListKey)
        }
    }

    companion object {
        /**
         * @see ChatListKey.stableId
         */
        const val ARG_CHAT_LIST_STABLE_ID = "stable_id"
    }
}

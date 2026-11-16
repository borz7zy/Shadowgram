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
    private val chatsStore = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient().apply {
            authorizationState.first { it is TdApi.AuthorizationStateReady }
        }.chats
    }

    private val chats = flow { emitAll(chatsStore.await().chatList(chatListKey)) }
        .map<_, FlowResult<List<ChatItem>, Nothing>> {
            when (it.isLoaded) {
                true -> FlowResult.Success(it.items)
                false -> FlowResult.Loading
            }
        }

    private val selectedChatIds = MutableStateFlow(setOf<Long>())

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
        selectedChatIds.update {
            when (chatId in it) {
                true -> it - chatId
                false -> it + chatId
            }
        }
    }

    fun clearSelection() {
        selectedChatIds.value = setOf()
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

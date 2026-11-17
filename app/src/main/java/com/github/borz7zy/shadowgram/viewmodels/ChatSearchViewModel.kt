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
import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.toPreviewContent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * The messages of a chat found for a query, all of them for none; or its pinned messages.
 * Newest first, a page at a time.
 */
class ChatSearchViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val chatId = savedStateHandle.get<Long>(ARG_CHAT_ID) ?: 0L
    private val topicId = savedStateHandle.get<Int>(ARG_TOPIC_ID) ?: 0
    val isPinned = savedStateHandle.get<Boolean>(ARG_PINNED) ?: false

    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient()
    }

    /**
     * @param name Who sent it
     * @param photoPath Their small photo, when it's downloaded
     * @param text What it says, null when it's only its [kind]
     */
    data class Found(
        val messageId: Long,
        val name: String,
        val accentColorId: Int,
        val photoPath: String?,
        val kind: MessagePreview.Kind,
        val text: String?,
        val date: Int,
    )

    /**
     * @param isLoaded TDLib answered the first page, an empty list really is empty
     * @param isEnd Nothing more to page through
     */
    data class State(
        val found: List<Found> = listOf(),
        val isLoaded: Boolean = false,
        val isEnd: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    val query = MutableStateFlow("")

    private var nextFromMessageId = 0L
    private var pageJob: Job? = null

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            query.debounce { if (it.isEmpty()) 0L else QUERY_DEBOUNCE_MS }.collectLatest {
                pageJob?.cancel()
                nextFromMessageId = 0L
                _state.value = State()
                loadPage(it)
            }
        }
    }

    fun loadMore() {
        val state = _state.value
        if (!state.isLoaded || state.isEnd || pageJob?.isActive == true) {
            return
        }
        pageJob = viewModelScope.launch { loadPage(query.value) }
    }

    private suspend fun loadPage(query: String) {
        val client = client.await()
        val result = client.send(
            TdApi.SearchChatMessages(
                chatId,
                topicId.takeIf { it != 0 }?.let { TdApi.MessageTopicForum(it) },
                query,
                null,
                nextFromMessageId,
                0,
                PAGE_SIZE,
                if (isPinned) TdApi.SearchMessagesFilterPinned() else null,
            )
        )
        val page = (result as? Result.Success)?.data
        val found = page?.messages.orEmpty().map {
            val sender = client.chats.sender(it.senderId)
            val (kind, text) = it.content.toPreviewContent()
            Found(
                messageId = it.id,
                name = sender.name.orEmpty(),
                accentColorId = sender.accentColorId,
                photoPath = sender.photo?.local?.takeIf { file -> file.isDownloadingCompleted }?.path,
                kind = kind,
                text = text,
                date = it.date,
            )
        }
        nextFromMessageId = page?.nextFromMessageId ?: 0L
        _state.value = State(
            found = _state.value.found + found,
            isLoaded = true,
            isEnd = nextFromMessageId == 0L,
        )
    }

    companion object {
        const val ARG_CHAT_ID = "chat_id"
        const val ARG_TOPIC_ID = "topic_id"
        const val ARG_PINNED = "pinned"

        private const val PAGE_SIZE = 50
        private const val QUERY_DEBOUNCE_MS = 300L
    }
}

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
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.InputContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Sends what the chat picker was opened with to the picked chat: messages forwarded, or files and
 * text shared by another app. Runs in the view model, so a rotation doesn't cut it halfway.
 */
class ChatPickerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val fromChatId = savedStateHandle.get<Long>(ARG_FROM_CHAT_ID) ?: 0L
    private val messageIds = savedStateHandle.get<LongArray>(ARG_MESSAGE_IDS)
    private val uris = savedStateHandle.get<Array<String>>(ARG_URIS).orEmpty()
    private val text = savedStateHandle.get<String>(ARG_TEXT)?.takeIf { it.isNotBlank() }

    /** Messages are forwarded, rather than files or text sent. */
    val isForward = messageIds != null

    /**
     * @param sentToChatId Where it all went, null until then
     */
    data class State(
        val isSending: Boolean = false,
        val sentToChatId: Long? = null,
        val isFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun send(chatId: Long) {
        if (_state.value.isSending || _state.value.sentToChatId != null) {
            return
        }
        _state.value = State(isSending = true)

        viewModelScope.launch {
            val client = getApplication<ShadowgramApplication>().accountsRepository.activeClient()
            val requests = when (val messageIds = messageIds) {
                null -> contents().map { TdApi.SendMessage(chatId, null, null, null, null, it) }
                else -> listOf(
                    TdApi.ForwardMessages(chatId, null, fromChatId, messageIds, null, false, false)
                )
            }
            val isSent = requests.isNotEmpty() && requests.all {
                when (val result = client.send(it)) {
                    is Result.Success -> true
                    is Result.Failure -> {
                        Log.w(LOG_TAG, "Can't send to $chatId: ${result.error.message}")
                        false
                    }
                }
            }
            _state.value = when (isSent) {
                true -> State(sentToChatId = chatId)
                false -> State(isFailed = true)
            }
        }
    }

    /** The shared files, the text as the first one's caption, or the text alone. */
    private suspend fun contents(): List<TdApi.InputMessageContent> = when {
        uris.isEmpty() -> listOfNotNull(
            text?.let { TdApi.InputMessageText(TdApi.FormattedText(it, arrayOf()), null, true) }
        )

        else -> uris.mapIndexedNotNull { index, uri ->
            InputContents.fromUri(getApplication(), Uri.parse(uri), false, text.takeIf { index == 0 })
                ?: null.also { Log.w(LOG_TAG, "Can't read $uri") }
        }
    }

    companion object {
        private const val LOG_TAG = "ChatPickerViewModel"

        const val ARG_FROM_CHAT_ID = "from_chat_id"
        const val ARG_MESSAGE_IDS = "message_ids"
        const val ARG_URIS = "uris"
        const val ARG_TEXT = "text"
    }
}

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
import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.models.ChatListKey
import com.github.borz7zy.shadowgram.models.ChatHeader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Chat folders of the active account, and the forum whose topics are open. Activity scoped:
 * the main top bar, the folders and every chat list share it.
 */
class ChatsViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val selectedFolderId = savedState.getStateFlow("selected_chat_folder", ChatListKey.Main.stableId)

    fun selectFolder(key: ChatListKey) {
        if (selectedFolderId.value == key.stableId) return
        savedState["selected_chat_folder"] = key.stableId
        closeForum()
    }

    /**
     * @param chatListStableId The [ChatListKey.stableId] of the list the forum was opened from
     */
    data class OpenForum(val chatListStableId: Long, val chatId: Long)

    private val _openForum = MutableStateFlow<OpenForum?>(null)
    val openForum = _openForum.asStateFlow()

    fun openForum(chatListKey: ChatListKey, chatId: Long) {
        _openForum.value = OpenForum(chatListKey.stableId, chatId)
    }

    fun closeForum() {
        _openForum.value = null
    }

    /**
     * The top bar of the open forum, null while none is.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val forumHeader = openForum
        .flatMapLatest { forum ->
            when (forum) {
                null -> flowOf<ChatHeader?>(null)
                else -> flow {
                    emitAll(
                        getApplication<ShadowgramApplication>().accountsRepository.activeClient()
                            .chats.header(forum.chatId, 0)
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val folders = flow {
        emitAll(
            getApplication<ShadowgramApplication>().accountsRepository.activeClient()
                .chats.folders()
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            listOf(ChatFolder(ChatListKey.Main, null)),
        )
}

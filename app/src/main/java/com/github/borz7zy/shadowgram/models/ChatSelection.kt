/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

enum class ChatBulkAction { ARCHIVE, MUTE, DELETE, PIN, REMOVE_FOLDER, UNREAD, CLEAR }

data class ChatSelection(
    val key: ChatListKey,
    val chats: List<ChatItem>,
    val busy: Boolean,
    val cancel: () -> Unit,
    val perform: (ChatBulkAction) -> Unit,
    val addToFolder: (Int) -> Unit,
)

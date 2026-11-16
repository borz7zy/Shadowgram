/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

/**
 * A row of a chat list: the chat and its selection state.
 *
 * @param chat The [ChatItem]
 * @param isSelected Whether the chat is selected
 * @param isSelectionMode Whether any chat of the list is selected
 * @param isOpenForum Whether this forum's topics are shown next to the list
 */
data class ChatRow(
    val chat: ChatItem,
    val isSelected: Boolean,
    val isSelectionMode: Boolean,
    val isOpenForum: Boolean = false,
) : UniqueItem<ChatRow> {
    override fun areItemsTheSame(other: ChatRow) = chat.id == other.chat.id

    override fun areContentsTheSame(other: ChatRow) = this == other
}

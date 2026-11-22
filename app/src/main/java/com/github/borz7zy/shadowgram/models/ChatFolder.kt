/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

/**
 * A chat list shown as a tab.
 *
 * @param key The [ChatListKey]
 * @param title The folder title, null for the main chat list
 */
data class ChatFolder(
    val key: ChatListKey,
    val title: String?,
    val iconName: String? = null,
) : UniqueItem<ChatFolder> {
    override fun areItemsTheSame(other: ChatFolder) = key == other.key

    override fun areContentsTheSame(other: ChatFolder) = this == other
}

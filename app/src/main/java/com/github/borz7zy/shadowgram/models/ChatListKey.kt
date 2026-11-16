/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

import org.drinkless.tdlib.TdApi

/**
 * A Telegram chat list: the main one, the archive or a chat folder.
 */
sealed interface ChatListKey {
    /**
     * An ID unique among chat lists, stable across folder changes.
     */
    val stableId: Long

    data object Main : ChatListKey {
        override val stableId = -1L
    }

    data object Archive : ChatListKey {
        override val stableId = -2L
    }

    data class Folder(val id: Int) : ChatListKey {
        override val stableId = id.toLong()
    }

    fun toTdApi(): TdApi.ChatList = when (this) {
        Main -> TdApi.ChatListMain()
        Archive -> TdApi.ChatListArchive()
        is Folder -> TdApi.ChatListFolder(id)
    }

    companion object {
        fun of(chatList: TdApi.ChatList): ChatListKey = when (chatList) {
            is TdApi.ChatListMain -> Main
            is TdApi.ChatListArchive -> Archive
            is TdApi.ChatListFolder -> Folder(chatList.chatFolderId)
            else -> throw IllegalArgumentException("Unknown chat list: $chatList")
        }

        fun of(stableId: Long): ChatListKey = when (stableId) {
            Main.stableId -> Main
            Archive.stableId -> Archive
            else -> Folder(stableId.toInt())
        }
    }
}

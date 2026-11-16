/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.ChatListKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatsStoreTest {
    private val requests = mutableListOf<TdApi.Function<*>>()
    private var acceptPinnedChats = true
    private val store = ChatsStore { function, handler ->
        requests += function
        when (function) {
            is TdApi.LoadChats -> handler(TdApi.Error(404, "Not Found"))
            is TdApi.SetPinnedChats -> handler(
                if (acceptPinnedChats) TdApi.Ok() else TdApi.Error(400, "Bad Request")
            )
        }
    }

    private fun chat(id: Long, title: String) = TdApi.Chat().apply {
        this.id = id
        this.title = title
        type = TdApi.ChatTypePrivate(id)
        positions = arrayOf()
        notificationSettings = TdApi.ChatNotificationSettings()
    }

    private fun position(list: TdApi.ChatList, order: Long, isPinned: Boolean = false) =
        TdApi.ChatPosition(list, order, isPinned, null)

    private fun items(key: ChatListKey) = runBlocking { store.chatList(key).first().items }

    @Test
    fun ordersChatsAndKeepsListsApart() {
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Old")))
        store.onUpdate(TdApi.UpdateNewChat(chat(2, "New")))
        store.onUpdate(TdApi.UpdateNewChat(chat(3, "Pinned")))
        store.onUpdate(TdApi.UpdateChatPosition(1, position(TdApi.ChatListMain(), 10)))
        store.onUpdate(TdApi.UpdateChatPosition(2, position(TdApi.ChatListMain(), 20)))
        store.onUpdate(TdApi.UpdateChatPosition(3, position(TdApi.ChatListMain(), 99, true)))
        store.onUpdate(TdApi.UpdateChatPosition(1, position(TdApi.ChatListFolder(5), 1)))

        assertEquals(listOf(3L, 2L, 1L), items(ChatListKey.Main).map { it.id })
        assertTrue(items(ChatListKey.Main).first().isPinned)
        assertEquals(listOf(1L), items(ChatListKey.Folder(5)).map { it.id })

        // Order 0 removes the chat from that list only
        store.onUpdate(TdApi.UpdateChatPosition(2, position(TdApi.ChatListMain(), 0)))
        assertEquals(listOf(3L, 1L), items(ChatListKey.Main).map { it.id })
        assertEquals(listOf(1L), items(ChatListKey.Folder(5)).map { it.id })
    }

    @Test
    fun updatesOnlyTheChangedFields() {
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Alice")))
        store.onUpdate(TdApi.UpdateChatPosition(1, position(TdApi.ChatListMain(), 10)))
        val before = items(ChatListKey.Main).single()

        store.onUpdate(TdApi.UpdateChatTitle(1, "Alice B."))
        val renamed = items(ChatListKey.Main).single()
        assertEquals("Alice B.", renamed.title)
        assertEquals(before.copy(title = "Alice B."), renamed)

        store.onUpdate(
            TdApi.UpdateChatAction(1, null, TdApi.MessageSenderUser(1), TdApi.ChatActionTyping())
        )
        val typing = items(ChatListKey.Main).single().typing
        assertEquals(ChatItem.Typing(null, ChatItem.Typing.Kind.TYPING), typing)

        store.onUpdate(
            TdApi.UpdateChatAction(1, null, TdApi.MessageSenderUser(1), TdApi.ChatActionCancel())
        )
        assertNull(items(ChatListKey.Main).single().typing)

        store.onUpdate(TdApi.UpdateChatReadInbox(1, 0, 4))
        assertEquals(4, items(ChatListKey.Main).single().unreadCount)
    }

    @Test
    fun foldersAndLoadState() = runBlocking {
        assertEquals(listOf(ChatFolder(ChatListKey.Main, null)), store.folders().first())

        store.onUpdate(
            TdApi.UpdateChatFolders(
                arrayOf(
                    TdApi.ChatFolderInfo().apply {
                        id = 5
                        name = TdApi.ChatFolderName(TdApi.FormattedText("Work", arrayOf()), false)
                    }
                ),
                1,
                false,
            )
        )
        assertEquals(
            listOf(ChatFolder(ChatListKey.Folder(5), "Work"), ChatFolder(ChatListKey.Main, null)),
            store.folders().first(),
        )

        // Empty and not loaded yet, then loaded once TDLib answers
        val key = ChatListKey.Folder(5)
        val snapshot = store.chatList(key).first { it.isLoaded }
        assertTrue(snapshot.items.isEmpty())
        assertEquals(1, requests.count { it is TdApi.LoadChats })

        // 404 means complete: no more requests
        store.loadMore(key)
        assertEquals(1, requests.count { it is TdApi.LoadChats })
    }

    @Test
    fun reordersPinnedChatsAndRevertsOnError() = runBlocking {
        listOf(1L to 30L, 2L to 20L, 3L to 10L).forEach { (id, order) ->
            store.onUpdate(TdApi.UpdateNewChat(chat(id, "Chat $id")))
            store.onUpdate(TdApi.UpdateChatPosition(id, position(TdApi.ChatListMain(), order, true)))
        }
        store.onUpdate(TdApi.UpdateNewChat(chat(4, "Not pinned")))
        store.onUpdate(TdApi.UpdateChatPosition(4, position(TdApi.ChatListMain(), 5)))

        assertTrue(store.setPinnedChats(ChatListKey.Main, listOf(3, 1, 2)))
        assertEquals(listOf(3L, 1L, 2L, 4L), items(ChatListKey.Main).map { it.id })

        acceptPinnedChats = false
        assertFalse(store.setPinnedChats(ChatListKey.Main, listOf(2, 3, 1)))
        assertEquals(listOf(3L, 1L, 2L, 4L), items(ChatListKey.Main).map { it.id })

        // Unpinned chats can't be part of the pinned order
        assertFalse(store.setPinnedChats(ChatListKey.Main, listOf(4, 3, 1, 2)))
    }
}

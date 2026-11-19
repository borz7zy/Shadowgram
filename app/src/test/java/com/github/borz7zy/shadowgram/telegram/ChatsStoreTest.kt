/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.models.ChatHeader
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
    @Test fun channelComposerFollowsPostingRights() {
        val channel = chat(-10, "Channel").apply { type = TdApi.ChatTypeSupergroup(10, true) }
        store.onUpdate(TdApi.UpdateNewChat(channel))
        store.onUpdate(TdApi.UpdateChatPosition(-10, position(TdApi.ChatListMain(), 10)))
        assertTrue(items(ChatListKey.Main).single().isChannel)
        assertFalse(items(ChatListKey.Main).single().canPostMessages)
        val group = TdApi.Supergroup().apply {
            id = 10; isChannel = true
            status = TdApi.ChatMemberStatusAdministrator(false, TdApi.ChatAdministratorRights().apply { canPostMessages = true })
        }
        store.onUpdate(TdApi.UpdateSupergroup(group))
        assertTrue(items(ChatListKey.Main).single().canPostMessages)
        group.status = TdApi.ChatMemberStatusMember()
        store.onUpdate(TdApi.UpdateSupergroup(group))
        assertFalse(items(ChatListKey.Main).single().canPostMessages)
    }

    @Test fun inheritedMuteSettingsAreReflectedInChatActions() {
        val privateChat = chat(1, "Alice").apply { notificationSettings.useDefaultMuteFor = true }
        store.onUpdate(TdApi.UpdateNewChat(privateChat))
        store.onUpdate(TdApi.UpdateChatPosition(1, position(TdApi.ChatListMain(), 10)))
        store.onUpdate(TdApi.UpdateScopeNotificationSettings(TdApi.NotificationSettingsScopePrivateChats(), TdApi.ScopeNotificationSettings().apply { muteFor = Int.MAX_VALUE }))
        assertTrue(items(ChatListKey.Main).single().isMuted)
        store.onUpdate(TdApi.UpdateChatNotificationSettings(1, TdApi.ChatNotificationSettings().apply { useDefaultMuteFor = false; muteFor = 0 }))
        assertFalse(items(ChatListKey.Main).single().isMuted)
    }

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
    fun collectibleStatusSuppliesTheProfileBackdropAndPattern() = runBlocking {
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Alice")))
        store.onUpdate(TdApi.UpdateUser(TdApi.User().apply {
            id = 1
            type = TdApi.UserTypeRegular()
            status = TdApi.UserStatusRecently()
            profileAccentColorId = 10
            profileBackgroundCustomEmojiId = 99L
            emojiStatus = TdApi.EmojiStatus(
                TdApi.EmojiStatusTypeUpgradedGift(
                    5L, "Gift", "Gift-42", 17L, 18L,
                    TdApi.UpgradedGiftBackdropColors(0x445566, 0x223344, 0x99AABB, 0xFFFFFF),
                ),
                0,
            )
        }))

        val header = requireNotNull(store.header(1, 0).first())
        val backdrop = listOf(0xFF223344.toInt(), 0xFF445566.toInt())
        assertEquals(ChatHeader.ProfileColors(backdrop, backdrop), header.profileColors)
        assertEquals(0xFF99AABB.toInt(), header.profilePatternColor)
        assertEquals(18L, header.profileEmojiId)
        assertEquals(17L, header.emojiStatusId)
    }

    @Test
    fun profilePatternFollowsChatAccentUpdatesWithACachedUser() = runBlocking {
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Alice")))
        store.onUpdate(TdApi.UpdateUser(TdApi.User().apply {
            id = 1
            type = TdApi.UserTypeRegular()
            status = TdApi.UserStatusRecently()
            profileBackgroundCustomEmojiId = 11L
            emojiStatus = TdApi.EmojiStatus(TdApi.EmojiStatusTypeCustomEmoji(22L), 0)
        }))

        store.onUpdate(TdApi.UpdateChatAccentColors(1, 0, 88L, null, 10, 33L))
        val header = requireNotNull(store.header(1, 0).first())
        assertEquals(33L, header.profileEmojiId)
        assertEquals(22L, header.emojiStatusId)

        store.onUpdate(TdApi.UpdateChatAccentColors(1, 0, 88L, null, 10, 0L))
        assertEquals(0L, store.header(1, 0).first()?.profileEmojiId)
    }

    @Test
    fun clearingAUserStatusDoesNotRestoreAnOldCollectiblePatternFromTheChat() = runBlocking {
        val oldStatus = TdApi.EmojiStatus(
            TdApi.EmojiStatusTypeUpgradedGift(
                5L, "Gift", "Gift-42", 17L, 18L,
                TdApi.UpgradedGiftBackdropColors(0x445566, 0x223344, 0x99AABB, 0xFFFFFF),
            ),
            0,
        )
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Alice").apply { emojiStatus = oldStatus }))
        store.onUpdate(TdApi.UpdateUser(TdApi.User().apply {
            id = 1
            type = TdApi.UserTypeRegular()
            status = TdApi.UserStatusRecently()
            profileBackgroundCustomEmojiId = 33L
        }))

        val header = requireNotNull(store.header(1, 0).first())
        assertEquals(33L, header.profileEmojiId)
        assertEquals(0L, header.emojiStatusId)
        assertNull(header.profilePatternColor)
    }

    @Test
    fun onlineIndicatorFollowsUserStatusUpdates() {
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Alice")))
        store.onUpdate(TdApi.UpdateChatPosition(1, position(TdApi.ChatListMain(), 10)))
        assertFalse(items(ChatListKey.Main).single().isOnline)
        store.onUpdate(TdApi.UpdateUser(TdApi.User().apply {
            id = 1
            type = TdApi.UserTypeRegular()
            status = TdApi.UserStatusOnline(Int.MAX_VALUE)
        }))
        assertTrue(items(ChatListKey.Main).single().isOnline)
        store.onUpdate(TdApi.UpdateUserStatus(1, TdApi.UserStatusOffline(100)))
        assertFalse(items(ChatListKey.Main).single().isOnline)
        store.onUpdate(TdApi.UpdateUserStatus(1, TdApi.UserStatusRecently()))
        assertFalse(items(ChatListKey.Main).single().isOnline)
    }

    @Test
    fun botsAndGroupsNeverShowAnOnlineDot() {
        store.onUpdate(TdApi.UpdateUser(TdApi.User().apply {
            id = 1
            type = TdApi.UserTypeBot()
            status = TdApi.UserStatusOnline(Int.MAX_VALUE)
        }))
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Bot")))
        store.onUpdate(TdApi.UpdateChatPosition(1, position(TdApi.ChatListMain(), 10)))
        store.onUpdate(TdApi.UpdateNewChat(chat(2, "Group").apply { type = TdApi.ChatTypeBasicGroup(1) }))
        store.onUpdate(TdApi.UpdateChatPosition(2, position(TdApi.ChatListMain(), 20)))
        assertTrue(items(ChatListKey.Main).none { it.isOnline })
    }

    @Test
    fun onlineDotAlsoWorksForSecretChats() {
        store.onUpdate(TdApi.UpdateUser(TdApi.User().apply {
            id = 10
            type = TdApi.UserTypeRegular()
            status = TdApi.UserStatusOnline(Int.MAX_VALUE)
        }))
        store.onUpdate(TdApi.UpdateNewChat(chat(1, "Secret").apply { type = TdApi.ChatTypeSecret(5, 10) }))
        store.onUpdate(TdApi.UpdateChatPosition(1, position(TdApi.ChatListMain(), 10)))
        assertTrue(items(ChatListKey.Main).single().isOnline)
    }

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

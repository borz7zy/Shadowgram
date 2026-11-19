package com.github.borz7zy.shadowgram.models

import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test

class SelectionActionsTest {
    private fun message(id: Long, date: Int = id.toInt(), text: String? = "message $id", kind: ChatItem.MessagePreview.Kind = ChatItem.MessagePreview.Kind.TEXT) =
        MessageItem(id, false, 1, "Alice", 0, kind = kind, text = text, date = date, state = MessageItem.State.SENT)

    private fun rights() = TdApi.MessageProperties().apply {
        canBeCopied = true; canBeForwarded = true; canBeDeletedOnlyForSelf = true
        canBeDeletedForAllUsers = true; canBeReplied = true
    }

    @Test fun permissionsMustArriveForEverySelectedMessage() {
        val first = message(1)
        val second = message(2)
        val actions = MessageSelectionActions.of(listOf(first, second), mapOf(1L to rights()), false, true)
        assertFalse(actions.forward); assertFalse(actions.copy)
        assertFalse(actions.deleteSelf); assertFalse(actions.deleteEveryone); assertFalse(actions.reply)
    }

    @Test fun protectedChatDisablesCopyAndForwardEvenWithOlderCachedPermissions() {
        val actions = MessageSelectionActions.of(listOf(message(1)), mapOf(1L to rights()), true, true)
        assertFalse(actions.forward); assertFalse(actions.copy)
        assertTrue(actions.reply); assertTrue(actions.deleteSelf)
    }

    @Test fun replyRequiresExactlyOneMessageAndWritableChat() {
        assertTrue(MessageSelectionActions.of(listOf(message(1)), mapOf(1L to rights()), false, true).reply)
        assertFalse(MessageSelectionActions.of(listOf(message(1)), mapOf(1L to rights()), false, false).reply)
        assertFalse(MessageSelectionActions.of(listOf(message(1), message(2)), mapOf(1L to rights(), 2L to rights()), false, true).reply)
    }

    @Test fun oneRestrictedMessageDisablesForwardingOfWholeSelection() {
        val restricted = rights().apply { canBeForwarded = false }
        val actions = MessageSelectionActions.of(listOf(message(1), message(2)), mapOf(1L to rights(), 2L to restricted), false, true)
        assertFalse(actions.forward); assertTrue(actions.copy)
    }

    @Test fun incompatibleDeleteScopesAreNotCombinedIntoARevokeRequest() {
        val onlySelf = rights().apply { canBeDeletedForAllUsers = false }
        val onlyEveryone = rights().apply { canBeDeletedOnlyForSelf = false }
        val actions = MessageSelectionActions.of(listOf(message(1), message(2)), mapOf(1L to onlySelf, 2L to onlyEveryone), false, true)
        assertFalse(actions.deleteSelf); assertFalse(actions.deleteEveryone)
    }

    @Test fun favoriteStickerActionRequiresOneSticker() {
        val sticker = message(1, text = null, kind = ChatItem.MessagePreview.Kind.STICKER)
        assertTrue(MessageSelectionActions.of(listOf(sticker), emptyMap(), false, true).favoriteSticker)
        assertFalse(MessageSelectionActions.of(listOf(sticker, message(2)), emptyMap(), false, true).favoriteSticker)
        assertFalse(MessageSelectionActions.of(listOf(message(1)), emptyMap(), false, true).favoriteSticker)
    }

    @Test fun copyContainsAuthorAndDateInChronologicalOrderAndKeepsMultilineText() {
        val selected = listOf(message(3, 20, "caption"), message(1, 10, "hello\nworld"), message(2, 10, null))
        assertEquals("Alice, [date 10]\nhello\nworld\n\nAlice, [date 20]\ncaption",
            selectedMessagesText(selected, { it.senderName!! }, { "date $it" }))
    }

    private fun chat(id: Long, cache: Boolean) = ChatItem(id, "Chat", 0, null, null, false, 1, null, null, null, 0, 0, false, false, clearsCacheOnly = cache)

    @Test fun historyAndCacheSelectionsMustRemainSeparate() {
        assertEquals(ChatHistoryClearAction.HISTORY, chatHistoryClearAction(listOf(chat(1, false), chat(2, false))))
        assertEquals(ChatHistoryClearAction.CACHE, chatHistoryClearAction(listOf(chat(1, true), chat(2, true))))
        assertNull(chatHistoryClearAction(listOf(chat(1, false), chat(2, true))))
        assertNull(chatHistoryClearAction(emptyList()))
    }
}

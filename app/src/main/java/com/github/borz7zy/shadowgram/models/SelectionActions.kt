package com.github.borz7zy.shadowgram.models

import org.drinkless.tdlib.TdApi

data class MessageSelectionActions(
    val forward: Boolean = false,
    val copy: Boolean = false,
    val deleteSelf: Boolean = false,
    val deleteEveryone: Boolean = false,
    val reply: Boolean = false,
    val favoriteSticker: Boolean = false,
) {
    companion object {
        fun of(messages: Collection<MessageItem>, properties: Map<Long, TdApi.MessageProperties>, protectedContent: Boolean, canReply: Boolean): MessageSelectionActions {
            if (messages.isEmpty()) return MessageSelectionActions()
            fun all(predicate: (TdApi.MessageProperties) -> Boolean) = messages.all { properties[it.id]?.let(predicate) == true }
            return MessageSelectionActions(
                forward = !protectedContent && all { it.canBeForwarded },
                copy = !protectedContent && all { it.canBeCopied } && messages.any { !it.text.isNullOrBlank() },
                deleteSelf = all { it.canBeDeletedOnlyForSelf },
                deleteEveryone = all { it.canBeDeletedForAllUsers },
                reply = messages.size == 1 && canReply && all { it.canBeReplied },
                favoriteSticker = messages.singleOrNull()?.kind == ChatItem.MessagePreview.Kind.STICKER,
            )
        }
    }
}

enum class ChatHistoryClearAction { HISTORY, CACHE }

fun chatHistoryClearAction(chats: List<ChatItem>): ChatHistoryClearAction? = when {
    chats.isEmpty() -> null
    chats.all { it.clearsCacheOnly } -> ChatHistoryClearAction.CACHE
    chats.none { it.clearsCacheOnly } -> ChatHistoryClearAction.HISTORY
    else -> null
}

fun selectedMessagesText(messages: Collection<MessageItem>, sender: (MessageItem) -> String, dateTime: (Int) -> String): String =
    messages.sortedWith(compareBy({ it.date }, { it.id })).filter { !it.text.isNullOrBlank() }
        .joinToString("\n\n") { "${sender(it)}, [${dateTime(it.date)}]\n${it.text}" }

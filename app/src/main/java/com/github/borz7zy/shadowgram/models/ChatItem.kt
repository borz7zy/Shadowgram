/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

/**
 * A chat as shown in a chat list. Immutable, so that lists can be diffed.
 *
 * @param id The chat ID
 * @param title The chat title
 * @param accentColorId The accent color ID, used for the avatar placeholder
 * @param photoPath The local path of the small chat photo, null if there's none or it's not
 *   downloaded yet
 * @param isPinned Whether the chat is pinned in this chat list
 * @param order The order of the chat in this chat list, higher first
 * @param lastMessage The last message, if any
 * @param draft The draft text, if any
 * @param typing The current chat action, if any
 * @param unreadCount The number of unread messages
 * @param unreadMentionCount The number of unread mentions
 * @param isMarkedAsUnread Whether the chat has been manually marked as unread
 * @param isMuted Whether the chat notifications are muted
 */
data class ChatItem(
    val id: Long,
    val title: String,
    val accentColorId: Int,
    val photoPath: String?,
    /** Tiny blurry JPEG shown until [photoPath] is downloaded, null if none. */
    val minithumbnail: ByteArray?,
    val isPinned: Boolean,
    val order: Long,
    val lastMessage: MessagePreview?,
    val draft: String?,
    val typing: Typing?,
    val unreadCount: Int,
    val unreadMentionCount: Int,
    val isMarkedAsUnread: Boolean,
    val isMuted: Boolean,
    /** Opens its topics instead of a chat. */
    val isForum: Boolean = false,
    /** ARGB of the topic icon when this row is a forum topic, null for chats. */
    val topicIconColor: Int? = null,
    /** Its messages can't be copied, forwarded, saved or screenshotted. */
    val hasProtectedContent: Boolean = false,
    /** Messages are waiting to be sent later, the composer shows a button for them. */
    val hasScheduledMessages: Boolean = false,
    /** Reactions to this account's messages not seen yet. */
    val unreadReactionCount: Int = 0,
    /** The small animated version of the photo, a file to stream; 0 for none. */
    val animationFileId: Int = 0,
    /** The emoji status next to the name, a custom emoji id, 0 for none. */
    val emojiStatusId: Long = 0L,
    /** A Premium user, shown with a star when there's no emoji status. */
    val isPremium: Boolean = false,
) : UniqueItem<ChatItem> {
    /**
     * @param senderName The name of the sender, null if it shouldn't be shown
     * @param isOutgoing Whether the message has been sent by the user
     * @param kind The [Kind] of the content
     * @param text The text or caption of the message, if any
     * @param date The message date, in Unix time
     */
    data class MessagePreview(
        val senderName: String?,
        val isOutgoing: Boolean,
        val kind: Kind,
        val text: String?,
        val date: Int,
    ) {
        enum class Kind {
            TEXT,
            PHOTO,
            VIDEO,
            VIDEO_NOTE,
            VOICE_NOTE,
            AUDIO,
            DOCUMENT,
            ANIMATION,
            STICKER,
            POLL,
            LOCATION,
            CONTACT,
            CALL,
            OTHER,
        }
    }

    /**
     * @param senderName The name of the user doing the action, null in private chats
     * @param kind The [Kind] of the action
     */
    data class Typing(
        val senderName: String?,
        val kind: Kind,
    ) {
        enum class Kind {
            TYPING,
            RECORDING_VOICE_NOTE,
            RECORDING_VIDEO,
            SENDING_MEDIA,
            CHOOSING_STICKER,
            OTHER,
        }
    }

    override fun areItemsTheSame(other: ChatItem) = id == other.id

    override fun areContentsTheSame(other: ChatItem) = this == other
}

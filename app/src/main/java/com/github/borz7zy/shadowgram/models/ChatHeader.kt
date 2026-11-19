/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

/**
 * What the top bar of an open chat or topic shows.
 *
 * @param chat The chat, or the topic for a forum topic ([ChatItem.topicIconColor] set)
 * @param subtitle The line under the title
 */
data class ChatHeader(
    val chat: ChatItem,
    val subtitle: Subtitle,
    /** The emoji status of the user or chat, a custom emoji id, 0 for none. */
    val emojiStatusId: Long = 0L,
    /** A Premium user, shown with a star when there's no emoji status. */
    val isPremium: Boolean = false,
    /** The profile's background chosen in Telegram, null for none. */
    val profileColors: ProfileColors? = null,
    /** The custom emoji patterned over the profile header, 0 for none. */
    val profileEmojiId: Long = 0L,
    /** The symbol tint of a collectible profile backdrop, if set. */
    val profilePatternColor: Int? = null,
) {
    /** The 1-2 colors of a profile's background, ARGB, for either theme. */
    data class ProfileColors(val light: List<Int>, val dark: List<Int>)

    sealed interface Subtitle {
        data object None : Subtitle
        data object Bot : Subtitle
        data class Status(val lastSeen: ContactItem.LastSeen) : Subtitle
        data class Members(val count: Int, val isChannel: Boolean) : Subtitle
        data class Typing(val typing: ChatItem.Typing) : Subtitle
        data class TopicMessages(val count: Int) : Subtitle
        data object Scheduled : Subtitle
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

/**
 * A row of an open chat: a message bubble or a date separator.
 */
sealed interface MessageRow : UniqueItem<MessageRow> {
    val key: Long

    override fun areItemsTheSame(other: MessageRow) = key == other.key

    override fun areContentsTheSame(other: MessageRow) = this == other

    /**
     * @param isGroupStart First bubble of a run from the same sender, gets the full top corners
     * @param isGroupEnd Last bubble of the run, gets the full bottom corners
     * @param showSender Show the sender name in the bubble
     * @param hasAvatar Leave room for the sender avatar, drawn once per run along its bubbles
     * @param album The messages of an album shown in this one bubble, oldest first; empty for a
     *   single message. [message] is the one whose caption, reactions and menu the bubble shows
     */
    data class Bubble(
        val message: MessageItem,
        val isGroupStart: Boolean,
        val isGroupEnd: Boolean,
        val showSender: Boolean,
        val hasAvatar: Boolean = false,
        val album: List<MessageItem> = listOf(),
    ) : MessageRow {
        // An acknowledgement can reorder an album's temporary and server IDs.
        override val key = album.minOfOrNull { it.rowId } ?: message.rowId
    }

    /**
     * Shown before a message sent after a pause, like Google Messages does.
     *
     * @param date Unix time of the message after it
     */
    data class Separator(val date: Int) : MessageRow {
        // Messages ids are positive, separators can't collide with them
        override val key get() = -date.toLong()
    }

    /**
     * Before the first message that was unread when the chat was opened.
     */
    data object UnreadSeparator : MessageRow {
        override val key get() = Long.MIN_VALUE
    }

    companion object {
        /** A pause this long starts a new separator, in seconds. */
        const val SEPARATOR_GAP = 60 * 60

        /** Messages of one sender closer than this are grouped, in seconds. */
        const val GROUP_GAP = 5 * 60

        /**
         * Build the rows of [messages], oldest first.
         *
         * @param isGroup Whether sender names and avatars are shown on incoming messages
         * @param unreadAfterId Messages after this one were unread when the chat was opened,
         *   null if none were
         */
        fun build(
            messages: List<MessageItem>,
            isGroup: Boolean,
            unreadAfterId: Long? = null,
        ): List<MessageRow> {
            val rows = ArrayList<MessageRow>(messages.size + 8)
            val firstUnreadId = unreadAfterId?.let { after ->
                messages.firstOrNull { it.id > after && !it.isOutgoing }?.id
            }

            // An album's messages come in a row and share one bubble, like TDLib groups them:
            // pictures as a mosaic, music and files as a stack of their players and rows
            val units = ArrayList<List<MessageItem>>(messages.size)
            messages.forEach { message ->
                val last = units.lastOrNull()
                if (message.mediaAlbumId != 0L && last != null && last.last().mediaAlbumId == message.mediaAlbumId
                    && message.id != firstUnreadId
                ) {
                    units[units.size - 1] = last + message
                } else {
                    units.add(listOf(message))
                }
            }

            units.forEachIndexed { i, unit ->
                val first = unit.first()
                val last = unit.last()
                val prev = units.getOrNull(i - 1)?.last()
                val next = units.getOrNull(i + 1)?.first()
                val isFirstUnread = first.id == firstUnreadId
                val newSection = prev == null || first.date - prev.date >= SEPARATOR_GAP

                if (newSection) {
                    rows.add(Separator(first.date))
                }
                if (isFirstUnread) {
                    rows.add(UnreadSeparator)
                }

                val isGroupStart = newSection || isFirstUnread || !prev.groupsWith(first)
                val isGroupEnd = next == null || next.date - last.date >= SEPARATOR_GAP
                        || next.id == firstUnreadId || !last.groupsWith(next)

                // The caption is on one of them, usually the first
                val message = unit.firstOrNull { !it.text.isNullOrBlank() } ?: first
                rows.add(
                    Bubble(
                        message,
                        isGroupStart,
                        isGroupEnd,
                        showSender = isGroup && !message.isOutgoing && isGroupStart,
                        hasAvatar = isGroup && !message.isOutgoing,
                        album = unit.takeIf { it.size > 1 }.orEmpty(),
                    )
                )
            }

            return rows
        }

        private fun MessageItem.groupsWith(next: MessageItem) =
            senderId == next.senderId && isOutgoing == next.isOutgoing
                    && next.date - date < GROUP_GAP
    }
}

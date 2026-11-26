/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.ui.recyclerview.MessageListAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRowTest {
    private fun message(id: Long, senderId: Long, date: Int, isOutgoing: Boolean = false) =
        MessageItem(
            id = id,
            isOutgoing = isOutgoing,
            senderId = senderId,
            senderName = "User $senderId",
            senderAccentColorId = 0,
            kind = MessagePreview.Kind.TEXT,
            text = "hi",
            date = date,
            state = MessageItem.State.SENT,
        )

    @Test
    fun groupsRunsAndSeparatesPauses() {
        val rows = MessageRow.build(
            listOf(
                message(1, senderId = 10, date = 0),
                message(2, senderId = 10, date = 60),
                message(3, senderId = 20, date = 120),
                message(4, senderId = 20, date = 120 + MessageRow.SEPARATOR_GAP),
            ),
            isGroup = true,
        )

        val shape = rows.map {
            when (it) {
                is MessageRow.Separator, MessageRow.UnreadSeparator -> "|"
                is MessageRow.Bubble -> buildString {
                    append(it.message.id)
                    if (it.isGroupStart) append('s')
                    if (it.isGroupEnd) append('e')
                    if (it.showSender) append('n')
                }
            }
        }

        // 1-2 one run, 3 another sender, 4 after a pause gets its own separator
        assertEquals(listOf("|", "1sn", "2e", "3sen", "|", "4sen"), shape)
    }

    private val photo = MessageItem.Media(MessageItem.Media.Type.PHOTO, null, null, 100, 100)

    @Test
    fun albumSharesOneBubbleWithItsCaption() {
        val rows = MessageRow.build(
            listOf(
                message(1, 10, 0).copy(mediaAlbumId = 7, text = null, media = photo),
                message(2, 10, 0).copy(mediaAlbumId = 7, text = "caption", media = photo),
                message(3, 10, 0).copy(mediaAlbumId = 7, text = null, media = photo),
                message(4, 10, 10),
            ),
            isGroup = false,
        )
        val bubbles = rows.filterIsInstance<MessageRow.Bubble>()

        assertEquals(2, bubbles.size)
        assertEquals(listOf(1L, 2L, 3L), bubbles[0].album.map { it.id })
        // The caption's message speaks for the album, the first keeps its place
        assertEquals(2L, bubbles[0].message.id)
        assertEquals(1L, bubbles[0].key)
        assertEquals(listOf<MessageItem>(), bubbles[1].album)
    }

    @Test
    fun musicAlbumSharesOneBubble() {
        val music = MessageItem.Playable(fileId = 1, duration = 53, isVideo = false, isMusic = true)
        val rows = MessageRow.build(
            (1L..3L).map { message(it, 10, 0).copy(mediaAlbumId = 7, playable = music) },
            isGroup = false,
        )
        val bubbles = rows.filterIsInstance<MessageRow.Bubble>()

        assertEquals(1, bubbles.size)
        assertEquals(listOf(1L, 2L, 3L), bubbles[0].album.map { it.id })
    }

    @Test
    fun outgoingStatusChangesKeepTheSameCellAndUseAMetadataPayload() {
        val pending = message(19, 10, 10, isOutgoing = true).copy(state = MessageItem.State.PENDING)
        val sent = pending.copy(id = 30, state = MessageItem.State.SENT)
        val read = sent.copy(state = MessageItem.State.READ)
        fun row(item: MessageItem) = MessageRow.Bubble(item, isGroupStart = true, isGroupEnd = true, showSender = false)

        val diff = MessageListAdapter.DiffCallback
        assertTrue(diff.areItemsTheSame(row(pending), row(sent)))
        assertTrue(diff.areItemsTheSame(row(sent), row(read)))
        val sentPayload = diff.getChangePayload(row(pending), row(sent))
        assertNotNull(sentPayload)
        assertEquals(sentPayload, diff.getChangePayload(row(sent), row(read)))
    }

    @Test
    fun albumKeySurvivesAcknowledgementsInDifferentOrder() {
        val first = message(19, 10, 10, isOutgoing = true).copy(mediaAlbumId = 7, text = null, media = photo)
        val second = message(20, 10, 10, isOutgoing = true).copy(mediaAlbumId = 7, media = photo)
        fun row(items: List<MessageItem>) = MessageRow.build(items, isGroup = false).filterIsInstance<MessageRow.Bubble>().single()

        assertEquals(row(listOf(first, second)).key, row(listOf(second, first.copy(id = 30))).key)
    }

    @Test
    fun unreadSeparatorSplitsTheRun() {
        val rows = MessageRow.build(
            listOf(message(1, 10, 0), message(2, 10, 10), message(3, 10, 20)),
            isGroup = false,
            unreadAfterId = 1,
        )

        assertEquals(MessageRow.UnreadSeparator, rows[2])
        assertEquals(true, (rows[1] as MessageRow.Bubble).isGroupEnd)
        assertEquals(true, (rows[3] as MessageRow.Bubble).isGroupStart)
    }

    @Test
    fun noSenderNamesOutsideGroups() {
        val rows = MessageRow.build(listOf(message(1, 10, 0)), isGroup = false)
        assertEquals(false, (rows[1] as MessageRow.Bubble).showSender)
    }
}

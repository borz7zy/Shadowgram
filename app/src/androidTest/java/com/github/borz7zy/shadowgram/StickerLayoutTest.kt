/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.models.MessageRow
import com.github.borz7zy.shadowgram.testing.MainSectionTestActivity
import com.github.borz7zy.shadowgram.ui.recyclerview.MessageListAdapter
import com.github.borz7zy.shadowgram.ui.views.MaxWidthLinearLayout
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerLayoutTest {
    private val callbacks = object : MessageListAdapter.Callbacks {
        override fun onMessageClick(message: MessageItem, bubble: View) = Unit
        override fun onMessageLongClick(message: MessageItem) = Unit
        override fun onSenderClick(senderId: Long) = Unit
        override fun onReactionClick(message: MessageItem, reaction: MessageItem.Reaction) = Unit
        override fun onMediaClick(message: MessageItem, view: ImageView) = Unit
        override fun onDocumentClick(message: MessageItem) = Unit
        override fun onReplyClick(message: MessageItem) = Unit
        override fun onMentionClick(userId: Long, username: String) = Unit
    }

    private fun sticker(outgoing: Boolean, details: Boolean = false) = MessageItem(
        id = 1, isOutgoing = outgoing, senderId = 1, senderName = "Alice", senderAccentColorId = 0,
        kind = ChatItem.MessagePreview.Kind.STICKER, text = null, date = 1_700_000_000,
        state = MessageItem.State.SENT,
        media = MessageItem.Media(MessageItem.Media.Type.STICKER, null, null, 512, 512),
        forward = if (details) MessageItem.Forward("Bob", 0, 2, null) else null,
        reply = if (details) MessageItem.Reply(2, "Alice", 0, ChatItem.MessagePreview.Kind.TEXT, "Reply text") else null,
    )

    @Test fun timeOnlyDoesNotReserveAnEmptyColumnAndStickerStaysAtItsEdge() {
        withRow { bind, row ->
            for (outgoing in listOf(true, false, true)) {
                bind(sticker(outgoing))
                val content = row.findViewById<View>(R.id.messageContentLayout)
                val media = row.findViewById<View>(R.id.mediaFrameLayout)
                val info = row.findViewById<View>(R.id.stickerInfoLayout)
                val meta = row.findViewById<View>(R.id.metaTextView)
                assertEquals("only the actual time occupies the side", meta.measuredWidth, info.measuredWidth)
                assertEquals("sticker has no empty trailing area", media.measuredWidth, content.measuredWidth)
                assertSide(row, outgoing)
                val card = row.findViewById<MaterialCardView>(R.id.bubbleCardView)
                assertEquals(Color.TRANSPARENT, card.cardBackgroundColor.defaultColor)
                assertFalse(card.clipToOutline)
            }
        }
    }

    @Test fun recycledRowRestoresBothDirectionsAndNormalMessageMetadata() {
        withRow { bind, row ->
            for (outgoing in listOf(false, true, false)) {
                bind(sticker(outgoing, details = true))
                val info = row.findViewById<MaxWidthLinearLayout>(R.id.stickerInfoLayout)
                assertTrue(info.measuredWidth <= info.maxWidth)
                assertSame(info, row.findViewById<View>(R.id.replyPlateLinearLayout).parent)
                assertSame(info, row.findViewById<View>(R.id.forwardLinearLayout).parent)
                assertSide(row, outgoing)
            }
            bind(sticker(true).copy(kind = ChatItem.MessagePreview.Kind.TEXT, media = null, text = "Normal message"))
            assertEquals(View.GONE, row.findViewById<View>(R.id.stickerInfoLayout).visibility)
            assertSame(row.findViewById<LinearLayout>(R.id.messageContentLayout), row.findViewById<TextView>(R.id.metaTextView).parent)
            assertEquals(android.view.Gravity.END, (row.findViewById<View>(R.id.metaTextView).layoutParams as LinearLayout.LayoutParams).gravity)
            bind(sticker(false))
            assertSide(row, false)
        }
    }

    private fun assertSide(row: View, outgoing: Boolean) {
        val body = row.findViewById<LinearLayout>(R.id.messageBubbleBodyLayout)
        val content = row.findViewById<View>(R.id.messageContentLayout)
        val info = row.findViewById<View>(R.id.stickerInfoLayout)
        if (outgoing) {
            assertTrue(info.right <= content.left)
            assertEquals(body.width, content.right)
        } else {
            assertEquals(0, content.left)
            assertTrue(info.left >= content.right)
        }
    }

    private fun withRow(test: ((MessageItem) -> Unit, View) -> Unit) {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = RecyclerView(activity)
                val adapter = MessageListAdapter({}, { _, _ -> error("No playable media in this fixture") }, callbacks)
                adapter.submitList(listOf(MessageRow.Bubble(sticker(true), true, true, false)))
                val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
                val row = holder.itemView
                val width = (400 * activity.resources.displayMetrics.density).toInt()
                test({ message ->
                    adapter.submitList(null)
                    adapter.submitList(listOf(MessageRow.Bubble(message, true, true, false)))
                    adapter.bindViewHolder(holder, 0)
                    row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, width, row.measuredHeight)
                }, row)
            }
        }
    }

}

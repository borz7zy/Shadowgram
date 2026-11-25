/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */
package com.github.borz7zy.shadowgram.ui.cells

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.DynamicDrawableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.ui.text.attachCustomEmoji
import com.github.borz7zy.shadowgram.ui.text.setCustomEmoji
import com.github.borz7zy.shadowgram.ui.text.stringResId

/** UI-only renderer; immutable Unicode ranges are prepared/cached by MessageTextPreparer. */
internal class MessageTextRenderer(
    private val messageTextView: TextView,
    private val textPreparer: MessageTextPreparer,
    private val onMentionClick: (Long, String) -> Unit,
) {
    fun bind(message: MessageItem) {
        val context = messageTextView.context
        val media = message.media
        val isSticker = when (media?.type) {
            MessageItem.Media.Type.STICKER, MessageItem.Media.Type.ANIMATED_STICKER,
            MessageItem.Media.Type.VIDEO_NOTE -> true
            else -> false
        }
        // Text: media without a caption has none, other kinds say what they are
        val text = when {
            message.kind == MessagePreview.Kind.TEXT -> message.text.orEmpty()
            // The player or the file says what it is
            message.playable != null || message.document != null -> message.text.orEmpty()
            media != null -> message.text.takeUnless { isSticker }.orEmpty()
            else -> SpannableStringBuilder().apply {
                append(context.getString(message.kind.stringResId), StyleSpan(Typeface.ITALIC), 0)
                message.text?.let { append('\n').append(it) }
            }
        }
        // Emoji drawn from Telegram's sprites, custom ones over them where the text has any
        val fontMetrics = messageTextView.paint.fontMetricsInt
        val ranges = textPreparer.ranges(text.toString())
        var spanned: CharSequence = if (ranges.isEmpty()) text else {
            SpannableStringBuilder(text).apply {
                for (range in ranges) {
                    val drawable = Emoji.getEmojiDrawable(range.code) ?: continue
                    val span = Emoji.EmojiSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM, fontMetrics)
                    span.emoji = range.code
                    setSpan(span, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        if (message.customEmoji.isNotEmpty()) {
            // Text without plain emoji comes back as is
            if (spanned !is Spannable) {
                spanned = android.text.SpannableString(spanned)
            }
            // Kinds that say what they are put the text after a line
            val shift = if (text is SpannableStringBuilder) text.length - message.text.orEmpty().length else 0
            spanned.setCustomEmoji(message.customEmoji, fontMetrics, shift)
        }
        // Mentions open the profile, like links open the browser
        if (message.mentions.isNotEmpty()) {
            if (spanned !is Spannable) {
                spanned = android.text.SpannableString(spanned)
            }
            val shift = if (text is SpannableStringBuilder) text.length - message.text.orEmpty().length else 0
            message.mentions.forEach { mention ->
                val start = mention.offset + shift
                val end = start + mention.length
                if (start < 0 || end <= start || end > spanned.length) {
                    return@forEach
                }
                val username = spanned.substring(start, end).removePrefix("@")
                spanned.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = onMentionClick(mention.userId, username)

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = ds.linkColor
                    }
                }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        messageTextView.text = spanned
        messageTextView.attachCustomEmoji()
        messageTextView.isVisible = text.isNotEmpty()
    }
}

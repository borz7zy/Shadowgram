/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.text

import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * A user mentioned by name in the composer, someone without a username: sent as a mention of
 * [userId] over the name, like Telegram does.
 */
class MentionSpan(val userId: Long, color: Int) : ForegroundColorSpan(color)

/** A mention by name in a text, offsets in UTF-16 code units like TDLib's. */
data class MentionRange(val offset: Int, val length: Int, val userId: Long)

fun Spanned.mentionRanges() = getSpans(0, length, MentionSpan::class.java).map {
    MentionRange(getSpanStart(it), getSpanEnd(it) - getSpanStart(it), it.userId)
}

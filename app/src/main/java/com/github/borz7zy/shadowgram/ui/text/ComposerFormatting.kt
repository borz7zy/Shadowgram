/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.text

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import org.drinkless.tdlib.TdApi

/** An existing entity follows its range in the editable text, including across insertions. */
private class ComposerEntity(val type: TdApi.TextEntityType)

fun TdApi.FormattedText.forComposer(view: TextView): CharSequence =
    SpannableString(text).apply {
        for (entity in entities.orEmpty()) {
            val start = entity.offset
            val end = start + entity.length
            if (start < 0 || end <= start || end > length) continue
            val span = when (val type = entity.type) {
                is TdApi.TextEntityTypeCustomEmoji -> CustomEmojiSpan(type.customEmojiId, view.paint.fontMetricsInt)
                is TdApi.TextEntityTypeMentionName -> MentionSpan(
                    type.userId, MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary),
                )
                else -> ComposerEntity(type)
            }
            setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val style = when (entity.type) {
                is TdApi.TextEntityTypeBold -> StyleSpan(Typeface.BOLD)
                is TdApi.TextEntityTypeItalic -> StyleSpan(Typeface.ITALIC)
                is TdApi.TextEntityTypeUnderline, is TdApi.TextEntityTypeTextUrl -> UnderlineSpan()
                is TdApi.TextEntityTypeStrikethrough -> StrikethroughSpan()
                is TdApi.TextEntityTypeCode, is TdApi.TextEntityTypePre, is TdApi.TextEntityTypePreCode ->
                    TypefaceSpan("monospace")
                else -> null
            }
            style?.let { setSpan(it, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

fun Spanned.composerContents(): TdApi.FormattedText {
    val entities = getSpans(0, length, ComposerEntity::class.java).map {
        TdApi.TextEntity(getSpanStart(it), getSpanEnd(it) - getSpanStart(it), it.type)
    } + customEmojiRanges().map {
        TdApi.TextEntity(it.offset, it.length, TdApi.TextEntityTypeCustomEmoji(it.customEmojiId))
    } + mentionRanges().map {
        TdApi.TextEntity(it.offset, it.length, TdApi.TextEntityTypeMentionName(it.userId))
    }
    return TdApi.FormattedText(
        toString(),
        entities.filter { it.offset >= 0 && it.length > 0 && it.offset + it.length <= length }
            .sortedWith(compareBy<TdApi.TextEntity> { it.offset }.thenByDescending { it.length })
            .toTypedArray(),
    )
}

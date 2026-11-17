/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * The bar beside the pinned message, like Telegram's: a segment per pinned message, the shown one
 * lit. Past [MAX_SEGMENTS] the segments scroll with it.
 */
class PinnedIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val lit = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var count = 0

    /** The shown one, 0 for the newest at the bottom. */
    private var index = 0

    fun set(count: Int, index: Int) {
        if (this.count != count || this.index != index) {
            this.count = count
            this.index = index
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (count == 0) {
            return
        }
        val shown = count.coerceAtMost(MAX_SEGMENTS)
        val gap = resources.displayMetrics.density * GAP_DP
        val segment = (height - gap * (shown - 1)) / shown
        val radius = width / 2f
        // The window of segments that holds the shown one, newest at the bottom
        val first = (index - shown / 2).coerceIn(0, count - shown)
        for (i in 0 until shown) {
            val number = first + i
            val bottom = height - i * (segment + gap)
            rect.set(0f, bottom - segment, width.toFloat(), bottom)
            paint.color = when (number) {
                index -> lit
                else -> (lit and 0x00FFFFFF) or (DIM_ALPHA shl 24)
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private companion object {
        const val MAX_SEGMENTS = 4
        const val GAP_DP = 2
        const val DIM_ALPHA = 0x55
    }
}

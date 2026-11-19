/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Wraps short contents while limiting longer text to the available space beside a sticker. */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var maxWidth: Int = Int.MAX_VALUE
        set(value) {
            val width = value.coerceAtLeast(0)
            if (field == width) return
            field = width
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxWidth == Int.MAX_VALUE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val width = if (mode == MeasureSpec.UNSPECIFIED) maxWidth
            else MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(maxWidth)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width,
            if (mode == MeasureSpec.EXACTLY) mode else MeasureSpec.AT_MOST), heightMeasureSpec)
    }
}

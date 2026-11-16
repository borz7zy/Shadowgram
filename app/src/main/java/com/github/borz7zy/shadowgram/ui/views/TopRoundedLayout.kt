/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.github.borz7zy.shadowgram.R

/**
 * A container with rounded top corners, clipping its content to them.
 *
 * Its outline is a plain round rect stretched past the bottom (and past the side of a square top
 * corner), so only the wanted corners show. The GPU clips that analytically; a MaterialCardView
 * with only some corners rounded clips to a path instead, whose coverage mask is rasterized in
 * software at the card's full size and uploaded again on every frame anything inside animates.
 */
class TopRoundedLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val attributes = context.obtainStyledAttributes(attrs, R.styleable.TopRoundedLayout)
    private val radius = attributes.getDimension(R.styleable.TopRoundedLayout_cornerRadius, 0f)
    private val roundTopStart = attributes.getBoolean(R.styleable.TopRoundedLayout_roundTopStart, true)
    private val roundTopEnd = attributes.getBoolean(R.styleable.TopRoundedLayout_roundTopEnd, true)

    init {
        attributes.recycle()

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = radius.toInt()
                val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
                val roundLeft = if (isRtl) roundTopEnd else roundTopStart
                val roundRight = if (isRtl) roundTopStart else roundTopEnd
                outline.setRoundRect(
                    if (roundLeft) 0 else -r,
                    0,
                    if (roundRight) view.width else view.width + r,
                    view.height + r,
                    radius,
                )
            }
        }
        clipToOutline = true
    }
}

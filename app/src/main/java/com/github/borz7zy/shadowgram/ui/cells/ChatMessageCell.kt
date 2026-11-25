/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */
package com.github.borz7zy.shadowgram.ui.cells

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager

/**
 * First-stage hybrid Cell. The existing XML children remain usable by the context menu,
 * album viewer, avatar decoration and accessibility. Only local geometry is animated;
 * no change-alpha animation or duplicate message ViewHolder is required.
 */
class ChatMessageCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    fun beginEditTransition(bubble: View, text: View, meta: View) {
        if (!isAttachedToWindow || !isLaidOut || bubble.width == 0 || bubble.height == 0) return
        // Do not stack transitions from consecutive UpdateMessageContent/Edited callbacks.
        finishEditTransition()
        val change = ChangeBounds().apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addTarget(bubble)
            addTarget(text)
            addTarget(meta)
        }
        TransitionManager.beginDelayedTransition(this, change)
    }

    fun finishEditTransition() {
        TransitionManager.endTransitions(this)
    }

    override fun onDetachedFromWindow() {
        finishEditTransition()
        super.onDetachedFromWindow()
    }
}

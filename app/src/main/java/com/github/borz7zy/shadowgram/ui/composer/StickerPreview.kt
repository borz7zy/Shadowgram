/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.telegram.StickersStore
import com.github.borz7zy.shadowgram.ui.views.StickerView
import java.io.File

/**
 * Sticker preview on long press, the way VK's sticker keyboard does it: the screen dims, the
 * sticker shows big in the middle, and sliding the finger over the grid previews the sticker
 * under it with a short pulse. Releasing closes it, the press doesn't send anything.
 *
 * @param stickerAt The sticker shown by a child of the grid, null for headers
 */
class StickerPreview(
    private val recyclerView: RecyclerView,
    private val stickerAt: (View) -> StickersStore.Sticker?,
) : RecyclerView.SimpleOnItemTouchListener() {
    private val context = recyclerView.context
    private val size = (PREVIEW_SIZE_DP * context.resources.displayMetrics.density).toInt()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val sticker = StickerView(context)
    private val unsupported = ImageView(context).apply {
        setImageResource(R.drawable.ic_close)
        setColorFilter(Color.WHITE)
    }
    private val emoji = TextView(context).apply {
        textSize = EMOJI_TEXT_SP
        gravity = Gravity.CENTER
    }
    private val content = FrameLayout(context).apply {
        addView(image, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        addView(sticker, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        addView(unsupported, FrameLayout.LayoutParams(size / 3, size / 3, Gravity.CENTER))
    }
    private val root = FrameLayout(context).apply {
        setBackgroundColor(DIM_COLOR)
        addView(content, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        addView(
            emoji,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { topMargin = size / 2 + size / 5 }
        )
    }
    private val window = PopupWindow(
        root, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, false
    ).apply { animationStyle = 0 }

    private val pulse = ObjectAnimator.ofPropertyValuesHolder(
        content,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_SCALE, 1f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_SCALE, 1f),
    ).apply { duration = PULSE_MS }

    private var downX = 0f
    private var downY = 0f
    private var isShowing = false
    private var shown: StickersStore.Sticker? = null

    private val onLongPress = Runnable {
        val child = recyclerView.findChildViewUnder(downX, downY) ?: return@Runnable
        val target = stickerAt(child) ?: return@Runnable
        isShowing = true
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        recyclerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Cancels the press on the cell, so it doesn't send on release
        child.isPressed = false
        child.cancelLongPress()
        show(target, animate = false)
        window.showAtLocation(recyclerView.rootView, Gravity.CENTER, 0, 0)
    }

    init {
        recyclerView.addOnItemTouchListener(this)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                rv.postDelayed(onLongPress, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> if (!isShowing &&
                (kotlin.math.abs(e.x - downX) > touchSlop || kotlin.math.abs(e.y - downY) > touchSlop)
            ) {
                rv.removeCallbacks(onLongPress)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rv.removeCallbacks(onLongPress)
                // The event that starts the interception never reaches onTouchEvent
                if (isShowing) {
                    dismiss()
                    return true
                }
            }
        }
        // Once showing, every event is ours: the grid neither scrolls nor clicks
        return isShowing
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val target = rv.findChildViewUnder(e.x, e.y)?.let(stickerAt) ?: return
                if (target != shown) {
                    rv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    show(target, animate = true)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dismiss()
        }
    }

    private fun show(target: StickersStore.Sticker, animate: Boolean) {
        shown = target
        val file = target.path?.let(::File)
        emoji.text = target.emoji

        sticker.isVisible = target.format == StickersStore.Format.ANIMATED
        image.isVisible = target.format == StickersStore.Format.WEBP
        unsupported.isVisible = target.format == StickersStore.Format.UNSUPPORTED

        sticker.setSticker(file.takeIf { sticker.isVisible }, size)
        when {
            image.isVisible && file != null -> image.load(file) { size(size, size) }
            else -> {
                image.dispose()
                image.setImageDrawable(null)
            }
        }

        if (animate) {
            pulse.cancel()
            pulse.start()
        }
    }

    private fun dismiss() {
        isShowing = false
        shown = null
        pulse.cancel()
        sticker.setSticker(null, 0)
        image.dispose()
        window.dismiss()
    }

    private companion object {
        const val PREVIEW_SIZE_DP = 220
        const val PULSE_SCALE = 1.08f
        const val PULSE_MS = 200L
        const val EMOJI_TEXT_SP = 32f
        const val DIM_COLOR = 0x77000000
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.github.borz7zy.shadowgram.R
import com.google.android.material.color.MaterialColors

/** A 48dp touch target around the divider; the grip is visible in the middle of the screen. */
class ChatPaneDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onResizeBy: ((Float) -> Unit)? = null
    var onResizeFinished: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null
    var widthFraction = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var downX = 0f
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryVariant)
    private val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.chat_resize_list)
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.SeekBar"
                info.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                    AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT, 0f, 1f, widthFraction,
                )
                if (isEnabled) {
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
                }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (isEnabled && action in listOf(
                        AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
                        AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,
                    )) {
                    resizeStep(if (action == AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD) 1 else -1)
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        paint.color = outline
        canvas.drawRect(x, 0f, x + density, height.toFloat(), paint)
        if (!isEnabled) return
        val y = height / 2f
        paint.color = surface
        canvas.drawRoundRect(x - 12 * density, y - 32 * density,
            x + 12 * density, y + 32 * density, 12 * density, 12 * density, paint)
        paint.color = if (isPressed || isFocused) primary else onSurface
        canvas.drawRoundRect(x - 2 * density, y - 16 * density,
            x + 2 * density, y + 16 * density, 2 * density, 2 * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (kotlin.math.abs(event.y - height / 2f) > 48 * density) return false
                pointerId = event.getPointerId(0)
                lastX = event.rawX
                downX = lastX
                moved = false
                isPressed = true
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return false
                val x = event.getRawX(index)
                if (kotlin.math.abs(x - downX) > touchSlop) moved = true
                if (moved) onResizeBy?.invoke(x - lastX)
                lastX = x
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    val next = if (event.actionIndex == 0) 1 else 0
                    pointerId = event.getPointerId(next)
                    lastX = event.getRawX(next)
                    downX = lastX
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && !moved) performClick()
                else onResizeFinished?.invoke()
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        super.performClick()
        onToggle?.invoke()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            resizeStep(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun resizeStep(direction: Int) {
        onResizeBy?.invoke(direction * 24 * density)
        onResizeFinished?.invoke()
    }
}

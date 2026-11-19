/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/** Keeps swipes across overflowing collection chips in the strip inside the profile pager. */
class GiftCollectionsScrollView(context: Context, attrs: AttributeSet?) :
    HorizontalScrollView(context, attrs) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (canScrollHorizontally(-1) || canScrollHorizontally(1))
        ) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(event)
    }
}

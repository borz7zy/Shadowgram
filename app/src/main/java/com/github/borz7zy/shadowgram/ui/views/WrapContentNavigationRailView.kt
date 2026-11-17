/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.annotation.AttrRes
import com.google.android.material.navigationrail.NavigationRailView

/** Leaves space for folders when this rail shares a column with a weighted scroll view. */
class WrapContentNavigationRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.navigationRailStyle,
) : NavigationRailView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST
        ) {
            // Material's internal NavigationRailFrameLayout uses the available height as a
            // minimum, even under AT_MOST. Measure intrinsic content with no height budget
            // first, then give it that exact bounded height. Font scaling and menu contents
            // determine the result; no fixed three-button height is assumed.
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val contentHeight = measuredHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY))
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

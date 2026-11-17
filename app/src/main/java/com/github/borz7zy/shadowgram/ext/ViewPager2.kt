/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ext

import androidx.viewpager2.widget.ViewPager2

/** Model feedback must not turn an in-progress swipe into an instant scroll to the same page. */
fun ViewPager2.setCurrentItemIfChanged(position: Int) {
    if (currentItem != position) setCurrentItem(position, false)
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import android.util.DisplayMetrics
import kotlin.math.roundToInt

/**
 * Convert a device independent pixel (dp) value to a pixel (px) value.
 */
fun DisplayMetrics.toPx(dp: Int): Int = dp.times(density).roundToInt()

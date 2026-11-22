/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.util.Log

private const val TAG = "ShadowgramNotify"

internal inline fun logNotificationDelivery(message: () -> String) {
    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message())
}

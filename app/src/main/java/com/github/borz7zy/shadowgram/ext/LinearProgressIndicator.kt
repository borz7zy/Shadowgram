/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import com.google.android.material.progressindicator.LinearProgressIndicator
import com.github.borz7zy.shadowgram.models.FlowResult

/**
 * @see LinearProgressIndicator.setProgressCompat
 */
fun <T, E> LinearProgressIndicator.setProgressCompat(status: FlowResult<T, E>) {
    when (status) {
        is FlowResult.Loading -> {
            if (!isIndeterminate) {
                hide()
                isIndeterminate = true
            }

            show()
        }

        else -> {
            hide()
        }
    }
}

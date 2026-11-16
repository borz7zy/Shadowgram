/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import android.content.ClipData

fun ClipData.asArray() = buildList {
    for (i in 0 until itemCount) {
        add(getItemAt(i))
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import android.content.res.Configuration

/**
 * Return whether the orientation is [Configuration.ORIENTATION_LANDSCAPE].
 */
val Configuration.isLandscape
    get() = orientation == Configuration.ORIENTATION_LANDSCAPE

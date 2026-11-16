/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import android.database.Cursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.github.borz7zy.shadowgram.models.ColumnIndexCache

fun <T> Flow<Cursor?>.mapEachRow(
    mapping: (ColumnIndexCache) -> T,
) = map { it.mapEachRow(mapping) }

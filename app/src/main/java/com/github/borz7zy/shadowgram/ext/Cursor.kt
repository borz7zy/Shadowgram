/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import android.database.Cursor
import com.github.borz7zy.shadowgram.models.ColumnIndexCache

fun <T> Cursor?.mapEachRow(
    mapping: (ColumnIndexCache) -> T,
) = this?.use { cursor ->
    if (!cursor.moveToFirst()) {
        return@use emptyList<T>()
    }

    val columnIndexCache = ColumnIndexCache(cursor)

    buildList(cursor.count) {
        do {
            add(mapping(columnIndexCache))
        } while (cursor.moveToNext())
    }
} ?: emptyList()

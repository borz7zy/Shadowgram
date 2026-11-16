/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.models

data class ActivityTab(
    val id: String,
    val title: LocalizedString,
    val items: List<MediaItem<*>>,
) : UniqueItem<ActivityTab> {
    override fun areItemsTheSame(other: ActivityTab) = id == other.id

    override fun areContentsTheSame(other: ActivityTab) =
        title == other.title && items.toTypedArray().contentEquals(other.items.toTypedArray())
}

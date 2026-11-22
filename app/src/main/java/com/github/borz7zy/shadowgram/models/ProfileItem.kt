/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

import com.github.borz7zy.shadowgram.playback.MessagePlayer

/**
 * An entry of a profile tab: a media cell, a file, a link, a member... Immutable, so that pages
 * can be diffed.
 *
 * @param key Unique in its page
 * @param title The first line of a row, unused by grid cells
 * @param subtitle The second line of a row
 * @param badge Shown over a grid cell, the duration of a video
 * @param imagePath A picture to show, a photo, a thumbnail or a WebP sticker, once downloaded
 * @param minithumbnail Tiny blurry JPEG shown until [imagePath] is downloaded
 * @param stickerPath A WebM or TGS sticker, played instead of [imagePath]
 * @param avatar Rows of chats and members show its avatar
 * @param chatId The chat or user a row opens, 0 for none
 * @param track What plays when tapped
 * @param document The file a tap downloads, cancels or opens
 */
data class ProfileItem(
    val key: String,
    val title: String? = null,
    val subtitle: String? = null,
    val badge: String? = null,
    val imagePath: String? = null,
    val minithumbnail: ByteArray? = null,
    val stickerPath: String? = null,
    val avatar: ChatItem? = null,
    val chatId: Long = 0L,
    val track: MessagePlayer.Track? = null,
    val document: MessageItem.Document? = null,
    val isPinned: Boolean = false,
    val profileAction: ProfileAction? = null,
) : UniqueItem<ProfileItem> {
    sealed interface ProfileAction {
        data class Story(val id: Int, val canPin: Boolean, val canChangeVisibility: Boolean, val posted: Boolean) :
            ProfileAction
        data class Gift(val id: String, val upgraded: Boolean, val saved: Boolean) : ProfileAction
    }

    override fun areItemsTheSame(other: ProfileItem) = key == other.key

    override fun areContentsTheSame(other: ProfileItem) = this == other
}

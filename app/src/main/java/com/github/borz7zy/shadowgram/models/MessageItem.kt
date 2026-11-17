/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview

/**
 * A message of an open chat.
 *
 * @param senderId The user or chat that sent it
 * @param senderName Shown on incoming messages in groups, null elsewhere
 * @param senderPhotoPath The downloaded small photo of the sender, for the avatar next to
 *   incoming messages in groups
 * @param kind What the message holds, text or the kind of its media
 * @param text The text or caption, if any
 * @param date Unix time
 * @param media The picture to show in the bubble, if any
 * @param uploadProgress While the message is being sent, percent of its file processed (see
 *   [isProcessing]) or uploaded, null when nothing is sent
 * @param isProcessing The file is still being prepared (a video note transcoded), its upload
 *   comes next
 */
data class MessageItem(
    val id: Long,
    val isOutgoing: Boolean,
    val senderId: Long,
    val senderName: String?,
    val senderAccentColorId: Int,
    val senderPhotoPath: String? = null,
    val kind: MessagePreview.Kind,
    val text: String?,
    val date: Int,
    val state: State,
    val media: Media? = null,
    val uploadProgress: Int? = null,
    val isProcessing: Boolean = false,
    val playable: Playable? = null,
    val document: Document? = null,
    /** The sender's emoji status, a custom emoji id, 0 for none. */
    val senderEmojiStatusId: Long = 0L,
    val senderIsPremium: Boolean = false,
    /** Reactions under the message, most used first. */
    val reactions: List<Reaction> = listOf(),
    /** Custom emoji in [text]. */
    val customEmoji: List<com.github.borz7zy.shadowgram.ui.text.CustomEmojiRange> = listOf(),
    /** Messages sent together as an album share it, 0 for none. TDLib's mediaAlbumId. */
    val mediaAlbumId: Long = 0L,
    /** Where it was forwarded from, null when it wasn't. */
    val forward: Forward? = null,
    /** The message it replies to, null when it doesn't. */
    val reply: Reply? = null,
    /** Mentions in [text] that open a profile. */
    val mentions: List<Mention> = listOf(),
) {
    /**
     * An @username or a mention by name in the text, offsets in UTF-16 like TDLib's.
     *
     * @param userId The user mentioned by name, 0 for an @username
     */
    data class Mention(val offset: Int, val length: Int, val userId: Long)

    /**
     * @param originId The user or chat whose profile opens, 0 when they hid their account
     * @param photoPath Their downloaded small photo, for the avatar next to the name
     */
    data class Forward(
        val name: String,
        val accentColorId: Int,
        val originId: Long,
        val photoPath: String?,
    )

    /**
     * @param messageId The replied message in this chat, 0 when it's in another one
     * @param name Who sent it, null while TDLib loads it
     * @param kind What it is, said when it has no [text]
     * @param quote The part of its text the reply is about, null for the whole message
     * @param quotePosition Where [quote] starts in its text, UTF-16
     */
    data class Reply(
        val messageId: Long,
        val name: String?,
        val accentColorId: Int,
        val kind: MessagePreview.Kind,
        val text: String?,
        val quote: String? = null,
        val quotePosition: Int = 0,
        /** It's gone: the reply says so instead. */
        val isDeleted: Boolean = false,
    )

    /**
     * @param emoji A plain emoji reaction, null for the others
     * @param customEmojiId A custom emoji reaction, 0 for the others; neither one is a paid star
     * @param isChosen Added by this account
     */
    data class Reaction(
        val emoji: String?,
        val customEmojiId: Long,
        val count: Int,
        val isChosen: Boolean,
    ) {
        val isPaid get() = emoji == null && customEmojiId == 0L
    }

    /**
     * A voice or video message or music, played by
     * [com.github.borz7zy.shadowgram.playback.MessagePlayer]; its file downloads when played.
     *
     * @param duration Seconds
     * @param isMusic An audio file, with its [title] and [performer] and album cover
     */
    data class Playable(
        val fileId: Int,
        val duration: Int,
        val isVideo: Boolean,
        val isMusic: Boolean = false,
        val title: String = "",
        val performer: String = "",
        val artworkPath: String? = null,
    )

    /**
     * A file sent as is, downloaded when asked to.
     *
     * @param size Bytes, as known so far
     * @param path The downloaded file, null until it's complete
     */
    data class Document(
        val fileId: Int,
        val name: String,
        val size: Long,
        val downloadedSize: Long,
        val isDownloading: Boolean,
        val path: String?,
        /** False in protected chats: viewed only, never handed to "Send to…". */
        val canBeSaved: Boolean,
    )

    /**
     * A picture of the message, decoded by RustCore through Coil or the sticker player.
     *
     * @param path The downloaded file, null while it downloads
     * @param minithumbnail Tiny blurry JPEG shown until [path] is there
     * @param videoPath The downloaded MP4 of a [Type.ANIMATION], played over its still [path]
     */
    data class Media(
        val type: Type,
        val path: String?,
        val minithumbnail: ByteArray?,
        val width: Int,
        val height: Int,
        val videoPath: String? = null,
        val videoFileId: Int = 0,
        val duration: Int = 0,
    ) {
        enum class Type {
            /** A photo, or the still thumbnail of a video or GIF. */
            PHOTO,

            /** A WebP sticker. */
            STICKER,

            /** A WebM or TGS sticker, played frame by frame by StickerView. */
            ANIMATED_STICKER,

            /** A GIF: Telegram sends them as silent MP4, looped by the platform decoder. */
            ANIMATION,

            /**
             * A video's still, its [videoFileId] previewed over it muted while it downloads,
             * [duration] seconds long.
             */
            VIDEO,

            /** The still thumbnail of a video note, shown round. */
            VIDEO_NOTE,
        }
    }

    enum class State {
        PENDING,
        FAILED,
        SENT,
        READ,
    }
}

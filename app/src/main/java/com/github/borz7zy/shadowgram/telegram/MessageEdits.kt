/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.R
import org.drinkless.tdlib.TdApi

/** The message edits handled by the chat composer. Rights come from TDLib. */
object MessageEdits {
    data class Attachment(
        val label: Int,
        val name: String? = null,
        val thumbnail: TdApi.File? = null,
    )

    class Target(
        val message: TdApi.Message,
        val text: TdApi.FormattedText,
        val attachment: Attachment?,
        val canReplaceMedia: Boolean,
        private val captionAboveMedia: Boolean,
    ) {
        val isCaption = message.content !is TdApi.MessageText &&
            message.content !is TdApi.MessageAnimatedEmoji && message.content !is TdApi.MessageGame
        val prefersAudio = message.content is TdApi.MessageAudio
        val isVisualAlbum = message.mediaAlbumId != 0L &&
            (message.content is TdApi.MessagePhoto || message.content is TdApi.MessageVideo)
        val isAudioAlbum = message.mediaAlbumId != 0L && prefersAudio
        val isDocumentAlbum = message.mediaAlbumId != 0L && message.content is TdApi.MessageDocument

        fun request(
            text: TdApi.FormattedText,
            replacement: TdApi.InputMessageContent? = null,
        ): TdApi.Function<TdApi.Message> {
            val caption = text.takeIf { it.text.isNotEmpty() }
            if (replacement != null) {
                val hasSpoiler = when (val content = message.content) {
                    is TdApi.MessagePhoto -> content.hasSpoiler
                    is TdApi.MessageVideo -> content.hasSpoiler
                    is TdApi.MessageAnimation -> content.hasSpoiler
                    else -> false
                }
                when (replacement) {
                    is TdApi.InputMessagePhoto -> {
                        replacement.caption = caption
                        replacement.showCaptionAboveMedia = captionAboveMedia
                        replacement.hasSpoiler = hasSpoiler
                    }
                    is TdApi.InputMessageVideo -> {
                        replacement.caption = caption
                        replacement.showCaptionAboveMedia = captionAboveMedia
                        replacement.hasSpoiler = hasSpoiler
                    }
                    is TdApi.InputMessageAnimation -> {
                        replacement.caption = caption
                        replacement.showCaptionAboveMedia = captionAboveMedia
                        replacement.hasSpoiler = hasSpoiler
                    }
                    is TdApi.InputMessageAudio -> replacement.caption = caption
                    is TdApi.InputMessageDocument -> replacement.caption = caption
                }
                return TdApi.EditMessageMedia(message.chatId, message.id, null, replacement)
            }
            return if (isCaption) {
                TdApi.EditMessageCaption(message.chatId, message.id, null, caption, captionAboveMedia)
            } else {
                val preview = (message.content as? TdApi.MessageText)?.linkPreviewOptions
                TdApi.EditMessageText(
                    message.chatId, message.id, null, TdApi.InputMessageText(text, preview, false),
                )
            }
        }

        fun accepts(replacement: TdApi.InputMessageContent): Boolean {
            if (!canReplaceMedia) return false
            return when {
                isVisualAlbum -> replacement is TdApi.InputMessagePhoto || replacement is TdApi.InputMessageVideo
                isAudioAlbum -> replacement is TdApi.InputMessageAudio
                isDocumentAlbum -> replacement is TdApi.InputMessageDocument
                else -> replacement is TdApi.InputMessagePhoto || replacement is TdApi.InputMessageVideo ||
                    replacement is TdApi.InputMessageAnimation || replacement is TdApi.InputMessageAudio ||
                    replacement is TdApi.InputMessageDocument
            }
        }
    }

    fun target(message: TdApi.Message, properties: TdApi.MessageProperties): Target? {
        if (!properties.canBeEdited) return null
        val content = message.content
        val (text, attachment) = when (content) {
            is TdApi.MessageText -> content.text to null
            is TdApi.MessageAnimatedEmoji -> {
                val custom = content.animatedEmoji.sticker?.fullType as? TdApi.StickerFullTypeCustomEmoji
                val entities = custom?.let {
                    arrayOf(TdApi.TextEntity(0, content.emoji.length, TdApi.TextEntityTypeCustomEmoji(it.customEmojiId)))
                } ?: emptyArray()
                TdApi.FormattedText(content.emoji, entities) to null
            }
            is TdApi.MessageGame -> content.game.text to Attachment(
                R.string.message_edit_game, content.game.title, photoThumbnail(content.game.photo),
            )
            is TdApi.MessagePhoto -> content.caption to Attachment(
                R.string.message_photo, thumbnail = photoThumbnail(content.photo),
            )
            is TdApi.MessageVideo -> content.caption to Attachment(
                R.string.message_video, content.video.fileName, content.video.thumbnail?.file,
            )
            is TdApi.MessageAnimation -> content.caption to Attachment(
                R.string.message_animation, content.animation.fileName, content.animation.thumbnail?.file,
            )
            is TdApi.MessageAudio -> content.caption to Attachment(
                R.string.message_audio, content.audio.title.ifBlank { content.audio.fileName },
                content.audio.albumCoverThumbnail?.file,
            )
            is TdApi.MessageDocument -> content.caption to Attachment(
                R.string.message_document, content.document.fileName, content.document.thumbnail?.file,
            )
            is TdApi.MessageVoiceNote -> content.caption to Attachment(R.string.message_voice_note)
            is TdApi.MessagePaidMedia -> content.caption to Attachment(R.string.message_edit_paid_media)
            else -> return null
        }
        val captionAbove = when (content) {
            is TdApi.MessagePhoto -> content.showCaptionAboveMedia
            is TdApi.MessageVideo -> content.showCaptionAboveMedia
            is TdApi.MessageAnimation -> content.showCaptionAboveMedia
            is TdApi.MessagePaidMedia -> content.showCaptionAboveMedia
            else -> false
        }
        return Target(
            message, text ?: TdApi.FormattedText("", emptyArray()), attachment,
            properties.canEditMedia, captionAbove,
        )
    }

    private fun photoThumbnail(photo: TdApi.Photo?) = photo?.sizes?.let { sizes ->
        val ready = sizes.filter { it.photo.local.isDownloadingCompleted }
        val candidates = ready.ifEmpty { sizes.asList() }
        (candidates.filter { maxOf(it.width, it.height) >= 160 }.minByOrNull { maxOf(it.width, it.height) }
            ?: candidates.maxByOrNull { maxOf(it.width, it.height) })?.photo
    }
}

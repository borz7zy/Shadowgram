/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.models.Result
import org.drinkless.tdlib.TdApi

/**
 * The sticker files behind custom emoji (emoji statuses, custom reactions) and emoji reactions,
 * downloaded once and kept by id.
 *
 * Main thread only.
 */
object EmojiStickers {
    /**
     * @param isAnimated TGS or WebM, played by StickerView; WebP otherwise, decoded through Coil
     */
    data class File(val path: String, val isAnimated: Boolean)

    private const val DOWNLOAD_PRIORITY = 16

    private val customEmoji = HashMap<Long, File>()
    private val reactions = HashMap<String, File>()

    suspend fun customEmoji(client: TelegramClient, id: Long): File? {
        customEmoji[id]?.let { return it }
        // ponytail: one request per emoji, batch the ids of a screen if it shows up in profiles
        val sticker = (client.send(TdApi.GetCustomEmojiStickers(longArrayOf(id))) as? Result.Success)
            ?.data?.stickers?.firstOrNull() ?: return null
        return download(client, sticker)?.also { customEmoji[id] = it }
    }

    /** The animation an emoji reaction plays when picked, its icon if there's none. */
    suspend fun emojiReaction(client: TelegramClient, emoji: String): File? {
        reactions[emoji]?.let { return it }
        val reaction = (client.send(TdApi.GetEmojiReaction(emoji)) as? Result.Success)?.data
            ?: return null
        val sticker = reaction.selectAnimation ?: reaction.staticIcon ?: return null
        return download(client, sticker)?.also { reactions[emoji] = it }
    }

    private suspend fun download(client: TelegramClient, sticker: TdApi.Sticker): File? {
        val file = client.send(TdApi.DownloadFile(sticker.sticker.id, DOWNLOAD_PRIORITY, 0, 0, true))
        val path = (file as? Result.Success)?.data?.local?.takeIf { it.isDownloadingCompleted }?.path
            ?: return null
        return File(path, sticker.format !is TdApi.StickerFormatWebp)
    }
}

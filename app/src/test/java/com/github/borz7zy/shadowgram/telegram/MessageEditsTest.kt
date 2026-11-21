/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditsTest {
    private val editable = TdApi.MessageProperties().apply {
        canBeEdited = true
        canEditMedia = true
    }

    private fun message(body: TdApi.MessageContent, albumId: Long = 0) = TdApi.Message().apply {
        chatId = 42
        id = 17
        content = body
        mediaAlbumId = albumId
    }

    private fun text(value: String) = TdApi.FormattedText(value, emptyArray())

    @Test
    fun requiresTdlibPermissionAndComposerCompatibleContent() {
        val original = message(TdApi.MessageText(text("Original"), null, null))
        assertNull(MessageEdits.target(original, TdApi.MessageProperties()))
        assertNull(MessageEdits.target(message(TdApi.MessagePoll()), editable))
        assertFalse(MessageEdits.target(original, TdApi.MessageProperties().apply {
            canBeEdited = true
        })!!.accepts(TdApi.InputMessagePhoto()))
    }

    @Test
    fun editsOriginalTextWithoutClearingTelegramDraft() {
        val options = TdApi.LinkPreviewOptions(true, "", false, false, false)
        val target = MessageEdits.target(message(TdApi.MessageText(text("Original"), null, options)), editable)!!
        val edited = text("Edited")
        val request = target.request(edited) as TdApi.EditMessageText
        val input = request.inputMessageContent as TdApi.InputMessageText
        assertEquals(42L, request.chatId)
        assertEquals(17L, request.messageId)
        assertSame(edited, input.text)
        assertSame(options, input.linkPreviewOptions)
        assertFalse(input.clearDraft)
    }

    @Test
    fun removingCaptionKeepsTheMedia() {
        val photo = TdApi.MessagePhoto().apply {
            caption = text("Original caption")
            showCaptionAboveMedia = true
        }
        val target = MessageEdits.target(message(photo), editable)!!
        val request = target.request(text("")) as TdApi.EditMessageCaption
        assertNull(request.caption)
        assertTrue(request.showCaptionAboveMedia)
    }

    @Test
    fun replacesOnlyTheSelectedAlbumItemWithCompatibleMedia() {
        val photo = TdApi.MessagePhoto().apply {
            caption = text("Original caption")
            hasSpoiler = true
            showCaptionAboveMedia = true
        }
        val target = MessageEdits.target(message(photo, albumId = 100), editable)!!
        val video = TdApi.InputMessageVideo()
        assertTrue(target.accepts(video))
        assertFalse(target.accepts(TdApi.InputMessageDocument()))
        assertFalse(target.accepts(TdApi.InputMessageAnimation()))

        val caption = text("New caption")
        val request = target.request(caption, video) as TdApi.EditMessageMedia
        assertEquals(17L, request.messageId)
        assertSame(video, request.inputMessageContent)
        assertSame(caption, video.caption)
        assertTrue(video.showCaptionAboveMedia)
        assertTrue(video.hasSpoiler)
    }

    @Test
    fun animatedEmojiIsEditedAsText() {
        val emoji = "\uD83D\uDE04"
        val content = TdApi.MessageAnimatedEmoji(TdApi.AnimatedEmoji(), emoji)
        val target = MessageEdits.target(message(content), editable)!!
        assertEquals(emoji, target.text.text)
        assertTrue(target.request(text("Hello")) is TdApi.EditMessageText)
    }
}

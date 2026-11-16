/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test

class InputContentsTest {
    private fun photo() = TdApi.InputMessagePhoto()
    private fun video() = TdApi.InputMessageVideo()
    private fun document() = TdApi.InputMessageDocument()
    private fun gif() = TdApi.InputMessageAnimation()

    private fun shape(contents: List<TdApi.InputMessageContent>) =
        InputContents.albums(contents).map { album -> album.map { it.javaClass.simpleName.removePrefix("InputMessage") } }

    @Test
    fun groupsKindsInARow() {
        assertEquals(
            listOf(
                listOf("Photo", "Video", "Photo"),
                listOf("Document", "Document"),
                listOf("Animation"),
                listOf("Animation"),
                listOf("Photo"),
            ),
            shape(listOf(photo(), video(), photo(), document(), document(), gif(), gif(), photo())),
        )
    }

    @Test
    fun splitsAtTen() {
        assertEquals(listOf(10, 10, 3), InputContents.albums(List(23) { photo() }).map { it.size })
    }
}

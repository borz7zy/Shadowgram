/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import com.github.borz7zy.shadowgram.ui.views.AnimatedStickerPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerTimelineTest {
    @Test fun recycledGiftKeepsTheTimeOfItsFirstFrame() {
        val timeline = AnimatedStickerPlayback.Timeline()
        assertNull(timeline.startedAt)
        assertEquals(100L, timeline.start(100L))
        assertEquals(100L, timeline.start(750L))
        assertEquals(100L, timeline.startedAt)
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveChatPanePolicyTest {
    @Test fun `phone windows ignore saved fold widths and hidden navigation at every density`() {
        listOf(320, 360, 393, 411, 599).forEach { widthDp ->
            listOf(1f, 2f, 3f).forEach { density ->
                val pane = AdaptiveChatPanePolicy.calculate((widthDp * density).toInt(), density, true,
                    preferredListWidthDp = 168f, navigationRailHidden = true)
                assertFalse(pane.isSplit)
                assertFalse(pane.isResizable)
                assertFalse(pane.navigationRailHidden)
                assertEquals(0, pane.chatLeft)
            }
        }
    }
    @Test fun `hiding navigation leaves exactly the avatar column`() {
        val pane = AdaptiveChatPanePolicy.calculate(840, 1f, true,
            preferredListWidthDp = 168f, navigationRailHidden = true)
        assertEquals(88, pane.listWidth)
        assertTrue(pane.navigationRailHidden)
        assertTrue(pane.isResizable)
    }

    @Test fun `expanding avatars restores the navigation rail`() {
        val pane = AdaptiveChatPanePolicy.calculate(840, 1f, true,
            preferredListWidthDp = 280f, navigationRailHidden = true)
        assertEquals(280, pane.listWidth)
        assertFalse(pane.navigationRailHidden)
    }

    @Test fun `navigation stays available in other sections`() {
        val pane = AdaptiveChatPanePolicy.calculate(840, 1f, true,
            preferredListWidthDp = 168f, isChatListSelected = false, navigationRailHidden = true)
        assertFalse(pane.navigationRailHidden)
        assertFalse(pane.isResizable)
        assertTrue(pane.listWidth >= 300)
    }

    @Test fun `hidden rail scales with display density`() {
        val pane = AdaptiveChatPanePolicy.calculate(1680, 2f, true,
            preferredListWidthDp = 168f, navigationRailHidden = true)
        assertEquals(176, pane.listWidth)
        assertTrue(pane.navigationRailHidden)
    }

    @Test fun `closing a chat restores full width navigation`() {
        val pane = AdaptiveChatPanePolicy.calculate(840, 1f, false,
            preferredListWidthDp = 168f, navigationRailHidden = true)
        assertEquals(840, pane.listWidth)
        assertFalse(pane.navigationRailHidden)
    }
    @Test fun `contacts and profile keep chat open with a fixed readable left pane`() {
        val default = AdaptiveChatPanePolicy.calculate(840, 1f, true)
        val pane = AdaptiveChatPanePolicy.calculate(840, 1f, true,
            preferredListWidthDp = 168f, isChatListSelected = false)
        assertTrue(pane.isSplit)
        assertFalse(pane.isResizable)
        assertEquals(default.listWidth, pane.listWidth)
        assertEquals(default.chatLeft, pane.chatLeft)
    }

    @Test fun `returning to chats uses the collapsed preference and re enables resizing`() {
        val pane = AdaptiveChatPanePolicy.calculate(840, 1f, true,
            preferredListWidthDp = AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP, isChatListSelected = true)
        assertEquals(168, pane.listWidth)
        assertTrue(pane.isResizable)
    }

    @Test fun `other sections remain full width without an open chat`() {
        val pane = AdaptiveChatPanePolicy.calculate(840, 1f, false,
            preferredListWidthDp = 168f, isChatListSelected = false)
        assertEquals(840, pane.listWidth)
        assertFalse(pane.isSplit)
        assertFalse(pane.isResizable)
    }

    @Test fun `a crease does not enable resizing outside chats`() {
        val pane = AdaptiveChatPanePolicy.calculate(900, 1f, true, 450, 450,
            preferredListWidthDp = 168f, isChatListSelected = false)
        assertEquals(450, pane.listWidth)
        assertFalse(pane.isResizable)
    }

    @Test fun `dragging left stops at navigation and avatar column`() {
        val panes = AdaptiveChatPanePolicy.calculate(840, 1f, true, preferredListWidthDp = -100f)
        assertTrue(panes.isResizable)
        assertEquals(168, panes.listWidth)
        assertEquals(panes.listWidth, panes.chatLeft)
    }

    @Test fun `dragging right only restores the original split`() {
        val default = AdaptiveChatPanePolicy.calculate(840, 1f, true)
        assertEquals(default, AdaptiveChatPanePolicy.calculate(840, 1f, true, preferredListWidthDp = 9999f))
    }

    @Test fun `preferred width stays in dp across changes in display density`() {
        assertEquals(224, AdaptiveChatPanePolicy.calculate(840, 1f, true, preferredListWidthDp = 224f).listWidth)
        assertEquals(448, AdaptiveChatPanePolicy.calculate(1680, 2f, true, preferredListWidthDp = 224f).listWidth)
    }

    @Test fun `closing or folding ignores the saved narrow width`() {
        assertEquals(840, AdaptiveChatPanePolicy.calculate(840, 1f, false, preferredListWidthDp = 168f).listWidth)
        assertEquals(0, AdaptiveChatPanePolicy.calculate(390, 1f, true, preferredListWidthDp = 168f).chatLeft)
    }

    @Test fun `physical hinge keeps panes out of the gap even with a saved width`() {
        val panes = AdaptiveChatPanePolicy.calculate(900, 1f, true, 440, 460, 168f)
        assertFalse(panes.isResizable)
        assertEquals(440, panes.listWidth)
        assertEquals(460, panes.chatLeft)
    }

    @Test fun `non occluding crease permits an explicit resize`() {
        val panes = AdaptiveChatPanePolicy.calculate(900, 1f, true, 450, 450, 168f)
        assertTrue(panes.isResizable)
        assertEquals(168, panes.listWidth)
        assertEquals(400, AdaptiveChatPanePolicy.calculate(900, 1f, true, 450, 450, 400f).listWidth)
    }

    @Test fun `invalid saved widths use the default`() {
        val default = AdaptiveChatPanePolicy.calculate(840, 1f, true)
        assertEquals(default, AdaptiveChatPanePolicy.calculate(840, 1f, true, preferredListWidthDp = Float.NaN))
        assertEquals(default, AdaptiveChatPanePolicy.calculate(840, 1f, true, preferredListWidthDp = Float.POSITIVE_INFINITY))
    }

    @Test fun `closed chat always leaves the whole window for the list`() {
        listOf(360, 600, 840, 1600).forEach { width ->
            val panes = AdaptiveChatPanePolicy.calculate(width, 1f, false, 400, 440)
            assertFalse(panes.isSplit)
            assertEquals(width, panes.listWidth)
            assertEquals(width, panes.chatLeft)
        }
    }

    @Test fun `folding to a narrow window shows only the open chat`() {
        val panes = AdaptiveChatPanePolicy.calculate(599, 1f, true)
        assertFalse(panes.isSplit)
        assertEquals(0, panes.listWidth)
        assertEquals(0, panes.chatLeft)
    }

    @Test fun `unfolded portrait sized window splits even without landscape orientation`() {
        val panes = AdaptiveChatPanePolicy.calculate(673, 1f, true)
        assertTrue(panes.isSplit)
        assertTrue(panes.listWidth >= 300)
        assertTrue(673 - panes.chatLeft >= 300)
    }

    @Test fun `threshold uses dp rather than physical pixels`() {
        assertFalse(AdaptiveChatPanePolicy.calculate(1198, 2f, true).isSplit)
        val panes = AdaptiveChatPanePolicy.calculate(1200, 2f, true)
        assertTrue(panes.isSplit)
        assertEquals(600, panes.listWidth)
    }

    @Test fun `list stays bounded on a tablet so the chat gets the extra space`() {
        val panes = AdaptiveChatPanePolicy.calculate(1600, 1f, true)
        assertEquals(420, panes.listWidth)
        assertEquals(420, panes.chatLeft)
    }

    @Test fun `separating hinge never covers either pane`() {
        val panes = AdaptiveChatPanePolicy.calculate(900, 1f, true, 440, 460)
        assertTrue(panes.isSplit)
        assertEquals(440, panes.listWidth)
        assertEquals(460, panes.chatLeft)
    }

    @Test fun `zero width fold is a valid separator`() {
        val panes = AdaptiveChatPanePolicy.calculate(900, 1f, true, 450, 450)
        assertEquals(450, panes.listWidth)
        assertEquals(450, panes.chatLeft)
    }

    @Test fun `invalid or off screen hinges cannot create negative or unusable panes`() {
        val default = AdaptiveChatPanePolicy.calculate(840, 1f, true)
        listOf(-10 to 0, 0 to 20, 820 to 860, 400 to 390, 1000 to 1020).forEach { (left, right) ->
            assertEquals(default, AdaptiveChatPanePolicy.calculate(840, 1f, true, left, right))
        }
    }
}

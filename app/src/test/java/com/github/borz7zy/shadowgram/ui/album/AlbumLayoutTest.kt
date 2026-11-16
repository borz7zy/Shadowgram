/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumLayoutTest {
    private val maxWidth = 300f
    private val maxHeight = 200f
    private val spacing = 2f

    private fun layout(vararg ratios: Float) =
        AlbumLayout.compute(ratios.toList(), maxWidth, maxHeight, spacing)

    @Test
    fun twoAlikeSquaresSideBySide() {
        val (a, b) = layout(1f, 1f)
        assertEquals(149f, a.width, 0.01f)
        assertEquals(a.width, b.width, 0.01f)
        assertEquals(a.top, b.top, 0.01f)
        assertEquals(a.right + spacing, b.left, 0.01f)
    }

    @Test
    fun threeWideOnesCoverOverTwo() {
        val cells = layout(1.5f, 1.5f, 1.5f)
        assertEquals(maxWidth, cells[0].width, 0.01f)
        assertEquals(cells[1].top, cells[2].top, 0.01f)
        assertTrue(cells[1].top > cells[0].bottom)
    }

    @Test
    fun threeTallOnesCoverByColumn() {
        val cells = layout(0.5f, 1f, 1f)
        assertEquals(maxHeight, cells[0].height, 0.01f)
        assertEquals(cells[1].left, cells[2].left, 0.01f)
        assertEquals(cells[1].bottom + spacing, cells[2].top, 0.01f)
    }

    @Test
    fun manyFillRowsWithoutOverlaps() {
        val cells = layout(1f, 1.5f, 0.7f, 1.2f, 1f, 0.8f, 1.3f)
        assertEquals(7, cells.size)
        // Rows fill the width
        cells.groupBy { it.top }.values.forEach { row ->
            assertEquals(maxWidth, row.maxOf { it.right }, 1f)
        }
        // No two overlap
        cells.forEachIndexed { i, a ->
            cells.drop(i + 1).forEach { b ->
                val overlaps = a.left < b.right - 0.5f && b.left < a.right - 0.5f &&
                        a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
                assertTrue("$a overlaps $b", !overlaps)
            }
        }
    }
}

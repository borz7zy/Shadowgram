/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.album

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the pictures of an album go in its bubble: the mosaic rules VK uses for several photos
 * of a post (a cover and a column or a row for 2 to 4 of them, the best of one to three rows
 * for more), written anew from how they lay out.
 *
 * Pure geometry, pixels in, cells out; the bubble is their union.
 */
object AlbumLayout {
    /** A picture's place, pixels from the album's top left. */
    data class Cell(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** Wider than this is landscape, narrower than [NARROW] portrait, else square. */
    private const val WIDE = 1.2f
    private const val NARROW = 0.8f

    /**
     * @param ratios Width over height of each picture, in order
     * @param maxWidth The widest the album may be
     * @param maxHeight The tallest it aims at
     * @param spacing Between pictures
     */
    fun compute(ratios: List<Float>, maxWidth: Float, maxHeight: Float, spacing: Float): List<Cell> {
        val r = ratios.map { if (it > 0f) it else 1f }
        val orientations = r.joinToString("") {
            when {
                it > WIDE -> "w"
                it < NARROW -> "n"
                else -> "q"
            }
        }
        val averageRatio = if (r.isEmpty()) 1f else r.sum() / r.size
        val maxRatio = maxWidth / maxHeight
        val m = spacing

        return when (r.size) {
            0 -> listOf()
            1 -> {
                val w = maxWidth
                val h = if (r[0] > 0.5f) w / r[0] else 2 * w
                listOf(Cell(0f, 0f, w, h))
            }

            2 -> when {
                // Two wide ones, alike: one above the other
                orientations == "ww" && averageRatio > 1.4f * maxRatio && r[1] - r[0] < 0.2f -> {
                    val w = maxWidth
                    val h = min(w / r[0], min(w / r[1], (maxHeight - m) / 2))
                    listOf(Cell(0f, 0f, w, h), Cell(0f, h + m, w, 2 * h + m))
                }

                // Alike: side by side, the same size
                orientations == "ww" || orientations == "qq" -> {
                    val w = (maxWidth - m) / 2
                    val h = min(w / r[0], min(w / r[1], maxHeight))
                    listOf(Cell(0f, 0f, w, h), Cell(w + m, 0f, 2 * w + m, h))
                }

                // Different: side by side, widths by their ratios
                else -> {
                    val w0 = ((maxWidth - m) / r[1]) / (1 / r[1] + 1 / r[0])
                    val w1 = maxWidth - w0 - m
                    val h = min(maxHeight, min(w0 / r[0], w1 / r[1]))
                    listOf(Cell(0f, 0f, w0, h), Cell(w0 + m, 0f, w0 + m + w1, h))
                }
            }

            3 -> when (orientations) {
                // A wide cover over two
                "www" -> {
                    val w = maxWidth
                    val coverHeight = min(w / r[0], (maxHeight - m) * 0.66f)
                    val half = (maxWidth - m) / 2
                    val h = min(maxHeight - coverHeight - m, min(half / r[1], half / r[2]))
                    val y = coverHeight + m
                    listOf(
                        Cell(0f, 0f, w, coverHeight),
                        Cell(0f, y, half, y + h),
                        Cell(half + m, y, 2 * half + m, y + h),
                    )
                }

                // A tall cover by a column of two
                else -> {
                    val coverWidth = min(r[0] * maxHeight, (maxWidth - m) * 0.75f)
                    val h2 = ((maxHeight - m) * r[1]) / (r[1] + r[2])
                    val h1 = maxHeight - h2 - m
                    val w = min(maxWidth - coverWidth - m, min(h2 * r[2], r[1] * h1))
                    val x = coverWidth + m
                    listOf(
                        Cell(0f, 0f, coverWidth, maxHeight),
                        Cell(x, 0f, x + w, h1),
                        Cell(x, h1 + m, x + w, h1 + m + h2),
                    )
                }
            }

            4 -> when (orientations) {
                // A wide cover over a row of three
                "wwww" -> {
                    val coverHeight = min(maxWidth / r[0], (maxHeight - m) * 0.66f)
                    val rowHeight = (maxWidth - 2 * m) / (r[1] + r[2] + r[3])
                    val h = min(maxHeight - coverHeight - m, rowHeight)
                    val y = coverHeight + m
                    var x = 0f
                    listOf(Cell(0f, 0f, maxWidth, coverHeight)) + (1..3).map {
                        val w = r[it] * rowHeight
                        Cell(x, y, x + w, y + h).also { x += w + m }
                    }
                }

                // A tall cover by a column of three
                else -> {
                    val coverWidth = min(r[0] * maxHeight, (maxWidth - m) * 0.66f)
                    val columnWidth = (maxHeight - 2 * m) / (1 / r[1] + 1 / r[2] + 1 / r[3])
                    val w = min(maxWidth - coverWidth - m, columnWidth)
                    val heights = listOf(
                        columnWidth / r[1],
                        columnWidth / r[2],
                        // The last one takes the spacing too, so the column ends with the cover
                        columnWidth / r[3] + m,
                    )
                    val x = coverWidth + m
                    var y = 0f
                    listOf(Cell(0f, 0f, coverWidth, maxHeight)) + heights.map { h ->
                        Cell(x, y, x + w, y + h).also { y += h + m }
                    }
                }
            }

            else -> rows(r, averageRatio, maxWidth, maxHeight, m)
        }
    }

    /**
     * Five and more: one, two or three rows, whichever ends up closest to [maxHeight]; rows
     * fuller than the one under them cost a bit more.
     */
    private fun rows(ratios: List<Float>, averageRatio: Float, maxWidth: Float, maxHeight: Float, m: Float): List<Cell> {
        // Mostly wide: none narrower than square, mostly tall: none wider
        val cropped = ratios.map { if (averageRatio > 1.1f) max(1f, it) else min(1f, it) }
        val count = cropped.size

        fun rowHeight(from: Int, to: Int) = (maxWidth - (to - from - 1) * m) / cropped.subList(from, to).sum()

        val tries = ArrayList<List<Int>>()
        tries.add(listOf(count))
        for (first in 1 until count) {
            tries.add(listOf(first, count - first))
        }
        for (first in 1..count - 2) {
            for (second in 1..count - first - 1) {
                tries.add(listOf(first, second, count - first - second))
            }
        }

        val best = tries.minBy { lines ->
            var start = 0
            val total = lines.sumOf { n ->
                rowHeight(start, start + n).toDouble().also { start += n }
            }.toFloat() + m * (lines.size - 1)
            var diff = abs(total - maxHeight)
            if (lines.size > 1 && (lines[0] > lines[1] || (lines.size > 2 && lines[1] > lines[2]))) {
                diff *= 1.1f
            }
            diff
        }

        val rects = ArrayList<Cell>(count)
        var start = 0
        var y = 0f
        best.forEach { n ->
            val h = rowHeight(start, start + n)
            var x = 0f
            for (i in start until start + n) {
                val w = cropped[i] * h
                rects.add(Cell(x, y, x + w, y + h))
                x += w + m
            }
            start += n
            y += h + m
        }
        return rects
    }
}

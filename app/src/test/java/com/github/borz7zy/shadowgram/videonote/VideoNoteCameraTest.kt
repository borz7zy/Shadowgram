/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.videonote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CameraX Quality needs Android, strings stand in for it
class VideoNoteCameraTest {
    private val bestFirst = listOf("UHD", "FHD", "HD")

    @Test
    fun prefersFrameRateThenQualityCommonToBothCameras() {
        val back = mapOf("UHD" to setOf(30, 60), "FHD" to setOf(30, 60, 120))
        // The front camera has no UHD 60: both settle on FHD 60, never 120
        val front = mapOf("UHD" to setOf(30), "FHD" to setOf(30, 60))

        assertEquals("FHD" to 60, pickQualityAndFps(bestFirst, 60, back, front))
    }

    @Test
    fun fallsBackTo30WhenNoCommon60() {
        val back = mapOf("UHD" to setOf(30, 60))
        val front = mapOf("UHD" to setOf(30), "HD" to setOf(60))

        assertEquals("UHD" to 30, pickQualityAndFps(bestFirst, 60, back, front))
    }

    @Test
    fun nothingInCommon() {
        assertNull(
            pickQualityAndFps(
                bestFirst, 60, mapOf("UHD" to setOf(30)), mapOf("HD" to setOf(30))
            )
        )
    }
}

class NextAttemptTest {
    private val max = VideoNoteCamera.MAX_SIZE_BYTES
    private val durationMs = 60_000L

    /** An encoder writing size = pixels × bitrate × duration, floored at a quality limit. */
    private fun encode(attempt: Attempt, floorBitsPerPixel: Double): Long {
        val seconds = (attempt.clipMs ?: durationMs) / 1000.0
        val pixels = attempt.side.toDouble() * attempt.side
        val bitrate = maxOf(attempt.bitrate.toDouble(), pixels * floorBitsPerPixel)
        return (bitrate * seconds / 8).toLong()
    }

    private fun fit(floorBitsPerPixel: Double): Attempt {
        var attempt = Attempt(640, 1_400_000, null)
        var previous: Long? = null
        repeat(8) { index ->
            val size = encode(attempt, floorBitsPerPixel)
            if (size <= max) {
                return attempt
            }
            attempt = nextAttempt(attempt, size, previous, durationMs, index)
            previous = size
        }
        throw AssertionError("Never fit: $attempt")
    }

    @Test
    fun overshootLowersTheBitrateOnly() {
        val attempt = nextAttempt(Attempt(640, 2_000_000, null), max + max / 5, null, durationMs, 0)
        assertEquals(640, attempt.side)
        assertNull(attempt.clipMs)
        assert(attempt.bitrate < 2_000_000)
    }

    @Test
    fun encoderIgnoringTheBitrateGetsFewerPixels() {
        val attempt = nextAttempt(Attempt(640, 600_000, null), 3 * max, 3 * max, durationMs, 1)
        assert(attempt.side in VideoNoteCamera.MIN_LENGTH until 640 && attempt.side % 16 == 0)
        assertNull(attempt.clipMs)
    }

    @Test
    fun keepsShrinkingBeforeCutting() {
        // Size barely follows pixels, like the emulator encoder on noise
        var attempt = Attempt(640, 600_000, null, ignoresBitrate = true)
        var size = 2 * max
        var index = 2
        while (attempt.clipMs == null) {
            val next = nextAttempt(attempt, size, size + 1, durationMs, index++)
            if (next.clipMs != null) {
                assertEquals(VideoNoteCamera.MIN_LENGTH, attempt.side)
            }
            attempt = next
            size = size * 19 / 20
        }
    }

    @Test
    fun alwaysFitsInTheEnd() {
        // A normal encoder, one floored like the emulator's on noise, and a hopeless one
        for (floor in listOf(0.0, 0.25, 2.0, 50.0)) {
            val attempt = fit(floor)
            assert(encode(attempt, floor) <= max) { "floor $floor: $attempt" }
        }
        // The normal encoder keeps the whole note at full size
        assertEquals(null, fit(0.0).clipMs)
        assertEquals(640, fit(0.0).side)
    }
}

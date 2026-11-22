/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.github.borz7zy.rustcore.RustMedia
import java.io.File
import kotlin.math.ceil

/** One WebM or TGS animation. Control and frame delivery run on the main thread. */
internal class AnimatedStickerPlayback(
    private val file: File,
    private val sizePx: Int,
    private val onFrame: (Bitmap) -> Boolean,
) {
    // Keep each native player on one worker for its whole lifetime.
    private val worker = workers[nextWorker]

    @Volatile
    private var stopped = false

    // Worker thread only. Keep the current and previous bitmaps out of the decoder while
    // preparing the next frame, since the render thread may still be using them.
    private var sticker: RustMedia.AnimatedSticker? = null
    private val bitmaps = arrayOfNulls<Bitmap>(3)

    init {
        nextWorker = (nextWorker + 1) % workers.size
    }

    fun start() {
        worker.post {
            if (stopped) return@post
            val sticker = RustMedia.AnimatedSticker.open(file, sizePx, sizePx) ?: return@post
            this.sticker = sticker
            if (sticker.frameCount <= 0) {
                release()
                return@post
            }
            val frameMs = if (sticker.durationMs > 0) {
                (sticker.durationMs.toDouble() / sticker.frameCount).coerceAtLeast(1.0)
            } else {
                DEFAULT_FRAME_MS
            }
            renderFrame(0, 0, null, frameMs)
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(this)
        worker.post { release() }
    }

    private fun renderFrame(nextFrame: Long, slot: Int, startedAt: Long?, frameMs: Double) {
        if (stopped) return
        val sticker = sticker ?: return
        val now = SystemClock.uptimeMillis()
        val epoch = startedAt ?: now
        // Catch up if decode work waited in the queue. Each animation has at most one frame
        // pending so it cannot build up a backlog of frames that are already late.
        val frame = maxOf(nextFrame, ((now - epoch) / frameMs).toLong())
        val due = epoch + ceil(frame * frameMs).toLong()
        val bitmap = bitmaps[slot] ?: sticker.newBitmap().also { bitmaps[slot] = it }
        if (!sticker.frame((frame % sticker.frameCount).toInt(), bitmap)) {
            release()
            return
        }
        val loop = sticker.frameCount > 1
        if (!loop) release()

        main.postAtTime({
            if (!stopped) {
                val start = startedAt ?: SystemClock.uptimeMillis()
                if (!onFrame(bitmap)) {
                    stop()
                } else if (loop && !stopped) {
                    worker.post { renderFrame(frame + 1, (slot + 1) % bitmaps.size, start, frameMs) }
                }
            }
        }, this, due)
    }

    private fun release() {
        sticker?.close()
        sticker = null
        bitmaps.fill(null)
    }

    private companion object {
        const val DEFAULT_FRAME_MS = 33.0
        val main = Handler(Looper.getMainLooper())
        val workers by lazy {
            val count = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            Array(count) { index ->
                Handler(HandlerThread("StickerDecoder-$index").apply { start() }.looper)
            }
        }
        var nextWorker = 0
    }
}

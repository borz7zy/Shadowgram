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
import android.view.Choreographer
import com.github.borz7zy.rustcore.RustMedia
import java.io.File
import kotlin.math.ceil

/** One WebM or TGS animation. Control and frame delivery run on the main thread. */
internal class AnimatedStickerPlayback(
    private val file: File,
    private val sizePx: Int,
    private val timeline: Timeline? = null,
    private val firstFrameOnly: Boolean = false,
    private val onFrame: (Bitmap) -> Boolean,
) {
    /** The first presentation starts the clock. View recycling leaves it running. */
    class Timeline {
        @Volatile
        var startedAt: Long? = null
            private set

        // Called on main after the first frame is shown.
        fun start(now: Long): Long {
            val previous = startedAt
            if (previous != null) return previous
            startedAt = now
            return now
        }
    }

    // Keep each native player on one worker for its whole lifetime.
    private val worker = workers[nextWorker]
    private val frameQueue = if (timeline != null) giftFrames else null

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
        worker.postAtTime({
            if (stopped) return@postAtTime
            val sticker = RustMedia.AnimatedSticker.open(file, sizePx, sizePx) ?: return@postAtTime
            this.sticker = sticker
            if (sticker.frameCount <= 0) {
                release()
                return@postAtTime
            }
            val frameMs = if (sticker.durationMs > 0) {
                (sticker.durationMs.toDouble() / sticker.frameCount).coerceAtLeast(1.0)
            } else {
                DEFAULT_FRAME_MS
            }
            renderFrame(0, 0, timeline?.startedAt, frameMs)
        }, this, SystemClock.uptimeMillis())
    }

    fun stop() {
        if (stopped) return
        stopped = true
        if (frameQueue != null) frameQueue.remove(this) else main.removeCallbacksAndMessages(this)
        worker.removeCallbacksAndMessages(this)
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
        val loop = !firstFrameOnly && sticker.frameCount > 1
        if (!loop) release()

        if (stopped) return
        val present = Runnable {
            if (stopped) return@Runnable
            if (!onFrame(bitmap)) {
                stop()
            } else if (loop && !stopped) {
                val now = SystemClock.uptimeMillis()
                val start = startedAt ?: timeline?.start(now) ?: now
                worker.postAtTime({
                    renderFrame(frame + 1, (slot + 1) % bitmaps.size, start, frameMs)
                }, this, now)
            }
        }
        if (frameQueue != null) frameQueue.post(this, due, present) else main.postAtTime(present, this, due)
    }

    private fun release() {
        sticker?.close()
        sticker = null
        bitmaps.fill(null)
    }

    private companion object {
        const val DEFAULT_FRAME_MS = 33.0
        val main = Handler(Looper.getMainLooper())
        val giftFrames by lazy { StickerFrameQueue(Choreographer.getInstance()) }
        val workers by lazy {
            val count = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            Array(count) { index ->
                Handler(HandlerThread("StickerDecoder-$index").apply { start() }.looper)
            }
        }
        var nextWorker = 0
    }
}

/** Present ready gift frames together before the display traversal. */
private class StickerFrameQueue(private val choreographer: Choreographer) : Choreographer.FrameCallback {
    private class Frame(val due: Long, val present: Runnable)

    private val pending = HashMap<AnimatedStickerPlayback, Frame>()
    private val handler = Handler.createAsync(Looper.getMainLooper())
    private var wakeAt: Long? = null
    private var framePosted = false
    private val wake = Runnable {
        synchronized(pending) {
            wakeAt = null
            scheduleNext()
        }
    }

    fun post(playback: AnimatedStickerPlayback, due: Long, present: Runnable) {
        synchronized(pending) {
            pending[playback] = Frame(due, present)
            scheduleNext()
        }
    }

    fun remove(playback: AnimatedStickerPlayback) {
        synchronized(pending) {
            if (pending.remove(playback) != null) scheduleNext()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        val ready = synchronized(pending) {
            framePosted = false
            val cutoff = SystemClock.uptimeMillis() + FRAME_LEAD_MS
            val ready = ArrayList<Frame>()
            val frames = pending.values.iterator()
            while (frames.hasNext()) {
                val frame = frames.next()
                if (frame.due <= cutoff) {
                    ready.add(frame)
                    frames.remove()
                }
            }
            scheduleNext()
            ready
        }
        for (frame in ready) frame.present.run()
    }

    private fun scheduleNext() {
        if (framePosted) return
        val next = pending.values.minOfOrNull { it.due - FRAME_LEAD_MS }
        if (wakeAt == next) return
        handler.removeCallbacks(wake)
        if (next == null || next > SystemClock.uptimeMillis()) {
            wakeAt = next
            if (next != null) handler.postAtTime(wake, next)
            return
        }
        wakeAt = null
        framePosted = true
        choreographer.postFrameCallback(this)
    }

    private companion object {
        // Use the nearest display frame even when uptime rounding puts the due time just after vsync.
        const val FRAME_LEAD_MS = 4L
    }
}

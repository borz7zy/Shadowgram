/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.ImageView
import com.github.borz7zy.rustcore.RustMedia
import java.io.File

/**
 * Plays a WebM (VP9) or TGS (Lottie) sticker with RustCore, looping while attached.
 *
 * Frames are decoded on one thread shared by every sticker into two bitmaps taking turns: one is
 * on screen while the next is drawn. Decoded frames land in RustCore's LRU, so the same sticker
 * shown many times, or looping, is mostly copied rather than decoded.
 */
class StickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var file: File? = null
    private var sizePx = 0

    /** Bumped on every change, frames of an older generation are dropped. */
    @Volatile
    private var generation = 0

    // Only touched on the decoder thread
    private var sticker: RustMedia.AnimatedSticker? = null
    private var bitmaps = arrayOf<Bitmap?>(null, null)

    // Built on the decoder thread with the bitmaps, read on the main thread
    @Volatile
    private var drawables = listOf<BitmapDrawable>()

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    /**
     * Play [file] fitted into [sizePx], null to stop.
     */
    fun setSticker(file: File?, sizePx: Int) {
        if (file == this.file && sizePx == this.sizePx) {
            return
        }
        this.file = file
        this.sizePx = sizePx
        setImageDrawable(null)
        restart()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun stop() {
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        decoder.post { release() }
    }

    private fun restart() {
        stop()
        val file = file ?: return
        if (!isAttachedToWindow || sizePx <= 0) {
            return
        }

        val generation = generation
        val size = sizePx
        decoder.post {
            release()
            val sticker = RustMedia.AnimatedSticker.open(file, size, size) ?: return@post
            this.sticker = sticker
            bitmaps = arrayOf(sticker.newBitmap(), sticker.newBitmap())
            drawables = bitmaps.map { bitmap -> BitmapDrawable(resources, bitmap) }
            val frameMs = when (sticker.frameCount) {
                0 -> DEFAULT_FRAME_MS
                else -> (sticker.durationMs / sticker.frameCount).toLong().coerceAtLeast(1)
            }
            drawFrame(generation, 0, 0, frameMs)
        }
    }

    // Decoder thread
    private fun drawFrame(generation: Int, index: Int, slot: Int, frameMs: Long) {
        val sticker = sticker ?: return
        val bitmap = bitmaps[slot] ?: return
        val start = SystemClock.uptimeMillis()

        if (generation != this.generation || !sticker.frame(index, bitmap)) {
            return
        }

        mainHandler.post {
            if (generation != this.generation) {
                return@post
            }
            // The same two drawables take turns: no allocation per frame, and the bitmap's
            // new content is uploaded once it's drawn
            val drawable = drawables.getOrNull(slot) ?: return@post
            setImageDrawable(drawable)
            drawable.invalidateSelf()

            // The other bitmap is off screen now, draw the next frame into it in time
            val delay = (frameMs - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0)
            mainHandler.postDelayed({
                decoder.post {
                    drawFrame(generation, (index + 1) % sticker.frameCount, 1 - slot, frameMs)
                }
            }, delay)
        }
    }

    // Decoder thread
    private fun release() {
        sticker?.close()
        sticker = null
        bitmaps = arrayOf(null, null)
        drawables = listOf()
    }

    private companion object {
        const val DEFAULT_FRAME_MS = 33L

        // ponytail: one thread for every sticker on screen, a small pool if many play at once
        val decoder by lazy {
            Handler(HandlerThread("StickerDecoder").apply { start() }.looper)
        }
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import java.io.File

/** Plays a WebM (VP9) or TGS (Lottie) sticker while visible. */
class StickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    private var file: File? = null
    private var sizePx = 0
    private var playback: AnimatedStickerPlayback? = null
    private var timeline: AnimatedStickerPlayback.Timeline? = null
    private var renderSizePx = 0
    var onFirstFrame: (() -> Unit)? = null

    /** Keep the last frame. A newly visible cell still draws its first frame. */
    var isAnimationPaused = false
        set(value) {
            if (field == value) return
            field = value
            stop()
            startIfVisible()
        }

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    /**
     * Play [file] fitted into [sizePx], null to stop.
     */
    fun setSticker(file: File?, sizePx: Int) = setSticker(file, sizePx, null)

    internal fun setSticker(file: File?, sizePx: Int, timeline: AnimatedStickerPlayback.Timeline?) {
        if (file == this.file && sizePx == this.sizePx && timeline === this.timeline) {
            return
        }
        this.file = file
        this.sizePx = sizePx
        this.timeline = timeline
        stop()
        setImageDrawable(null)
        startIfVisible()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startIfVisible()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startIfVisible() else stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (timeline != null) {
            if (renderSizePx != minOf(sizePx, w, h)) stop()
            startIfVisible()
        }
    }

    private fun startIfVisible() {
        val file = file ?: return
        val targetSize = if (timeline != null) minOf(sizePx, width, height) else sizePx
        if (playback != null || targetSize <= 0 || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE) {
            return
        }
        if (isAnimationPaused && drawable != null) return
        renderSizePx = targetSize
        playback = AnimatedStickerPlayback(file, targetSize, timeline, firstFrameOnly = isAnimationPaused) { bitmap ->
            val isFirstFrame = drawable == null
            if (timeline != null) showGiftFrame(bitmap) else setImageBitmap(bitmap)
            if (isFirstFrame) onFirstFrame?.invoke()
            true
        }.also { it.start() }
    }

    private fun showGiftFrame(bitmap: Bitmap) {
        val current = drawable as? GiftFrameDrawable
        if (current != null && current.bitmap.width == bitmap.width && current.bitmap.height == bitmap.height) {
            current.bitmap = bitmap
        } else {
            setImageDrawable(GiftFrameDrawable(bitmap, resources.displayMetrics.densityDpi))
        }
    }

    private fun stop() {
        playback?.stop()
        playback = null
    }
}

/** Updating the frame invalidates drawing without assigning a new image to the cell. */
private class GiftFrameDrawable(firstFrame: Bitmap, densityDpi: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val width = firstFrame.getScaledWidth(densityDpi)
    private val height = firstFrame.getScaledHeight(densityDpi)

    var bitmap: Bitmap = firstFrame
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) = canvas.drawBitmap(bitmap, null, bounds, paint)
    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height
    override fun getAlpha() = paint.alpha

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

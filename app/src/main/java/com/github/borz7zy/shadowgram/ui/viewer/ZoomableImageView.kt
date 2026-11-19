/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.util.AttributeSet
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.fromFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/** Glimpse's zoom and pan, with the viewer's vertical swipe back to the source message. */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ZoomImageView(context, attrs) {
    interface Listener {
        fun onTap(image: ZoomableImageView, x: Float)

        /** Pulled away by [fraction] of the screen height, 0 when back. */
        fun onDismissProgress(fraction: Float)

        fun onDismiss()
    }

    var listener: Listener? = null

    /** The video texture follows the position and zoom of its still. */
    var onDisplayRectChanged: (() -> Unit)? = null

    val isZoomed get() = zoomable.userTransformState.value.scaleX > 1.01f

    private var videoSize: Size? = null
    private var dismissOffset = 0f
    private var isDismissing = false
    private var canDismiss = false
    private var isFastDismiss = false
    private var dismissAnimator: ValueAnimator? = null
    private val dismissVelocity = 1_000f * resources.displayMetrics.density

    private val dismissDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // ZoomImage also supports holding the second tap and sliding to zoom.
            canDismiss = false
            return true
        }

        override fun onScroll(start: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (start == null || !canDismiss) return false
            val offset = current.y - start.y
            if (!isDismissing && abs(current.x - start.x) >= abs(offset)) {
                canDismiss = false
                return false
            }
            isDismissing = true
            updateDismissOffset(offset)
            return true
        }

        override fun onFling(start: MotionEvent?, end: MotionEvent, vx: Float, vy: Float): Boolean {
            isFastDismiss = isDismissing && abs(vy) > dismissVelocity
            return isFastDismiss
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        scaleType = ImageView.ScaleType.FIT_CENTER
        scrollBar = null
        zoomable.setKeepTransformWhenSameAspectRatioContentSizeChanged(true)
        onViewTapListener = { _, point -> listener?.onTap(this, point.x) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        coroutineScope?.launch(Dispatchers.Main.immediate) {
            zoomable.contentDisplayRectFState.collect { onDisplayRectChanged?.invoke() }
        }
    }

    override fun onDetachedFromWindow() {
        dismissAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    fun setVideoSize(width: Int, height: Int) {
        val size = if (width > 0 && height > 0) Size(width, height) else null
        if (videoSize == size) return
        videoSize = size
        setImageDrawable(drawable)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        val preview = (drawable as? VideoPreview)?.drawable ?: drawable
        val sizedPreview = videoSize?.let { VideoPreview(preview ?: ColorDrawable(Color.BLACK), it) }
        super.setImageDrawable(sizedPreview ?: preview)
    }

    @SuppressLint("ClickableViewAccessibility") // The zoom engine reports confirmed taps.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            dismissAnimator?.cancel()
            if (dismissOffset != 0f) updateDismissOffset(0f)
            isDismissing = false
            canDismiss = !isZoomed
            isFastDismiss = false
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            canDismiss = false
        }

        val wasDismissing = isDismissing
        dismissDetector.onTouchEvent(event)
        if (!isDismissing) return super.onTouchEvent(event)
        if (!wasDismissing) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
            cancel.recycle()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isDismissing = false
            if (action == MotionEvent.ACTION_UP && canDismiss &&
                (isFastDismiss || abs(dismissOffset) > height * DISMISS_FRACTION)
            ) {
                listener?.onDismiss()
            } else {
                dismissAnimator = ValueAnimator.ofFloat(dismissOffset, 0f).apply {
                    duration = 250
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { updateDismissOffset(it.animatedValue as Float) }
                    start()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.translate(0f, dismissOffset)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    /** The same geometry is used to place the video and to animate back to the thumbnail. */
    fun displayRect(): RectF {
        val rect = zoomable.contentDisplayRectFState.value
        return RectF(rect.left, rect.top + dismissOffset, rect.right, rect.bottom + dismissOffset)
    }

    fun setFullSizePhoto(file: File) {
        setSubsamplingImage(ImageSource.fromFile(file))
    }

    fun resetMedia() {
        dismissAnimator?.cancel()
        dismissOffset = 0f
        setSubsamplingImage(subsamplingImage = null)
        zoomable.reset()
    }

    private fun updateDismissOffset(offset: Float) {
        dismissOffset = offset
        invalidate()
        onDisplayRectChanged?.invoke()
        listener?.onDismissProgress(abs(offset) / height.coerceAtLeast(1))
    }

    /** Keep the video geometry when a thumbnail arrives with different dimensions. */
    private class VideoPreview(drawable: Drawable, private val size: Size) : DrawableWrapper(drawable) {
        override fun getIntrinsicWidth() = size.width
        override fun getIntrinsicHeight() = size.height
    }

    private companion object {
        const val DISMISS_FRACTION = 0.25f
    }
}

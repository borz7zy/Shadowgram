/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A photo of the viewer: fitted to the screen, pinched or double tapped to zoom, dragged within
 * its edges while zoomed. At its fitted size a vertical drag pulls it away to close the viewer,
 * a horizontal one goes to the neighbouring photo.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    interface Listener {
        fun onTap()

        /** Pulled away by [fraction] of the screen height, 0 when back. */
        fun onDismissProgress(fraction: Float)

        /** Pulled far or fast enough: close. */
        fun onDismiss()
    }

    var listener: Listener? = null

    /** The picture moved or was resized: what is drawn over it follows, see [displayRect]. */
    var onDisplayRectChanged: (() -> Unit)? = null

    private val drawMatrix = Matrix()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dismissVelocity = DISMISS_VELOCITY_DP * resources.displayMetrics.density

    /** Fits the picture in the view. */
    private var baseScale = 1f

    /** On top of [baseScale], 1 when fitted. */
    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var isDismissing = false
    private var isDecided = false
    private var animator: ValueAnimator? = null

    val isZoomed get() = scale > 1.01f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (isDismissing) {
                return false
            }
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            when (isZoomed) {
                true -> animateTo(1f, 0f, 0f)
                false -> {
                    // Zoom in on the tapped point
                    val px = e.x - width / 2f
                    val py = e.y - height / 2f
                    val target = DOUBLE_TAP_SCALE
                    val tx = px - (px - translateX) * target / scale
                    val ty = py - (py - translateY) * target / scale
                    animateTo(target, clampX(tx, target), clampY(ty, target))
                }
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) {
                return true
            }
            if (isZoomed) {
                val newX = translateX - dx
                // At an edge: let the pager have the drag
                if (clampX(newX, scale) != newX && abs(dx) > abs(dy)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                translateX = clampX(newX, scale)
                translateY = clampY(translateY - dy, scale)
                apply()
                return true
            }

            if (!isDecided && e1 != null) {
                val totalX = e2.x - e1.x
                val totalY = e2.y - e1.y
                if (abs(totalY) > touchSlop && abs(totalY) > abs(totalX)) {
                    isDecided = true
                    isDismissing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (abs(totalX) > touchSlop) {
                    isDecided = true
                }
            }
            if (isDismissing) {
                translateY -= dy
                apply()
                listener?.onDismissProgress(abs(translateY) / height)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (isDismissing && abs(vy) > dismissVelocity) {
                isDismissing = false
                listener?.onDismiss()
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        val old = this.drawable
        super.setImageDrawable(drawable)
        // A sharper copy of the same picture keeps the zoom
        val isSamePicture = old != null && drawable != null && old.intrinsicWidth > 0
                && abs(old.intrinsicWidth.toFloat() / old.intrinsicHeight - drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight) < 0.01f
        if (!isSamePicture) {
            scale = 1f
            translateX = 0f
            translateY = 0f
        }
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    @SuppressLint("ClickableViewAccessibility") // Taps go to the listener
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            isDecided = false
            // Zoomed in, drags pan rather than page
            parent?.requestDisallowInterceptTouchEvent(isZoomed)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            when {
                isDismissing && abs(translateY) > height * DISMISS_FRACTION -> {
                    isDismissing = false
                    listener?.onDismiss()
                }

                isDismissing -> {
                    isDismissing = false
                    animateTo(1f, 0f, 0f, isPulledBack = true)
                }

                // Pinched below fitted, or past an edge: settle
                scale < 1f -> animateTo(1f, 0f, 0f)
                else -> animateTo(min(scale, MAX_SCALE), clampX(translateX, scale), clampY(translateY, scale))
            }
        }
        return true
    }

    /** Where the picture is drawn now, in this view. */
    fun displayRect(): RectF {
        val d = drawable ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val w = d.intrinsicWidth * baseScale * scale
        val h = d.intrinsicHeight * baseScale * scale
        val left = (width - w) / 2 + translateX
        val top = (height - h) / 2 + translateY
        return RectF(left, top, left + w, top + h)
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val newScale = (scale * factor).coerceIn(MIN_PINCH_SCALE, MAX_SCALE * 1.5f)
        val f = newScale / scale
        val px = focusX - width / 2f
        val py = focusY - height / 2f
        translateX = px - (px - translateX) * f
        translateY = py - (py - translateY) * f
        scale = newScale
        apply()
    }

    private fun clampX(x: Float, scale: Float): Float {
        val d = drawable ?: return 0f
        val extra = (d.intrinsicWidth * baseScale * scale - width) / 2
        return if (extra > 0) x.coerceIn(-extra, extra) else 0f
    }

    private fun clampY(y: Float, scale: Float): Float {
        val d = drawable ?: return 0f
        val extra = (d.intrinsicHeight * baseScale * scale - height) / 2
        return if (extra > 0) y.coerceIn(-extra, extra) else 0f
    }

    /**
     * @param isPulledBack Coming back from a drag that didn't close: the background follows
     */
    private fun animateTo(targetScale: Float, targetX: Float, targetY: Float, isPulledBack: Boolean = false) {
        animator?.cancel()
        val fromScale = scale
        val fromX = translateX
        val fromY = translateY
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                scale = fromScale + (targetScale - fromScale) * t
                translateX = fromX + (targetX - fromX) * t
                translateY = fromY + (targetY - fromY) * t
                apply()
                if (isPulledBack) {
                    listener?.onDismissProgress(abs(translateY) / max(height, 1))
                }
            }
            start()
        }
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
            return
        }
        baseScale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        apply()
    }

    private fun apply() {
        val d = drawable ?: return
        val s = baseScale * scale
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate(
            (width - d.intrinsicWidth * s) / 2 + translateX,
            (height - d.intrinsicHeight * s) / 2 + translateY,
        )
        imageMatrix = drawMatrix
        onDisplayRectChanged?.invoke()
    }

    private companion object {
        const val DOUBLE_TAP_SCALE = 2.5f
        const val MAX_SCALE = 4f
        const val MIN_PINCH_SCALE = 0.7f
        const val ANIMATION_MS = 250L

        /** Like VK: a quarter of the screen, or a fast fling. */
        const val DISMISS_FRACTION = 0.25f
        const val DISMISS_VELOCITY_DP = 1000f
    }
}

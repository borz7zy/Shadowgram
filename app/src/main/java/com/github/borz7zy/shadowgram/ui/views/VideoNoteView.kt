/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.setPlaying
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.motion.MotionUtils
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A video message in its bubble. Tap to play or pause; while it plays it grows to the width of
 * the chat, a wavy ring around it shows where playback is and drags to seek. Holding the right
 * half plays at 2x, holding the left half pauses, both until released.
 *
 * The video goes to this view's surface while it's on screen, the floating circle takes over
 * once it scrolls away.
 */
class VideoNoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), MessagePlayer.Listener {
    private val stillImageView: ShapeableImageView
    private val videoTextureView: TextureView
    private val ringProgressIndicator: CircularProgressIndicator
    private val stateImageView: ImageView
    private val speedTextView: TextView

    private val smallSize = resources.getDimensionPixelSize(R.dimen.video_note_size)
    private val ringSpace = resources.getDimensionPixelSize(R.dimen.video_note_ring_space)
    private val waveAmplitude = resources.getDimensionPixelSize(R.dimen.video_note_wave_amplitude)
    private val seekBand = resources.getDimensionPixelSize(R.dimen.video_note_seek_band)

    private var track: MessagePlayer.Track? = null
    private var isCurrent = false
    private var isPlaying = false

    /** 0 small, 1 as wide as the chat. */
    private var expansion = 0f
    private var expansionAnimator: ValueAnimator? = null

    private var surface: Surface? = null
    private var isSurfaceOffered = false

    // Touches
    private var isSeeking = false
    private var holdSide: Boolean? = null // true for right
    private var isHolding = false
    private val startHold = Runnable {
        isHolding = true
        parent?.requestDisallowInterceptTouchEvent(true)
        when (holdSide) {
            true -> MessagePlayer.holdFast(true)
            else -> MessagePlayer.holdPause(true)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isCurrent || !isAttachedToWindow) {
                return
            }
            updateProgress()
            postOnAnimation(this)
        }
    }

    init {
        inflate(context, R.layout.video_note_view, this)
        stillImageView = findViewById(R.id.stillImageView)
        videoTextureView = findViewById(R.id.videoTextureView)
        ringProgressIndicator = findViewById(R.id.ringProgressIndicator)
        stateImageView = findViewById(R.id.stateImageView)
        speedTextView = findViewById(R.id.speedTextView)

        videoTextureView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setOval(0, 0, view.width, view.height)
        }
        videoTextureView.clipToOutline = true

        videoTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                updateSurface()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                withdrawSurface()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (isSurfaceOffered) {
                    videoTextureView.alpha = 1f
                }
            }
        }
    }

    /**
     * Show [track], its still picture from [stillPath] or [minithumbnail] meanwhile.
     */
    fun bind(track: MessagePlayer.Track, stillPath: String?, minithumbnail: ByteArray?) {
        val isSame = this.track?.isOf(track.chatId, track.messageId) == true
        this.track = track
        if (!isSame) {
            when (val still = stillPath?.let(::File) ?: minithumbnail) {
                null -> {
                    stillImageView.dispose()
                    stillImageView.setImageDrawable(null)
                }

                else -> stillImageView.load(still)
            }
        }
        onStateChanged(MessagePlayer.state, animate = isSame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Back on screen: start over, the progress ticks again if it's current
        isCurrent = false
        MessagePlayer.addListener(this)
    }

    override fun onDetachedFromWindow() {
        MessagePlayer.removeListener(this)
        expansionAnimator?.cancel()
        withdrawSurface()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateSurface()
    }

    override fun onStateChanged(state: MessagePlayer.State) = onStateChanged(state, true)

    private fun onStateChanged(state: MessagePlayer.State, animate: Boolean) {
        val track = track ?: return
        val wasCurrent = isCurrent
        isCurrent = state.track?.isOf(track.chatId, track.messageId) == true
        isPlaying = isCurrent && state.isPlaying

        ringProgressIndicator.isVisible = isCurrent
        // The wave settles while paused
        ringProgressIndicator.waveAmplitude = if (isPlaying) waveAmplitude else 0
        stateImageView.isVisible = !isPlaying
        stateImageView.setPlaying(isPlaying)
        speedTextView.isVisible = isCurrent && state.isFast

        expand(if (isCurrent) 1f else 0f, animate)
        updateSurface()

        if (isCurrent && !wasCurrent) {
            updateProgress()
            postOnAnimation(tick)
        }
    }

    private fun updateProgress() {
        val duration = MessagePlayer.duration
        if (duration > 0 && !isSeeking) {
            ringProgressIndicator.setProgressCompat(
                (MessagePlayer.position * 1000 / duration).toInt(), false
            )
        }
    }

    private fun expand(target: Float, animate: Boolean) {
        if (expansionAnimator == null && expansion == target) {
            return
        }
        expansionAnimator?.cancel()
        if (!animate || !isLaidOut) {
            expansionAnimator = null
            expansion = target
            requestLayout()
            return
        }
        expansionAnimator = ValueAnimator.ofFloat(expansion, target).apply {
            duration = MotionUtils.resolveThemeDuration(
                context, com.google.android.material.R.attr.motionDurationMedium4, 400
            ).toLong()
            interpolator = MotionUtils.resolveThemeInterpolator(
                context,
                com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
                android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f),
            )
            addUpdateListener {
                expansion = it.animatedValue as Float
                requestLayout()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As wide as the bubble may be, the chat minus its paddings
        val available = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> smallSize
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }.coerceAtLeast(smallSize)
        val size = (smallSize + (available - smallSize) * expansion).toInt()
        val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ringProgressIndicator.indicatorSize = minOf(w, h)
    }

    /** Offer the surface while this is the current video and on screen, take it back else. */
    private fun updateSurface() {
        val surface = surface
        val shouldOffer = isCurrent && track?.isVideo == true && surface != null
                && isAttachedToWindow && windowVisibility == VISIBLE
        when {
            shouldOffer && !isSurfaceOffered -> {
                isSurfaceOffered = true
                MessagePlayer.setSurface(this, surface!!, MessagePlayer.PRIORITY_INLINE)
            }

            !shouldOffer -> withdrawSurface()
        }
    }

    private fun withdrawSurface() {
        if (isSurfaceOffered) {
            isSurfaceOffered = false
            MessagePlayer.clearSurface(this)
        }
        videoTextureView.alpha = 0f
    }

    @SuppressLint("ClickableViewAccessibility") // performClick on taps
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val track = track ?: return false
        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = hypot(event.x - cx, event.y - cy)
                isSeeking = isCurrent && distance > width / 2f - ringSpace - seekBand
                if (isSeeking) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    seek(event, cx, cy)
                } else if (isCurrent) {
                    holdSide = event.x > cx
                    postDelayed(startHold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> if (isSeeking) {
                seek(event, cx, cy)
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(startHold)
                when {
                    isSeeking -> {
                        seek(event, cx, cy)
                        isSeeking = false
                    }

                    isHolding -> releaseHold()
                    else -> {
                        performClick()
                        MessagePlayer.play(track)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startHold)
                isSeeking = false
                if (isHolding) {
                    releaseHold()
                }
            }
        }
        return true
    }

    override fun performClick() = super.performClick()

    private fun releaseHold() {
        isHolding = false
        MessagePlayer.holdFast(false)
        MessagePlayer.holdPause(false)
    }

    /** Clockwise from the top, like the ring fills. */
    private fun seek(event: MotionEvent, cx: Float, cy: Float) {
        var angle = atan2(event.x - cx, cy - event.y)
        if (angle < 0) {
            angle += (2 * PI).toFloat()
        }
        val fraction = (angle / (2 * PI)).toFloat()
        ringProgressIndicator.setProgressCompat((fraction * 1000).toInt(), false)
        MessagePlayer.seekTo(fraction)
    }
}

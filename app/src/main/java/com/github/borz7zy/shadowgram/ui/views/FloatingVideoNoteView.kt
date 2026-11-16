/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.SurfaceTexture
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.setPlaying
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.google.android.material.motion.MotionUtils
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlin.math.hypot

/**
 * The video message playing while its bubble isn't on screen, in a corner of every activity
 * (see [MessagePlayer.init]). Tap to pause or play.
 */
class FloatingVideoNoteView(context: Context) : FrameLayout(context), MessagePlayer.Listener {
    private val videoTextureView: TextureView
    private val stateImageView: ImageView
    private val ringProgressIndicator: CircularProgressIndicator

    private var surface: Surface? = null
    private var isSurfaceOffered = false
    private var isShown = false

    // Dragging
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var systemInsets = androidx.core.graphics.Insets.NONE
    private var downX = 0f
    private var downY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var isDragging = false
    private val waveAmplitude = toPx(2)

    private val tick = object : Runnable {
        override fun run() {
            if (!isShown || !isAttachedToWindow) {
                return
            }
            val duration = MessagePlayer.duration
            if (duration > 0) {
                ringProgressIndicator.setProgressCompat(
                    (MessagePlayer.position * 1000 / duration).toInt(), false
                )
            }
            postOnAnimation(this)
        }
    }

    init {
        val size = resources.getDimensionPixelSize(R.dimen.floating_video_note_size)
        layoutParams = LayoutParams(size, size, Gravity.BOTTOM or Gravity.END)
        inflate(context, R.layout.floating_video_note, this)
        videoTextureView = findViewById(R.id.videoTextureView)
        stateImageView = findViewById(R.id.stateImageView)
        ringProgressIndicator = findViewById(R.id.ringProgressIndicator)
        // Round video
        findViewById<android.view.View>(R.id.circleFrameLayout).clipToOutline = true
        // The circle's shadow goes past these bounds
        clipChildren = false
        isVisible = false

        // Tapped: pause or play, dragged: moved, see onTouchEvent
        setOnClickListener { MessagePlayer.toggle() }
        contentDescription = context.getString(R.string.playback_video_message)

        // Insets: above the navigation bar and what's usually at the bottom (the composer, the
        // navigation bar view)
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updateLayoutParams<LayoutParams> {
                marginEnd = insets.right + toPx(MARGIN_DP)
                bottomMargin = insets.bottom + toPx(BOTTOM_MARGIN_DP)
            }
            systemInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            windowInsets
        }

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

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MessagePlayer.addListener(this)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Where it was left, in this activity too, kept on screen
        if (!isDragging) {
            translationX = clampX(savedTranslationX)
            translationY = clampY(savedTranslationY)
        }
    }

    private fun clampX(x: Float): Float {
        val parent = parent as? android.view.View ?: return x
        val margin = toPx(MARGIN_DP)
        return x.coerceIn(
            (systemInsets.left + margin - left).toFloat(),
            (parent.width - systemInsets.right - margin - right).toFloat().coerceAtLeast(0f),
        )
    }

    private fun clampY(y: Float): Float {
        val parent = parent as? android.view.View ?: return y
        val margin = toPx(MARGIN_DP)
        return y.coerceIn(
            (systemInsets.top + margin - top).toFloat(),
            (parent.height - systemInsets.bottom - margin - bottom).toFloat().coerceAtLeast(0f),
        )
    }

    @SuppressLint("ClickableViewAccessibility") // performClick on taps
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startTranslationX = translationX
                startTranslationY = translationY
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!isDragging && hypot(dx, dy) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isDragging) {
                    translationX = clampX(startTranslationX + dx)
                    translationY = clampY(startTranslationY + dy)
                }
            }

            MotionEvent.ACTION_UP -> when (isDragging) {
                true -> settle()
                false -> performClick()
            }

            MotionEvent.ACTION_CANCEL -> if (isDragging) {
                settle()
            }
        }
        return true
    }

    /** Snap to the nearest side, like the official apps, and remember the place. */
    private fun settle() {
        isDragging = false
        val parent = parent as? android.view.View ?: return
        val center = left + translationX + width / 2f
        val x = clampX(if (center < parent.width / 2f) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY)
        savedTranslationX = x
        savedTranslationY = translationY
        animate().translationX(x)
            .setDuration(
                MotionUtils.resolveThemeDuration(
                    context, com.google.android.material.R.attr.motionDurationMedium2, 300
                ).toLong()
            )
            .setInterpolator(
                MotionUtils.resolveThemeInterpolator(
                    context,
                    com.google.android.material.R.attr.motionEasingEmphasizedDecelerateInterpolator,
                    android.view.animation.DecelerateInterpolator(),
                )
            )
            .start()
    }

    override fun onDetachedFromWindow() {
        MessagePlayer.removeListener(this)
        withdrawSurface()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateSurface()
    }

    /** Shown a moment later: a bubble that just started playing takes the video first. */
    private val applyShown = Runnable {
        val state = MessagePlayer.state
        val shouldShow = state.track?.isVideo == true && !state.hasInlineSurface
        if (shouldShow != isShown) {
            isShown = shouldShow
            show(shouldShow)
            if (shouldShow) {
                postOnAnimation(tick)
            }
            updateSurface()
        }
    }

    override fun onStateChanged(state: MessagePlayer.State) {
        val shouldShow = state.track?.isVideo == true && !state.hasInlineSurface
        removeCallbacks(applyShown)
        when (shouldShow) {
            true -> if (!isShown) postDelayed(applyShown, SHOW_DELAY_MS)
            false -> applyShown.run()
        }
        stateImageView.isVisible = !state.isPlaying
        stateImageView.setPlaying(state.isPlaying)
        ringProgressIndicator.waveAmplitude = if (state.isPlaying) waveAmplitude else 0
        updateSurface()
    }

    private fun show(isShown: Boolean) {
        val duration = MotionUtils.resolveThemeDuration(
            context, com.google.android.material.R.attr.motionDurationMedium2, 300
        ).toLong()
        animate().cancel()
        if (isShown) {
            isVisible = true
            scaleX = 0.5f
            scaleY = 0.5f
            alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(duration).start()
        } else {
            animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(duration)
                .withEndAction { isVisible = false }
                .start()
        }
    }

    private fun updateSurface() {
        val surface = surface
        val shouldOffer = isShown && surface != null && isAttachedToWindow
                && windowVisibility == VISIBLE
        when {
            shouldOffer && !isSurfaceOffered -> {
                isSurfaceOffered = true
                MessagePlayer.setSurface(this, surface!!, MessagePlayer.PRIORITY_FLOATING)
            }

            !shouldOffer -> withdrawSurface()
        }
    }

    private fun withdrawSurface() {
        if (isSurfaceOffered) {
            isSurfaceOffered = false
            MessagePlayer.clearSurface(this)
        }
    }

    private companion object {
        /** Where it was dragged to, shared by the circles of all activities. */
        var savedTranslationX = 0f
        var savedTranslationY = 0f

        const val MARGIN_DP = 16
        const val BOTTOM_MARGIN_DP = 96
        const val SHOW_DELAY_MS = 150L
    }
}

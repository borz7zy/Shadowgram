/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.github.borz7zy.shadowgram.models.ChatHeader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The background of a profile's header chosen in Telegram: its colors spread out from the avatar,
 * its emoji patterned around it in rings that fade towards the edges. Draws nothing without colors.
 */
class ProfileCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gradientPaint = Paint()
    private val emojiPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }
    private val rect = RectF()

    /** Fades the bottom out into whatever is behind. */
    private val fadePaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    /** Narrows towards the bottom on both sides, as if it flowed down. */
    private val shape = android.graphics.Path()

    private var colors: List<Int>? = null
    private var emoji: Bitmap? = null

    /** The avatar's center and radius, in this view. */
    private var centerX = 0f
    private var centerY = 0f
    private var avatarRadius = 0f

    /** The pattern stays above this, clear of the name. */
    private var patternBottom = Float.MAX_VALUE

    val hasColors get() = colors != null

    fun setColors(colors: ChatHeader.ProfileColors?) {
        val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val shown = colors?.let { if (isDark) it.dark else it.light }?.takeIf { it.isNotEmpty() }
        if (this.colors != shown) {
            this.colors = shown
            updateShader()
            invalidate()
        }
    }

    fun setEmoji(emoji: Bitmap?) {
        this.emoji = emoji
        invalidate()
    }

    /** Where the avatar is, the pattern goes around it and above [patternBottom]. */
    fun setAvatar(centerX: Float, centerY: Float, radius: Float, patternBottom: Float) {
        if (this.centerX != centerX || this.centerY != centerY || avatarRadius != radius
            || this.patternBottom != patternBottom
        ) {
            this.centerX = centerX
            this.centerY = centerY
            avatarRadius = radius
            this.patternBottom = patternBottom
            updateShader()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()

        val density = resources.displayMetrics.density
        val fade = (FADE_DP * density).coerceAtMost(h.toFloat())
        val inset = SIDE_INSET_DP * density
        val top = h - fade
        fadePaint.shader = android.graphics.LinearGradient(
            0f, top, 0f, h.toFloat(), Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        shape.reset()
        shape.moveTo(0f, 0f)
        shape.lineTo(w.toFloat(), 0f)
        shape.lineTo(w.toFloat(), top)
        shape.cubicTo(w.toFloat(), top + fade * 0.6f, w - inset, h.toFloat(), w - inset * 2, h.toFloat())
        shape.lineTo(inset * 2, h.toFloat())
        shape.cubicTo(inset, h.toFloat(), 0f, top + fade * 0.6f, 0f, top)
        shape.close()
    }

    private fun updateShader() {
        val colors = colors ?: return
        val radius = max(width, height).toFloat().coerceAtLeast(1f)
        // The second color, the lighter one, glows around the avatar
        gradientPaint.shader = RadialGradient(
            centerX, centerY, radius,
            colors.getOrElse(1) { colors[0] }, colors[0],
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        colors ?: return
        val layer = canvas.saveLayer(null, null)
        canvas.clipPath(shape)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
        drawPattern(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
        canvas.restoreToCount(layer)
    }

    private fun drawPattern(canvas: Canvas) {
        val emoji = emoji ?: return
        val density = resources.displayMetrics.density
        RINGS.forEach { ring ->
            val distance = avatarRadius * ring.distance
            val half = ring.sizeDp * density / 2
            emojiPaint.alpha = (ring.alpha * 255).toInt()
            repeat(ring.count) { i ->
                val angle = Math.toRadians(ring.offsetDegrees + 360.0 * i / ring.count)
                val x = centerX + (cos(angle) * distance).toFloat()
                val y = centerY + (sin(angle) * distance).toFloat()
                if (y + half > patternBottom) {
                    return@repeat
                }
                rect.set(x - half, y - half, x + half, y + half)
                canvas.drawBitmap(emoji, null, rect, emojiPaint)
            }
        }
    }

    /**
     * @param distance From the avatar's center, in avatar radii
     */
    private class Ring(
        val count: Int,
        val distance: Float,
        val sizeDp: Int,
        val alpha: Float,
        val offsetDegrees: Double,
    )

    private companion object {
        // Like Telegram's: bigger and clearer close to the avatar, smaller and fainter further out
        val RINGS = listOf(
            Ring(6, 1.35f, 22, 0.4f, 0.0),
            Ring(8, 1.9f, 18, 0.26f, 22.5),
            Ring(12, 2.5f, 14, 0.14f, 7.5),
        )

        /** How far up from the bottom it fades out, behind the buttons under the name. */
        const val FADE_DP = 120

        /** How far in each side has flowed at the very bottom. */
        const val SIDE_INSET_DP = 20
    }
}

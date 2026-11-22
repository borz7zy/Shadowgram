/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
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

    private fun startIfVisible() {
        val file = file ?: return
        if (playback != null || sizePx <= 0 || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE) {
            return
        }
        playback = AnimatedStickerPlayback(file, sizePx) { bitmap ->
            setImageBitmap(bitmap)
            true
        }.also { it.start() }
    }

    private fun stop() {
        playback?.stop()
        playback = null
    }
}

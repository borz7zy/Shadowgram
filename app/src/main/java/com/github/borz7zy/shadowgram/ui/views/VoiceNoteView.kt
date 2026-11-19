/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.setPlaying
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

/**
 * A voice message or music in its bubble: play/pause, the song and its performer, a seek bar
 * while it's the one playing, and the time. Plays through [MessagePlayer], so the bar in the top bars shows it too.
 */
class VoiceNoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), MessagePlayer.Listener {
    private val playPauseMaterialButton: MaterialButton
    private val seekSlider: Slider
    private val timeTextView: TextView
    private val titleTextView: TextView

    private var track: MessagePlayer.Track? = null
    private var isCurrent = false
    private var isTracking = false

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
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        inflate(context, R.layout.voice_note_view, this)
        playPauseMaterialButton = findViewById(R.id.playPauseMaterialButton)
        seekSlider = findViewById(R.id.seekSlider)
        timeTextView = findViewById(R.id.timeTextView)
        titleTextView = findViewById(R.id.titleTextView)

        playPauseMaterialButton.setOnClickListener { track?.let(MessagePlayer::play) }
        seekSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isTracking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isTracking = false
                MessagePlayer.seekTo(slider.value)
            }
        })
    }

    fun bind(track: MessagePlayer.Track, @ColorInt background: Int, @ColorInt foreground: Int) {
        val contentTint = ColorStateList.valueOf(foreground)
        titleTextView.setTextColor(foreground)
        timeTextView.setTextColor(foreground)
        seekSlider.thumbTintList = contentTint
        seekSlider.trackActiveTintList = contentTint
        seekSlider.trackInactiveTintList = ColorStateList.valueOf(
            ColorUtils.blendARGB(background, foreground, 0.55f)
        )
        seekSlider.tickInactiveTintList = ColorStateList.valueOf(background)
        seekSlider.haloTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground, 0x33))
        // Material updates the stop indicator from the tick tint on a state refresh.
        seekSlider.refreshDrawableState()

        this.track = track
        isCurrent = false
        titleTextView.isVisible = track.isMusic
        titleTextView.text = when {
            track.subtitle.isBlank() -> track.title
            else -> context.getString(R.string.playback_music_title, track.title, track.subtitle)
        }
        onStateChanged(MessagePlayer.state)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isCurrent = false
        MessagePlayer.addListener(this)
    }

    override fun onDetachedFromWindow() {
        MessagePlayer.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onStateChanged(state: MessagePlayer.State) {
        val track = track ?: return
        val wasCurrent = isCurrent
        isCurrent = state.track?.isOf(track.chatId, track.messageId) == true

        playPauseMaterialButton.setPlaying(isCurrent && state.isPlaying)
        seekSlider.isEnabled = isCurrent

        when {
            isCurrent && !wasCurrent -> {
                updateProgress()
                postOnAnimation(tick)
            }

            !isCurrent -> {
                seekSlider.value = 0f
                timeTextView.text = DateUtils.formatElapsedTime(track.duration.toLong())
            }
        }
    }

    private fun updateProgress() {
        val duration = MessagePlayer.duration
        val position = MessagePlayer.position
        if (!isTracking && duration > 0) {
            seekSlider.value = (position.toFloat() / duration).coerceIn(0f, 1f)
        }
        timeTextView.text = context.getString(
            R.string.playback_time,
            DateUtils.formatElapsedTime(position / 1000),
            DateUtils.formatElapsedTime(duration / 1000),
        )
    }
}

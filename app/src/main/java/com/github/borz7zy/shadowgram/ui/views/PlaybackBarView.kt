/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.setPlaying
import com.github.borz7zy.shadowgram.fragments.NowPlayingDialogFragment
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.google.android.material.button.MaterialButton
import java.text.DecimalFormat

/**
 * What [MessagePlayer] plays, in a top bar: who sent it and in which chat or the song and its
 * performer, the speed, play/pause and stop; the progress is on the now playing screen. Hidden
 * while nothing plays. A tap opens the now playing screen, with the chat's playlist.
 */
class PlaybackBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), MessagePlayer.Listener {
    private val playPauseMaterialButton: MaterialButton
    private val titleTextView: TextView
    private val subtitleTextView: TextView
    private val speedMaterialButton: MaterialButton

    private var track: MessagePlayer.Track? = null
    private val speedFormat = DecimalFormat("0.#")

    init {
        orientation = VERTICAL
        inflate(context, R.layout.playback_bar, this)
        playPauseMaterialButton = findViewById(R.id.playPauseMaterialButton)
        titleTextView = findViewById(R.id.titleTextView)
        subtitleTextView = findViewById(R.id.subtitleTextView)
        speedMaterialButton = findViewById(R.id.speedMaterialButton)

        setOnClickListener {
            NowPlayingDialogFragment.open(findFragment<Fragment>().requireActivity())
        }
        playPauseMaterialButton.setOnClickListener { MessagePlayer.toggle() }
        speedMaterialButton.setOnClickListener { MessagePlayer.nextSpeed() }
        findViewById<MaterialButton>(R.id.closeMaterialButton).setOnClickListener {
            MessagePlayer.stop()
        }
        isVisible = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        track = null
        MessagePlayer.addListener(this)
    }

    override fun onDetachedFromWindow() {
        MessagePlayer.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onStateChanged(state: MessagePlayer.State) {
        val track = state.track
        isVisible = track != null
        if (track == null) {
            this.track = null
            return
        }

        if (track != this.track) {
            this.track = track
            titleTextView.text = track.title
            subtitleTextView.text = when {
                track.isMusic -> track.subtitle
                else -> context.getString(
                    R.string.chat_separator,
                    context.getString(
                        if (track.isVideo) R.string.playback_video_message else R.string.playback_voice_message
                    ),
                    track.subtitle,
                )
            }
        }

        playPauseMaterialButton.setPlaying(state.isPlaying)
        speedMaterialButton.text = context.getString(
            R.string.playback_speed_format,
            speedFormat.format(if (state.isFast) 2f else state.speed),
        )
    }
}

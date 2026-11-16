/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ext

import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.github.borz7zy.shadowgram.R
import com.google.android.material.button.MaterialButton

/**
 * The play/pause icon for [isPlaying]: morphs with avd_play_to_pause / avd_pause_to_play when it
 * changes, set as is the first time.
 */
private fun View.playPauseIcon(isPlaying: Boolean): Drawable? {
    val shown = getTag(R.id.play_pause_state) as Boolean?
    if (shown == isPlaying) {
        return null
    }
    setTag(R.id.play_pause_state, isPlaying)
    contentDescription = context.getString(
        if (isPlaying) R.string.playback_pause else R.string.playback_play
    )

    // Each one starts from what the other ends at
    val avd = context.getDrawable(
        if (isPlaying) R.drawable.avd_play_to_pause else R.drawable.avd_pause_to_play
    ) as AnimatedVectorDrawable
    return when (shown) {
        // Both show the play triangle until started, the pause icon is its end state
        null -> context.getDrawable(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)

        else -> avd.apply { post { start() } }
    }
}

fun MaterialButton.setPlaying(isPlaying: Boolean) {
    playPauseIcon(isPlaying)?.let { icon = it }
}

fun ImageView.setPlaying(isPlaying: Boolean) {
    playPauseIcon(isPlaying)?.let(::setImageDrawable)
}

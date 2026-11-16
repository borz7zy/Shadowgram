/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.models

/**
 * Player playback status.
 */
enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

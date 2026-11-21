/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.utils

import android.content.res.Resources
import android.media.MediaActionSound
import android.os.Build
import org.lineageos.aperture.repositories.PreferencesRepository
import java.util.concurrent.Executor

class CameraSoundsUtils(
    private val preferencesRepository: PreferencesRepository,
    private val executor: Executor,
) {
    // Accessed only by the camera executor, which also orders playback and release.
    private var mediaActionSound: MediaActionSound? = null

    init {
        executor.execute {
            mediaActionSound = MediaActionSound().apply {
                load(MediaActionSound.SHUTTER_CLICK)
                load(MediaActionSound.START_VIDEO_RECORDING)
                load(MediaActionSound.STOP_VIDEO_RECORDING)
            }
        }
    }

    fun playShutterClick() {
        if (preferencesRepository.shutterSound.value || mustPlaySounds) {
            executor.execute { mediaActionSound?.play(MediaActionSound.SHUTTER_CLICK) }
        }
    }

    fun playStartVideoRecording(): Boolean {
        if (preferencesRepository.shutterSound.value || mustPlaySounds) {
            executor.execute { mediaActionSound?.play(MediaActionSound.START_VIDEO_RECORDING) }
            return true
        }
        return false
    }

    fun playStopVideoRecording() {
        if (preferencesRepository.shutterSound.value || mustPlaySounds) {
            executor.execute { mediaActionSound?.play(MediaActionSound.STOP_VIDEO_RECORDING) }
        }
    }

    fun release() = executor.execute {
        mediaActionSound?.release()
        mediaActionSound = null
    }

    companion object {
        val mustPlaySounds: Boolean
            @Suppress("DiscouragedApi")
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                MediaActionSound.mustPlayShutterSound()
            } else {
                val resources = Resources.getSystem()
                val id = resources.getIdentifier("config_camera_sound_forced", "bool", "android")
                id > 0 && resources.getBoolean(id)
            }
    }
}

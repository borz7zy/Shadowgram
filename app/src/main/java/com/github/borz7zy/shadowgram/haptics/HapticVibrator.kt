/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal class HapticVibrator(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
    val available get() = vibrator.hasVibrator()
    val basic get() = !vibrator.hasAmplitudeControl()
    private var owner: Any? = null

    fun effect(plan: IntArray): VibrationEffect? {
        if (!available || plan.size < 2 || plan.indices.step(2).none { plan[it + 1] > 0 }) return null
        if (basic) return VibrationEffect.createWaveform(onOff(plan), -1)
        val size = plan.size / 2
        val timings = LongArray(size) { plan[it * 2].toLong() }
        val amplitudes = IntArray(size) { plan[it * 2 + 1] }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    fun playMedia(session: Any, effect: VibrationEffect) =
        play(session, effect, AudioAttributes.USAGE_MEDIA, VibrationAttributes.USAGE_MEDIA)

    fun playNotificationPreview(session: Any, effect: VibrationEffect) =
        play(session, effect, AudioAttributes.USAGE_NOTIFICATION, VibrationAttributes.USAGE_NOTIFICATION)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun play(session: Any, effect: VibrationEffect, audioUsage: Int, vibrationUsage: Int) {
        try {
            owner = session
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(vibrationUsage))
            } else {
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(audioUsage).build())
            }
        } catch (_: SecurityException) {
            owner = null
        }
    }

    @SuppressLint("MissingPermission")
    fun stop(session: Any) {
        if (owner !== session) return
        owner = null
        vibrator.cancel()
    }

    companion object {
        fun onOff(plan: IntArray): LongArray {
            val timings = mutableListOf(0L) // Android alternates OFF (index 0), ON, OFF, …
            for (i in plan.indices step 2) {
                val on = plan[i + 1] > 0
                if (on != (timings.lastIndex % 2 == 1)) timings.add(0L)
                timings[timings.lastIndex] += plan[i]
            }
            return timings.toLongArray()
        }
    }
}

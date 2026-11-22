/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.github.borz7zy.rustcore.RustHaptics
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The player is the clock. Segment boundaries are reanchored to its position after all seeks. */
internal class HapticPlayback(
    private val haptics: AudioHaptics,
    private val player: Player,
    private val resolve: suspend (MediaItem) -> HapticAudioSource?,
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private val scope = MainScope()
    private var session: Job? = null
    private var lastFallback: MediaItem? = null
    private var itemProvider: (suspend () -> HapticAudioSource?)? = null

    init {
        player.addListener(this)
        haptics.preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
            val item = player.currentMediaItem
            if (item != null) {
                val provider: suspend () -> HapticAudioSource? = { resolve(item) }
                itemProvider = provider
                haptics.currentMedia = provider
            } else {
                detachCurrentMedia()
            }
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            lastFallback = null
        }
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
        ) {
            restart()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == AudioHaptics.ENABLED || key == AudioHaptics.MEDIA || key == AudioHaptics.PROFILE ||
            key == AudioHaptics.INTENSITY || key == AudioHaptics.OFFSET
        ) {
            scope.launch { restart() }
        }
    }

    fun close() {
        player.removeListener(this)
        haptics.preferences.unregisterOnSharedPreferenceChangeListener(this)
        detachCurrentMedia()
        session?.cancel()
        haptics.vibrator.stop(this)
        scope.cancel()
    }

    private fun detachCurrentMedia() {
        if (haptics.currentMedia === itemProvider) haptics.currentMedia = null
        itemProvider = null
    }

    private fun restart() {
        session?.cancel()
        haptics.vibrator.stop(this)
        if (!haptics.enabled(AudioHaptics.MEDIA) || haptics.intensity() == 0 || !player.isPlaying ||
            !haptics.vibrator.available
        ) return
        val item = player.currentMediaItem ?: return
        val profile = haptics.profile()
        session = scope.launch {
            haptics.safely("play haptics") {
                val ready = withContext(Dispatchers.IO) {
                    val source = resolve(item) ?: return@withContext null
                    val revision = source.revision(haptics.context) ?: return@withContext null
                    val saved = haptics.storage.lookup(source.key, revision, profile)
                    saved to (saved == null && haptics.storage.status(source.key, revision) > 0)
                }?.let { (saved, useFallback) ->
                    if (useFallback && lastFallback != item) {
                        lastFallback = item
                        haptics.vibrator.effect(intArrayOf(35, haptics.intensity() * 255 / 100))
                            ?.let { haptics.vibrator.playMedia(this@HapticPlayback, it) }
                    }
                    saved
                } ?: return@safely
                playSegments(ready)
            }
        }
    }

    private suspend fun playSegments(frames: ByteArray) {
        val intensity = haptics.intensity()
        val basic = haptics.vibrator.basic
        while (player.isPlaying) {
            val start = player.currentPosition
            val offset = haptics.offset()
            val speed = player.playbackParameters.speed
            val started = SystemClock.elapsedRealtime()
            val plan = withContext(Dispatchers.Default) {
                RustHaptics.plan(frames, start - offset, SEGMENT_MS, speed, intensity, basic)
            }
            if (plan.isEmpty()) return
            val age = SystemClock.elapsedRealtime() - started
            if (abs(player.currentPosition - start - (age * speed).roundToLong()) > DRIFT_MS) continue
            haptics.vibrator.effect(plan)?.let { haptics.vibrator.playMedia(this, it) }
            val until = SystemClock.elapsedRealtime() + plan.indices.step(2).sumOf { plan[it].toLong() }
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val remaining = until - now
                if (remaining <= 0) break
                if (abs(player.currentPosition - start - ((now - started) * speed).roundToLong()) > DRIFT_MS) {
                    haptics.vibrator.stop(this)
                    break
                }
                delay(minOf(remaining, 100L))
            }
        }
    }

    companion object {
        private const val SEGMENT_MS = 1_000L
        private const val DRIFT_MS = 90L
    }
}

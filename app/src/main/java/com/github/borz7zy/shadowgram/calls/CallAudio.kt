/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.calls

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import com.github.borz7zy.rustcore.RustCall
import java.util.concurrent.atomic.AtomicBoolean

internal class CallAudio(
    context: Context,
    private val captureMedia: RustCall,
    private val playbackMedia: RustCall,
    dispatch: (() -> Unit) -> Unit,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val running = AtomicBoolean(false)
    private val oldMode = manager.mode
    private val route = CallAudioRoute(context, manager, dispatch, ::updateRoute)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS && running.get()) onFailure() }
        .build()
    private var streams: Streams? = null

    fun start() {
        check(running.compareAndSet(false, true))
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            manager.requestAudioFocus(focus)
            route.start()
            updateRoute()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun speaker(enabled: Boolean): Boolean {
        if (!running.get() || !route.speaker(enabled)) return false
        updateRoute()
        return true
    }

    private fun updateRoute() {
        if (!running.get()) return
        val legacySco = route.apply()
        if (streams?.legacySco == legacySco) return
        streams?.close()
        streams = null
        streams = openStreams(legacySco)
    }

    @SuppressLint("MissingPermission")
    private fun openStreams(legacySco: Boolean): Streams {
        val captureRate = if (legacySco) 8_000 else RustCall.SAMPLE_RATE
        val playbackRate = if (legacySco) 16_000 else RustCall.SAMPLE_RATE
        val minCapture = AudioRecord.getMinBufferSize(captureRate, inChannel, encoding)
        val minPlayback = AudioTrack.getMinBufferSize(playbackRate, outChannel, encoding)
        var capture: AudioRecord? = null
        var playback: AudioTrack? = null
        try {
            capture = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format(inChannel, captureRate))
                .setBufferSizeInBytes(maxOf(minCapture, captureRate * 6 / 50))
                .build()
            playback = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format(outChannel, playbackRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minPlayback, playbackRate * 6 / 50))
                .build()
            check(capture.state == AudioRecord.STATE_INITIALIZED && playback.state == AudioTrack.STATE_INITIALIZED)
            capture.startRecording()
            playback.play()
        } catch (error: Exception) {
            capture?.release()
            playback?.release()
            throw error
        }
        return Streams(capture, playback, legacySco, captureRate, playbackRate)
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        try {
            streams?.close()
        } finally {
            streams = null
            try {
                route.close()
            } finally {
                manager.mode = oldMode
                manager.abandonAudioFocusRequest(focus)
            }
        }
    }

    private inner class Streams(
        private val source: AudioRecord,
        private val sink: AudioTrack,
        val legacySco: Boolean,
        captureRate: Int,
        playbackRate: Int,
    ) : AutoCloseable {
        private val open = AtomicBoolean(true)
        private val captureThread = launchAudio("call-capture", source::release) { copyCapture(captureRate) }
        private val playbackThread = launchAudio("call-playback", sink::release) { copyPlayback(playbackRate) }

        private fun launchAudio(name: String, release: () -> Unit, copy: () -> Unit) = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                copy()
            } catch (_: Exception) {
                if (open.get() && running.get()) onFailure()
            } finally {
                release()
            }
        }, name).apply { start() }

        override fun close() {
            if (!open.getAndSet(false)) return
            runCatching { source.stop() }
            runCatching { sink.pause() }
            captureThread.join(200)
            playbackThread.join(200)
        }

        private fun copyCapture(rate: Int) {
            val pcm = ShortArray(rate / 50)
            val frame = if (legacySco) ShortArray(RustCall.FRAME_SAMPLES) else pcm
            var filled = 0
            while (open.get()) {
                val n = source.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                check(n > 0 || !open.get())
                if (n <= 0) return
                filled += n
                if (filled == pcm.size && open.get()) {
                    if (legacySco) {
                        val factor = frame.size / pcm.size
                        for (i in pcm.indices) {
                            val first = pcm[i].toInt()
                            val next = pcm[(i + 1).coerceAtMost(pcm.lastIndex)].toInt()
                            for (j in 0 until factor) {
                                frame[i * factor + j] = (first + (next - first) * j / factor).toShort()
                            }
                        }
                    }
                    captureMedia.capture(frame)
                    filled = 0
                }
            }
        }

        private fun copyPlayback(rate: Int) {
            val pcm = ShortArray(rate / 50)
            val frame = if (legacySco) ShortArray(RustCall.FRAME_SAMPLES) else pcm
            while (open.get()) {
                if (!playbackMedia.playback(frame)) frame.fill(0)
                if (legacySco) {
                    val factor = frame.size / pcm.size
                    for (i in pcm.indices) {
                        var sum = 0
                        for (j in 0 until factor) sum += frame[i * factor + j]
                        pcm[i] = (sum / factor).toShort()
                    }
                }
                var written = 0
                while (written < pcm.size && open.get()) {
                    val n = sink.write(pcm, written, pcm.size - written, AudioTrack.WRITE_BLOCKING)
                    check(n > 0 || !open.get())
                    if (n <= 0) return
                    written += n
                }
            }
        }
    }

    private fun format(channel: Int, rate: Int) = AudioFormat.Builder()
        .setSampleRate(rate)
        .setEncoding(encoding)
        .setChannelMask(channel)
        .build()

    private companion object {
        const val inChannel = AudioFormat.CHANNEL_IN_MONO
        const val outChannel = AudioFormat.CHANNEL_OUT_MONO
        const val encoding = AudioFormat.ENCODING_PCM_16BIT
    }
}

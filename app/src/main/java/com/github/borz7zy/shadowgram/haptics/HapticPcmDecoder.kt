/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal object HapticPcmDecoder {
    suspend fun decode(
        context: Context, uri: Uri,
        format: (rate: Int, channels: Int, encoding: Int) -> Unit,
        pcm: (ByteBuffer, Int, Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val source = extractor.getTrackFormat(track)
            val mime = source.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing audio MIME type")
            if (source.getLong(MediaFormat.KEY_DURATION, 0) > MAX_DURATION_US) {
                throw IOException("Audio exceeds the haptics duration limit")
            }
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                format(source.getInteger(MediaFormat.KEY_SAMPLE_RATE), source.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding(source))
                val buffer = ByteBuffer.allocateDirect(MAX_BUFFER)
                while (true) {
                    coroutineContext.ensureActive()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size > MAX_BUFFER) throw IOException("PCM block exceeds the haptics limit")
                    pcm(buffer, 0, size)
                    buffer.clear()
                    extractor.advance()
                }
                return
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(source, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastProgress = SystemClock.elapsedRealtime()
            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - lastProgress > 10_000) {
                    throw IOException("Audio decoder timed out")
                }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("Missing decoder input")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (size > buffer.capacity()) throw IOException("Audio packet is too large")
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 5_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> decoder.outputFormat.let {
                        format(it.getInteger(MediaFormat.KEY_SAMPLE_RATE), it.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding(it))
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = decoder.getOutputBuffer(index) ?: throw IOException("Missing PCM output")
                                pcm(buffer, info.offset, info.size)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = SystemClock.elapsedRealtime()
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } finally {
            try {
                codec?.release()
            } finally {
                extractor.release()
            }
        }
    }

    private fun encoding(format: MediaFormat): Int = when (
        format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    ) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_FLOAT -> 5
        else -> throw IOException("Unsupported PCM encoding")
    }

    private const val MAX_DURATION_US = 30 * 60 * 1_000_000L
    private const val MAX_BUFFER = 1024 * 1024
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.videonote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import org.lineageos.aperture.ApertureApplication
import org.lineageos.aperture.models.Camera
import java.io.File
import kotlin.math.sqrt

/**
 * Video note (round video message) capture on top of Aperture's camera models.
 *
 * Front and back camera share one [Recorder] and one [VideoCapture]: the recording is persistent,
 * so flipping rebinds the use case to the other camera and the file goes on. Both cameras record
 * the same [Quality] at the same frame rate, so the square stream never changes size mid-file.
 * The Recorder stamps audio and video on one clock, the square is then scaled to 640×640 by
 * media3 Transformer, which copies the audio track as is and keeps every timestamp.
 */
object VideoNoteCamera {
    private const val LOG_TAG = "VideoNoteCamera"

    /** TDLib limit for a video note side. */
    const val MAX_LENGTH = 640

    /** Telegram limit for a video note duration. */
    const val MAX_DURATION_MS = 60_000L

    /** TDLib refuses bigger video note files (option video_note_size_max, 12 MB by default). */
    internal const val MAX_SIZE_BYTES = 12L * 1024 * 1024

    /** Smallest side a note is scaled down to before its end gets cut, see [nextAttempt]. */
    internal const val MIN_LENGTH = 240

    /** Tries before giving up, the last ones cut the end until the note fits. */
    internal const val MAX_ATTEMPTS = 8

    /** Side of the thumbnail sent with the note, TDLib wants it at most 320. */
    private const val THUMBNAIL_LENGTH = 320

    private const val MAX_FPS = 60

    private const val PROGRESS_INTERVAL_MS = 200L

    /** Best first, see [Config.quality]. */
    private val qualities = listOf(Quality.UHD, Quality.QHD, Quality.FHD, Quality.HD, Quality.SD)

    // Calibration knobs: the intermediate file is re-encoded once, keep it near lossless;
    // the sent note is 640×640@60, raise it if fast motion blocks up. Long notes get less so
    // they fit MAX_SIZE_BYTES: the audio track is copied as is, AUDIO_RESERVE is kept for it,
    // and encoders overshoot their target a bit, hence SIZE_MARGIN.
    private const val RECORDER_BITRATE = 40_000_000
    private const val VIDEO_NOTE_BITRATE = 4_000_000
    private const val AUDIO_RESERVE = 256_000
    internal const val SIZE_MARGIN = 0.85

    /**
     * What both cameras record.
     *
     * @param fps The highest frame rate (up to 60) both cameras support with some quality
     * @param quality The best quality both cameras support at [fps]
     */
    data class Config(
        val back: Camera,
        val front: Camera,
        val quality: Quality,
        val fps: Int,
    )

    private val scope = MainScope()
    private var prewarmed: Deferred<Pair<ProcessCameraProvider, Config?>>? = null

    private fun Context.hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Initialize CameraX and both cameras' capabilities ahead of time, so the recorder opens
     * without that delay. Does not open a camera. No-op without the camera permission.
     */
    fun prewarm(context: Context) {
        if (prewarmed != null || !context.hasPermission(Manifest.permission.CAMERA)) {
            return
        }

        val application = context.applicationContext as ApertureApplication
        prewarmed = scope.async(Dispatchers.IO) {
            val provider = ProcessCameraProvider.getInstance(application).await()
            val back = application.cameraRepository.mainBackCamera
            val front = application.cameraRepository.mainFrontCamera
            provider to (back to front).let { (back, front) ->
                if (back == null || front == null) null else configFor(back, front)
            }.also { Log.i(LOG_TAG, "Config: $it") }
        }
    }

    private fun configFor(back: Camera, front: Camera): Config? {
        fun Camera.frameRates() = supportedVideoQualities.mapValues { (_, info) ->
            info.supportedFrameRates.map { it.value }.toSet()
        }

        return pickQualityAndFps(qualities, MAX_FPS, back.frameRates(), front.frameRates())
            ?.let { (quality, fps) -> Config(back, front, quality, fps) }
    }

    /**
     * @return The provider and the config, null when there is no front and back camera pair
     */
    suspend fun awaitConfig(context: Context): Pair<ProcessCameraProvider, Config?> {
        prewarm(context)
        val prewarmed = requireNotNull(prewarmed) { "Camera permission not granted" }
        return try {
            prewarmed.await()
        } catch (e: Exception) {
            // Try again next time (camera busy, provider init failure)
            if (this.prewarmed === prewarmed) {
                this.prewarmed = null
            }
            throw e
        }
    }

    /**
     * One recorder screen: both cameras' use cases, prepared once.
     */
    @OptIn(ExperimentalPersistentRecording::class, ExperimentalCamera2Interop::class)
    class Session(
        private val context: Context,
        private val lifecycleOwner: LifecycleOwner,
        private val provider: ProcessCameraProvider,
        private val config: Config,
        backSurfaceProvider: Preview.SurfaceProvider,
        frontSurfaceProvider: Preview.SurfaceProvider,
    ) {
        private val frameRate = Range(config.fps, config.fps)

        private val recorder = Recorder.Builder()
            // Concurrent streams may not reach the quality, the fallback keeps the frame rate
            .setQualitySelector(
                QualitySelector.from(
                    config.quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .setTargetVideoEncodingBitRate(RECORDER_BITRATE)
            .build()

        private val videoCapture = VideoCapture.Builder(recorder)
            .setTargetFrameRate(frameRate)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()

        private val backPreview = Preview.Builder()
            .setTargetFrameRate(frameRate)
            .build()
            .apply { surfaceProvider = backSurfaceProvider }

        private val frontPreview = Preview.Builder()
            .setTargetFrameRate(frameRate)
            .build()
            .apply { surfaceProvider = frontSurfaceProvider }

        private val viewPort = ViewPort.Builder(Rational(1, 1), Surface.ROTATION_0).build()

        private var recording: Recording? = null

        /** The camera feeding [videoCapture], the one shown. */
        var isFront = true
            private set

        /**
         * Whether both cameras stream at once, the inactive preview stays live.
         * Decided by the first [bind].
         */
        var isConcurrent: Boolean? = null
            private set

        private fun group(vararg useCases: UseCase) = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .apply { useCases.forEach(::addUseCase) }
            .build()

        /**
         * Bind the cameras for [isFront]. Tries both cameras at once first, if the device can't
         * do that at this frame rate it falls back to the active camera only.
         */
        fun bind() {
            provider.unbindAll()

            if (isConcurrent != false) {
                isConcurrent = runCatching { bindConcurrent() }
                    .onFailure { Log.i(LOG_TAG, "Concurrent cameras unavailable: $it") }
                    .isSuccess
                if (isConcurrent == true) {
                    return
                }
                provider.unbindAll()
            }

            val (camera, preview) = when (isFront) {
                true -> config.front to frontPreview
                false -> config.back to backPreview
            }
            provider.bindToLifecycle(lifecycleOwner, camera.cameraSelector, group(preview, videoCapture))
        }

        private fun bindConcurrent() {
            // Same lookup as Aperture's Camera.cameraId
            @Suppress("DEPRECATION")
            val pair = provider.availableConcurrentCameraInfos.any { infos ->
                val ids = infos.map { Camera2CameraInfo.from(it).cameraId }
                ids.contains(config.back.cameraId) && ids.contains(config.front.cameraId)
            }
            check(pair) { "No concurrent front and back pair" }

            provider.bindToLifecycle(
                listOf(
                    SingleCameraConfig(
                        config.back.cameraSelector,
                        if (isFront) group(backPreview) else group(backPreview, videoCapture),
                        lifecycleOwner,
                    ),
                    SingleCameraConfig(
                        config.front.cameraSelector,
                        if (isFront) group(frontPreview, videoCapture) else group(frontPreview),
                        lifecycleOwner,
                    ),
                )
            )
        }

        /** Switch to the other camera, a recording in progress continues. */
        fun flip() {
            isFront = !isFront
            bind()
        }

        /**
         * Start recording into [file], up to [MAX_DURATION_MS].
         *
         * @param onFinalized Called with the error code ([VideoRecordEvent.Finalize.ERROR_NONE]
         *   or [VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED] mean a usable file)
         */
        @SuppressLint("MissingPermission") // Checked, see the precondition
        fun start(file: File, onFinalized: (Int) -> Unit) {
            check(context.hasPermission(Manifest.permission.RECORD_AUDIO))

            recording = recorder
                .prepareRecording(
                    context,
                    FileOutputOptions.Builder(file).setDurationLimitMillis(MAX_DURATION_MS).build()
                )
                .withAudioEnabled()
                .asPersistentRecording()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        recording = null
                        onFinalized(event.error)
                    }
                }
        }

        /** Finish the file, [start]'s callback follows. */
        fun stop() {
            recording?.stop()
        }

        /** Stop everything; the persistent recording would outlive the screen otherwise. */
        fun close() {
            recording?.close()
            recording = null
            provider.unbindAll()
        }
    }

    /**
     * Scale the square [input] to at most [MAX_LENGTH], H.264, always under [MAX_SIZE_BYTES]:
     * when the encoder overshoots, it tries again with a lower bitrate, then fewer pixels, and
     * as a last resort cuts the end, see [nextAttempt].
     *
     * @param onProgress Percent of the current try, on the main thread
     */
    suspend fun transcode(
        context: Context,
        input: File,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        val (side, durationMs) = probe(input)
        val seconds = (durationMs / 1000.0).coerceAtLeast(1.0)
        var attempt = Attempt(
            side = side,
            bitrate = minOf(
                VIDEO_NOTE_BITRATE,
                (MAX_SIZE_BYTES * 8 * SIZE_MARGIN / seconds).toInt() - AUDIO_RESERVE,
            ),
            clipMs = null,
        )
        var previousSize: Long? = null

        repeat(MAX_ATTEMPTS) { index ->
            export(context, input, output, attempt, onProgress)
            val size = output.length()
            Log.i(LOG_TAG, "Video note try $index: $attempt, $size bytes")
            check(size > 0) { "Video note export wrote nothing" }
            if (size <= MAX_SIZE_BYTES) {
                return
            }
            attempt = nextAttempt(attempt, size, previousSize, durationMs, index)
            previousSize = size
        }
        error("Video note stays over $MAX_SIZE_BYTES bytes")
    }

    /** Must run on a looper thread, hence Main. */
    @OptIn(UnstableApi::class)
    private suspend fun export(
        context: Context,
        input: File,
        output: File,
        attempt: Attempt,
        onProgress: (Int) -> Unit,
    ): Unit = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<ExportResult>()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(attempt.bitrate).build()
                    )
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    result.complete(exportResult)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    result.completeExceptionally(exportException)
                }
            })
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .apply {
                attempt.clipMs?.let {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(it).build()
                    )
                }
            }
            .build()
        val item = EditedMediaItem.Builder(mediaItem)
            .setEffects(
                Effects(
                    listOf(),
                    listOf(
                        Presentation.createForWidthAndHeight(
                            attempt.side, attempt.side, Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    ),
                )
            )
            .build()

        transformer.start(item, output.absolutePath)
        val progressHolder = ProgressHolder()
        val progress = launch {
            while (true) {
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress)
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
        try {
            result.await()
            onProgress(100)
        } finally {
            progress.cancel()
            transformer.cancel()
        }
    }

    /**
     * Write the first frame of the [note] as a JPEG [output], shown while the note uploads and
     * downloads.
     *
     * @return The side of the thumbnail, null if the frame couldn't be read
     */
    fun thumbnail(note: File, output: File): Int? = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(note.absolutePath)
        val frame = retriever.getFrameAtTime(0) ?: return null
        val side = minOf(frame.width, THUMBNAIL_LENGTH)
        val scaled = Bitmap.createScaledBitmap(frame, side, side, true)
        output.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        side
    }

    /**
     * @return The side of the square, at most [MAX_LENGTH] (a fallback quality may record below
     *   it, it's not upscaled), and the duration in ms
     */
    internal fun probe(file: File) = MediaMetadataRetriever().use {
        it.setDataSource(file.absolutePath)
        val width = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val duration = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val side = minOf(width?.toIntOrNull() ?: MAX_LENGTH, height?.toIntOrNull() ?: MAX_LENGTH)
        side.coerceAtMost(MAX_LENGTH) to (duration?.toLongOrNull() ?: MAX_DURATION_MS)
    }
}

/**
 * The highest frame rate (up to [maxFps]) both cameras share, then the best quality at that rate.
 * Frame rate first: a smooth 60 fps note at FHD beats a 30 fps one at UHD.
 */
internal fun <Q> pickQualityAndFps(
    bestFirst: List<Q>,
    maxFps: Int,
    back: Map<Q, Set<Int>>,
    front: Map<Q, Set<Int>>,
): Pair<Q, Int>? {
    val common = bestFirst.associateWith { quality ->
        back[quality].orEmpty().intersect(front[quality].orEmpty()).filter { it <= maxFps }
    }
    val fps = common.values.flatten().maxOrNull() ?: return null
    return bestFirst.first { common.getValue(it).contains(fps) } to fps
}

/**
 * One way to transcode a video note.
 *
 * @param clipMs Where the note is cut, null to keep all of it
 * @param ignoresBitrate The encoder was seen ignoring a lower bitrate, only fewer pixels help
 */
internal data class Attempt(
    val side: Int,
    val bitrate: Int,
    val clipMs: Long?,
    val ignoresBitrate: Boolean = false,
)

/**
 * What to try after [attempt] wrote [size] bytes, over [VideoNoteCamera.MAX_SIZE_BYTES].
 *
 * Usually a bitrate that much lower is enough. An encoder flat out at its quality floor ignores
 * the bitrate (the size barely moved since [previousSize]): from then on it gets fewer pixels.
 * Once at [VideoNoteCamera.MIN_LENGTH], or on the last tries, the end is cut: the size follows
 * the duration, so repeating that always ends under the limit.
 *
 * @param index Of [attempt], from 0
 */
internal fun nextAttempt(
    attempt: Attempt,
    size: Long,
    previousSize: Long?,
    durationMs: Long,
    index: Int,
): Attempt {
    val ratio = VideoNoteCamera.MAX_SIZE_BYTES * VideoNoteCamera.SIZE_MARGIN / size
    val isStuck = previousSize != null && size > previousSize * 9 / 10
    val ignoresBitrate = attempt.ignoresBitrate || isStuck
    val canShrink = attempt.side > VideoNoteCamera.MIN_LENGTH

    return when {
        attempt.clipMs != null || (ignoresBitrate && !canShrink)
                || index >= VideoNoteCamera.MAX_ATTEMPTS - 3 ->
            attempt.copy(clipMs = ((attempt.clipMs ?: durationMs) * ratio).toLong())

        // Encoders want sides that are multiples of 16
        ignoresBitrate -> attempt.copy(
            side = ((attempt.side * sqrt(ratio)).toInt() / 16 * 16)
                .coerceIn(VideoNoteCamera.MIN_LENGTH, attempt.side - 16),
            ignoresBitrate = true,
        )

        else -> attempt.copy(bitrate = (attempt.bitrate * ratio).toInt())
    }
}

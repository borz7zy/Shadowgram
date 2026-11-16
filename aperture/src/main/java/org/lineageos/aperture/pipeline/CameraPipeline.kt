/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package org.lineageos.aperture.pipeline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.CameraCaptureResults
import androidx.camera.view.CameraController
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.lineageos.aperture.models.BurstProcessing
import org.lineageos.aperture.utils.StorageUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * Burst RAW capture (spec 01) + the Rust camera_pipeline (spec 02-12) + JPEG to MediaStore.
 */
@OptIn(ExperimentalCamera2Interop::class)
object CameraPipeline {
    private const val LOG_TAG = "CameraPipeline"

    /** Non-ZSL burst length (01 §1.1: default 7). */
    private const val HDR_FRAMES = 7

    /** MFSR burst length (01 §1.1: 12, 10 on some devices; 10 to bound memory). */
    private const val SUPER_RES_FRAMES = 10

    /** Upper bound for the HDR ratio ([H16]). */
    private const val MAX_HDR_RATIO = 8f

    /** Gyro metering window for Night Sight. */
    private const val GYRO_WINDOW_MS = 300L

    /** FACT reference width for Night Sight motion units. */
    private const val NIGHT_REF_WIDTH = 4032f

    private const val JPEG_QUALITY = 95

    /** TotalCaptureResult may land after the image; wait up to 2 s for it. */
    private const val RESULT_WAIT_STEPS = 100
    private const val RESULT_WAIT_STEP_MS = 20L

    // Layout shared with RustCore/native/camera/src/lib.rs (`idx`).
    private const val PARAM_MODE = 0
    private const val PARAM_WIDTH = 1
    private const val PARAM_HEIGHT = 2
    private const val PARAM_STRIDE = 3
    private const val PARAM_CFA = 4
    private const val PARAM_WHITE_LEVEL = 5
    private const val PARAM_NOISE_S = 6
    private const val PARAM_NOISE_O = 10
    private const val PARAM_WB = 14
    private const val PARAM_CCM = 18
    private const val PARAM_R_HDR = 27
    private const val PARAM_ROTATION = 28
    private const val PARAM_COUNT = 29
    private const val FRAME_META = 5

    /**
     * Whether the pipeline is shipped: it is part of librustcore.so (:RustCore, crate camera).
     */
    val isAvailable = runCatching { System.loadLibrary("rustcore") }
        .onFailure { Log.w(LOG_TAG, "camera_pipeline not available", it) }
        .isSuccess

    @JvmStatic
    private external fun nativeProcess(
        frames: Array<ByteBuffer>,
        frameMeta: FloatArray,
        params: FloatArray,
        lsc: FloatArray?,
        lscCols: Int,
        lscRows: Int,
        outSize: IntArray,
    ): IntArray?

    @JvmStatic
    private external fun nativeHdrRatio(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        cfa: Int,
        blackLevel: FloatArray,
        whiteLevel: Float,
    ): Float

    @JvmStatic
    private external fun nativeAlloc(size: Int): ByteBuffer

    private external fun nativeFree(buffer: ByteBuffer)

    private external fun nativeNightPlan(
        tet: Float,
        omega: Float,
        focalPx: Float,
        isoMin: Float,
        isoMax: Float,
        expMaxMs: Float,
    ): FloatArray?

    private class RawFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val stridePx: Int,
        val rotation: Int,
        val result: CaptureResult?,
    ) {
        var timestampNs = 0L
    }

    /**
     * CameraX 1.7 (CameraPipe) hands ImageCapture images an ImmutableImageInfo, dropping the
     * CaptureResult. Tap the camera control's internal request listener and match results to
     * images by SENSOR_TIMESTAMP.
     * ponytail: reflection on CameraX internals, pinned to camera-camera2 1.7.0-rc01; drop it
     * once CameraController can take an ImageCapture with Camera2Interop session callbacks.
     */
    private class ResultTap(control: Camera2CameraControl) : AutoCloseable {
        private val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        private val combo = control.javaClass
            .getMethod("getRequestListener\$camera_camera2").invoke(control)
        private val listenerClass = Class.forName("androidx.camera.camera2.pipe.Request\$Listener")
        private val listener = Proxy.newProxyInstance(
            listenerClass.classLoader, arrayOf(listenerClass)
        ) { proxy, method, args ->
            when {
                method.name == "equals" -> proxy === args?.get(0)
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "CameraPipeline.ResultTap"
                method.name.startsWith("onTotalCaptureResult") -> {
                    val frameInfo = args!![2]
                    val result = frameInfo.javaClass.getMethod("unwrapAs", Class::class.java)
                        .invoke(frameInfo, TotalCaptureResult::class.java) as TotalCaptureResult?
                    result?.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = result }
                    null
                }
                else -> null
            }
        }

        init {
            combo.javaClass.getMethod("addListener", listenerClass, Executor::class.java)
                .invoke(combo, listener, Executor(Runnable::run))
        }

        suspend fun await(timestampNs: Long): CaptureResult? {
            repeat(RESULT_WAIT_STEPS) {
                results.remove(timestampNs)?.let { return it }
                delay(RESULT_WAIT_STEP_MS)
            }
            Log.w(LOG_TAG, "No CaptureResult for timestamp $timestampNs")
            return null
        }

        override fun close() {
            combo.javaClass.getMethod("removeListener", listenerClass).invoke(combo, listener)
        }
    }

    /**
     * Capture a burst with [controller] (RAW output format), process it and store a JPEG.
     * @return The MediaStore [Uri] of the JPEG.
     */
    suspend fun captureAndProcess(
        context: Context,
        controller: CameraController,
        mode: BurstProcessing,
        executor: Executor,
        location: Location?,
        onCaptureStarted: () -> Unit,
        onProcessing: () -> Unit,
    ): Uri? = try {
        captureAndProcessImpl(
            context, controller, mode, executor, location, onCaptureStarted, onProcessing
        )
    } finally {
        synchronized(nativeBuffers) {
            nativeBuffers.forEach(::nativeFree)
            nativeBuffers.clear()
        }
    }

    /** Native frame buffers of the capture in flight, freed when it ends. */
    private val nativeBuffers = mutableListOf<ByteBuffer>()

    private suspend fun captureAndProcessImpl(
        context: Context,
        controller: CameraController,
        mode: BurstProcessing,
        executor: Executor,
        location: Location?,
        onCaptureStarted: () -> Unit,
        onProcessing: () -> Unit,
    ): Uri? {
        val cameraInfo = controller.cameraInfo ?: error("Camera not ready")
        val control = controller.cameraControl?.let { Camera2CameraControl.from(it) }
            ?: error("Camera not ready")
        val chars = Camera2CameraInfo.from(cameraInfo)

        val cfa = chars.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        ) ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val isoRange = chars.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )
        val expRange = chars.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val isoMin = (isoRange?.lower ?: 100).toFloat()
        val isoMax = (isoRange?.upper ?: 3200).toFloat()
        val expMaxMs = (expRange?.upper ?: 250_000_000L) / 1e6f
        val shadingApplied = chars.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED
        ) == true

        val savedFlashMode = controller.imageCaptureFlashMode
        val savedOptions = control.getCaptureRequestOptions()
        controller.imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF

        val frames = mutableListOf<RawFrame>()
        val rHdr: Float
        val tap = ResultTap(control)
        try {
            // 1. Metering frame with HAL AE/AWB (stand-in for the HDR+ AE, spec 01 §1.3).
            val meter = controller.takeRaw(executor, tap)
            onCaptureStarted()
            val meterResult = meter.result ?: error("No capture result for the RAW frame")
            val aeExpMs = (meterResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_000_000L) / 1e6f
            val aeIso = (meterResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).toFloat()
            val tet = aeExpMs * aeIso

            // 2. Burst plan.
            val expMs: Float
            val iso: Float
            val count: Int
            if (mode == BurstProcessing.NIGHT_SIGHT) {
                val focalPx = focalPx(chars)
                val omega = gyroRms(context)
                val plan = nativeNightPlan(tet, omega, focalPx, isoMin, isoMax, expMaxMs)
                    ?: error("Night plan failed")
                expMs = plan[0]
                iso = plan[1]
                count = plan[2].toInt()
                rHdr = (tet / (expMs * iso)).coerceIn(1f, MAX_HDR_RATIO)
                Log.i(LOG_TAG, "Night: omega=$omega tripod=${plan[3]} plan=${plan.toList()}")
            } else {
                val ratio = nativeHdrRatio(
                    meter.buffer, meter.width, meter.height, meter.stridePx, cfa,
                    blackLevel(meterResult, chars), whiteLevel(meterResult, chars),
                ).coerceIn(1f, MAX_HDR_RATIO)
                val shortTet = tet / ratio
                expMs = if (shortTet / aeExpMs >= isoMin) aeExpMs else shortTet / isoMin
                iso = (shortTet / expMs).coerceIn(isoMin, isoMax)
                rHdr = (tet / (expMs * iso)).coerceIn(1f, MAX_HDR_RATIO)
                count = if (mode == BurstProcessing.SUPER_RES) SUPER_RES_FRAMES else HDR_FRAMES
            }

            // 3. Manual exposure burst with locked AWB (spec 01 §1.2).
            val expNs = (expMs * 1e6).toLong()
            control.addCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, expNs)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.toInt())
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .setCaptureRequestOption(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                    .build()
            ).await()
            Log.i(LOG_TAG, "Burst $mode: $count x ${expMs}ms ISO $iso, R_hdr=$rHdr")
            repeat(count) {
                frames += controller.takeRaw(executor, tap)
            }
        } finally {
            tap.close()
            control.setCaptureRequestOptions(savedOptions)
            controller.imageCaptureFlashMode = savedFlashMode
        }

        onProcessing()
        val first = frames.first()
        val result = first.result ?: error("No capture result for the RAW frame")
        val params = FloatArray(PARAM_COUNT).apply {
            this[PARAM_MODE] = when (mode) {
                BurstProcessing.SUPER_RES -> 1f
                BurstProcessing.NIGHT_SIGHT -> 2f
                else -> 0f
            }
            this[PARAM_WIDTH] = first.width.toFloat()
            this[PARAM_HEIGHT] = first.height.toFloat()
            this[PARAM_STRIDE] = first.stridePx.toFloat()
            this[PARAM_CFA] = cfa.toFloat()
            this[PARAM_WHITE_LEVEL] = whiteLevel(result, chars)
            noiseProfile(result).forEachIndexed { i, (s, o) ->
                this[PARAM_NOISE_S + i] = s
                this[PARAM_NOISE_O + i] = o
            }
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                this[PARAM_WB] = it.red
                this[PARAM_WB + 1] = it.greenEven
                this[PARAM_WB + 2] = it.greenOdd
                this[PARAM_WB + 3] = it.blue
            } ?: floatArrayOf(1f, 1f, 1f, 1f).copyInto(this, PARAM_WB)
            // ponytail: identity when the HAL reports no transform; derive it from
            // SENSOR_FORWARD_MATRIX1/2 (REVIEW 1.10) if such devices show up.
            val ccm = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            for (r in 0 until 3) for (c in 0 until 3) {
                this[PARAM_CCM + 3 * r + c] =
                    ccm?.getElement(c, r)?.toFloat() ?: if (r == c) 1f else 0f
            }
            this[PARAM_R_HDR] = rHdr
            this[PARAM_ROTATION] = first.rotation.toFloat()
        }
        val meta = FloatArray(frames.size * FRAME_META)
        frames.forEachIndexed { i, f ->
            val r = f.result ?: result
            blackLevel(r, chars).copyInto(meta, i * FRAME_META)
            meta[i * FRAME_META + 4] =
                (r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 1L) / 1e6f *
                        (r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100)
        }
        val lsc = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?.takeUnless { shadingApplied }
        val lscData = lsc?.let { FloatArray(it.gainFactorCount).also { a -> it.copyGainFactors(a, 0) } }

        val size = IntArray(2)
        val argb = withContext(Dispatchers.Default) {
            nativeProcess(
                frames.map { it.buffer }.toTypedArray(), meta, params,
                lscData, lsc?.columnCount ?: 0, lsc?.rowCount ?: 0, size,
            )
        } ?: error("Processing failed")
        frames.clear()

        return withContext(Dispatchers.IO) {
            val bitmap = Bitmap.createBitmap(argb, size[0], size[1], Bitmap.Config.ARGB_8888)
            saveJpeg(context, bitmap, location).also { bitmap.recycle() }
        }
    }

    private fun saveJpeg(context: Context, bitmap: Bitmap, location: Location?): Uri? {
        val resolver = context.contentResolver
        val uri = StorageUtils.insertPhoto(resolver, "image/jpeg", System.currentTimeMillis())
            ?: return null
        resolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        location?.let { loc ->
            resolver.openFileDescriptor(uri, "rw")?.use {
                ExifInterface(it.fileDescriptor).apply {
                    setGpsInfo(loc)
                    saveAttributes()
                }
            }
        }
        return uri
    }

    @SuppressLint("RestrictedApi")
    private suspend fun CameraController.takeRaw(executor: Executor, tap: ResultTap): RawFrame {
        val frame = takeRawImage(executor)
        return if (frame.result != null) frame else RawFrame(
            frame.buffer, frame.width, frame.height, frame.stridePx, frame.rotation,
            tap.await(frame.timestampNs),
        ).also { it.timestampNs = frame.timestampNs }
    }

    private suspend fun CameraController.takeRawImage(executor: Executor): RawFrame =
        suspendCancellableCoroutine { cont ->
            takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame = runCatching {
                        image.use {
                            require(it.format == ImageFormat.RAW_SENSOR) {
                                "Expected RAW_SENSOR, got ${it.format}"
                            }
                            val plane = it.planes[0]
                            val src = plane.buffer.rewind() as ByteBuffer
                            val copy = nativeAlloc(src.remaining())
                                .also { b -> synchronized(nativeBuffers) { nativeBuffers += b } }
                                .order(ByteOrder.nativeOrder())
                                .put(src)
                            RawFrame(
                                copy, it.width, it.height, plane.rowStride / plane.pixelStride,
                                it.imageInfo.rotationDegrees,
                                CameraCaptureResults.retrieveCameraCaptureResult(it.imageInfo)
                                    ?.captureResult,
                            ).also { f -> f.timestampNs = it.imageInfo.timestamp }
                        }
                    }
                    frame.fold(cont::resume, cont::resumeWithException)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    /** Raster 2x2 order, as SENSOR_BLACK_LEVEL_PATTERN. */
    private fun blackLevel(result: CaptureResult, chars: Camera2CameraInfo): FloatArray =
        result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size == 4 }
            ?: chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                ?.let { p -> FloatArray(4) { i -> p.getOffsetForIndex(i % 2, i / 2).toFloat() } }
            ?: floatArrayOf(64f, 64f, 64f, 64f)

    private fun whiteLevel(result: CaptureResult, chars: Camera2CameraInfo): Float =
        (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: 1023).toFloat()

    /**
     * SENSOR_NOISE_PROFILE (S, O) per channel; falls back to the spec 15.4 order of magnitude
     * (IMX363-like) model when the HAL does not report one.
     */
    private fun noiseProfile(result: CaptureResult): List<Pair<Float, Float>> {
        result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.takeIf { it.size >= 4 }?.let { p ->
            return p.take(4).map { it.first.toFloat() to maxOf(it.second.toFloat(), 1e-37f) }
        }
        val iso = (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).toFloat()
        return List(4) { 3.5e-6f * iso to 2e-11f * iso * iso + 5e-7f }
    }

    /** Focal length in pixels at the 4032 px reference width (spec 12 §12.1). */
    private fun focalPx(chars: Camera2CameraInfo): Float {
        val f = chars.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )?.firstOrNull() ?: 4.4f
        val w = chars.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        )?.width ?: 6.17f
        return f / w * NIGHT_REF_WIDTH
    }

    /** RMS angular speed (rad/s) over a short window; handheld-like default without a gyro. */
    private suspend fun gyroRms(context: Context): Float {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return 0.05f
        var sum = 0.0
        var n = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                synchronized(this) {
                    sum += (x * x + y * y + z * z).toDouble()
                    n++
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        delay(GYRO_WINDOW_MS)
        sensorManager.unregisterListener(listener)
        return synchronized(listener) { if (n == 0) 0.05f else sqrt(sum / n).toFloat() }
    }
}

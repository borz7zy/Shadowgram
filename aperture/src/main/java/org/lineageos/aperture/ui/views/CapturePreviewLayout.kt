/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.ui.views

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.load
import coil3.request.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.aperture.R
import org.lineageos.aperture.ext.smoothRotate
import org.lineageos.aperture.models.MediaType
import org.lineageos.aperture.models.Rotation
import org.lineageos.aperture.utils.ExifUtils

/**
 * Image/video preview fragment
 */
class CapturePreviewLayout(context: Context, attrs: AttributeSet?) : ConstraintLayout(
    context, attrs
) {
    private var uri: Uri? = null
    private var photoBytes: ByteArray? = null
    private lateinit var mediaType: MediaType
    private var photoJob: Job? = null
    private var imageRequest: Disposable? = null

    private var exoPlayer: ExoPlayer? = null

    private val cancelButton by lazy { findViewById<ImageButton>(R.id.cancelButton) }
    private val confirmButton by lazy { findViewById<ImageButton>(R.id.confirmButton) }
    private val imageView by lazy { findViewById<ImageView>(R.id.imageView) }
    private val videoView by lazy { findViewById<PlayerView>(R.id.videoView) }

    /**
     * input is null == canceled
     * input is not null == confirmed
     */
    internal var onChoiceCallback: (input: Any?) -> Unit = {}

    private var screenRotation = Rotation.ROTATION_0

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        setOnClickListener {
            // Prevent clicks behind the view
        }

        cancelButton.setOnClickListener {
            stopPreview()
            onChoiceCallback(null)
        }
        confirmButton.setOnClickListener {
            val input = uri ?: photoBytes ?: return@setOnClickListener
            stopPreview()
            onChoiceCallback(input)
        }
    }

    internal fun updateSource(uri: Uri, mediaType: MediaType) {
        stopPreview()
        this.uri = uri
        this.mediaType = mediaType

        imageView.isVisible = mediaType == MediaType.PHOTO
        videoView.isVisible = mediaType == MediaType.VIDEO

        startPreview()
    }

    internal fun updateSource(photoBytes: ByteArray) {
        stopPreview()
        this.photoBytes = photoBytes
        this.mediaType = MediaType.PHOTO

        imageView.isVisible = true
        videoView.isVisible = false

        startPreview()
    }

    fun setScreenRotation(screenRotation: Rotation) {
        this.screenRotation = screenRotation

        val compensationValue = screenRotation.compensationValue.toFloat()

        cancelButton.smoothRotate(compensationValue)
        confirmButton.smoothRotate(compensationValue)
    }

    private fun startPreview() {
        when (mediaType) {
            MediaType.PHOTO -> {
                val source = uri
                if (source != null) {
                    imageView.rotation = 0f
                    imageView.scaleX = 1f
                    imageRequest = imageView.load(source)
                } else {
                    val bytes = checkNotNull(photoBytes)
                    photoJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        val (bitmap, transform) = withContext(Dispatchers.Default) {
                            bytes.inputStream().use { input ->
                                val transform = ExifUtils.getTransform(input)
                                BitmapFactory.decodeStream(input) to transform
                            }
                        }
                        Log.d(LOG_TAG, "Preview transform=$transform screenRotation=$screenRotation")
                        imageView.rotation = transform.rotation.offset.toFloat() - screenRotation.offset
                        imageView.scaleX = if (transform.mirror) -1f else 1f
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }

            MediaType.VIDEO -> {
                exoPlayer = ExoPlayer.Builder(context)
                    .build()
                    .also {
                        videoView.player = it

                        it.setMediaItem(MediaItem.fromUri(uri!!))

                        it.playWhenReady = true
                        it.seekTo(0)
                        it.prepare()
                    }
            }
        }
    }

    private fun stopPreview() {
        photoJob?.cancel()
        photoJob = null
        imageRequest?.dispose()
        imageRequest = null
        imageView.setImageDrawable(null)
        exoPlayer?.let {
            videoView.player = null
            it.release()
        }
        exoPlayer = null
        uri = null
        photoBytes = null
    }

    override fun onDetachedFromWindow() {
        stopPreview()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LOG_TAG = "Aperture"
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The gallery panel, like Google Messages': a live camera to shoot a photo, "Folders" for the
 * system photo picker, then the latest photos and videos to pick from and confirm.
 */
class GalleryPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    interface Listener {
        /** The media permissions are needed to list the gallery. */
        fun onRequestMediaPermission()

        /** The camera permission is needed for the live camera. */
        fun onRequestCameraPermission()

        /** Open the system photo picker. */
        fun onFolders()

        /** A photo was shot, send it. */
        fun onPhotoTaken(file: File)

        /** The picked media were confirmed, they go above the composer. */
        fun onConfirm(selection: List<Uri>)
    }

    var listener: Listener? = null

    private val recyclerView = RecyclerView(context)
    private val confirmButton = ExtendedFloatingActionButton(context).apply {
        setIconResource(R.drawable.ic_check)
        isVisible = false
        setOnClickListener {
            listener?.onConfirm(selection.toList())
            clearSelection()
        }
    }
    private val adapter = Adapter()

    private var media = listOf<Uri>()
    private var mediaJob: Job? = null
    private val selection = ArrayList<Uri>()
    private val thumbnails = LruCache<Uri, Bitmap>(THUMBNAIL_CACHE_SIZE)

    // Camera, bound while the panel is shown
    private var cameraOwner: LifecycleOwner? = null
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var isFront = false
    private var previewView: PreviewView? = null

    init {
        val columns = COLUMNS
        recyclerView.layoutManager = GridLayoutManager(context, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (position == 0) columns else 1
            }
        }
        recyclerView.adapter = adapter
        val padding = dp(8)
        recyclerView.setPadding(padding, padding, padding, padding)
        recyclerView.clipToPadding = false
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            confirmButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(dp(16), dp(16), dp(16), dp(16)) },
        )
    }

    /** Start the camera and list the gallery, as the panel opens. */
    fun start(owner: LifecycleOwner) {
        cameraOwner = owner
        bindCamera()
        reload()
    }

    /** Release the camera, as the panel closes. */
    fun stop() {
        mediaJob?.cancel()
        provider?.unbindAll()
        cameraOwner = null
        imageCapture = null
    }

    fun clearSelection() {
        selection.clear()
        adapter.notifyItemRangeChanged(1, media.size, PAYLOAD_SELECTION)
        updateConfirm()
    }

    private fun updateConfirm() {
        confirmButton.isVisible = selection.isNotEmpty()
        confirmButton.text = context.getString(R.string.gallery_add, selection.size)
    }

    /** List the gallery again, after the media permission was granted. */
    fun reload() {
        val owner = findViewTreeLifecycleOwner() ?: return
        mediaJob?.cancel()
        if (!hasMediaPermission()) {
            media = listOf()
            selection.clear()
            adapter.notifyDataSetChanged()
            updateConfirm()
            return
        }
        mediaJob = owner.lifecycleScope.launch {
            val (items, available) = withContext(Dispatchers.IO) {
                val items = try {
                    queryMedia()
                } catch (e: SecurityException) {
                    Log.w(LOG_TAG, "Media permission changed", e)
                    emptyList()
                }
                items to items.toSet()
            }
            media = items
            selection.retainAll(available)
            adapter.notifyDataSetChanged()
            updateConfirm()
        }
    }

    /** Bind the camera again, after its permission was granted. */
    fun bindCamera() {
        val owner = cameraOwner ?: return
        val previewView = previewView ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        owner.lifecycleScope.launch {
            val provider = runCatching { ProcessCameraProvider.getInstance(context).await() }
                .getOrNull() ?: return@launch
            this@GalleryPanelView.provider = provider
            if (cameraOwner !== owner) {
                return@launch
            }

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val selector = when (isFront) {
                true -> CameraSelector.DEFAULT_FRONT_CAMERA
                false -> CameraSelector.DEFAULT_BACK_CAMERA
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, capture)
                imageCapture = capture
            }.onFailure { Log.w(LOG_TAG, "Camera unavailable", it) }
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val owner = cameraOwner ?: return
        owner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "outgoing/${System.nanoTime()}").apply { mkdirs() }
                File(directory, "photo.jpg")
            }
            if (imageCapture !== capture) return@launch
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        listener?.onPhotoTaken(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(LOG_TAG, "Photo failed", exception)
                    }
                },
            )
        }
    }

    private fun hasMediaPermission() = mediaPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Photos and videos, newest first. */
    private fun queryMedia(): List<Uri> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val types = listOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
        )
        val uris = ArrayList<Uri>()
        // ponytail: the latest MEDIA_LIMIT only, page the query if people scroll further
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${types.joinToString()})",
            null,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && uris.size < MEDIA_LIMIT) {
                val id = cursor.getLong(0)
                val base = when (cursor.getInt(1)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI

                    else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                uris.add(ContentUris.withAppendedId(base, id))
            }
        }
        return uris
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1 + media.size

        override fun getItemViewType(position: Int) = if (position == 0) TYPE_HEADER else TYPE_MEDIA

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.gallery_panel_header, parent, false)
            )

            else -> MediaHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.gallery_panel_item, parent, false)
            )
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind()
                is MediaHolder -> holder.bind(media[position - 1])
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>,
        ) {
            when {
                holder is MediaHolder && payloads.isNotEmpty() ->
                    holder.bindSelection(media[position - 1])

                else -> super.onBindViewHolder(holder, position, payloads)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? MediaHolder)?.recycle()
        }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val preview = view.findViewById<PreviewView>(R.id.previewView)
        private val permission = view.findViewById<MaterialButton>(R.id.cameraPermissionMaterialButton)
        private val mediaPermission = view.findViewById<MaterialButton>(R.id.mediaPermissionMaterialButton)

        init {
            preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            view.findViewById<View>(R.id.shutterMaterialButton).setOnClickListener { takePhoto() }
            view.findViewById<View>(R.id.flipCameraMaterialButton).setOnClickListener {
                isFront = !isFront
                bindCamera()
            }
            view.findViewById<View>(R.id.foldersMaterialButton).setOnClickListener {
                listener?.onFolders()
            }
            permission.setOnClickListener { listener?.onRequestCameraPermission() }
            mediaPermission.setOnClickListener { listener?.onRequestMediaPermission() }
        }

        fun bind() {
            permission.isVisible = ContextCompat.checkSelfPermission(
                itemView.context, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
            mediaPermission.isVisible = !hasMediaPermission()
            // A new header after scrolling back up: the preview needs the camera again
            previewView = preview
            bindCamera()
        }
    }

    private inner class MediaHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image = view.findViewById<ImageView>(R.id.mediaImageView)
        private val check = view.findViewById<TextView>(R.id.selectionTextView)
        private var job: Job? = null
        private var uri: Uri? = null

        init {
            view.setOnClickListener {
                val uri = uri ?: return@setOnClickListener
                if (!selection.remove(uri)) {
                    selection.add(uri)
                }
                // Numbers of the others move too
                adapter.notifyItemRangeChanged(1, media.size, PAYLOAD_SELECTION)
                updateConfirm()
            }
        }

        fun bind(uri: Uri) {
            this.uri = uri
            bindSelection(uri)
            job?.cancel()
            val cached = thumbnails.get(uri)
            image.setImageBitmap(cached)
            if (cached != null) {
                return
            }
            // The cell isn't attached yet while bound, the panel is
            val owner = this@GalleryPanelView.findViewTreeLifecycleOwner() ?: return
            job = owner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
                    }.getOrNull()
                } ?: return@launch
                thumbnails.put(uri, bitmap)
                image.setImageBitmap(bitmap)
            }
        }

        @SuppressLint("SetTextI18n")
        fun bindSelection(uri: Uri) {
            val index = selection.indexOf(uri)
            check.isVisible = index >= 0
            check.text = "${index + 1}"
            itemView.scaleX = if (index >= 0) SELECTED_SCALE else 1f
            itemView.scaleY = itemView.scaleX
        }

        fun recycle() {
            job?.cancel()
            image.setImageDrawable(null)
        }
    }

    companion object {
        private const val LOG_TAG = "GalleryPanelView"

        private const val COLUMNS = 3
        private const val MEDIA_LIMIT = 300
        private const val THUMBNAIL_PX = 256
        private const val THUMBNAIL_CACHE_SIZE = 120
        private const val SELECTED_SCALE = 0.9f

        private const val TYPE_HEADER = 0
        private const val TYPE_MEDIA = 1
        private const val PAYLOAD_SELECTION = "selection"

        /** What lists the gallery on this Android version. */
        fun mediaPermissions() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

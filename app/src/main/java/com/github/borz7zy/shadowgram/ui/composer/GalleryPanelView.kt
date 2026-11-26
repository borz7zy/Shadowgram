/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.background.AsyncTask
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.RejectedExecutionException

class GalleryPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Listener {
        fun onRequestMediaPermission()
        fun onBrowse()
        fun onConfirm(selection: List<Uri>)
    }

    private data class Media(
        val uri: Uri,
        val albumId: String?,
        val albumName: String?,
        val name: String?,
        val video: Boolean,
        val durationMs: Long,
        val added: Long,
    )

    var listener: Listener? = null
    var singleSelection = false
        set(value) {
            if (value == field) return
            field = value
            clearSelection()
        }

    private val thumbnails = LruCache<Uri, Bitmap>(96)
    private val selection = linkedSetOf<Uri>()
    private var allMedia = emptyList<Media>()
    private var visibleMedia = emptyList<Media>()
    private var selectedAlbumId: String? = null
    private var mediaJob: Job? = null
    private var thumbnailScope: AsyncTask.Scope? = null
    private val grid = GridLayoutManager(context, 3)
    private val adapter = GalleryAdapter()
    private val list = RecyclerListView(context).apply {
        layoutManager = grid
        adapter = this@GalleryPanelView.adapter
        setPaddingWithoutRequestLayout(dp(4), 0, dp(4), dp(4))
        clipToPadding = false
    }
    private val albumButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        .apply {
            strokeWidth = 0
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setIconResource(R.drawable.ic_folder)
            setOnClickListener { showAlbums() }
        }
    private val confirm = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle)
        .apply {
            setIconResource(R.drawable.ic_check)
            isVisible = false
            setOnClickListener {
                listener?.onConfirm(selection.toList())
                clearSelection()
            }
        }
    private val empty = TextView(context).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), 0, dp(20), 0)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
    private val permission = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        .apply {
            strokeWidth = 0
            setText(R.string.gallery_allow_media)
            setOnClickListener { listener?.onRequestMediaPermission() }
        }

    init {
        orientation = VERTICAL
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(albumButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_gallery)
            contentDescription = context.getString(R.string.gallery_browse)
            tooltipText = contentDescription
            setOnClickListener { listener?.onBrowse() }
        }, LayoutParams(dp(48), dp(48)))
        bar.addView(confirm)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(permission, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        albumButton.setText(R.string.gallery_recent)
        empty.isVisible = false
        permission.isVisible = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            grid.spanCount = (w / dp(100)).coerceIn(2, 7)
            list.post { adapter.notifyDataSetChanged() }
        }
    }

    fun reload() {
        val owner = findViewTreeLifecycleOwner() ?: return
        mediaJob?.cancel()
        val allowed = hasMediaPermission()
        if (!allowed) {
            allMedia = emptyList()
            clearSelection()
            showAlbum()
            return
        }
        mediaJob = owner.lifecycleScope.launch {
            val media = withContext(Dispatchers.IO) { queryMedia() }
            allMedia = media
            if (allMedia.none { it.albumId == selectedAlbumId }) selectedAlbumId = null
            if (selection.isNotEmpty()) selection.retainAll(allMedia.mapTo(HashSet()) { it.uri })
            showAlbum()
            updateConfirm()
        }
    }

    fun stop() {
        mediaJob?.cancel()
    }

    private fun thumbnailScope(): AsyncTask.Scope? {
        val owner = findViewTreeLifecycleOwner() ?: return null
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return null
        thumbnailScope?.let { return it }
        val scope = AsyncTask.Scope()
        thumbnailScope = scope
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.close()
                thumbnailScope = null
            }
        })
        return scope
    }

    private fun clearSelection() {
        selection.clear()
        adapter.notifyItemRangeChanged(0, visibleMedia.size, SELECTION)
        updateConfirm()
    }

    private fun updateConfirm() {
        confirm.isVisible = selection.isNotEmpty()
        confirm.text = context.getString(R.string.gallery_add, selection.size)
    }

    fun showAlbums() {
        val albums = allMedia.mapNotNull { item ->
            item.albumId?.let { id -> item.albumName?.takeIf(String::isNotBlank)?.let { id to it } }
        }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        if (albums.isEmpty()) {
            listener?.onBrowse()
            return
        }
        val names = listOf(context.getString(R.string.gallery_recent)) + albums.map { it.second }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.gallery_folders)
            .setSingleChoiceItems(names.toTypedArray(), albums.indexOfFirst { it.first == selectedAlbumId } + 1) { dialog, index ->
                selectedAlbumId = albums.getOrNull(index - 1)?.first
                showAlbum()
                dialog.dismiss()
            }
            .show()
    }

    private fun showAlbum() {
        val id = selectedAlbumId
        visibleMedia = if (id == null) allMedia else allMedia.filter { it.albumId == id }
        albumButton.text = visibleMedia.firstOrNull()?.albumName?.takeIf { id != null }
            ?: context.getString(R.string.gallery_recent)
        albumButton.isEnabled = allMedia.isNotEmpty()
        val allowed = hasMediaPermission()
        list.isVisible = visibleMedia.isNotEmpty()
        empty.isVisible = visibleMedia.isEmpty()
        empty.setText(if (allowed) R.string.gallery_empty else R.string.gallery_permission_hint)
        permission.isVisible = !allowed
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
    }

    private fun hasMediaPermission() = mediaPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun queryMedia(): List<Media> = buildList {
        val volume = MediaStore.VOLUME_EXTERNAL
        addAll(queryCollection(MediaStore.Images.Media.getContentUri(volume), video = false))
        addAll(queryCollection(MediaStore.Video.Media.getContentUri(volume), video = true))
    }.sortedByDescending(Media::added)

    private fun queryCollection(collection: Uri, video: Boolean): List<Media> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        ).let { if (video) it + MediaStore.Video.VideoColumns.DURATION else it }
        return try {
            buildList {
                context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        add(Media(
                            ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1),
                            cursor.getString(2), cursor.getString(3), video, if (video) cursor.getLong(5) else 0,
                            cursor.getLong(4),
                        ))
                    }
                }
            }
        } catch (_: SecurityException) {
            // Photo and video access can be granted independently.
            emptyList()
        }
    }

    private inner class GalleryAdapter : RecyclerView.Adapter<MediaHolder>() {
        override fun getItemCount() = visibleMedia.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MediaHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.gallery_panel_item, parent, false)
        )

        override fun onBindViewHolder(holder: MediaHolder, position: Int) = holder.bind(visibleMedia[position])

        override fun onBindViewHolder(holder: MediaHolder, position: Int, payloads: MutableList<Any>) {
            if (SELECTION in payloads) holder.bindSelection(visibleMedia[position].uri)
            else super.onBindViewHolder(holder, position, payloads)
        }

        override fun onViewRecycled(holder: MediaHolder) = holder.clear()
    }

    private inner class MediaHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image = view.findViewById<ImageView>(R.id.mediaImageView)
        private val check = view.findViewById<TextView>(R.id.selectionTextView)
        private val duration = view.findViewById<TextView>(R.id.galleryVideoDuration)
        private var thumbnailTask: ThumbnailTask? = null
        private val retryThumbnail = Runnable {
            val task = thumbnailTask ?: return@Runnable
            thumbnailTask = null
            if (!task.scope.isClosed) loadThumbnail(task.uri, task.scope)
        }

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                val item = visibleMedia.getOrNull(position) ?: return@setOnClickListener
                if (singleSelection) listener?.onConfirm(listOf(item.uri))
                else {
                    if (!selection.remove(item.uri)) selection.add(item.uri)
                    val first = grid.findFirstVisibleItemPosition().coerceAtLeast(0)
                    val last = grid.findLastVisibleItemPosition().coerceAtLeast(first)
                    adapter.notifyItemRangeChanged(first, last - first + 1, SELECTION)
                    updateConfirm()
                }
            }
        }

        fun bind(item: Media) {
            clear()
            val cellWidth = (width - list.paddingLeft - list.paddingRight) / grid.spanCount
            itemView.layoutParams.height = (cellWidth - dp(4)).coerceAtLeast(dp(48))
            itemView.contentDescription = item.name ?: context.getString(R.string.attach_gallery)
            duration.isVisible = item.video
            duration.text = if (item.video) DateUtils.formatElapsedTime(item.durationMs / 1_000) else null
            bindSelection(item.uri)
            loadThumbnail(item.uri, thumbnailScope())
        }

        private fun loadThumbnail(uri: Uri, scope: AsyncTask.Scope?) {
            val cached = thumbnails.get(uri)
            if (cached != null) {
                image.setImageBitmap(cached)
                return
            }
            if (scope == null) return
            val task = ThumbnailTask(uri, scope)
            thumbnailTask = task
            task.inScope(scope).withPriority(AsyncTask.Priority.NORMAL).execBackgroundOn(AsyncTask.IO_EXECUTOR)
        }

        private inner class ThumbnailTask(val uri: Uri, val scope: AsyncTask.Scope) : AsyncTask<Void, Void, Bitmap?>() {
            private val signal = CancellationSignal()

            override fun doInBackground(vararg params: Void): Bitmap? = try {
                context.contentResolver.loadThumbnail(uri, THUMBNAIL_SIZE, signal)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: OperationCanceledException) {
                null
            }

            override fun onPostExecute(result: Bitmap?) {
                if (thumbnailTask !== this) return
                thumbnailTask = null
                if (result != null) {
                    thumbnails.put(uri, result)
                    image.setImageBitmap(result)
                }
            }

            override fun onError(t: Throwable) {
                if (thumbnailTask !== this) return
                if (t is RejectedExecutionException) {
                    itemView.postDelayed(retryThumbnail, THUMBNAIL_RETRY_MS)
                } else {
                    thumbnailTask = null
                    super.onError(t)
                }
            }

            fun cancelLoad() {
                if (cancel(false)) signal.cancel()
            }
        }

        fun bindSelection(uri: Uri) {
            val position = selection.indexOf(uri)
            check.isVisible = position >= 0
            check.text = (position + 1).toString()
            itemView.isSelected = position >= 0
            image.scaleX = if (position >= 0) .92f else 1f
            image.scaleY = image.scaleX
        }

        fun clear() {
            thumbnailTask?.let { task ->
                if (task.state == AsyncTask.State.FAILED) itemView.removeCallbacks(retryThumbnail)
                else task.cancelLoad()
            }
            thumbnailTask = null
            image.setImageDrawable(null)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SELECTION = "selection"
        private const val THUMBNAIL_RETRY_MS = 100L
        private val THUMBNAIL_SIZE = Size(256, 256)

        fun mediaPermissions() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

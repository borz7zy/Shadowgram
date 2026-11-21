/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.animation.AnimatorSet
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.telegram.InputContents
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Files picked for the next message, above the composer, like the old VK attachments editor:
 * the settings button first, then the files, each with its remove button. Holding a file drags
 * it to another place. Nothing is uploaded until the message is sent.
 */
class AttachmentStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    /** The files changed: added, removed or moved. */
    var onChanged: ((List<InputContents.Picked>) -> Unit)? = null

    var onGroupedChanged: ((Boolean) -> Unit)? = null

    /** Whether the files go grouped into albums. */
    var isGrouped = true

    private val items = ArrayList<InputContents.Picked>()

    private val tileSize = dp(80)
    private val tileMargin = dp(4)

    private val scrollView = DragScrollView()
    private val settings: MaterialButton
    private val content = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Room for a held tile, it grows
        setPadding(0, dp(4), 0, dp(4))
        layoutTransition = LayoutTransition()
    }

    // Dragging
    private var dragView: View? = null
    private var dragPosition = 0
    private var dragStartX = 0f
    private var scrollDirection = 0
    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragView == null || scrollDirection == 0) {
                return
            }
            val before = scrollView.scrollX
            scrollView.scrollBy(scrollDirection * dp(4), 0)
            val delta = scrollView.scrollX - before
            if (delta == 0) {
                return
            }
            dragStartX -= delta
            updateDrag()
            scrollView.postDelayed(this, AUTO_SCROLL_MS)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), 0, 0)
        isVisible = false

        settings = MaterialButton(
            context, null, com.google.android.material.R.attr.materialIconButtonStyle
        ).apply {
            setIconResource(R.drawable.ic_settings)
            contentDescription = context.getString(R.string.attachment_settings)
            tooltipText = contentDescription
            setOnClickListener { showSettings(it) }
        }
        addView(settings, LayoutParams(dp(48), dp(48)))

        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.isFillViewport = true
        scrollView.clipToPadding = false
        scrollView.setPadding(0, 0, dp(8), 0)
        scrollView.addView(content)
        addView(scrollView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        setTransitions(isDragging = false)
    }

    /** Show [list] as is, without animations, as the view is made again. */
    fun setItems(list: List<InputContents.Picked>) {
        settings.isVisible = true
        val transition = content.layoutTransition
        content.layoutTransition = null
        content.removeAllViews()
        items.clear()
        list.forEach { addTile(it, animate = false) }
        content.layoutTransition = transition
        isVisible = items.isNotEmpty()
    }

    /** The existing attachment or its proposed replacement, with the action available for it. */
    fun showEditAttachment(
        name: String?,
        preview: Uri?,
        isReplacement: Boolean,
        action: (() -> Unit)?,
    ) {
        setItems(emptyList())
        settings.isVisible = false
        isVisible = name != null || preview != null
        if (!isVisible) return

        val tile = LayoutInflater.from(context).inflate(R.layout.attachment_strip_item, content, false)
        tile.findViewById<TextView>(R.id.nameTextView).text = name
        tile.contentDescription = name
        tile.findViewById<MaterialButton>(R.id.removeMaterialButton).apply {
            isVisible = action != null
            val label = if (isReplacement) R.string.attachment_remove else R.string.message_replace_attachment
            contentDescription = context.getString(label)
            tooltipText = contentDescription
            setIconResource(if (isReplacement) R.drawable.ic_close else R.drawable.ic_edit)
            setOnClickListener { action?.invoke() }
        }
        if (preview != null) loadPreview(tile, preview, name)
        content.addView(tile, LayoutParams(tileSize, tileSize).apply { marginStart = tileMargin })
    }

    /** Add [list] after the files there, skipping ones already added. */
    fun add(list: List<InputContents.Picked>) {
        val new = list.filter { picked -> items.none { it.uri == picked.uri } }
        if (new.isEmpty()) {
            return
        }
        isVisible = true
        new.forEach { addTile(it, animate = true) }
        post { scrollView.smoothScrollTo(content.width, 0) }
        onChanged?.invoke(items.toList())
    }

    fun clear() {
        setItems(listOf())
        onChanged?.invoke(listOf())
    }

    private fun addTile(picked: InputContents.Picked, animate: Boolean) {
        val tile = LayoutInflater.from(context).inflate(R.layout.attachment_strip_item, content, false)
        // The appearing animation waits a bit, hidden till then
        tile.alpha = if (animate) 0f else 1f
        tile.setOnLongClickListener { startDrag(it) }
        tile.findViewById<View>(R.id.removeMaterialButton).setOnClickListener { remove(tile) }
        loadPreview(tile, picked.uri)
        items.add(picked)
        content.addView(tile, LayoutParams(tileSize, tileSize).apply { marginStart = tileMargin })
    }

    private fun remove(tile: View) {
        val index = content.indexOfChild(tile)
        if (index < 0 || dragView != null) {
            return
        }
        items.removeAt(index)
        content.removeView(tile)
        onChanged?.invoke(items.toList())
        // The strip goes once the last file has flown away
        if (items.isEmpty()) {
            postDelayed({ if (items.isEmpty()) isVisible = false }, REMOVE_MS)
        }
    }

    private fun showSettings(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(R.string.attachment_group).apply {
                isCheckable = true
                isChecked = isGrouped
            }
            setOnMenuItemClickListener { item ->
                isGrouped = !item.isChecked
                onGroupedChanged?.invoke(isGrouped)
                true
            }
            show()
        }
    }

    /** A picture of the photo or video, the name of anything else. */
    private fun loadPreview(tile: View, uri: Uri, displayName: String? = null) {
        val image = tile.findViewById<ImageView>(R.id.thumbnailImageView)
        val name = tile.findViewById<TextView>(R.id.nameTextView)
        val owner = findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch {
            val (bitmap, title) = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val size = Size(tileSize * 2, tileSize * 2)
                val bitmap = runCatching { resolver.loadThumbnail(uri, size, null) }
                    .recoverCatching {
                        // A photo shot in the gallery panel is a file of our own
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                            val scale = size.width.toFloat() / maxOf(info.size.width, info.size.height)
                            if (scale < 1f) {
                                decoder.setTargetSize(
                                    (info.size.width * scale).toInt().coerceAtLeast(1),
                                    (info.size.height * scale).toInt().coerceAtLeast(1),
                                )
                            }
                        }
                    }
                    .getOrNull()
                val title = displayName ?: runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment
                bitmap to title
            }
            image.setImageBitmap(bitmap)
            name.text = title
            tile.contentDescription = title
        }
    }

    /** Like VK: tiles pop in with an overshoot, fly away turning; none of that while dragging. */
    private fun setTransitions(isDragging: Boolean) {
        val transition = content.layoutTransition ?: return
        transition.setAnimateParentHierarchy(false)
        when (isDragging) {
            true -> {
                transition.setAnimator(LayoutTransition.APPEARING, null)
                transition.setAnimator(LayoutTransition.DISAPPEARING, null)
                transition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0)
                transition.setStartDelay(LayoutTransition.APPEARING, 0)
            }

            false -> {
                transition.setAnimator(LayoutTransition.APPEARING, AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(null, View.SCALE_X, 0.1f, 1f)
                            .apply { interpolator = OvershootInterpolator() },
                        ObjectAnimator.ofFloat(null, View.SCALE_Y, 0.1f, 1f)
                            .apply { interpolator = OvershootInterpolator() },
                        ObjectAnimator.ofFloat(null, View.ALPHA, 0f, 1f)
                            .apply { setInterpolator { minOf(1f, it * 6f) } },
                    )
                })
                transition.setAnimator(LayoutTransition.DISAPPEARING, AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(null, View.SCALE_X, 1f, 0.1f),
                        ObjectAnimator.ofFloat(null, View.SCALE_Y, 1f, 0.1f),
                        ObjectAnimator.ofFloat(null, View.ROTATION, 90f),
                        ObjectAnimator.ofFloat(null, View.ALPHA, 0f),
                    )
                    duration = REMOVE_MS
                })
                transition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 150)
                transition.setStartDelay(LayoutTransition.CHANGE_APPEARING, 0)
                transition.setStartDelay(LayoutTransition.APPEARING, APPEAR_DELAY_MS)
                transition.setStartDelay(LayoutTransition.DISAPPEARING, 0)
            }
        }
    }

    private fun startDrag(tile: View): Boolean {
        if (dragView != null) {
            return false
        }
        dragView = tile
        dragPosition = content.indexOfChild(tile)
        dragStartX = scrollView.lastX
        setTransitions(isDragging = true)
        scrollView.parent.requestDisallowInterceptTouchEvent(true)
        // Raised above the others, Z orders drawing
        tile.animate().scaleX(DRAG_SCALE).scaleY(DRAG_SCALE).translationZ(dp(8).toFloat())
            .setDuration(DRAG_ANIMATION_MS).start()
        return true
    }

    private fun updateDrag() {
        val tile = dragView ?: return
        tile.translationX = scrollView.lastX - dragStartX
        val step = tileSize + tileMargin
        val target = (dragPosition + tile.translationX / step).roundToInt()
            .coerceIn(0, content.childCount - 1)
        if (target != dragPosition) {
            content.removeView(tile)
            content.addView(tile, target)
            items.add(target, items.removeAt(dragPosition))
            val shift = (target - dragPosition) * step
            tile.translationX -= shift
            dragStartX += shift
            dragPosition = target
        }

        // Scroll on near the edges
        val left = tile.left + tile.translationX - scrollView.scrollX
        val direction = when {
            left + tile.width * 2 / 3 > scrollView.width -> 1
            left + tile.width / 3 < 0 -> -1
            else -> 0
        }
        if (direction != scrollDirection) {
            scrollDirection = direction
            scrollView.removeCallbacks(autoScroll)
            if (direction != 0) {
                scrollView.post(autoScroll)
            }
        }
    }

    private fun endDrag() {
        val tile = dragView ?: return
        dragView = null
        scrollDirection = 0
        scrollView.removeCallbacks(autoScroll)
        setTransitions(isDragging = false)
        tile.animate().scaleX(1f).scaleY(1f).translationX(0f).translationZ(0f)
            .setDuration(DRAG_ANIMATION_MS).start()
        onChanged?.invoke(items.toList())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class DragScrollView : HorizontalScrollView(context) {
        var lastX = 0f

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            lastX = ev.x
            if (dragView == null) {
                return super.dispatchTouchEvent(ev)
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateDrag()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    // The held tile still waits for its gesture to end
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            return true
        }
    }

    private companion object {
        const val REMOVE_MS = 300L
        const val APPEAR_DELAY_MS = 500L
        const val DRAG_ANIMATION_MS = 200L
        const val AUTO_SCROLL_MS = 10L
        const val DRAG_SCALE = 1.1f
    }
}

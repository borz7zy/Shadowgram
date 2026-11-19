/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.album

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.models.MessageItem
import java.io.File
import kotlin.math.ceil

/**
 * The pictures of an album in one bubble, laid out by [AlbumLayout]. Cells are kept between
 * binds and only reload a picture when its file changes.
 */
class AlbumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    /** A picture tapped: open it, from where it is. */
    var onItemClick: ((MessageItem, ImageView) -> Unit)? = null
    var onItemLongClick: ((MessageItem) -> Unit)? = null

    private val spacing = toPx(SPACING_DP).toFloat()
    private var items = listOf<MessageItem>()
    private var cells = listOf<AlbumLayout.Cell>()
    private var maxWidth = 0

    private class Cell(context: Context) : FrameLayout(context) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val play = ImageView(context).apply {
            setImageResource(R.drawable.ic_play_arrow)
            setBackgroundResource(R.drawable.bg_playback_scrim)
            setColorFilter(android.graphics.Color.WHITE)
            val padding = context.resources.displayMetrics.density.times(6).toInt()
            setPadding(padding, padding, padding, padding)
        }
        var shown: Any? = null

        init {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val size = (context.resources.displayMetrics.density * 36).toInt()
            addView(play, LayoutParams(size, size, Gravity.CENTER))
        }
    }

    /**
     * @param maxWidth The widest the album may be, its height as much at most
     */
    fun setItems(items: List<MessageItem>, maxWidth: Int) {
        this.items = items
        this.maxWidth = maxWidth
        cells = AlbumLayout.compute(
            items.map { it.media?.takeIf { m -> m.width > 0 && m.height > 0 }?.let { m -> m.width.toFloat() / m.height } ?: 1f },
            maxWidth.toFloat(),
            maxWidth * HEIGHT_RATIO,
            spacing,
        )

        while (childCount > items.size) {
            removeViewAt(childCount - 1)
        }
        while (childCount < items.size) {
            addView(Cell(context))
        }

        items.forEachIndexed { index, item ->
            val cell = getChildAt(index) as Cell
            val media = item.media
            val picture = media?.path?.let(::File) ?: media?.minithumbnail
            if (cell.shown != picture) {
                cell.shown = picture
                when (picture) {
                    null -> {
                        cell.image.dispose()
                        cell.image.setImageDrawable(null)
                    }

                    else -> cell.image.load(picture) { crossfade(media?.path != null) }
                }
            }
            cell.play.isVisible = item.kind == MessagePreview.Kind.VIDEO
            // Hidden while the viewer showed it, the cell may hold another one now
            cell.alpha = 1f
            cell.setOnClickListener { onItemClick?.invoke(item, cell.image) }
            cell.setOnLongClickListener { onItemLongClick?.invoke(item); true }
        }
        requestLayout()
    }

    /** The picture of an album message, null if it isn't in this album. */
    fun imageOf(messageId: Long): ImageView? {
        val index = items.indexOfFirst { it.id == messageId }
        return (getChildAt(index) as? Cell)?.image
    }

    /** How wide the album lays out, at most the width it was given. */
    val contentWidth get() = ceil(cells.maxOfOrNull { it.right } ?: 0f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = contentWidth
        val height = ceil(cells.maxOfOrNull { it.bottom } ?: 0f).toInt()
        cells.forEachIndexed { index, cell ->
            getChildAt(index)?.measure(
                MeasureSpec.makeMeasureSpec(cell.width.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cell.height.toInt(), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        cells.forEachIndexed { index, cell ->
            getChildAt(index)?.layout(
                cell.left.toInt(), cell.top.toInt(),
                cell.left.toInt() + cell.width.toInt(), cell.top.toInt() + cell.height.toInt(),
            )
        }
    }

    private companion object {
        const val SPACING_DP = 2

        /** Like VK's posts and comments: a third less tall than wide. */
        const val HEIGHT_RATIO = 1f
    }
}

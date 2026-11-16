/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.recyclerview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.res.use
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.models.MessageRow
import com.github.borz7zy.shadowgram.ui.text.initials
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one sender avatar per run of incoming messages in groups, like Telegram: it sits next to
 * the last bubble of the run and, while that one is below the screen, slides along the run
 * pinned to the bottom, until the first bubble of the run pushes it away.
 *
 * Rows with [MessageRow.Bubble.hasAvatar] leave room for it, see [MessageListAdapter].
 */
class SenderAvatarDecoration(
    private val recyclerView: RecyclerView,
    private val adapter: MessageListAdapter,
) : RecyclerView.ItemDecoration() {
    private val size = recyclerView.toPx(AVATAR_SIZE_DP)

    private val accentColors = recyclerView.resources.obtainTypedArray(R.array.chat_accent_colors)
        .use { IntArray(it.length()) { i -> it.getColor(i, 0) } }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = size * 0.4f
        // Same as AvatarView initials, on the accent colors
        color = Color.WHITE
    }

    /** Loaded photos by path, null while loading. */
    private val photos = HashMap<String, Drawable?>()

    /** Where avatars were drawn last frame, and whose message they stand by. */
    private val drawn = ArrayList<Pair<Rect, MessageItem>>()

    /** The sender whose avatar is at [x], [y] in the list, null for none. */
    fun senderAt(x: Float, y: Float) =
        drawn.firstOrNull { it.first.contains(x.toInt(), y.toInt()) }?.second?.senderId

    /** The avatar of [senderId] drawn next to [row], a child of the list: where, and whose message. */
    fun avatarBeside(row: View, senderId: Long): Pair<Rect, MessageItem>? {
        val top = row.top + row.translationY
        val bottom = row.bottom + row.translationY
        return drawn.firstOrNull { (rect, message) ->
            message.senderId == senderId && rect.bottom > top && rect.bottom <= bottom + 1
        }
    }

    private class Run(var top: Int, var bottom: Int, var endBottom: Int?, val message: MessageItem)

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val rows = adapter.currentList
        val runs = LinkedHashMap<Int, Run>()

        parent.children.forEach { child ->
            val position = parent.getChildAdapterPosition(child)
            val row = rows.getOrNull(position) as? MessageRow.Bubble ?: return@forEach
            if (!row.hasAvatar) {
                return@forEach
            }

            // Newest first: the end of the run is at a lower position
            var end = position
            while ((rows.getOrNull(end) as? MessageRow.Bubble)?.isGroupEnd == false) {
                end--
            }

            val top = (child.top + child.translationY).toInt()
            val bottom = (child.bottom + child.translationY).toInt()
            val run = runs.getOrPut(end) {
                Run(top, bottom, null, (rows[end] as MessageRow.Bubble).message)
            }
            run.top = min(run.top, top)
            run.bottom = max(run.bottom, bottom)
            if (position == end) {
                run.endBottom = bottom
            }
        }

        val limit = parent.height - parent.paddingBottom
        val left = parent.paddingLeft

        drawn.clear()
        runs.values.forEach { run ->
            val bottom = max(min(run.endBottom ?: run.bottom, limit), run.top + size)
            draw(c, run.message, left, bottom - size)
            drawn.add(Rect(left, bottom - size, left + size, bottom) to run.message)
        }
    }

    /** The avatar of the sender of [message] at [left], [top] of [c]. */
    fun draw(c: Canvas, message: MessageItem, left: Int, top: Int) {
        val photo = message.senderPhotoPath?.let { photo(it) }
        if (photo != null) {
            photo.setBounds(left, top, left + size, top + size)
            photo.draw(c)
            return
        }

        val radius = size / 2f
        circlePaint.color = accentColors[message.senderAccentColorId.mod(accentColors.size)]
        c.drawCircle(left + radius, top + radius, radius, circlePaint)

        val initials = message.senderName?.initials().orEmpty()
        val baseline = top + radius - (textPaint.descent() + textPaint.ascent()) / 2
        c.drawText(initials, left + radius, baseline, textPaint)
    }

    private fun photo(path: String): Drawable? {
        if (path in photos) {
            return photos[path]
        }
        photos[path] = null

        val context = recyclerView.context
        context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(File(path))
                .size(size)
                .transformations(CircleCropTransformation())
                .target(onSuccess = {
                    photos[path] = it.asDrawable(context.resources)
                    recyclerView.invalidate()
                })
                .build()
        )
        return null
    }

    companion object {
        const val AVATAR_SIZE_DP = 36

        /** Room left for the avatar at the start of rows that have one. */
        const val AVATAR_SPACE_DP = AVATAR_SIZE_DP + 8
    }
}

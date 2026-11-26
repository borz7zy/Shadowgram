/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.toPx

/** Fits the reactions, the unscaled message preview and the actions inside the window. */
internal class MessageMenuLayout(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    private lateinit var reactions: View
    private lateinit var reactionsRow: LinearLayout
    private lateinit var reactionList: RecyclerView
    private lateinit var expandButton: View
    private lateinit var dim: View
    private lateinit var transition: View
    private lateinit var preview: NestedScrollView
    private lateinit var actions: NestedScrollView

    var source = Rect()
        set(value) {
            field = value
            requestLayout()
        }
    var isExpanded = false
        set(value) {
            field = value
            requestLayout()
        }
    var systemInsets = Insets.NONE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private val margin = toPx(12)
    private val gap = toPx(8)
    private val reactionHeight = toPx(48)
    private val reactionWidth = toPx(44)
    private val maxGridHeight = toPx(240)
    private val previewRoom = toPx(112)

    override fun onFinishInflate() {
        super.onFinishInflate()
        reactions = findViewById(R.id.reactionsCardView)
        reactionsRow = findViewById(R.id.reactionsLinearLayout)
        reactionList = findViewById(R.id.reactionsRecyclerView)
        expandButton = findViewById(R.id.expandMaterialButton)
        dim = findViewById(R.id.messageMenuDimView)
        transition = findViewById(R.id.transitionClipLayout)
        preview = findViewById(R.id.bubbleScrollView)
        actions = findViewById(R.id.actionsScrollView)
        // An XML attribute would call NestedScrollView's setter before its helper exists.
        preview.isNestedScrollingEnabled = false
        actions.isNestedScrollingEnabled = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - systemInsets.left - systemInsets.right)
            .coerceAtLeast(0)
        val availableHeight = (MeasureSpec.getSize(heightMeasureSpec) - systemInsets.top - systemInsets.bottom - 2 * margin)
            .coerceAtLeast(0)
        val minPreview = minOf(source.height(), previewRoom, availableHeight / 2)
        val controlsWidth = (availableWidth - 2 * margin).coerceAtLeast(0)
        val reactionContentWidth = (controlsWidth - reactionsRow.paddingLeft - reactionsRow.paddingRight).coerceAtLeast(0)
        val listWidth: Int
        val listHeight: Int
        if (isExpanded) {
            val columns = (reactionContentWidth / reactionWidth).coerceIn(1, MAX_COLUMNS)
            (reactionList.layoutManager as GridLayoutManager).spanCount = columns
            listWidth = (columns * reactionWidth).coerceAtMost(reactionContentWidth)
            listHeight = (availableHeight - reactionHeight - gap - minPreview).coerceIn(0, maxGridHeight)
        } else {
            val arrowWidth = if (expandButton.isVisible) expandButton.layoutParams.width else 0
            val desiredWidth = (reactionList.adapter?.itemCount ?: 0) * reactionWidth
            listWidth = desiredWidth.coerceAtMost((reactionContentWidth - arrowWidth).coerceAtLeast(0))
            listHeight = reactionHeight
        }
        val listParams = reactionList.layoutParams
        if (listParams.width != listWidth || listParams.height != listHeight) {
            listParams.width = listWidth
            listParams.height = listHeight
            reactionList.forceLayout()
            reactionsRow.forceLayout()
            reactions.forceLayout()
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (source.isEmpty) return
        val reactionsHeight = if (reactions.isVisible) reactions.measuredHeight + gap else 0
        val spaceBelowReactions = (availableHeight - reactionsHeight).coerceAtLeast(0)
        val actionsHeight = if (actions.isVisible) {
            val limit = (spaceBelowReactions - minPreview - gap).coerceAtLeast(0)
            actions.measure(atMost(controlsWidth), atMost(limit))
            if (actions.measuredHeight > 0) actions.measuredHeight + gap else 0
        } else 0
        val previewHeight = source.height().coerceAtMost(spaceBelowReactions - actionsHeight)
        preview.measure(exact(source.width().coerceAtMost(availableWidth)), exact(previewHeight.coerceAtLeast(0)))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        dim.layout(0, 0, width, height)
        transition.layout(0, 0, width, height)
        if (source.isEmpty) return
        val topLimit = systemInsets.top + margin
        val hasReactions = reactions.isVisible
        val hasActions = actions.isVisible && actions.measuredHeight > 0
        val minTop = topLimit + if (hasReactions) reactions.measuredHeight + gap else 0
        val maxBottom = height - systemInsets.bottom - margin - if (hasActions) actions.measuredHeight + gap else 0
        val previewTop = source.top.coerceIn(minTop, (maxBottom - preview.measuredHeight).coerceAtLeast(minTop))
        val alignRight = source.centerX() > width / 2

        fun place(child: View, childTop: Int) {
            val freeWidth = width - systemInsets.left - systemInsets.right - child.measuredWidth
            val edge = minOf(margin, (freeWidth / 2).coerceAtLeast(0))
            val minLeft = systemInsets.left + edge
            val maxLeft = width - systemInsets.right - edge - child.measuredWidth
            val anchor = if (alignRight) source.right - child.measuredWidth else source.left
            val childLeft = anchor.coerceIn(minLeft, maxLeft.coerceAtLeast(minLeft))
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
        }

        place(preview, previewTop)
        if (hasReactions) place(reactions, previewTop - gap - reactions.measuredHeight)
        if (hasActions) place(actions, previewTop + preview.measuredHeight + gap)
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
    private fun atMost(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)

    private companion object {
        const val MAX_COLUMNS = 7
    }
}

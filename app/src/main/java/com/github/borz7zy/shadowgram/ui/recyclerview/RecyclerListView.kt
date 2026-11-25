/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

/// inspired by Telegram

package com.github.borz7zy.shadowgram.ui.recyclerview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

class RecyclerListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var ignoreLayoutRequests = false

    var suppressLayoutRequests: Boolean = false

    override fun requestLayout() {
        if (ignoreLayoutRequests || suppressLayoutRequests) {
            return
        }

        super.requestLayout()
    }

    fun setPaddingWithoutRequestLayout(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        if (
            paddingLeft == left &&
            paddingTop == top &&
            paddingRight == right &&
            paddingBottom == bottom
        ) {
            return
        }

        ignoreLayoutRequests = true

        try {
            setPadding(left, top, right, bottom)
        } finally {
            ignoreLayoutRequests = false
        }

        invalidate()
    }

    inline fun withoutLayoutRequests(
        block: RecyclerListView.() -> Unit
    ) {
        beginIgnoreLayout()

        try {
            block()
        } finally {
            endIgnoreLayout()
        }
    }

    fun beginIgnoreLayout() {
        ignoreLayoutRequests = true
    }

    fun endIgnoreLayout(
        requestLayoutAfter: Boolean = true
    ) {
        ignoreLayoutRequests = false

        if (requestLayoutAfter) {
            requestLayout()
        } else {
            invalidate()
        }
    }


    protected val drawRect = Rect()
    protected val drawRectF = RectF()
    protected val drawPath = Path()

    private var reusableBackgroundPaint: Paint? = null

    private fun backgroundPaint(): Paint {
        var result = reusableBackgroundPaint

        if (result == null) {
            result = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            reusableBackgroundPaint = result
        }

        return result
    }

    fun drawItemBackground(
        canvas: Canvas,
        child: View,
        color: Int
    ) {
        val paint = backgroundPaint()

        paint.color = color

        canvas.drawRect(
            child.left.toFloat(),
            child.top.toFloat(),
            child.right.toFloat(),
            child.bottom.toFloat(),
            paint
        )
    }

    fun drawItemBackground(
        canvas: Canvas,
        child: View,
        color: Int,
        radius: Float
    ) {
        val paint = backgroundPaint()

        paint.color = color

        drawRectF.set(
            child.left.toFloat(),
            child.top.toFloat(),
            child.right.toFloat(),
            child.bottom.toFloat()
        )

        canvas.drawRoundRect(
            drawRectF,
            radius,
            radius,
            paint
        )
    }

    interface SectionedAdapter {

        fun getSectionId(position: Int): Long

        fun createSectionHeader(
            parent: RecyclerListView
        ): View

        fun bindSectionHeader(
            header: View,
            sectionId: Long
        )
    }

    private val headerCache = ArrayDeque<View>()

    private val usedHeaders = ArrayList<View>(8)

    var sectionHeadersEnabled = false

    var sectionHeadersPaddingTop = 0

    var maxCachedSectionHeaders = 16


    private fun obtainHeader(
        adapter: SectionedAdapter
    ): View {
        return headerCache.pollFirst()
            ?: adapter.createSectionHeader(this)
    }

    private fun recycleUsedHeaders() {
        val count = usedHeaders.size

        for (i in 0 until count) {
            if (headerCache.size >= maxCachedSectionHeaders) {
                break
            }

            headerCache.addLast(
                usedHeaders[i]
            )
        }

        usedHeaders.clear()
    }

    fun clearSectionHeaderCache() {
        headerCache.clear()
        usedHeaders.clear()
    }

    override fun onDetachedFromWindow() {
        clearSectionHeaderCache()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (sectionHeadersEnabled) {
            drawSectionHeaders(canvas)
        }
    }

    private fun drawSectionHeaders(
        canvas: Canvas
    ) {
        val recyclerAdapter = adapter ?: return
        val sectionAdapter =
            recyclerAdapter as? SectionedAdapter
                ?: return

        val children = childCount

        if (children == 0) {
            recycleUsedHeaders()
            return
        }

        var previousSection = Long.MIN_VALUE

        try {
            for (i in 0 until children) {

                val child = getChildAt(i)

                val position =
                    getChildAdapterPosition(child)

                if (position == NO_POSITION) {
                    continue
                }

                val section =
                    sectionAdapter.getSectionId(position)

                /*
                 * Header нужен только в начале новой секции.
                 */
                if (section == previousSection) {
                    continue
                }

                previousSection = section

                val header =
                    obtainHeader(sectionAdapter)

                usedHeaders.add(header)

                sectionAdapter.bindSectionHeader(
                    header,
                    section
                )

                measureHeader(header)

                val headerTop =
                    child.top + sectionHeadersPaddingTop

                val save = canvas.save()

                canvas.translate(
                    paddingLeft.toFloat(),
                    headerTop.toFloat()
                )

                header.draw(canvas)

                canvas.restoreToCount(save)
            }
        } finally {
            recycleUsedHeaders()
        }
    }

    private fun measureHeader(
        header: View
    ) {
        val availableWidth =
            width - paddingLeft - paddingRight

        if (
            header.measuredWidth == availableWidth &&
            header.measuredHeight > 0
        ) {
            return
        }

        val widthSpec = MeasureSpec.makeMeasureSpec(
            availableWidth,
            MeasureSpec.EXACTLY
        )

        val heightSpec = MeasureSpec.makeMeasureSpec(
            0,
            MeasureSpec.UNSPECIFIED
        )

        header.measure(
            widthSpec,
            heightSpec
        )

        header.layout(
            0,
            0,
            header.measuredWidth,
            header.measuredHeight
        )
    }

    private val sectionPaint = Paint().apply {
        style = Paint.Style.FILL

        /*
         * drawVertices всё равно не поддерживает AA.
         */
        isAntiAlias = false
    }

    private var sectionVertices =
        FloatArray(INITIAL_VERTEX_FLOATS)

    var sectionBackgroundsEnabled = false

    var sectionBackgroundColor: Int =
        Color.TRANSPARENT

    var sectionInsetLeft = 0
    var sectionInsetRight = 0

    private val sectionDecoration =
        OptimizedSectionDecoration()


    init {
        addItemDecoration(sectionDecoration)
    }


    private fun ensureVertexCapacity(
        required: Int
    ) {
        if (sectionVertices.size >= required) {
            return
        }

        var newSize = sectionVertices.size

        while (newSize < required) {
            newSize *= 2
        }

        sectionVertices =
            sectionVertices.copyOf(newSize)
    }


    private fun writeRectVertices(
        offset: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Int {

        var index = offset

        sectionVertices[index++] = left
        sectionVertices[index++] = top

        sectionVertices[index++] = right
        sectionVertices[index++] = top

        sectionVertices[index++] = left
        sectionVertices[index++] = bottom

        sectionVertices[index++] = right
        sectionVertices[index++] = top

        sectionVertices[index++] = right
        sectionVertices[index++] = bottom

        sectionVertices[index++] = left
        sectionVertices[index++] = bottom

        return index
    }


    private inner class OptimizedSectionDecoration :
        ItemDecoration() {

        override fun onDraw(
            canvas: Canvas,
            parent: RecyclerView,
            state: State
        ) {
            if (!sectionBackgroundsEnabled) {
                return
            }

            if (sectionBackgroundColor == Color.TRANSPARENT) {
                return
            }

            val recyclerAdapter =
                adapter ?: return

            val sectionAdapter =
                recyclerAdapter as? SectionedAdapter
                    ?: return

            val count = childCount

            if (count == 0) {
                return
            }

            ensureVertexCapacity(
                count * FLOATS_PER_RECT
            )

            var vertexOffset = 0

            var currentSection = Long.MIN_VALUE

            var sectionTop = 0f
            var sectionBottom = 0f

            var hasSection = false

            val left =
                paddingLeft.toFloat() +
                        sectionInsetLeft

            val right =
                width.toFloat() -
                        paddingRight -
                        sectionInsetRight

            for (i in 0 until count) {

                val child =
                    getChildAt(i)

                val position =
                    getChildAdapterPosition(child)

                if (position == NO_POSITION) {
                    continue
                }

                val section =
                    sectionAdapter.getSectionId(position)

                val top = child.y

                val bottom =
                    child.y + child.height

                if (!hasSection) {

                    currentSection = section
                    sectionTop = top
                    sectionBottom = bottom
                    hasSection = true

                    continue
                }

                if (section == currentSection) {

                    sectionTop =
                        min(sectionTop, top)

                    sectionBottom =
                        max(sectionBottom, bottom)

                } else {

                    vertexOffset =
                        writeRectVertices(
                            offset = vertexOffset,
                            left = left,
                            top = sectionTop,
                            right = right,
                            bottom = sectionBottom
                        )

                    currentSection = section
                    sectionTop = top
                    sectionBottom = bottom
                }
            }

            if (hasSection) {

                vertexOffset =
                    writeRectVertices(
                        offset = vertexOffset,
                        left = left,
                        top = sectionTop,
                        right = right,
                        bottom = sectionBottom
                    )
            }

            if (vertexOffset == 0) {
                return
            }

            sectionPaint.color =
                sectionBackgroundColor

            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,

                vertexOffset,

                sectionVertices,
                0,

                null,
                0,

                null,
                0,

                null,
                0,
                0,

                sectionPaint
            )
        }
    }

    abstract class DiffAdapter<
            T,
            VH : ViewHolder
            >(
        diffCallback: DiffUtil.ItemCallback<T>
    ) : Adapter<VH>() {

        private val differ = AsyncListDiffer<T>(
            this,
            diffCallback
        )

        val currentList: List<T>
            get() = differ.currentList

        protected fun getItem(
            position: Int
        ): T {
            return differ.currentList[position]
        }

        override fun getItemCount(): Int {
            return differ.currentList.size
        }

        fun submitList(
            list: List<T>?
        ) {
            differ.submitList(list)
        }

        fun submitList(
            list: List<T>?,
            commitCallback: Runnable
        ) {
            differ.submitList(
                list,
                commitCallback
            )
        }
    }


    companion object {
        private const val FLOATS_PER_RECT = 12

        private const val INITIAL_VERTEX_FLOATS =
            FLOATS_PER_RECT * 16
    }
}
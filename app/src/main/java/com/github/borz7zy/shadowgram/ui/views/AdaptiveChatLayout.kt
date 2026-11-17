/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.Rect
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.View
import android.view.WindowInsets
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit
import com.github.borz7zy.shadowgram.ui.AdaptiveChatPanePolicy

/** The home NavHost, a divider and the chat NavHost, kept alive when the window changes size. */
class AdaptiveChatLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    var chatOpen = false
        set(value) {
            if (field == value) return
            animatePaneBounds()
            field = value
            requestLayout()
        }

    var isChatListSelected = true
        set(value) {
            if (field == value) return
            field = value
            if (!value && chatOpen) {
                // Returning to chats starts with the avatar column, while other sections
                // use a readable fixed pane instead of inheriting that tiny width.
                preferredWidthDp = AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP
                saveWidth()
            }
            if (!value) divider.isEnabled = false
            requestLayout()
        }

    /** Window coordinates supplied by WindowManager; null for a flat, uninterrupted display. */
    var separatingHinge: Rect? = null
        set(value) {
            if (field == value) return
            field = value?.let(::Rect)
            requestLayout()
        }

    private var panes = AdaptiveChatPanePolicy.Panes(0, 0, false)
    private var insetsNeedUpdate = true
    private val preferences = context.getSharedPreferences("chat_panes", Context.MODE_PRIVATE)
    private var preferredWidthDp = preferences.getFloat("list_width_dp", Float.NaN)
        .takeIf { it.isFinite() }
    var navigationRailHidden = preferences.getBoolean("navigation_rail_hidden", false)
        set(value) {
            if (field == value) return
            animatePaneBounds()
            field = value
            preferences.edit { putBoolean("navigation_rail_hidden", value) }
            requestLayout()
        }
    val isNavigationRailHidden get() = panes.navigationRailHidden
    val isWideWindow get() = (if (width > 0) width / resources.displayMetrics.density
        else resources.configuration.screenWidthDp.toFloat()) >= 600f
    val isSplitChatLayout get() = chatOpen && panes.isSplit
    val isAvatarOnly get() = chatOpen && panes.isSplit && isChatListSelected &&
        panes.listWidth / resources.displayMetrics.density + railOffsetDp() <=
        AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP + 24f
    private val divider get() = getChildAt(1) as ChatPaneDividerView
    private var primaryFocusability: Int? = null
    private var primaryAccessibility: Int? = null

    init {
        isChildrenDrawingOrderEnabled = true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        divider.onResizeBy = resize@{ delta ->
            if (!isChatListSelected || !panes.isResizable) return@resize
            val density = resources.displayMetrics.density
            val minimum = AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP
            val maximum = defaultPanes(width).listWidth / density
            val current = (preferredWidthDp ?: (panes.listWidth / density + railOffsetDp())).coerceIn(minimum, maximum)
            preferredWidthDp = (current + delta / density).coerceIn(minimum, maximum)
            requestLayout()
        }
        divider.onResizeFinished = {
            // Snap the final short stretch to the avatar column so its padding stays even.
            if ((preferredWidthDp ?: Float.MAX_VALUE) < AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP + 24) {
                preferredWidthDp = AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP
                requestLayout()
            }
            saveWidth()
        }
        divider.onToggle = {
            animatePaneBounds()
            val minimum = AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP
            preferredWidthDp = if (panes.listWidth / resources.displayMetrics.density + railOffsetDp() <= minimum + 24) null
                else minimum
            saveWidth()
            requestLayout()
        }
    }

    private fun railOffsetDp() = if (panes.navigationRailHidden) AdaptiveChatPanePolicy.NAVIGATION_RAIL_WIDTH_DP else 0f

    private fun animatePaneBounds() {
        if (!isLaidOut || childCount != 3 || !isWideWindow) return
        TransitionManager.beginDelayedTransition(this, ChangeBounds().apply {
            duration = 260
            addTarget(getChildAt(0))
            addTarget(getChildAt(1))
            addTarget(getChildAt(2))
        })
    }

    private fun saveWidth() {
        preferences.edit {
            val preferred = preferredWidthDp
            if (preferred == null) remove("list_width_dp") else putFloat("list_width_dp", preferred)
        }
    }

    private fun defaultPanes(width: Int): AdaptiveChatPanePolicy.Panes {
        val location = IntArray(2)
        getLocationInWindow(location)
        val hinge = separatingHinge
        return AdaptiveChatPanePolicy.calculate(width, resources.displayMetrics.density, true,
            hinge?.left?.minus(location[0]), hinge?.right?.minus(location[0]))
    }

    // The handle overlaps both panes and must receive taps/draw above their content.
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int) =
        when (drawingPosition) { 1 -> 2; 2 -> 1; else -> 0 }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val location = IntArray(2)
        getLocationInWindow(location)
        val hinge = separatingHinge
        val nextPanes = AdaptiveChatPanePolicy.calculate(
            width, resources.displayMetrics.density, chatOpen,
            hinge?.left?.minus(location[0]), hinge?.right?.minus(location[0]),
            preferredWidthDp,
            isChatListSelected,
            navigationRailHidden,
        )
        insetsNeedUpdate = insetsNeedUpdate || panes != nextPanes
        panes = nextPanes

        getChildAt(0).apply {
            // On phones the chat overlays the full-size home screen. Never measure the
            // preserved folder pager at zero width: that changes its selected page/rail mode
            // and forces a complete re-layout as Back reveals it again.
            val covered = chatOpen && !panes.isSplit
            visibility = VISIBLE
            if (primaryAccessibility == null) primaryAccessibility = importantForAccessibility
            importantForAccessibility = if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else primaryAccessibility!!
            (this as ViewGroup).let { primary ->
                if (primaryFocusability == null) primaryFocusability = primary.descendantFocusability
                primary.descendantFocusability = if (covered) ViewGroup.FOCUS_BLOCK_DESCENDANTS else primaryFocusability!!
            }
            measure(MeasureSpec.makeMeasureSpec(if (covered) width else panes.listWidth, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
        getChildAt(1).apply {
            visibility = if (panes.isSplit) VISIBLE else GONE
            isEnabled = panes.isResizable
            val gap = (panes.chatLeft - panes.listWidth).coerceAtLeast((48 * resources.displayMetrics.density).toInt())
            measure(MeasureSpec.makeMeasureSpec(gap, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
        val minimum = AdaptiveChatPanePolicy.MIN_LIST_WIDTH_DP * resources.displayMetrics.density
        val maximum = defaultPanes(width).listWidth
        divider.widthFraction = if (maximum > minimum)
            ((panes.listWidth + railOffsetDp() * resources.displayMetrics.density - minimum) /
                (maximum - minimum)).coerceIn(0f, 1f) else 1f
        getChildAt(2).apply {
            visibility = if (chatOpen) VISIBLE else GONE
            isClickable = chatOpen && !panes.isSplit
            measure(
                MeasureSpec.makeMeasureSpec(width - panes.chatLeft, MeasureSpec.EXACTLY),
                heightMeasureSpec,
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Chats stay physically on the left, including when the content language is RTL.
        getChildAt(0).layout(0, 0, if (chatOpen && !panes.isSplit) width else panes.listWidth, height)
        getChildAt(1).let {
            val middle = (panes.listWidth + panes.chatLeft) / 2
            it.layout(middle - it.measuredWidth / 2, 0, middle + it.measuredWidth / 2, height)
        }
        getChildAt(2).layout(panes.chatLeft, 0, width, height)
        if (insetsNeedUpdate) {
            insetsNeedUpdate = false
            ViewCompat.requestApplyInsets(this)
        }
    }

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val original = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        listOf(0, 2).forEach { index ->
            val child = getChildAt(index)
            if (child.visibility != GONE) {
                val builder = WindowInsetsCompat.Builder(original)
                if (panes.isSplit) {
                    // The internal edges have no cutout/system bar. Keep the IME insets so
                    // the chat's composer and its existing keyboard panels behave normally.
                    listOf(
                        WindowInsetsCompat.Type.statusBars(),
                        WindowInsetsCompat.Type.navigationBars(),
                        WindowInsetsCompat.Type.captionBar(),
                        WindowInsetsCompat.Type.displayCutout(),
                    ).forEach { type ->
                        val edge = original.getInsets(type)
                        builder.setInsets(type, Insets.of(
                            if (index == 0) edge.left else 0, edge.top,
                            if (index == 2) edge.right else 0, edge.bottom,
                        ))
                    }
                }
                ViewCompat.dispatchApplyWindowInsets(child, builder.build())
            }
        }
        return insets
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context, attrs)
}

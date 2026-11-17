/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

/** Window widths, rather than orientation or device model, drive the list/detail layout. */
internal object AdaptiveChatPanePolicy {
    data class Panes(
        val listWidth: Int,
        val chatLeft: Int,
        val isSplit: Boolean,
        val isResizable: Boolean = false,
        val navigationRailHidden: Boolean = false,
    )

    // 80dp navigation rail + 88dp chat avatar column.
    const val MIN_LIST_WIDTH_DP = 168f
    const val NAVIGATION_RAIL_WIDTH_DP = 80f

    fun calculate(
        width: Int,
        density: Float,
        chatOpen: Boolean,
        hingeLeft: Int? = null,
        hingeRight: Int? = null,
        preferredListWidthDp: Float? = null,
        isChatListSelected: Boolean = true,
        navigationRailHidden: Boolean = false,
    ): Panes {
        if (!chatOpen) return Panes(width, width, false)
        if (width / density < 600f) return Panes(0, 0, false)

        // A separating vertical hinge is the natural boundary. Ignore features outside the
        // window, or ones that would leave an unusably narrow pane in multi-window mode.
        val minimumPane = (240 * density).toInt()
        val validHinge = hingeLeft != null && hingeRight != null && hingeLeft <= hingeRight &&
            hingeLeft >= minimumPane && width - hingeRight >= minimumPane
        if (validHinge) {
            // A physical gap must remain between the panes. A crease with no occlusion can
            // be crossed when the user explicitly moves the divider.
            if (hingeRight > hingeLeft || preferredListWidthDp == null || !isChatListSelected) {
                return Panes(hingeLeft, hingeRight, true, hingeRight == hingeLeft && isChatListSelected)
            }
        }

        val defaultWidth = (width * .42f).toInt()
            .coerceIn((320 * density).toInt(), (420 * density).toInt())
            .coerceAtMost(width / 2)
        val listWidth = if (validHinge && hingeLeft == hingeRight) hingeLeft else defaultWidth
        val preferred = preferredListWidthDp?.takeIf { it.isFinite() && isChatListSelected }
            ?.let { (it * density).toInt().coerceIn((MIN_LIST_WIDTH_DP * density).toInt(), listWidth) }
            ?: listWidth
        val hideRail = navigationRailHidden && isChatListSelected &&
            preferred / density <= MIN_LIST_WIDTH_DP + 24f
        val renderedWidth = preferred - if (hideRail) (NAVIGATION_RAIL_WIDTH_DP * density).toInt() else 0
        return Panes(renderedWidth, renderedWidth, true, isChatListSelected, hideRail)
    }
}

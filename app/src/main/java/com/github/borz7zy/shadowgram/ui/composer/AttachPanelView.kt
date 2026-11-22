/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.github.borz7zy.shadowgram.R
import com.google.android.material.button.MaterialButton

class AttachPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    enum class Action(@DrawableRes val icon: Int, @StringRes val label: Int) {
        GALLERY(R.drawable.ic_gallery, R.string.attach_gallery),
        CAMERA(R.drawable.ic_photo_camera, R.string.attach_camera),
        FILES(R.drawable.ic_attach_file, R.string.attach_files),
        LOCATION(R.drawable.ic_place, R.string.attach_location),
        CONTACT(R.drawable.ic_person, R.string.attach_contact),
    }

    var onAction: ((Action) -> Unit)? = null
    private val actions = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(6))
    }
    private val buttons = Action.entries.associateWith { action ->
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            strokeWidth = 0
            text = context.getString(action.label)
            isAllCaps = false
            textSize = 11f
            letterSpacing = 0f
            maxLines = 2
            setIconResource(action.icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
            iconSize = dp(22)
            iconPadding = dp(3)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(3), dp(5), dp(3), dp(5))
            setOnClickListener { onAction?.invoke(action) }
            actions.addView(this, LinearLayout.LayoutParams(dp(61), dp(56), 1f))
        }
    }

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun showActions(allowed: Set<Action>) {
        buttons.forEach { (action, button) -> button.isVisible = action in allowed }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

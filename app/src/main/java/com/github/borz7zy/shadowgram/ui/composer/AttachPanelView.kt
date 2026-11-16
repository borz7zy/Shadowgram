/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.widget.TextViewCompat
import com.github.borz7zy.shadowgram.R
import com.google.android.material.button.MaterialButton

/**
 * The "+" panel: what else can be sent, laid out like Google Messages' attachments.
 */
class AttachPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GridLayout(context, attrs) {
    enum class Action(@DrawableRes val icon: Int, @StringRes val label: Int) {
        CAMERA(R.drawable.ic_photo_camera, R.string.attach_camera),
        FILES(R.drawable.ic_attach_file, R.string.attach_files),
        LOCATION(R.drawable.ic_place, R.string.attach_location),
        CONTACT(R.drawable.ic_person, R.string.attach_contact),
        SCHEDULE(R.drawable.ic_schedule, R.string.attach_schedule),
    }

    var onAction: ((Action) -> Unit)? = null

    private val labels = HashMap<Action, TextView>()

    init {
        columnCount = COLUMNS
        val padding = dp(16)
        setPadding(padding, padding, padding, padding)

        Action.entries.forEach { action ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }

            val button = MaterialButton(
                context, null, com.google.android.material.R.attr.materialIconButtonFilledTonalStyle
            ).apply {
                setIconResource(action.icon)
                iconSize = dp(28)
                insetTop = 0
                insetBottom = 0
                contentDescription = context.getString(action.label)
                setOnClickListener { onAction?.invoke(action) }
            }
            item.addView(button, LinearLayout.LayoutParams(dp(72), dp(56)))

            val label = TextView(context).apply {
                TextViewCompat.setTextAppearance(
                    this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
                )
                gravity = Gravity.CENTER_HORIZONTAL
                setText(action.label)
                setPadding(0, dp(8), 0, 0)
            }
            labels[action] = label
            item.addView(label)

            addView(item, LayoutParams(spec(UNDEFINED, 1f), spec(UNDEFINED, 1f)).apply { width = 0 })
        }
    }

    /** Scheduling turns into cancelling it while a time is set. */
    fun setScheduled(isScheduled: Boolean) {
        labels[Action.SCHEDULE]?.setText(
            if (isScheduled) R.string.attach_schedule_cancel else R.string.attach_schedule
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val COLUMNS = 4
    }
}

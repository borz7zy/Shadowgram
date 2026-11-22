/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.children
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.models.ChatListKey
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** Folder buttons scroll independently of the three main navigation destinations. */
class ChatFolderRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onFolderSelected: ((ChatListKey) -> Unit)? = null
    private var folders = emptyList<ChatFolder>()

    init { orientation = VERTICAL }

    fun submit(folders: List<ChatFolder>, selectedId: Long, unread: Map<ChatListKey, Pair<Int, Int>>) {
        if (this.folders != folders) {
            this.folders = folders.toList()
            removeAllViews()
            folders.forEach { folder ->
                val button = MaterialButton(context).apply {
                    minHeight = dp(60)
                    tag = folder.key.stableId
                    val title = folder.title ?: context.getString(R.string.all_chats)
                    text = title
                    tooltipText = title
                    isAllCaps = false
                    letterSpacing = 0f
                    textSize = 11f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    isCheckable = true
                    setIconResource(when {
                        folder.key == ChatListKey.Main -> R.drawable.ic_chat
                        folder.iconName == "Personal" -> R.drawable.ic_person
                        folder.iconName == "Groups" -> R.drawable.ic_contacts
                        folder.iconName == "Channels" -> R.drawable.ic_campaign
                        folder.iconName == "Favorite" -> R.drawable.ic_favorite
                        folder.iconName == "Travel" -> R.drawable.ic_travel
                        else -> R.drawable.ic_folder
                    })
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
                    iconSize = dp(22)
                    iconPadding = dp(3)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    cornerRadius = dp(12)
                    setPadding(dp(4), dp(9), dp(4), dp(6))
                    strokeWidth = 0
                    backgroundTintList = ColorStateList(arrayOf(
                        intArrayOf(android.R.attr.state_checked), intArrayOf(),
                    ), intArrayOf(
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer),
                        Color.TRANSPARENT,
                    ))
                    val foreground = ColorStateList(arrayOf(
                        intArrayOf(android.R.attr.state_checked), intArrayOf(),
                    ), intArrayOf(
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer),
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
                    ))
                    setTextColor(foreground)
                    iconTint = foreground
                    setOnClickListener {
                        select(folder.key.stableId)
                        onFolderSelected?.invoke(folder.key)
                    }
                }
                val badge = TextView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    textSize = 10f
                    minHeight = dp(16)
                    minWidth = dp(16)
                    setPadding(dp(3), 0, dp(3), 0)
                }
                addView(FrameLayout(context).apply {
                    addView(button, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    addView(badge, FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
                    ).apply {
                        marginEnd = dp(6)
                        topMargin = dp(4)
                    })
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
        }
        folders.forEachIndexed { index, folder ->
            val row = getChildAt(index) as FrameLayout
            val button = row.getChildAt(0) as MaterialButton
            val badge = row.getChildAt(1) as TextView
            val (count, unmuted) = unread[folder.key] ?: (0 to 0)
            badge.isVisible = count > 0
            ViewCompat.setStateDescription(button, count.takeIf { it > 0 }?.let {
                resources.getQuantityString(R.plurals.folder_unread_chats, it, it)
            })
            if (count > 0) {
                badge.text = if (count > 99) "99+" else count.toString()
                val color = if (unmuted > 0) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorSurfaceVariant
                val onColor = if (unmuted > 0) com.google.android.material.R.attr.colorOnPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant
                badge.setTextColor(MaterialColors.getColor(badge, onColor))
                badge.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(MaterialColors.getColor(badge, color))
                }
            }
        }
        select(selectedId)
    }

    private fun select(id: Long) {
        children.forEach { row ->
            val button = (row as FrameLayout).getChildAt(0) as MaterialButton
            button.isChecked = button.tag == id
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

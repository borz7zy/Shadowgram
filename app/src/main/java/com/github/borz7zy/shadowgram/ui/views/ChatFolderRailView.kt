/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.LinearLayout
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

    fun submit(folders: List<ChatFolder>, selectedId: Long) {
        if (this.folders != folders) {
            this.folders = folders.toList()
            removeAllViews()
            folders.forEach { folder ->
                addView(MaterialButton(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    minHeight = dp(76)
                    tag = folder.key.stableId
                    val title = folder.title ?: context.getString(R.string.all_chats)
                    text = title
                    tooltipText = title
                    contentDescription = title
                    textSize = 11f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    isCheckable = true
                    setIconResource(R.drawable.ic_folder)
                    iconGravity = MaterialButton.ICON_GRAVITY_TOP
                    iconSize = dp(24)
                    iconPadding = dp(4)
                    insetTop = dp(3)
                    insetBottom = dp(3)
                    minWidth = 0
                    minimumWidth = 0
                    cornerRadius = dp(16)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
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
                })
            }
        }
        select(selectedId)
    }

    private fun select(id: Long) {
        children.forEach { (it as MaterialButton).isChecked = it.tag == id }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

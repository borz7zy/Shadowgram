/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.use
import androidx.core.view.isVisible
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.ui.text.initials
import com.google.android.material.card.MaterialCardView
import java.io.File

/**
 * Round chat avatar: the photo, its minithumbnail while it downloads, or initials on the accent
 * color. Forum topics get their colored topic icon with the first letter instead.
 *
 * [setChat] only touches what changed, so rebinding the same chat doesn't reload the photo.
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs, com.google.android.material.R.attr.materialCardViewFilledStyle) {
    private val initialsTextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextAppearance(
            context.obtainStyledAttributes(
                intArrayOf(com.google.android.material.R.attr.textAppearanceTitleMedium)
            ).use { it.getResourceId(0, 0) }
        )
        setTextColor(Color.WHITE)
    }

    private val photoImageView = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val accentColors = resources.obtainTypedArray(R.array.chat_accent_colors).use {
        IntArray(it.length()) { i -> it.getColor(i, 0) }
    }

    private var chat: ChatItem? = null

    init {
        strokeWidth = 0
        cardElevation = 0f
        addView(photoImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(initialsTextView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = minOf(w, h) / 2f
        // Grows with the avatar, the profile one and while it's shared with it
        initialsTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(w, h) * INITIALS_SCALE)
    }

    fun setChat(chat: ChatItem) {
        val old = this.chat
        this.chat = chat

        if (old?.title != chat.title || old.topicIconColor != chat.topicIconColor) {
            initialsTextView.text = when (chat.topicIconColor) {
                null -> chat.title.initials()
                else -> chat.title.firstOrNull { it.isLetterOrDigit() }?.uppercase()
            }
        }

        if (old != null && old.topicIconColor == chat.topicIconColor
            && old.accentColorId == chat.accentColorId && old.photoPath == chat.photoPath
            && old.minithumbnail === chat.minithumbnail
        ) {
            return
        }

        chat.topicIconColor?.let {
            setCardBackgroundColor(Color.TRANSPARENT)
            photoImageView.dispose()
            photoImageView.setImageResource(R.drawable.ic_topic)
            photoImageView.imageTintList = ColorStateList.valueOf(it)
            photoImageView.scaleType = ImageView.ScaleType.FIT_CENTER
            initialsTextView.isVisible = true
            return
        }

        setCardBackgroundColor(accentColors[chat.accentColorId.mod(accentColors.size)])
        photoImageView.imageTintList = null
        photoImageView.scaleType = ImageView.ScaleType.CENTER_CROP

        when (val photo = chat.photoPath?.let(::File) ?: chat.minithumbnail) {
            null -> {
                photoImageView.dispose()
                photoImageView.setImageDrawable(null)
            }

            else -> photoImageView.load(photo)
        }
        initialsTextView.isVisible = chat.photoPath == null && chat.minithumbnail == null
    }

    companion object {
        /** Title medium on a 40dp avatar. */
        private const val INITIALS_SCALE = 0.4f
    }
}

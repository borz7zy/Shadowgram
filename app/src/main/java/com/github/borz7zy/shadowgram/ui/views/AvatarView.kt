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
import com.github.borz7zy.shadowgram.playback.TdFileDataSource
import com.github.borz7zy.shadowgram.ui.text.initials
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
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

    /** An animated photo, looped over the still; made the first time there is one. */
    private var animationView: AnimationView? = null

    private var chat: ChatItem? = null
    private var hasAnimatedBounds = false

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

        // ChangeBounds resizes the card without a layout pass for its children.
        val hasUnmeasuredBounds = w != measuredWidth || h != measuredHeight
        if (hasUnmeasuredBounds || hasAnimatedBounds) {
            hasAnimatedBounds = hasUnmeasuredBounds
            measureChildren(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
            super.onLayout(true, 0, 0, w, h)
        }
    }

    /**
     * Loop the animated version of the photo over it, a file streamed while it downloads; 0 for
     * none, just the still.
     */
    fun setAnimation(fileId: Int) {
        val view = animationView ?: if (fileId == 0) return else AnimationView(context).also {
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            animationView = it
            addView(it, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        view.setVideo(fileId.takeIf { it != 0 }?.let(TdFileDataSource::uriOf))
    }

    /** What the avatar shows now, the photo the viewer opens from; null without one. */
    val photoDrawable get() = photoImageView.drawable?.takeIf { photoImageView.isVisible }

    fun setChat(chat: ChatItem) {
        val old = this.chat
        this.chat = chat
        setAnimation(if (chat.isSavedMessages) 0 else chat.animationFileId)

        if (old?.title != chat.title || old.topicIconColor != chat.topicIconColor) {
            initialsTextView.text = when (chat.topicIconColor) {
                null -> chat.title.initials()
                else -> chat.title.firstOrNull { it.isLetterOrDigit() }?.uppercase()
            }
        }

        if (old != null && old.topicIconColor == chat.topicIconColor
            && old.isSavedMessages == chat.isSavedMessages
            && old.accentColorId == chat.accentColorId && old.photoPath == chat.photoPath
            && old.minithumbnail === chat.minithumbnail
        ) {
            return
        }

        if (chat.isSavedMessages) {
            setCardBackgroundColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            photoImageView.dispose()
            photoImageView.setImageResource(R.drawable.ic_bookmark)
            photoImageView.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
            )
            photoImageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            initialsTextView.isVisible = false
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

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ChatHeader
import com.github.borz7zy.shadowgram.ui.text.subtitleText

/**
 * Avatar, title and subtitle of a chat or topic, as shown in a top bar.
 */
class ChatHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    /** Shared with the profile it opens. */
    val avatarView: AvatarView
    private val titleTextView: TextView
    private val emojiStatusView: CustomEmojiView
    private val subtitleTextView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        inflate(context, R.layout.chat_header, this)
        avatarView = findViewById(R.id.avatarView)
        titleTextView = findViewById(R.id.titleTextView)
        emojiStatusView = findViewById(R.id.emojiStatusView)
        subtitleTextView = findViewById(R.id.subtitleTextView)
    }

    fun setHeader(header: ChatHeader) {
        avatarView.setChat(header.chat)
        titleTextView.text = header.chat.title
        emojiStatusView.setEmojiStatus(header.emojiStatusId, header.isPremium)

        val subtitle = context.subtitleText(header.subtitle)

        subtitleTextView.text = subtitle
        subtitleTextView.isVisible = subtitle != null
    }
}

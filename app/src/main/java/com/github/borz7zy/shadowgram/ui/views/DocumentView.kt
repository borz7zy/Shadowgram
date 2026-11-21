/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.MessageItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * A file in its bubble: its button downloads it, cancels the download or opens it once it's
 * there, with its progress around the button.
 */
class DocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val actionMaterialButton: MaterialButton
    private val downloadProgressIndicator: CircularProgressIndicator
    private val nameTextView: TextView
    private val sizeTextView: TextView

    /** The button was tapped, the owner downloads, cancels or opens [MessageItem.Document]. */
    var onActionClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        inflate(context, R.layout.document_view, this)
        actionMaterialButton = findViewById(R.id.actionMaterialButton)
        downloadProgressIndicator = findViewById(R.id.downloadProgressIndicator)
        nameTextView = findViewById(R.id.nameTextView)
        sizeTextView = findViewById(R.id.sizeTextView)

        actionMaterialButton.setOnClickListener { onActionClick?.invoke() }
    }

    /** Uses the same Material foreground as the message hosting the file. */
    fun setBubbleContentColor(@ColorInt color: Int) {
        nameTextView.setTextColor(color)
        sizeTextView.setTextColor(color)
        downloadProgressIndicator.setIndicatorColor(color)
        downloadProgressIndicator.trackColor = ColorUtils.setAlphaComponent(color, 0x40)
    }

    fun bind(document: MessageItem.Document) {
        nameTextView.text = document.name

        val size = Formatter.formatShortFileSize(context, document.size)
        sizeTextView.text = when {
            document.isDownloading -> context.getString(
                R.string.playback_time,
                Formatter.formatShortFileSize(context, document.downloadedSize),
                size,
            )

            else -> size
        }

        downloadProgressIndicator.isVisible = document.isDownloading
        if (document.isDownloading && document.size > 0) {
            downloadProgressIndicator.isIndeterminate = false
            downloadProgressIndicator.setProgressCompat(
                (document.downloadedSize * 100 / document.size).toInt(), true
            )
        }

        val (icon, description) = when {
            document.path != null && document.isApk && document.canBeSaved ->
                R.drawable.ic_draft to R.string.document_install
            document.path != null -> R.drawable.ic_draft to R.string.document_open
            document.isDownloading -> R.drawable.ic_close to R.string.document_cancel
            else -> R.drawable.ic_download to R.string.document_download
        }
        actionMaterialButton.setIconResource(icon)
        actionMaterialButton.contentDescription = context.getString(description)
    }
}

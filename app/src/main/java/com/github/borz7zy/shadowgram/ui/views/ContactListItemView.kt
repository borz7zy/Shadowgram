/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.res.use
import androidx.core.view.isVisible
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.ContactItem
import com.github.borz7zy.shadowgram.models.ContactItem.LastSeen
import com.github.borz7zy.shadowgram.models.ContactRow
import com.github.borz7zy.shadowgram.ui.text.formatLastSeen
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.listitem.ListItemLayout
import java.io.File

/**
 * A contacts list row, a segment of the card of its group. [setRow] only touches the child views
 * whose content changed, so that status updates don't flicker.
 */
class ContactListItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.listItemLayoutStyle,
) : ListItemLayout(context, attrs, defStyleAttr) {
    // Views
    private val avatarCardView by lazy { findViewById<MaterialCardView>(R.id.avatarCardView) }

    /** Shared with the profile it opens. */
    val avatarView: View get() = avatarCardView
    private val initialTextView by lazy { findViewById<TextView>(R.id.initialTextView) }
    private val lastSeenTextView by lazy { findViewById<TextView>(R.id.lastSeenTextView) }
    private val nameTextView by lazy { findViewById<TextView>(R.id.nameTextView) }
    private val photoImageView by lazy { findViewById<ImageView>(R.id.photoImageView) }

    private val accentColors = resources.obtainTypedArray(R.array.chat_accent_colors).use {
        IntArray(it.length()) { i -> it.getColor(i, 0) }
    }

    private val colorPrimary = MaterialColors.getColor(
        this, androidx.appcompat.R.attr.colorPrimary
    )
    private val colorOnSurfaceVariant = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private var row: ContactRow.Contact? = null

    init {
        // The gap between the segments of a card
        setPadding(0, 0, 0, toPx(SEGMENTS_GAP_DP))

        inflate(context, R.layout.contact_list_item, this)
    }

    fun setRow(row: ContactRow.Contact) {
        val oldRow = this.row?.takeIf { it.contact.id == row.contact.id }
        this.row = row

        if (oldRow?.segment != row.segment) {
            updateAppearance(
                when (row.segment) {
                    ContactRow.Segment.FIRST -> POSITION_FIRST
                    ContactRow.Segment.MIDDLE -> POSITION_MIDDLE
                    ContactRow.Segment.LAST -> POSITION_LAST
                    ContactRow.Segment.SINGLE -> POSITION_SINGLE
                }
            )
        }

        val contact = row.contact
        val old = oldRow?.contact

        // Shared elements need a name, unique on screen
        avatarCardView.transitionName = "avatar_${contact.id}"

        if (old?.name != contact.name) {
            nameTextView.text = contact.name
            initialTextView.text = contact.name.firstOrNull { it.isLetterOrDigit() }
                ?.uppercase()
        }

        if (old?.accentColorId != contact.accentColorId) {
            avatarCardView.setCardBackgroundColor(
                accentColors[contact.accentColorId.mod(accentColors.size)]
            )
        }

        if (old == null || old.photoPath != contact.photoPath
            || old.minithumbnail !== contact.minithumbnail
        ) {
            when (val photo = contact.photoPath?.let(::File) ?: contact.minithumbnail) {
                null -> {
                    photoImageView.dispose()
                    photoImageView.setImageDrawable(null)
                }
                else -> photoImageView.load(photo)
            }
            initialTextView.isVisible = contact.photoPath == null && contact.minithumbnail == null
        }

        if (oldRow?.showLastSeen != row.showLastSeen) {
            lastSeenTextView.isVisible = row.showLastSeen
        }

        if (row.showLastSeen && (oldRow?.showLastSeen != true || old?.lastSeen != contact.lastSeen)) {
            lastSeenTextView.text = context.formatLastSeen(contact.lastSeen)
            lastSeenTextView.setTextColor(
                when (contact.lastSeen.kind) {
                    LastSeen.Kind.ONLINE -> colorPrimary
                    else -> colorOnSurfaceVariant
                }
            )
        }
    }

    companion object {
        private const val SEGMENTS_GAP_DP = 2
    }
}

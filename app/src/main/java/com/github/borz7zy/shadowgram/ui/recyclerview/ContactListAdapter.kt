/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView.DiffAdapter
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ContactRow
import com.github.borz7zy.shadowgram.ui.views.ContactListItemView

/**
 * Contacts list adapter: contacts and the headers of their groups.
 */
class ContactListAdapter(
    /** With the avatar, shared with the profile it opens. */
    private val onClick: (ContactRow.Contact, View) -> Unit,
) : DiffAdapter<ContactRow, RecyclerView.ViewHolder>(diffCallback) {
    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ContactRow.Header -> R.layout.contact_list_header
        is ContactRow.Contact -> R.layout.contact_list_item
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(
        when (viewType) {
            R.layout.contact_list_header -> LayoutInflater.from(parent.context).inflate(
                viewType, parent, false
            )

            else -> ContactListItemView(parent.context)
        }
    ) {
        init {
            (itemView as? ContactListItemView)?.let { view ->
                view.setOnClickListener {
                    (bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem) as? ContactRow.Contact)?.let { row ->
                        onClick(row, view.avatarView)
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ContactRow.Header -> (holder.itemView as TextView).text = row.title
            is ContactRow.Contact -> (holder.itemView as ContactListItemView).setRow(row)
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<ContactRow>() {
            override fun areItemsTheSame(oldItem: ContactRow, newItem: ContactRow) =
                oldItem.areItemsTheSame(newItem)

            override fun areContentsTheSame(oldItem: ContactRow, newItem: ContactRow) =
                oldItem.areContentsTheSame(newItem)

            // A payload makes RecyclerView rebind the same view instead of cross-fading a new
            // one, ContactListItemView then only touches the views that changed
            override fun getChangePayload(oldItem: ContactRow, newItem: ContactRow) = newItem
        }
    }
}

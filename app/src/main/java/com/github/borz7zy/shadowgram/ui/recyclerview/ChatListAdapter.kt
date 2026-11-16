/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.recyclerview

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.models.ChatRow
import com.github.borz7zy.shadowgram.ui.views.ChatListItemView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chat list adapter. Unlike a ListAdapter it owns its list, so that rows can be moved by hand
 * while dragging; diffs are still computed off the main thread.
 *
 * @param onClick Called when a row is clicked
 * @param onLongClick Called when a row is long clicked
 */
class ChatListAdapter(
    private val onClick: (row: ChatRow, viewHolder: ViewHolder) -> Unit,
    private val onLongClick: (row: ChatRow, viewHolder: ViewHolder) -> Boolean,
) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {
    inner class ViewHolder(val view: ChatListItemView) : RecyclerView.ViewHolder(view) {
        val row
            get() = items.getOrNull(bindingAdapterPosition)

        init {
            view.setOnClickListener {
                row?.let { onClick(it, this) }
            }
            view.setOnLongClickListener {
                row?.let { onLongClick(it, this) } ?: false
            }
        }
    }

    var items = listOf<ChatRow>()
        private set

    /**
     * @see ChatListItemView.collapse, applied to rows as they get bound. Rows on screen are
     * updated by whoever animates it.
     */
    var collapse = 0f

    /**
     * Replace the rows, diffing them in background. Does nothing if the rows have been moved
     * meanwhile, the caller then submits again.
     */
    suspend fun submit(newItems: List<ChatRow>) {
        val oldItems = items
        val diffResult = withContext(Dispatchers.Default) {
            DiffUtil.calculateDiff(DiffCallback(oldItems, newItems))
        }

        if (items !== oldItems) {
            return
        }

        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * Move a row, used while dragging.
     */
    fun move(fromPosition: Int, toPosition: Int) {
        items = items.toMutableList().apply {
            add(toPosition, removeAt(fromPosition))
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ChatListItemView(parent.context))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.view.setRow(items[position])
        holder.view.collapse = collapse
    }

    private class DiffCallback(
        private val oldItems: List<ChatRow>,
        private val newItems: List<ChatRow>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldItems.size

        override fun getNewListSize() = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldItems[oldItemPosition].areItemsTheSame(newItems[newItemPosition])

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldItems[oldItemPosition].areContentsTheSame(newItems[newItemPosition])

        // A payload makes RecyclerView rebind the same view instead of cross-fading a new one,
        // ChatListItemView then only touches the views that changed
        override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int) =
            newItems[newItemPosition]
    }
}

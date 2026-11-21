/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.updatePadding
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.ui.text.stringResId
import com.github.borz7zy.shadowgram.ui.views.AvatarView
import com.github.borz7zy.shadowgram.viewmodels.ChatSearchViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A chat's messages, searched as the query is typed, all of them without one; or its pinned
 * messages. A tap goes back to the chat at that message.
 */
class ChatSearchFragment : Fragment(R.layout.fragment_chat_search) {
    // View models
    private val viewModel by viewModels<ChatSearchViewModel>()

    // Views
    private val emptyTextView by getViewProperty<TextView>(R.id.emptyTextView)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val queryEditText by getViewProperty<EditText>(R.id.queryEditText)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)

    private val adapter = FoundAdapter { found ->
        setFragmentResult(REQUEST_KEY, Bundle().apply { putLong(KEY_MESSAGE_ID, found.messageId) })
        WindowCompat.getInsetsController(requireActivity().window, queryEditText)
            .hide(WindowInsetsCompat.Type.ime())
        findNavController().navigateUp()
    }

    /** Redraws the emoji whose sprite just loaded; Emoji keeps it weakly. */
    private val emojiInvalidator = Runnable { recyclerView.invalidate(); adapter.notifyItemRangeChanged(0, adapter.itemCount) }

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager
            if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                viewModel.loadMore()
            }
        }
    }

    private var emojiInvalidatorRegistered = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            v.updatePadding(windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()), bottom = true)
            windowInsets
        }

        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        when (viewModel.isPinned) {
            true -> toolbar.setTitle(R.string.chat_pinned_messages)
            false -> {
                queryEditText.isVisible = true
                queryEditText.doAfterTextChanged { viewModel.query.value = it?.toString()?.trim().orEmpty() }
                // Ready to type when it opens, not again after a rotation
                if (savedInstanceState == null) {
                    queryEditText.requestFocus()
                    WindowCompat.getInsetsController(requireActivity().window, queryEditText)
                        .show(WindowInsetsCompat.Type.ime())
                }
            }
        }

        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(onScrollListener)

        viewLifecycleOwner.lifecycleScope.launch {
            (requireContext().applicationContext as ShadowgramApplication).emojiReady.await()
            Emoji.registerInvalidator(emojiInvalidator)
            emojiInvalidatorRegistered = true
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    linearProgressIndicator.isVisible = !state.isLoaded
                    emptyTextView.isVisible = state.isLoaded && state.found.isEmpty()
                    adapter.submitList(state.found)
                }
            }
        }
    }

    override fun onDestroyView() {
        if (emojiInvalidatorRegistered) {
            Emoji.unregisterInvalidator(emojiInvalidator)
            emojiInvalidatorRegistered = false
        }
        recyclerView.removeOnScrollListener(onScrollListener)
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private class FoundAdapter(
        private val onClick: (ChatSearchViewModel.Found) -> Unit,
    ) : ListAdapter<ChatSearchViewModel.Found, FoundAdapter.ViewHolder>(
        object : DiffUtil.ItemCallback<ChatSearchViewModel.Found>() {
            override fun areItemsTheSame(oldItem: ChatSearchViewModel.Found, newItem: ChatSearchViewModel.Found) =
                oldItem.messageId == newItem.messageId

            override fun areContentsTheSame(oldItem: ChatSearchViewModel.Found, newItem: ChatSearchViewModel.Found) =
                oldItem == newItem
        }
    ) {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val avatarView: AvatarView = view.findViewById(R.id.avatarView)
            val nameTextView: TextView = view.findViewById(R.id.nameTextView)
            val dateTextView: TextView = view.findViewById(R.id.dateTextView)
            val textTextView: TextView = view.findViewById(R.id.textTextView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.chat_search_item, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val found = getItem(position)
            val context = holder.itemView.context
            holder.itemView.setOnClickListener { onClick(found) }
            holder.avatarView.setChat(
                ChatItem(
                    id = found.messageId,
                    title = found.name,
                    accentColorId = found.accentColorId,
                    photoPath = found.photoPath,
                    minithumbnail = null,
                    isPinned = false,
                    order = 0L,
                    lastMessage = null,
                    draft = null,
                    typing = null,
                    unreadCount = 0,
                    unreadMentionCount = 0,
                    isMarkedAsUnread = false,
                    isMuted = false,
                )
            )
            holder.nameTextView.text = found.name
            val millis = found.date * 1000L
            holder.dateTextView.text = when (DateUtils.isToday(millis)) {
                true -> DateFormat.getTimeFormat(context).format(millis)
                false -> DateUtils.formatDateTime(
                    context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                )
            }
            holder.textTextView.text = Emoji.replaceEmoji(
                found.text?.takeIf { it.isNotBlank() } ?: context.getString(found.kind.stringResId),
                holder.textTextView.paint.fontMetricsInt,
                false,
            )
        }
    }

    companion object {
        /** A message was picked, the chat goes to it. */
        const val REQUEST_KEY = "chat_search"
        const val KEY_MESSAGE_ID = "message_id"

        private const val LOAD_MORE_THRESHOLD = 10

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         *
         * @param isPinned The pinned messages rather than a search
         */
        fun createBundle(chatId: Long, topicId: Int, isPinned: Boolean) = Bundle().apply {
            putLong(ChatSearchViewModel.ARG_CHAT_ID, chatId)
            putInt(ChatSearchViewModel.ARG_TOPIC_ID, topicId)
            putBoolean(ChatSearchViewModel.ARG_PINNED, isPinned)
        }
    }
}

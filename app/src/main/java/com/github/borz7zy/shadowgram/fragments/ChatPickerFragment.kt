/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.updatePadding
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.ui.recyclerview.ChatListAdapter
import com.github.borz7zy.shadowgram.viewmodels.ChatListViewModel
import com.github.borz7zy.shadowgram.viewmodels.ChatPickerViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The chats of the main list to send something to: messages forwarded (the now playing screen's
 * audio), or files and text shared by another app (see [com.github.borz7zy.shadowgram.ShareActivity]),
 * which then open the picked chat.
 */
class ChatPickerFragment : Fragment(R.layout.fragment_chat_picker) {
    // View models
    private val chatListViewModel by viewModels<ChatListViewModel>()
    private val viewModel by viewModels<ChatPickerViewModel>()

    // Views
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)

    // RecyclerView
    private val adapter = ChatListAdapter(
        onClick = { row, _ -> viewModel.send(row.chat.id) },
        onLongClick = { _, _ -> false },
    )

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager
            if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                chatListViewModel.loadMore()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(insets, bottom = true)
            windowInsets
        }

        toolbar.setTitle(
            when (viewModel.isForward) {
                true -> R.string.chat_picker_forward
                false -> R.string.chat_picker_send
            }
        )
        // Opened by another app it's the only screen: back there
        toolbar.setNavigationOnClickListener {
            if (!findNavController().navigateUp()) {
                requireActivity().finish()
            }
        }

        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(onScrollListener)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatListViewModel.rows.collectLatest {
                        if (it is FlowResult.Success) {
                            adapter.submit(it.data)
                        }
                    }
                }

                launch {
                    viewModel.state.collectLatest(::onState)
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.removeOnScrollListener(onScrollListener)
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private fun onState(state: ChatPickerViewModel.State) {
        linearProgressIndicator.isVisible = state.isSending
        recyclerView.isEnabled = !state.isSending

        if (state.isFailed) {
            Toast.makeText(requireContext(), R.string.chat_picker_failed, Toast.LENGTH_SHORT).show()
        }

        val chatId = state.sentToChatId ?: return
        when (viewModel.isForward) {
            true -> {
                Toast.makeText(requireContext(), R.string.chat_picker_forwarded, Toast.LENGTH_SHORT)
                    .show()
                findNavController().navigateUp()
            }

            // Shared from another app: the chat it went to, in place of the picker
            false -> {
                findNavController().navigateSafe(
                    R.id.action_chatPickerFragment_to_shareChatActivity,
                    ChatFragment.createBundle(chatId),
                )
                requireActivity().finish()
            }
        }
    }

    companion object {
        private const val LOAD_MORE_THRESHOLD = 10

        /** Forward [messageIds] of [fromChatId]. */
        fun createForwardBundle(fromChatId: Long, messageIds: LongArray) = bundleOf(
            ChatPickerViewModel.ARG_FROM_CHAT_ID to fromChatId,
            ChatPickerViewModel.ARG_MESSAGE_IDS to messageIds,
        )

        /** Send files, [text] as the first one's caption, or the text alone. */
        fun createShareBundle(uris: List<String>, text: String?) = bundleOf(
            ChatPickerViewModel.ARG_URIS to uris.toTypedArray(),
            ChatPickerViewModel.ARG_TEXT to text,
        )
    }
}

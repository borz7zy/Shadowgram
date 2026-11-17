/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.updatePadding
import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.ui.recyclerview.ChatListAdapter
import com.github.borz7zy.shadowgram.viewmodels.ChatListViewModel
import com.github.borz7zy.shadowgram.viewmodels.ChatPickerViewModel
import com.github.borz7zy.shadowgram.viewmodels.ChatsViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The chats to send something to, a tab per chat folder: messages forwarded (a message's menu,
 * the now playing screen's audio), or files and text shared by another app (see
 * [com.github.borz7zy.shadowgram.ShareActivity]), which then open the picked chat.
 */
class ChatPickerFragment : Fragment(R.layout.fragment_chat_picker) {
    // View models
    private val chatsViewModel by viewModels<ChatsViewModel>()
    private val viewModel by viewModels<ChatPickerViewModel>()

    // Views
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val tabLayout by getViewProperty<TabLayout>(R.id.tabLayout)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    // ViewPager2: a page per folder, like the main chat list, so lists never get diffed
    // against each other
    private inner class FoldersAdapter : FragmentStateAdapter(
        childFragmentManager, viewLifecycleOwner.lifecycle
    ) {
        var folders = listOf<ChatFolder>()
            set(value) {
                if (field != value) {
                    field = value
                    @Suppress("NotifyDataSetChanged")
                    notifyDataSetChanged()
                }
            }

        override fun getItemCount() = folders.size

        override fun getItemId(position: Int) = folders[position].key.stableId

        override fun containsItem(itemId: Long) = folders.any { it.key.stableId == itemId }

        override fun createFragment(position: Int) = Page().apply {
            arguments = ChatListFragment.createBundle(folders[position].key)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        val adapter = FoldersAdapter().apply {
            // Set synchronously, so that restored pages find their folder
            folders = chatsViewModel.folders.value
        }
        viewPager2.adapter = adapter
        TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
            adapter.folders[position].title?.let { tab.text = it } ?: tab.setText(R.string.all_chats)
        }.attach()
        tabLayout.isVisible = adapter.folders.size > 1

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatsViewModel.folders.collectLatest {
                        adapter.folders = it
                        tabLayout.isVisible = it.size > 1
                    }
                }

                launch {
                    viewModel.state.collectLatest(::onState)
                }
            }
        }
    }

    override fun onDestroyView() {
        viewPager2.adapter = null

        super.onDestroyView()
    }

    private fun onState(state: ChatPickerViewModel.State) {
        linearProgressIndicator.isVisible = state.isSending

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

    /** The chats of one folder, a tap sends to the chat. */
    class Page : Fragment() {
        private val chatListViewModel by viewModels<ChatListViewModel>()
        private val pickerViewModel by viewModels<ChatPickerViewModel>({ requireParentFragment() })

        private val adapter = ChatListAdapter(
            onClick = { row, _ -> pickerViewModel.send(row.chat.id) },
            onLongClick = { _, _ -> false },
        )

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View = RecyclerView(inflater.context).apply {
            // A grid of one column, like the main chat list: its rows take the whole width
            layoutManager = GridLayoutManager(context, 1)
            clipToPadding = false
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val recyclerView = view as RecyclerView
            ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(insets, bottom = true)
                windowInsets
            }
            recyclerView.adapter = adapter
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                        chatListViewModel.loadMore()
                    }
                }
            })

            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    chatListViewModel.rows.collectLatest {
                        if (it is FlowResult.Success) {
                            adapter.submit(it.data)
                        }
                    }
                }
            }
        }

        override fun onDestroyView() {
            (view as RecyclerView).adapter = null

            super.onDestroyView()
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

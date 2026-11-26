/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.animation.ValueAnimator
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.MainActivity
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.ext.updatePadding
import com.github.borz7zy.shadowgram.models.ChatListKey
import com.github.borz7zy.shadowgram.models.ChatSelection
import com.github.borz7zy.shadowgram.models.ChatRow
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.ui.recyclerview.ChatListAdapter
import com.github.borz7zy.shadowgram.ui.views.TopRoundedLayout
import com.github.borz7zy.shadowgram.viewmodels.ChatListViewModel
import com.github.borz7zy.shadowgram.viewmodels.ChatsViewModel
import com.google.android.material.R as MaterialR
import com.google.android.material.motion.MotionUtils
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The chats of a single chat list.
 *
 * A long click selects a chat, then clicks toggle the selection and back clears it. While
 * selecting, a long click on a pinned chat drags it among the other pinned chats.
 *
 * Opening a forum collapses the list into a rail of avatars and slides its topics in next to it,
 * like Telegram does; back or the open forum's avatar brings the list back.
 */
class ChatListFragment : Fragment(R.layout.fragment_chat_list) {
    // View models
    private val viewModel by viewModels<ChatListViewModel>()
    private val chatsViewModel by activityViewModels<ChatsViewModel>()

    private val chatListKey
        get() = ChatListKey.of(
            requireArguments().getLong(ChatListViewModel.ARG_CHAT_LIST_STABLE_ID)
        )

    // Views
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val recyclerView by getViewProperty<RecyclerListView>(R.id.recyclerView)
    private val noTopicsTextView by getViewProperty<TextView>(R.id.noTopicsTextView)
    private val topicsCardView by getViewProperty<TopRoundedLayout>(R.id.topicsCardView)
    private val topicsLinearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.topicsLinearProgressIndicator)
    private val topicsRecyclerView by getViewProperty<RecyclerListView>(R.id.topicsRecyclerView)

    // RecyclerView
    private val adapter: ChatListAdapter = ChatListAdapter(
        onClick = { row, _ ->
            when {
                row.isSelectionMode -> viewModel.toggleSelection(row.chat.id)
                row.isOpenForum -> chatsViewModel.closeForum()
                row.chat.isForum -> {
                    viewModel.clearSelection()
                    chatsViewModel.openForum(chatListKey, row.chat.id)
                }

                else -> openChat(row.chat.id)
            }
        },
        onLongClick = { row, viewHolder ->
            when {
                // The rail is only for switching
                forumProgress > 0f -> return@ChatListAdapter false
                row.isSelectionMode && row.chat.isPinned -> itemTouchHelper.startDrag(viewHolder)
                else -> viewModel.toggleSelection(row.chat.id)
            }
            true
        },
    )

    private val topicsAdapter = ChatListAdapter(
        onClick = { row, _ ->
            chatsViewModel.openForum.value?.let { openChat(it.chatId, row.chat.id.toInt()) }
        },
        onLongClick = { _, _ -> false },
    )

    // Forum
    private var forumProgress = 0f
    private var paneCollapse = 0f
    private var forumAnimator: ValueAnimator? = null

    /**
     * The pinned chats order shown while dragging or until TDLib confirms it, rows with another
     * order are held back meanwhile.
     */
    private val heldPinnedChatIds = MutableStateFlow<List<Long>?>(null)

    private val itemTouchHelper: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false

        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ) = when ((viewHolder as ChatListAdapter.ViewHolder).row?.isDraggable) {
            true -> makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            else -> 0
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            // Pinned chats only move among themselves
            if ((target as ChatListAdapter.ViewHolder).row?.isDraggable != true) {
                return false
            }

            adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            heldPinnedChatIds.value = adapter.pinnedChatIds

            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // Swipe is disabled
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)

            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                (viewHolder as ChatListAdapter.ViewHolder).view.isDragged = true
                heldPinnedChatIds.value = adapter.pinnedChatIds
            }
        }

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ) {
            super.clearView(recyclerView, viewHolder)

            (viewHolder as ChatListAdapter.ViewHolder).view.isDragged = false

            val pinnedChatIds = adapter.pinnedChatIds
            heldPinnedChatIds.value = pinnedChatIds

            viewLifecycleOwner.lifecycleScope.launch {
                if (!viewModel.setPinnedChats(pinnedChatIds)) {
                    // Refused and reverted, show the list as it is
                    heldPinnedChatIds.value = null
                }
            }
        }
    })

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.clearSelection()
        }
    }

    private val forumOnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            chatsViewModel.closeForum()
        }
    }

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager

            if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                viewModel.loadMore()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Opening a chat narrows this pane without recreating it. Keep the forum avatar rail
        // and topic animation aligned with its new width as well as after folding/resizing.
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val widthDp = (right - left) / resources.displayMetrics.density
                paneCollapse = if ((activity as? MainActivity)?.isSplitChatLayout == true && widthDp > 0)
                    ((160f - widthDp) / (160f - 112f)).coerceIn(0f, 1f) else 0f
                applyForumProgress(forumProgress)
            }
        }

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            v.updatePadding(
                insets,
                start = true,
                end = true,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(topicsRecyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            v.updatePadding(insets, end = true, bottom = true)

            windowInsets
        }

        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(onScrollListener)
        itemTouchHelper.attachToRecyclerView(recyclerView)
        topicsRecyclerView.adapter = topicsAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isSelectionMode.collectLatest {
                        onBackPressedCallback.isEnabled = it
                    }
                }
                launch { viewModel.selectedChats.collectLatest { publishSelection() } }
                launch { viewModel.selectionBusy.collectLatest { publishSelection() } }
                launch { viewModel.errors.collectLatest {
                    android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                } }

                launch {
                    chatsViewModel.openForum.collectLatest {
                        val chatId = it?.takeIf { forum ->
                            forum.chatListStableId == chatListKey.stableId
                        }?.chatId

                        viewModel.setForum(chatId)
                        forumOnBackPressedCallback.isEnabled = chatId != null
                        // Animate the user's taps, restore a rotated screen as it was
                        setForumOpen(chatId != null, animate = view.isLaidOut)
                    }
                }

                launch {
                    viewModel.topicRows.collectLatest {
                        topicsLinearProgressIndicator.setProgressCompat(it)
                        if (it is FlowResult.Success) {
                            topicsAdapter.submit(it.data)
                            noTopicsTextView.isVisible = it.data.isEmpty()
                        }
                    }
                }

                loadData()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        publishSelection()

        // Only the visible chat list handles back, the others are paused by the view pager
        requireActivity().onBackPressedDispatcher.addCallback(onBackPressedCallback)
        requireActivity().onBackPressedDispatcher.addCallback(forumOnBackPressedCallback)
    }

    override fun onPause() {
        if (chatsViewModel.chatSelection.value?.key == chatListKey) chatsViewModel.chatSelection.value = null
        if (activity?.isChangingConfigurations != true) viewModel.clearSelection()
        forumOnBackPressedCallback.remove()
        onBackPressedCallback.remove()

        super.onPause()
    }

    private fun publishSelection() {
        if (!isResumed) return
        val chats = viewModel.selectedChats.value
        chatsViewModel.chatSelection.value = if (chats.isEmpty()) null else ChatSelection(
            chatListKey, chats, viewModel.selectionBusy.value, viewModel::clearSelection,
            viewModel::performSelection, viewModel::addSelectionToFolder,
        )
    }

    override fun onDestroyView() {
        forumAnimator?.cancel()
        forumAnimator = null
        topicsRecyclerView.adapter = null
        itemTouchHelper.attachToRecyclerView(null)
        recyclerView.removeOnScrollListener(onScrollListener)
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private fun openChat(chatId: Long, topicId: Int = 0) {
        (requireActivity() as MainActivity).openChat(ChatFragment.createBundle(chatId, topicId))
    }

    /**
     * Collapse the list into a rail of avatars and slide the topics in, or the other way round.
     */
    private fun setForumOpen(isOpen: Boolean, animate: Boolean) {
        val target = if (isOpen) 1f else 0f
        if (forumProgress == target && forumAnimator == null) {
            return
        }

        forumAnimator?.cancel()
        forumAnimator = null
        topicsCardView.isVisible = true

        if (!animate) {
            applyForumProgress(target)
            topicsCardView.isVisible = isOpen
            return
        }

        val context = requireContext()
        forumAnimator = ValueAnimator.ofFloat(forumProgress, target).apply {
            duration = MotionUtils.resolveThemeDuration(
                context, MaterialR.attr.motionDurationLong2, FORUM_ANIMATION_DURATION
            ).toLong()
            interpolator = MotionUtils.resolveThemeInterpolator(
                context,
                MaterialR.attr.motionEasingEmphasizedInterpolator,
                androidx.interpolator.view.animation.FastOutSlowInInterpolator(),
            )
            addUpdateListener { applyForumProgress(it.animatedValue as Float) }
            doOnEnd {
                if (forumAnimator === it) {
                    forumAnimator = null
                    topicsCardView.isVisible = isOpen
                }
            }
            start()
        }
    }

    private fun applyForumProgress(progress: Float) {
        forumProgress = progress

        val fullWidth = (recyclerView.parent as View).width
        val railWidth = resources.getDimensionPixelSize(R.dimen.chat_list_rail_width)

        val avatarsOnly = paneCollapse == 1f
        recyclerView.updateLayoutParams {
            width = when {
                progress == 0f || fullWidth == 0 -> ViewGroup.LayoutParams.MATCH_PARENT
                else -> (fullWidth + (railWidth - fullWidth) * progress).toInt()
            }
        }

        setCollapse(adapter, recyclerView, maxOf(progress, paneCollapse))
        setCollapse(topicsAdapter, topicsRecyclerView, paneCollapse)
        // When the entire pane is one avatar wide, show topic avatars in that column rather
        // than giving the forum's topic list a zero-width area beside the forum rail.
        topicsCardView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = if (avatarsOnly) 0 else railWidth
        }
        recyclerView.isVisible = adapter.items.isNotEmpty() && !(avatarsOnly && progress > 0f)

        val slide = if (avatarsOnly) 0 else (fullWidth - railWidth).coerceAtLeast(0)
        topicsCardView.translationX = slide * (1f - progress)
        topicsCardView.alpha = (progress * 2f).coerceAtMost(1f)
    }

    private fun setCollapse(adapter: ChatListAdapter, recycler: RecyclerView, collapse: Float) {
        adapter.collapse = collapse
        recycler.children.forEach {
            (recycler.getChildViewHolder(it) as ChatListAdapter.ViewHolder).view.collapse = collapse
        }
    }

    private suspend fun loadData() {
        combine(viewModel.rows, heldPinnedChatIds) { rows, heldPinnedChatIds ->
            rows to heldPinnedChatIds
        }.collectLatest { (it, heldPinnedChatIds) ->
            if (it is FlowResult.Success && heldPinnedChatIds != null) {
                when (it.data.pinnedChatIds == heldPinnedChatIds) {
                    // TDLib caught up with the new order, stop holding
                    true -> this.heldPinnedChatIds.value = null
                    // Still dragging or waiting for TDLib, keep the rows as they are
                    false -> return@collectLatest
                }
            }

            linearProgressIndicator.setProgressCompat(it)

            when (it) {
                is FlowResult.Loading -> {
                    // Do nothing
                }

                is FlowResult.Success -> {
                    val recyclerView = recyclerView
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    adapter.submit(it.data) { diff ->
                        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return@submit
                        val anchor = layoutManager.findViewByPosition(layoutManager.findFirstVisibleItemPosition())
                            ?: return@submit
                        val first = recyclerView.getChildAdapterPosition(anchor)
                        if (first !in adapter.items.indices) return@submit
                        val offset = layoutManager.getDecoratedTop(anchor) - recyclerView.paddingTop
                        val newPosition = diff.convertOldPositionToNew(first)
                        if (first == 0 && offset >= 0) {
                            if (newPosition != 0) layoutManager.scrollToPositionWithOffset(0, 0)
                        } else if (newPosition != RecyclerView.NO_POSITION && newPosition != first) {
                            layoutManager.scrollToPositionWithOffset(newPosition, offset)
                        }
                    }

                    val isEmpty = it.data.isEmpty()
                    recyclerView.isVisible = !isEmpty && !(paneCollapse == 1f && forumProgress > 0f)
                    noElementsLinearLayout.isVisible = isEmpty
                }

                is FlowResult.Failure -> {
                    Log.e(LOG_TAG, "Failed to load chats, error: ${it.error}", it.throwable)

                    recyclerView.isVisible = false
                    noElementsLinearLayout.isVisible = true
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = ChatListFragment::class.simpleName!!

        private const val LOAD_MORE_THRESHOLD = 10
        private const val FORUM_ANIMATION_DURATION = 500

        private val ChatRow.isDraggable
            get() = isSelectionMode && chat.isPinned

        private val List<ChatRow>.pinnedChatIds
            get() = filter { it.chat.isPinned }.map { it.chat.id }

        private val ChatListAdapter.pinnedChatIds
            get() = items.pinnedChatIds

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         *
         * @param chatListKey The [ChatListKey] to show
         */
        fun createBundle(chatListKey: ChatListKey) = Bundle().apply {
            putLong(ChatListViewModel.ARG_CHAT_LIST_STABLE_ID, chatListKey.stableId)
        }
    }
}

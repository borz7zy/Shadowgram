/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.MainActivity
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.setCurrentItemIfChanged
import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.viewmodels.ChatsViewModel
import com.google.android.material.motion.MotionUtils
import com.google.android.material.tabs.TabLayout
import com.google.android.material.R as MaterialR
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chat folders, each one with its own [ChatListFragment] so that lists never get diffed against
 * each other. While a forum is open the folder tabs fold away and swiping between folders stops.
 */
class ChatsFragment : Fragment(R.layout.fragment_chats) {
    // View models
    private val viewModel by activityViewModels<ChatsViewModel>()

    // Views
    private val tabLayout by getViewProperty<TabLayout>(R.id.tabLayout)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    private var hasFolders = false
    private var isForumOpen = false
    private var isAvatarOnly = false
    private var tabsAnimator: ValueAnimator? = null
    private var foldersAdapter: FoldersAdapter? = null
    private var foldersMediator: TabLayoutMediator? = null
    private var synchronizingFolder = false
    private var folderViewRestored = false
    private val folderPageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (folderViewRestored && !synchronizingFolder) {
                val folders = foldersAdapter?.folders ?: return
                // A saved custom folder may arrive after the initial Main-only snapshot.
                // An adapter restore must not overwrite that pending selection.
                val selectionPending = folders.none { it.key.stableId == viewModel.selectedFolderId.value }
                if (!selectionPending || viewPager2.scrollState != ViewPager2.SCROLL_STATE_IDLE) {
                    folders.getOrNull(position)?.let { viewModel.selectFolder(it.key) }
                }
            }
        }
    }

    // ViewPager2
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

        // Stable IDs keep each folder bound to its own fragment when folders change
        override fun getItemId(position: Int) = folders[position].key.stableId

        override fun containsItem(itemId: Long) = folders.any { it.key.stableId == itemId }

        override fun createFragment(position: Int) = ChatListFragment().apply {
            arguments = ChatListFragment.createBundle(folders[position].key)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        folderViewRestored = false

        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val currentWidth = right - left
            if (currentWidth <= 0) return@addOnLayoutChangeListener
            val compact = (activity as? MainActivity)?.isAvatarChatPane == true &&
                currentWidth <= (112 * resources.displayMetrics.density).toInt()
            if (compact != isAvatarOnly) {
                isAvatarOnly = compact
                tabsAnimator?.cancel()
                tabsAnimator = null
                tabLayout.alpha = 1f
                tabLayout.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                tabLayout.isVisible = hasFolders && !isForumOpen && !isAvatarOnly
            }
            if (oldRight - oldLeft > 0 && currentWidth != oldRight - oldLeft) viewPager2.post {
                if (this@ChatsFragment.view === view) applySelectedFolder(snap = true)
            }
        }

        val adapter = FoldersAdapter().apply {
            // Set synchronously, so that restored fragments find their folder
            folders = viewModel.folders.value
        }
        foldersAdapter = adapter
        viewPager2.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
            val folder = adapter.folders[position]

            folder.title?.let {
                tab.text = it
            } ?: tab.setText(R.string.all_chats)
        }.also { foldersMediator = it }.attach()
        applySelectedFolder()
        viewPager2.registerOnPageChangeCallback(folderPageCallback)

        onFoldersChanged(adapter.folders)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.folders.collectLatest {
                        synchronizingFolder = true
                        try {
                            adapter.folders = it
                            onFoldersChanged(it)
                            applySelectedFolder()
                        } finally {
                            synchronizingFolder = false
                        }
                    }
                }

                launch {
                    viewModel.selectedFolderId.collectLatest { applySelectedFolder() }
                }

                launch {
                    viewModel.openForum.collectLatest {
                        setForumOpen(it != null, animate = view.isLaidOut)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        tabsAnimator?.cancel()
        tabsAnimator = null
        foldersMediator?.detach()
        foldersMediator = null
        viewPager2.unregisterOnPageChangeCallback(folderPageCallback)
        viewPager2.adapter = null
        foldersAdapter = null
        folderViewRestored = false

        super.onDestroyView()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        applySelectedFolder(snap = true)
        folderViewRestored = true
    }

    private fun applySelectedFolder(snap: Boolean = false) {
        val folders = foldersAdapter?.folders?.takeIf { it.isNotEmpty() } ?: return
        var index = folders.indexOfFirst { it.key.stableId == viewModel.selectedFolderId.value }
        if (index < 0) {
            index = 0
        }
        val wasSynchronizing = synchronizingFolder
        synchronizingFolder = true
        try {
            // onPageSelected fires while a tab click/swipe is still settling. Re-selecting
            // that same item with smoothScroll=false interrupts the user's animation.
            viewPager2.setCurrentItemIfChanged(index)
            if (snap) {
                val recycler = viewPager2.getChildAt(0) as RecyclerView
                recycler.stopScroll()
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
            }
        } finally {
            synchronizingFolder = wasSynchronizing
        }
    }

    private fun onFoldersChanged(folders: List<ChatFolder>) {
        hasFolders = folders.size > 1
        tabLayout.isVisible = hasFolders && !isForumOpen && !isAvatarOnly

        // Building a chat list takes several frames, so every folder keeps its page instead of
        // getting it built again in the middle of the switch animation.
        // ponytail: all the folders stay alive, fine for the 10-20 ones Telegram allows. Cap
        // the limit and share a prewarmed RecycledViewPool if memory ever says so
        viewPager2.offscreenPageLimit = folders.size.coerceAtLeast(1)
    }

    private fun setForumOpen(isOpen: Boolean, animate: Boolean) {
        if (isForumOpen == isOpen) {
            return
        }
        isForumOpen = isOpen
        viewPager2.isUserInputEnabled = !isOpen

        tabsAnimator?.cancel()
        tabsAnimator = null

        if (!hasFolders || isAvatarOnly) {
            tabLayout.isVisible = false
            return
        }

        // Fold the tabs away by their height, so the list grows into the space smoothly
        tabLayout.isVisible = true
        tabLayout.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        tabLayout.measure(
            View.MeasureSpec.makeMeasureSpec((tabLayout.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullHeight = tabLayout.measuredHeight

        fun apply(progress: Float) {
            tabLayout.alpha = 1f - progress
            tabLayout.updateLayoutParams { height = (fullHeight * (1f - progress)).toInt() }
        }

        fun finish() {
            tabLayout.isVisible = !isOpen && !isAvatarOnly
            tabLayout.alpha = 1f
            tabLayout.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        }

        if (!animate) {
            finish()
            return
        }

        val context = requireContext()
        tabsAnimator = ValueAnimator.ofFloat(if (isOpen) 0f else 1f, if (isOpen) 1f else 0f).apply {
            duration = MotionUtils.resolveThemeDuration(
                context, MaterialR.attr.motionDurationLong2, TABS_ANIMATION_DURATION
            ).toLong()
            interpolator = MotionUtils.resolveThemeInterpolator(
                context,
                MaterialR.attr.motionEasingEmphasizedInterpolator,
                androidx.interpolator.view.animation.FastOutSlowInInterpolator(),
            )
            addUpdateListener { apply(it.animatedValue as Float) }
            doOnEnd {
                if (tabsAnimator === it) {
                    tabsAnimator = null
                    finish()
                }
            }
            start()
        }
    }

    private companion object {
        const val TABS_ANIMATION_DURATION = 500
    }
}

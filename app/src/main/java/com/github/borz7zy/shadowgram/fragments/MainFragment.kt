/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commitNow
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.search.SearchView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.MainActivity
import com.github.borz7zy.shadowgram.SettingsActivity
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.isRtl
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.scheduleHideSoftInput
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.ext.updatePadding
import com.github.borz7zy.shadowgram.models.Album
import com.github.borz7zy.shadowgram.models.Artist
import com.github.borz7zy.shadowgram.models.Audio
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.Genre
import com.github.borz7zy.shadowgram.models.MediaItem
import com.github.borz7zy.shadowgram.models.Playlist
import com.github.borz7zy.shadowgram.models.areContentsTheSame
import com.github.borz7zy.shadowgram.models.areItemsTheSame
import com.github.borz7zy.shadowgram.ui.recyclerview.SimpleListAdapter
import com.github.borz7zy.shadowgram.ui.MainSectionHost
import com.github.borz7zy.shadowgram.ui.views.ListItem
import com.github.borz7zy.shadowgram.models.ChatHeader
import com.github.borz7zy.shadowgram.ui.views.ChatHeaderView
import com.github.borz7zy.shadowgram.ui.views.ChatFolderRailView
import com.github.borz7zy.shadowgram.viewmodels.ChatsViewModel
import com.github.borz7zy.shadowgram.viewmodels.MainViewModel
import com.github.borz7zy.shadowgram.viewmodels.NewChatViewModel

/**
 * The home page.
 */
class MainFragment : Fragment(R.layout.fragment_main) {
    // View models
    private val viewModel by viewModels<MainViewModel>()
    private val chatsViewModel by activityViewModels<ChatsViewModel>()

    // Views
    private val navigationBarView by getViewProperty<NavigationBarView>(R.id.navigationBarView)
    private val fabMenuFloatingActionButton by getViewProperty<FloatingActionButton>(R.id.fabMenuFloatingActionButton)
    private val fabMenuActionsLinearLayout by getViewProperty<LinearLayout>(R.id.fabMenuActionsLinearLayout)
    private val fabMenuActionsNestedScrollView by getViewProperty<NestedScrollView>(R.id.fabMenuActionsNestedScrollView)
    private val providerMaterialButton by getViewProperty<MaterialButton>(R.id.storiesMaterialButton)
    private val searchLinearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.searchLinearProgressIndicator)
    private val searchNoElementsLinearLayout by getViewProperty<LinearLayout>(R.id.searchNoElementsLinearLayout)
    private val searchRecyclerView by getViewProperty<RecyclerView>(R.id.searchRecyclerView)
    private val searchView by getViewProperty<SearchView>(R.id.searchView)
    private val settingsMaterialButton by getViewProperty<MaterialButton>(R.id.settingsMaterialButton)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val forumHeaderView by getViewProperty<ChatHeaderView>(R.id.forumHeaderView)
    private val mainSectionContainer by getViewProperty<View>(R.id.mainSectionContainer)

    private val hasNavigationRail get() = navigationBarView is NavigationRailView
    private var synchronizingNavigation = false

    private var selectedPage = Page.ACTIVITY
    private var sectionHost: MainSectionHost? = null

    fun showChats() {
        showSection(Page.ACTIVITY)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPage = Page.entries.firstOrNull {
            it.name == savedInstanceState?.getString(STATE_SELECTED_PAGE)
        } ?: Page.ACTIVITY
    }

    // System services
    private val inputMethodManager: InputMethodManager
        get() = requireContext().getSystemService(InputMethodManager::class.java)

    // New chat menu
    private var isFabMenuOpen = false

    /**
     * The scrims of the content and of the bars, together they dim the whole screen but the menu.
     */
    private val fabMenuScrimViews
        get() = listOf(
            R.id.fabMenuScrimView,
            R.id.fabMenuAppBarScrimView,
            R.id.fabMenuNavigationBarScrimView,
        ).map { requireView().findViewById<View>(it) }

    private val fabMenuOnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setFabMenuOpen(false)
        }
    }

    // RecyclerView
    private val searchAdapter by lazy {
        object : SimpleListAdapter<MediaItem<*>, ListItem>(
            searchDiffCallback,
            ::ListItem
        ) {
            override fun ViewHolder.onBindView(item: MediaItem<*>) {
                view.setOnLongClickListener {
                    findNavController().navigateSafe(
                        R.id.action_mainFragment_to_fragment_media_item_bottom_sheet_dialog,
                        MediaItemBottomSheetDialogFragment.createBundle(item.uri)
                    )
                    true
                }

                when (item) {
                    is Album -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_album,
                                AlbumFragment.createBundle(item.uri)
                            )
                        }

                        view.setTrailingIconImage(R.drawable.ic_album)
                        view.headlineText = item.title
                        view.supportingText = item.artistName
                    }

                    is Artist -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_artist,
                                ArtistFragment.createBundle(item.uri)
                            )
                        }

                        view.setTrailingIconImage(R.drawable.ic_person)
                        view.headlineText = item.name
                        view.supportingText = null
                    }

                    is Audio -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_media_item_bottom_sheet_dialog,
                                MediaItemBottomSheetDialogFragment.createBundle(item.uri)
                            )
                        }

                        view.setTrailingIconImage(R.drawable.ic_music_note)
                        view.headlineText = item.title
                        view.supportingText = item.artistName
                    }

                    is Genre -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_genre,
                                GenreFragment.createBundle(item.uri)
                            )
                        }

                        view.setTrailingIconImage(R.drawable.ic_genres)
                        view.headlineText = item.name
                        view.supportingText = null
                    }

                    is Playlist -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_playlist,
                                PlaylistFragment.createBundle(item.uri)
                            )
                        }

                        view.setTrailingIconImage(R.drawable.ic_playlist_play)
                        view.headlineText = item.name
                        view.supportingText = null
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCompactPane()
        }
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.contentCoordinatorLayout)) { _, insets ->
            updateCompactPane(insets)
            insets
        }

        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateToolbarActions()
        }

        view.findViewById<MaterialButton>(R.id.hideNavigationRailButton)?.setOnClickListener {
            (activity as? MainActivity)?.setNavigationRailHidden(true)
        }
        view.findViewById<MaterialButton>(R.id.showNavigationRailButton)?.setOnClickListener {
            (activity as? MainActivity)?.setNavigationRailHidden(false)
        }
        view.findViewById<ChatFolderRailView>(R.id.chatFolderRailView)?.onFolderSelected = chatsViewModel::selectFolder

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                start = !hasNavigationRail,
                end = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(navigationBarView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            (view.findViewById<View>(R.id.navigationRailContainer) ?: v).updatePadding(
                insets,
                start = hasNavigationRail,
                top = hasNavigationRail,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(mainSectionContainer) { v, windowInsets ->
            val displayCutoutInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.displayCutout()
            )
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            fun adjustInsets(insets: Insets) = Insets.of(
                when (!v.isRtl && hasNavigationRail) {
                    true -> 0
                    false -> insets.left
                },
                insets.top,
                when (v.isRtl && hasNavigationRail) {
                    true -> 0
                    false -> insets.right
                },
                when (hasNavigationRail) {
                    true -> insets.bottom
                    false -> 0
                },
            )

            WindowInsetsCompat.Builder(windowInsets)
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    adjustInsets(systemBarsInsets),
                )
                .setInsets(
                    WindowInsetsCompat.Type.displayCutout(),
                    adjustInsets(displayCutoutInsets),
                )
                .build()
        }

        ViewCompat.setOnApplyWindowInsetsListener(searchRecyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                start = true,
                end = true,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(searchNoElementsLinearLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                start = true,
                end = true,
                bottom = true,
            )

            windowInsets
        }

        // On back pressed
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(searchView.isShowing) {
                override fun handleOnBackStarted(backEvent: BackEventCompat) {
                    searchView.startBackProgress(backEvent)
                }

                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    searchView.updateBackProgress(backEvent)
                }

                override fun handleOnBackPressed() {
                    searchView.handleBackInvoked()
                }

                override fun handleOnBackCancelled() {
                    searchView.cancelBackProgress()
                }
            }.also {
                searchView.addTransitionListener { searchView, _, newState ->
                    val isShowing = newState in listOf(
                        SearchView.TransitionState.SHOWN,
                        SearchView.TransitionState.SHOWING,
                    )

                    it.isEnabled = isShowing

                    // Clear search query if hidden
                    if (!isShowing) {
                        searchView.clearText()
                    }
                }
            }
        )

        toolbar.setupWithNavController(findNavController())

        providerMaterialButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_mainFragment_to_fragment_provider_selector_dialog
            )
        }
        providerMaterialButton.setOnLongClickListener {
            viewModel.navigationProvider.value?.let {
                findNavController().navigateSafe(
                    R.id.action_mainFragment_to_fragment_provider_information_bottom_sheet_dialog,
                    ManageProviderFragment.createBundle(providerIdentifier = it.identifier),
                )
                true
            } ?: false
        }

        settingsMaterialButton.setOnClickListener {
            val intent = Intent(context, SettingsActivity::class.java)
            startActivity(intent)
        }

        // New chat menu
        isFabMenuOpen = false
        fabMenuOnBackPressedCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, fabMenuOnBackPressedCallback
        )

        // Scrolling the actions must not hide the menu like scrolling the page does.
        // Can't be set from XML: View's constructor would call it before the view is ready
        fabMenuActionsNestedScrollView.isNestedScrollingEnabled = false

        fabMenuFloatingActionButton.setOnClickListener {
            setFabMenuOpen(!isFabMenuOpen)
        }
        fabMenuScrimViews.forEach {
            it.setOnClickListener {
                setFabMenuOpen(false)
            }
        }

        mapOf(
            R.id.addContactMaterialButton to NewChatViewModel.Action.ADD_CONTACT,
            R.id.createGroupMaterialButton to NewChatViewModel.Action.CREATE_GROUP,
            R.id.createChannelMaterialButton to NewChatViewModel.Action.CREATE_CHANNEL,
        ).forEach { (viewId, action) ->
            view.findViewById<View>(viewId).setOnClickListener {
                setFabMenuOpen(false)
                findNavController().navigateSafe(
                    R.id.action_mainFragment_to_fragment_new_chat_dialog,
                    NewChatDialogFragment.createBundle(action),
                )
            }
        }
        view.findViewById<View>(R.id.addStoryMaterialButton).setOnClickListener {
            // Stories aren't there yet
            setFabMenuOpen(false)
        }
        view.findViewById<View>(R.id.newDialogMaterialButton).setOnClickListener {
            // A dialog starts from a contact
            navigationBarView.selectedItemId = R.id.contactsFragment
        }

        // Discard pages saved by the old top-level ViewPager2 before they create their views.
        // Their dynamically generated page containers no longer exist after an app update.
        val legacyPages = childFragmentManager.fragments.filter { fragment ->
            Page.entries.any { fragment.tag == "f${it.ordinal}" }
        }
        if (legacyPages.isNotEmpty()) childFragmentManager.commitNow {
            legacyPages.forEach(::remove)
        }
        sectionHost = MainSectionHost(childFragmentManager, R.id.mainSectionContainer)

        navigationBarView.setOnItemSelectedListener { item ->
            if (synchronizingNavigation) return@setOnItemSelectedListener true
            setFabMenuOpen(false)

            val page = Page.entries.firstOrNull { it.menuItemId == item.itemId }
            page != null && showSection(page)
        }
        showSection(selectedPage)

        // Search
        searchRecyclerView.adapter = searchAdapter

        searchView.editText.addTextChangedListener { text ->
            viewModel.setSearchQuery(text.toString())
        }
        searchView.editText.setOnEditorActionListener { _, _, _ ->
            inputMethodManager.scheduleHideSoftInput(searchView.editText, 0)
            searchView.editText.clearFocus()
            viewModel.setSearchQuery(searchView.editText.text.toString(), true)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatsViewModel.forumHeader.collectLatest { header ->
                        setForumHeader(header.takeIf { selectedPage == Page.ACTIVITY })
                    }
                }

                launch {
                    chatsViewModel.folders.collectLatest { renderFolderRail() }
                }
                launch {
                    chatsViewModel.selectedFolderId.collectLatest { renderFolderRail() }
                }

                launch {
                    viewModel.navigationProvider.collectLatest {
                        it?.let {
                            providerMaterialButton.text = it.name
                            providerMaterialButton.setIconResource(it.type.iconDrawableResId)
                        } ?: run {
                            providerMaterialButton.setText(R.string.no_provider)
                            providerMaterialButton.setIconResource(R.drawable.ic_warning)
                        }
                    }
                }

                launch {
                    viewModel.searchResults.collectLatest {
                        searchLinearProgressIndicator.setProgressCompat(it)

                        when (it) {
                            is FlowResult.Loading -> {
                                // Do nothing
                            }

                            is FlowResult.Success -> {
                                searchAdapter.submitList(it.data)

                                val isEmpty = it.data.isEmpty()
                                searchRecyclerView.isVisible = !isEmpty
                                searchNoElementsLinearLayout.isVisible =
                                    isEmpty && searchView.editText.text.isNotEmpty()
                            }

                            is FlowResult.Failure -> {
                                Log.e(
                                    LOG_TAG,
                                    "Failed to load search results, error: ${it.error}",
                                    it.throwable
                                )

                                searchAdapter.submitList(listOf())

                                searchRecyclerView.isVisible = false
                                searchNoElementsLinearLayout.isVisible = true
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Older versions only stored the menu's checked item in the view hierarchy.
        val restoredPage = if (savedInstanceState?.containsKey(STATE_SELECTED_PAGE) == true) selectedPage
            else Page.entries.firstOrNull { it.menuItemId == navigationBarView.selectedItemId } ?: selectedPage
        showSection(restoredPage)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_PAGE, selectedPage.name)
        super.onSaveInstanceState(outState)
    }

    private fun showSection(page: Page): Boolean {
        if (sectionHost?.show("main_section_${page.name}", create = page.createFragment) != true) return false
        selectedPage = page
        synchronizingNavigation = true
        try {
            navigationBarView.selectedItemId = page.menuItemId
        } finally {
            synchronizingNavigation = false
        }
        (activity as? MainActivity)?.onMainSectionSelected(page == Page.ACTIVITY)
        setForumHeader(chatsViewModel.forumHeader.value.takeIf { page == Page.ACTIVITY })
        ViewCompat.requestApplyInsets(mainSectionContainer)
        return true
    }

    override fun onDestroyView() {
        sectionHost = null

        // Search
        searchRecyclerView.adapter = null

        super.onDestroyView()
    }

    /**
     * Show or hide the new chat actions above their button, dimming the screen behind them.
     */
    /**
     * While a forum is open the top bar is its header: back, avatar, title and member count
     * instead of the app title and buttons.
     */
    private fun setForumHeader(header: ChatHeader?) {
        val isOpen = header != null
        header?.let(forumHeaderView::setHeader)

        if (forumHeaderView.isVisible == isOpen) {
            return
        }

        settingsMaterialButton.isVisible = !isOpen
        forumHeaderView.isVisible = isOpen
        updateToolbarActions()

        if (isOpen) {
            toolbar.title = null
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationContentDescription(R.string.chat_back)
            toolbar.setNavigationIconTint(
                MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
            )
            toolbar.setNavigationOnClickListener { chatsViewModel.closeForum() }

            forumHeaderView.alpha = 0f
            forumHeaderView.animate().alpha(1f).setDuration(FORUM_HEADER_FADE_MS).start()
        } else {
            toolbar.title = findNavController().currentDestination?.label
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        }
    }

    private fun updateToolbarActions() {
        // The list pane can be much narrower than the window on an unfolded phone.
        providerMaterialButton.isVisible = !forumHeaderView.isVisible &&
            toolbar.width >= (360 * resources.displayMetrics.density).toInt()
    }

    private fun updateCompactPane(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(requireView())) {
        val content = requireView().findViewById<View>(R.id.contentCoordinatorLayout)
        val compact = (activity as? MainActivity)?.isAvatarChatPane == true
        val hidden = compact && (activity as? MainActivity)?.isNavigationRailHidden == true
        requireView().findViewById<View>(R.id.appBarLayout).isVisible = !compact
        fabMenuFloatingActionButton.isVisible = !compact
        if (compact && isFabMenuOpen) setFabMenuOpen(false)
        val rail = requireView().findViewById<View>(R.id.navigationRailContainer)
        val header = requireView().findViewById<View>(R.id.compactNavigationHeader)
        if (rail != null && header != null) {
            rail.isVisible = !hidden
            header.isVisible = hidden
            requireView().findViewById<View>(R.id.hideNavigationRailButton).isVisible = compact
            requireView().findViewById<View>(R.id.foldersRailScrollView).isVisible = compact
            requireView().findViewById<View>(R.id.foldersRailDivider).isVisible = compact
            val targetHeight = if (compact) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            val targetWeight = if (compact) 0f else 1f
            val navParams = navigationBarView.layoutParams as LinearLayout.LayoutParams
            if (navParams.height != targetHeight || navParams.weight != targetWeight) {
                navigationBarView.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = targetHeight
                    weight = targetWeight
                }
            }
            val targetTop = if (hidden) R.id.compactNavigationHeader else R.id.appBarLayout
            if ((content.layoutParams as ConstraintLayout.LayoutParams).topToBottom != targetTop) {
                content.updateLayoutParams<ConstraintLayout.LayoutParams> { topToBottom = targetTop }
            }
            val headerTop = if (hidden) insets?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )?.top ?: 0 else 0
            header.setPadding(0, headerTop, 0, 0)
        }
        // Without the app bar, the first avatar still starts below the status bar/cutout.
        val top = if (compact && !hidden) insets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )?.top ?: 0 else 0
        content.setPadding(content.paddingLeft, top, content.paddingRight, content.paddingBottom)
    }

    private fun renderFolderRail() {
        val folders = chatsViewModel.folders.value
        val selected = chatsViewModel.selectedFolderId.value
        view?.findViewById<ChatFolderRailView>(R.id.chatFolderRailView)?.submit(
            folders, selected.takeIf { id -> folders.any { it.key.stableId == id } }
                ?: folders.firstOrNull()?.key?.stableId ?: selected,
        )
    }

    private fun setFabMenuOpen(open: Boolean) {
        if (isFabMenuOpen == open) {
            return
        }
        isFabMenuOpen = open
        fabMenuOnBackPressedCallback.isEnabled = open

        // The plus becomes a cross
        fabMenuFloatingActionButton.animate().rotation(if (open) 45f else 0f)

        fabMenuScrimViews.forEach { scrimView ->
            if (open) {
                scrimView.isVisible = true
            }
            scrimView.animate()
                .alpha(if (open) 1f else 0f)
                .withEndAction { scrimView.isVisible = isFabMenuOpen }
        }

        // The actions slide out of the button, the closest one first
        fabMenuActionsLinearLayout.children
            .toList()
            .asReversed()
            .forEachIndexed { index, action ->
                val offset = action.resources.displayMetrics.density *
                        FAB_MENU_ACTION_OFFSET_DP * (index + 1)

                if (open) {
                    action.alpha = 0f
                    action.translationY = offset
                    action.isVisible = true
                }
                action.animate()
                    .alpha(if (open) 1f else 0f)
                    .translationY(if (open) 0f else offset)
                    .withEndAction { action.isVisible = isFabMenuOpen }
            }

        if (open) {
            // When the actions don't fit, start from the ones next to the button
            val scrollView = fabMenuActionsNestedScrollView
            scrollView.post {
                scrollView.scrollTo(0, scrollView.getChildAt(0).height)
            }
        }
    }

    private enum class Page(
        @param:IdRes val menuItemId: Int,
        val createFragment: () -> Fragment,
    ) {
        ACTIVITY(R.id.activityFragment, ::ChatsFragment),
        CONTACTS(R.id.contactsFragment, ::ContactsFragment),
        LIBRARY(R.id.libraryFragment, ::LibraryFragment),
    }

    companion object {
        private const val STATE_SELECTED_PAGE = "selected_main_section"
        private const val FORUM_HEADER_FADE_MS = 200L

        private val LOG_TAG = MainFragment::class.simpleName!!

        private const val FAB_MENU_ACTION_OFFSET_DP = 24

        private val searchDiffCallback = object : DiffUtil.ItemCallback<MediaItem<*>>() {
            override fun areItemsTheSame(
                oldItem: MediaItem<*>,
                newItem: MediaItem<*>
            ) = when (oldItem) {
                is Album -> oldItem.areItemsTheSame(newItem)
                is Artist -> oldItem.areItemsTheSame(newItem)
                is Audio -> oldItem.areItemsTheSame(newItem)
                is Genre -> oldItem.areItemsTheSame(newItem)
                is Playlist -> oldItem.areItemsTheSame(newItem)
            }

            override fun areContentsTheSame(
                oldItem: MediaItem<*>,
                newItem: MediaItem<*>
            ) = when (oldItem) {
                is Album -> oldItem.areContentsTheSame(newItem)
                is Artist -> oldItem.areContentsTheSame(newItem)
                is Audio -> oldItem.areContentsTheSame(newItem)
                is Genre -> oldItem.areContentsTheSame(newItem)
                is Playlist -> oldItem.areContentsTheSame(newItem)
            }
        }
    }
}

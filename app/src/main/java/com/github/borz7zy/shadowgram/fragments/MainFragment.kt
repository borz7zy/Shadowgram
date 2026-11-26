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
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.search.SearchView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ChatListKey
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.ChatBulkAction
import com.github.borz7zy.shadowgram.models.ChatSelection
import com.github.borz7zy.shadowgram.models.chatHistoryClearAction
import com.github.borz7zy.shadowgram.models.ChatHistoryClearAction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.github.borz7zy.shadowgram.telegram.ChatsStore
import com.github.borz7zy.shadowgram.ui.cells.AppTitleCell
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
    private val searchRecyclerView by getViewProperty<RecyclerListView>(R.id.searchRecyclerView)
    private val searchView by getViewProperty<SearchView>(R.id.searchView)
    private val settingsMaterialButton by getViewProperty<MaterialButton>(R.id.settingsMaterialButton)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val appTitleCell by getViewProperty<AppTitleCell>(R.id.appTitleCell)
    private val forumHeaderView by getViewProperty<ChatHeaderView>(R.id.forumHeaderView)
    private val mainSectionContainer by getViewProperty<View>(R.id.mainSectionContainer)

    private val hasNavigationRail get() = navigationBarView is NavigationRailView
    private var synchronizingNavigation = false

    private var selectedPage = Page.ACTIVITY
    private var sectionHost: MainSectionHost? = null

    internal val isShowingSettings get() = selectedPage == Page.SETTINGS

    internal fun showSettings() {
        if (hasNavigationRail && (activity as? MainActivity)?.canUseSettingsPanes == true) {
            showSection(Page.SETTINGS)
        } else {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    fun showChats() {
        showSection(Page.ACTIVITY)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val section = savedInstanceState?.getString(STATE_SELECTED_PAGE)
        selectedPage = when (section) {
            "LIBRARY" -> Page.PROFILE
            else -> Page.entries.firstOrNull { it.name == section } ?: Page.ACTIVITY
        }
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

    private val settingsOnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val pane = childFragmentManager.primaryNavigationFragment as? SettingsActivity.SettingsPaneFragment
            if (pane?.popSection() != true) showChats()
        }
    }

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

        toolbar.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            updateToolbarActions()
            if (right - left != oldRight - oldLeft && chatsViewModel.chatSelection.value != null) renderChatSelection()
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

        chatsViewModel.appTitle.value.let { appTitleCell.bind(it.connectionState, it.emojiStatusId) }
        toolbar.setOnMenuItemClickListener { item ->
            val selection = chatsViewModel.chatSelection.value ?: return@setOnMenuItemClickListener false
            if (selection.busy) return@setOnMenuItemClickListener true
            when (item.itemId) {
                R.id.chats_archive -> selection.perform(ChatBulkAction.ARCHIVE)
                R.id.chats_mute -> selection.perform(ChatBulkAction.MUTE)
                R.id.chats_delete -> confirmChatAction(selection, ChatBulkAction.DELETE)
                R.id.chats_pin -> selection.perform(ChatBulkAction.PIN)
                R.id.chats_unread -> selection.perform(ChatBulkAction.UNREAD)
                R.id.chats_clear -> confirmChatAction(selection, ChatBulkAction.CLEAR)
                R.id.chats_folder -> if (selection.key is ChatListKey.Folder) selection.perform(ChatBulkAction.REMOVE_FOLDER) else chooseFolder(selection)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

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
            showSettings()
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
            fragment.tag == "main_section_LIBRARY" || Page.entries.any { fragment.tag == "f${it.ordinal}" }
        }
        if (legacyPages.isNotEmpty()) childFragmentManager.commitNow {
            legacyPages.forEach(::remove)
        }
        sectionHost = MainSectionHost(childFragmentManager, R.id.mainSectionContainer)

        if (hasNavigationRail || selectedPage == Page.SETTINGS) {
            navigationBarView.menu.add(0, R.id.mainSettingsSection, 2, R.string.title_activity_settings)
                .setIcon(R.drawable.ic_settings)
        }
        navigationBarView.setOnItemSelectedListener { item ->
            if (synchronizingNavigation) return@setOnItemSelectedListener true
            setFabMenuOpen(false)

            val page = Page.entries.firstOrNull { it.menuItemId == item.itemId }
            if (page == Page.ACTIVITY || page == Page.CONTACTS) {
                (activity as? MainActivity)?.closeSettingsDetail()
            }
            page != null && showSection(page)
        }
        showSection(selectedPage)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, settingsOnBackPressedCallback)

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
                    combine(chatsViewModel.folderState, chatsViewModel.selectedFolderId, ::Pair)
                        .collectLatest { (state, selected) -> renderFolderRail(state, selected) }
                }
                launch {
                    chatsViewModel.appTitle.collectLatest { appTitleCell.bind(it.connectionState, it.emojiStatusId) }
                }
                launch { chatsViewModel.chatSelection.collectLatest { renderChatSelection() } }

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
        renderChatSelection()
        updateCompactPane()
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
        if (selectedPage == Page.ACTIVITY && chatsViewModel.chatSelection.value != null) return
        val isOpen = header != null
        header?.let(forumHeaderView::setHeader)

        if (forumHeaderView.isVisible == isOpen) {
            return
        }

        settingsMaterialButton.isVisible = !isOpen
        forumHeaderView.isVisible = isOpen
        appTitleCell.isVisible = !isOpen
        updateToolbarActions()

        if (isOpen) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationContentDescription(R.string.chat_back)
            toolbar.setNavigationIconTint(
                MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
            )
            toolbar.setNavigationOnClickListener { chatsViewModel.closeForum() }

            forumHeaderView.alpha = 0f
            forumHeaderView.animate().alpha(1f).setDuration(FORUM_HEADER_FADE_MS).start()
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        }
    }

    private fun updateToolbarActions() {
        // The list pane can be much narrower than the window on an unfolded phone.
        providerMaterialButton.isVisible = chatsViewModel.chatSelection.value == null && !forumHeaderView.isVisible &&
            toolbar.width >= (360 * resources.displayMetrics.density).toInt()
    }

    private fun updateCompactPane(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(requireView())) {
        val content = requireView().findViewById<View>(R.id.contentCoordinatorLayout)
        val main = activity as? MainActivity
        val compact = main?.isAvatarChatPane == true
        settingsOnBackPressedCallback.isEnabled = selectedPage == Page.SETTINGS && main?.hasOpenChat != true
        val hidden = compact && main?.isNavigationRailHidden == true
        val selecting = selectedPage == Page.ACTIVITY && chatsViewModel.chatSelection.value != null
        val hasOwnHeader = selectedPage == Page.PROFILE || selectedPage == Page.SETTINGS
        requireView().findViewById<View>(R.id.appBarLayout).isVisible = !hasOwnHeader && (!compact || selecting)
        fabMenuFloatingActionButton.isVisible = !hasOwnHeader && !compact && !selecting
        if ((hasOwnHeader || compact) && isFabMenuOpen) setFabMenuOpen(false)
        val rail = requireView().findViewById<View>(R.id.navigationRailContainer)
        val header = requireView().findViewById<View>(R.id.compactNavigationHeader)
        if (rail != null && header != null) {
            rail.isVisible = !hidden
            header.isVisible = hidden
            requireView().findViewById<View>(R.id.hideNavigationRailButton).isVisible = compact
            val showFolders = selectedPage == Page.ACTIVITY && !hidden &&
                chatsViewModel.folderState.value.folders.size > 1
            requireView().findViewById<View>(R.id.foldersRailScrollView).isVisible = showFolders
            requireView().findViewById<View>(R.id.foldersRailDivider).isVisible = showFolders
            val targetHeight = if (showFolders || compact) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            val targetWeight = if (showFolders || compact) 0f else 1f
            (childFragmentManager.primaryNavigationFragment as? ChatsFragment)?.updateFolderRail(showFolders)
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

    private fun renderChatSelection() {
        val selection = chatsViewModel.chatSelection.value.takeIf { selectedPage == Page.ACTIVITY }
        if (selection == null) {
            toolbar.menu.clear()
            val header = chatsViewModel.forumHeader.value.takeIf { selectedPage == Page.ACTIVITY }
            forumHeaderView.isVisible = header != null
            header?.let(forumHeaderView::setHeader)
            appTitleCell.isVisible = header == null
            toolbar.title = null
            if (header == null) toolbar.navigationIcon = null else toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationContentDescription(R.string.chat_back)
            toolbar.setNavigationOnClickListener { if (header != null) chatsViewModel.closeForum() else findNavController().navigateUp() }
            settingsMaterialButton.isVisible = header == null
            updateToolbarActions()
            updateCompactPane()
            return
        }
        if (isFabMenuOpen) setFabMenuOpen(false)
        forumHeaderView.isVisible = false
        appTitleCell.isVisible = false
        providerMaterialButton.isVisible = false
        settingsMaterialButton.isVisible = false
        toolbar.title = getString(R.string.selection_count, selection.chats.size)
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationContentDescription(R.string.selection_cancel)
        toolbar.setNavigationOnClickListener { selection.cancel() }
        if (toolbar.menu.findItem(R.id.chats_archive) == null) toolbar.inflateMenu(R.menu.chat_list_selection)
        toolbar.menu.findItem(R.id.chats_archive).setTitle(if (selection.key == ChatListKey.Archive) R.string.chats_unarchive else R.string.chats_archive)
        val muted = selection.chats.all { it.isMuted }
        toolbar.menu.findItem(R.id.chats_mute).apply {
            setTitle(if (muted) R.string.chat_unmute else R.string.chat_mute)
            setIcon(if (muted) R.drawable.ic_notifications else R.drawable.ic_notifications_off)
        }
        toolbar.menu.findItem(R.id.chats_pin).setTitle(if (selection.chats.all { it.isPinned }) R.string.chats_unpin else R.string.chats_pin)
        toolbar.menu.findItem(R.id.chats_folder).setTitle(if (selection.key is ChatListKey.Folder) R.string.chats_remove_folder else R.string.chats_add_folder)
        toolbar.menu.findItem(R.id.chats_clear).apply {
            val clear = chatHistoryClearAction(selection.chats)
            isVisible = clear != null
            setTitle(if (clear == ChatHistoryClearAction.CACHE) R.string.chats_clear_cache else R.string.chats_clear_history)
        }
        for (i in 0 until toolbar.menu.size()) toolbar.menu.getItem(i).apply {
            isEnabled = !selection.busy
            iconTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface))
            val primary = itemId == R.id.chats_archive || itemId == R.id.chats_mute || itemId == R.id.chats_delete
            val room = toolbar.width >= toolbar.toPx(360) * resources.configuration.fontScale.coerceAtLeast(1f)
            setShowAsAction(if (primary && room) android.view.MenuItem.SHOW_AS_ACTION_ALWAYS else android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        }
        updateCompactPane()
    }

    private fun confirmChatAction(selection: ChatSelection, action: ChatBulkAction) {
        val cache = action == ChatBulkAction.CLEAR && selection.chats.all { it.clearsCacheOnly }
        val title = if (action == ChatBulkAction.DELETE) R.string.selection_delete else if (cache) R.string.chats_clear_cache else R.string.chats_clear_history
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(getString(if (action == ChatBulkAction.DELETE) R.string.chats_delete_confirm else if (cache) R.string.chats_cache_confirm else R.string.chats_clear_confirm, selection.chats.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(title) { _, _ -> selection.perform(action) }
            .show()
    }

    private fun chooseFolder(selection: ChatSelection) {
        val folders = chatsViewModel.folderState.value.folders.filter { it.key is ChatListKey.Folder }
        if (folders.isEmpty()) {
            android.widget.Toast.makeText(context, R.string.chats_no_folders, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.chats_add_folder)
            .setItems(folders.map { it.title.orEmpty() }.toTypedArray()) { _, index ->
                selection.addToFolder((folders[index].key as ChatListKey.Folder).id)
            }.show()
    }

    private fun renderFolderRail(state: ChatsStore.FolderSnapshot, selected: Long?) {
        val folders = state.folders
        view?.findViewById<ChatFolderRailView>(R.id.chatFolderRailView)?.submit(
            folders, selected.takeIf { id -> folders.any { it.key.stableId == id } }
                ?: folders.firstOrNull()?.key?.stableId ?: ChatListKey.Main.stableId,
            state.unread,
        )
        view?.let { updateCompactPane() }
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
        PROFILE(R.id.libraryFragment, ProfileFragment::createOwnProfile),
        SETTINGS(R.id.mainSettingsSection, { SettingsActivity.SettingsPaneFragment() }),
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

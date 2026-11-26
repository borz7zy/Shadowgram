/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.commitNow
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.slideDown
import com.github.borz7zy.shadowgram.ext.slideUp
import com.github.borz7zy.shadowgram.ext.updateBarsVisibility
import com.github.borz7zy.shadowgram.fragments.AlbumFragment
import com.github.borz7zy.shadowgram.fragments.ArtistFragment
import com.github.borz7zy.shadowgram.fragments.GenreFragment
import com.github.borz7zy.shadowgram.fragments.ChatPaneNavHostFragment
import com.github.borz7zy.shadowgram.fragments.MainFragment
import com.github.borz7zy.shadowgram.fragments.NowPlayingDialogFragment
import com.github.borz7zy.shadowgram.fragments.PlaylistFragment
import com.github.borz7zy.shadowgram.models.MediaType
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.notifications.ShadowgramFirebaseMessagingService
import com.github.borz7zy.shadowgram.ui.views.NowPlayingBar
import com.github.borz7zy.shadowgram.ui.views.AdaptiveChatLayout
import com.github.borz7zy.shadowgram.viewmodels.ChatViewModel
import com.github.borz7zy.shadowgram.viewmodels.FullscreenViewModel
import com.github.borz7zy.shadowgram.viewmodels.IntentsViewModel
import com.github.borz7zy.shadowgram.viewmodels.NowPlayingViewModel
import kotlin.reflect.cast

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // View models
    private val fullscreenViewModel by viewModels<FullscreenViewModel>()
    private val intentsViewModel by viewModels<IntentsViewModel>()
    private val nowPlayingViewModel by viewModels<NowPlayingViewModel>()

    // Views
    private val frameLayout by lazy { findViewById<FrameLayout>(R.id.linearLayout) }
    private val nowPlayingBar by lazy { findViewById<NowPlayingBar>(R.id.nowPlayingBar) }
    private val chatPanes by lazy { findViewById<AdaptiveChatLayout>(R.id.chatPanes) }
    private val settingsDetailPane by lazy { findViewById<FrameLayout>(R.id.settingsDetailPane) }
    private val chatPane by lazy { findViewById<FrameLayout>(R.id.chatPane) }
    private val settingsBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val detail = supportFragmentManager.findFragmentByTag(SETTINGS_DETAIL_TAG)
                as? SettingsActivity.SettingsPaneFragment
            if (detail?.popSection() != true) closeSettingsDetail()
        }
    }
    private var chatArguments: Bundle? = null
    private var pendingChatArguments: Bundle? = null
    private var closingChat = false
    private var closeChatOnResume = false

    // NavController
    private val navHostFragment by lazy {
        NavHostFragment::class.cast(
            supportFragmentManager.findFragmentById(R.id.navHostFragment)
        )
    }
    private val navController by lazy { navHostFragment.navController }

    // Intents
    private val intentListener = Consumer<Intent> {
        intentsViewModel.onIntent(it)
        handleChatIntent(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        chatArguments = savedInstanceState?.getBundle(STATE_CHAT_ARGUMENTS)
        chatPanes.chatOpen = chatArguments != null
        val detailRestored = supportFragmentManager.findFragmentByTag(SETTINGS_DETAIL_TAG) != null
        showSettingsDetailPane(detailRestored)
        chatPanes.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                ViewCompat.requestApplyInsets(frameLayout)
                frameLayout.post(::updateMainPaneLifecycle)
            }
        }

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout) { _, windowInsets ->
            val navigation = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val taskbar = windowInsets.getInsets(
                WindowInsetsCompat.Type.tappableElement() or WindowInsetsCompat.Type.mandatorySystemGestures()
            )
            val bottom = if (chatPanes.isWideWindow &&
                windowInsets.isVisible(WindowInsetsCompat.Type.navigationBars())
            ) maxOf(navigation.bottom, taskbar.bottom) else 0
            chatPanes.updatePadding(bottom = bottom)
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val systemBarsInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            nowPlayingBar.setContentPadding(
                insets.left,
                0,
                insets.right,
                systemBarsInsets.bottom,
            )

            // This translates to bottom padding + now playing bar height
            WindowInsetsCompat.Builder(windowInsets)
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(
                        insets.left,
                        insets.top,
                        insets.right,
                        when (nowPlayingBar.isVisible) {
                            true -> nowPlayingBar.measuredHeight
                            false -> insets.bottom
                        }
                    )
                )
                .build()
        }

        // Intents
        intentsViewModel.onIntent(intent)
        addOnNewIntentListener(intentListener)
        if (savedInstanceState == null) handleChatIntent(intent)

        // Now playing bar
        nowPlayingBar.setOnClickListener {
            navigateToNowPlayingFragment()
        }

        nowPlayingBar.setOnPlayPauseClickListener {
            nowPlayingViewModel.togglePlayPause()
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
            }
        }

        val app = application as ShadowgramApplication
        app.backgroundScope.launch { ShadowgramFirebaseMessagingService.scheduleRegistration(app) }
        lifecycleScope.launch { requestNotificationPermission() }
    }

    private suspend fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return

        val alreadyAsked = withContext(Dispatchers.IO) {
            getPreferences(MODE_PRIVATE).getBoolean("notification_permission_asked", false)
        }
        if (!alreadyAsked) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            getPreferences(MODE_PRIVATE).edit { putBoolean("notification_permission_asked", true) }
        }
    }

    override fun onDestroy() {
        removeOnNewIntentListener(intentListener)
        if (closingChat) supportFragmentManager.findFragmentByTag(CHAT_HOST_TAG)?.view?.animate()?.cancel()
        settingsBack.remove()

        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (closingChat && !supportFragmentManager.isStateSaved) {
            supportFragmentManager.findFragmentByTag(CHAT_HOST_TAG)?.let { completeCloseChat(it, animate = false) }
        }
        outState.putBundle(STATE_CHAT_ARGUMENTS, chatArguments)
        super.onSaveInstanceState(outState)
    }

    override fun onPostResume() {
        super.onPostResume()
        updateMainPaneLifecycle()
        if (chatPanes.settingsDetailOpen) prioritizeSettingsBack()
        if (closeChatOnResume) {
            closeChatOnResume = false
            closeChat()
        }
        pendingChatArguments?.let {
            pendingChatArguments = null
            openChat(it)
        }
    }

    private fun handleChatIntent(intent: Intent) {
        intent.extras?.takeIf { it.containsKey(ChatViewModel.ARG_CHAT_ID) }?.let(::openChat)
    }

    /** All entry points (including profiles and the share picker) use the same chat pane. */
    fun openChat(arguments: Bundle) {
        if (supportFragmentManager.isStateSaved) {
            pendingChatArguments = Bundle(arguments)
            return
        }
        NowPlayingDialogFragment.close(this)
        if (closingChat) {
            supportFragmentManager.findFragmentByTag(CHAT_HOST_TAG)?.let { completeCloseChat(it, animate = false) }
        }
        navController.popBackStack(R.id.mainFragment, false)
        navHostFragment.childFragmentManager.executePendingTransactions()
        val main = navHostFragment.childFragmentManager.primaryNavigationFragment as? MainFragment
        if (!chatPanes.isWideWindow || main?.isShowingSettings != true) main?.showChats()
        closeSettingsDetail()

        val current = chatArguments
        if (current != null &&
            current.getLong(ChatViewModel.ARG_CHAT_ID) == arguments.getLong(ChatViewModel.ARG_CHAT_ID) &&
            current.getInt(ChatViewModel.ARG_TOPIC_ID) == arguments.getInt(ChatViewModel.ARG_TOPIC_ID)
        ) {
            (supportFragmentManager.findFragmentByTag(CHAT_HOST_TAG) as? NavHostFragment)
                ?.navController?.popBackStack(R.id.chatFragment, false)
            return
        }

        val host = ChatPaneNavHostFragment().apply { this.arguments = Bundle(arguments) }
        chatArguments = Bundle(arguments)
        chatPanes.chatOpen = true
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            if (chatPanes.isWideWindow) {
                setCustomAnimations(R.animator.pane_content_enter, R.animator.pane_content_exit)
            }
            // Keep the left pane interactive on wide screens and the phone's covered pane paused.
            setMaxLifecycle(navHostFragment, if (chatPanes.isWideWindow) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
            replace(R.id.chatPane, host, CHAT_HOST_TAG)
            setPrimaryNavigationFragment(host)
        }
        if (!chatPanes.isWideWindow) host.view?.apply {
            // One animator owns phone enter/exit, so a quick Back can cancel enter cleanly.
            alpha = 0f
            translationX = resources.getDimension(R.dimen.chat_phone_translation)
            animate().alpha(1f).translationX(0f)
                .setDuration(resources.getInteger(R.integer.chat_phone_enter_duration).toLong())
                .setInterpolator(AnimationUtils.loadInterpolator(this@MainActivity, android.R.interpolator.fast_out_slow_in))
                .withLayer().start()
        }
    }

    fun closeChat() {
        val host = supportFragmentManager.findFragmentByTag(CHAT_HOST_TAG) ?: return
        if (closingChat) return
        if (supportFragmentManager.isStateSaved) {
            closeChatOnResume = true
            return
        }
        currentFocus?.let {
            androidx.core.view.WindowCompat.getInsetsController(window, it)
                .hide(WindowInsetsCompat.Type.ime())
            it.clearFocus()
        }
        val chatView = host.view
        if (!chatPanes.isWideWindow && chatView != null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            closingChat = true
            chatView.animate().cancel()
            chatView.animate().alpha(0f)
                .translationX(resources.getDimension(R.dimen.chat_phone_translation))
                .setDuration(resources.getInteger(R.integer.chat_phone_exit_duration).toLong())
                .setInterpolator(AnimationUtils.loadInterpolator(this, android.R.interpolator.fast_out_slow_in))
                .withLayer()
                .withEndAction { completeCloseChat(host, animate = false) }
                .start()
        } else completeCloseChat(host, animate = chatPanes.isWideWindow)
    }

    private fun completeCloseChat(host: Fragment, animate: Boolean) {
        closingChat = false
        host.view?.animate()?.cancel()
        if (supportFragmentManager.isStateSaved || isDestroyed) {
            closeChatOnResume = !isDestroyed
            return
        }
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            if (animate) setCustomAnimations(R.animator.pane_content_enter, R.animator.pane_content_exit)
            remove(host)
            setPrimaryNavigationFragment(navHostFragment)
            setMaxLifecycle(navHostFragment, Lifecycle.State.RESUMED)
        }
        chatArguments = null
        chatPanes.chatOpen = false
    }

    internal val reservedNavigationBottom get() = chatPanes.paddingBottom

    internal val canUseSettingsPanes get() = chatPanes.isWideWindow

    internal fun openSettingsDetail(arguments: Bundle): Boolean {
        if (!canUseSettingsPanes || supportFragmentManager.isStateSaved) return false
        val current = supportFragmentManager.findFragmentByTag(SETTINGS_DETAIL_TAG)
        val detail = SettingsActivity.SettingsPaneFragment().apply { this.arguments = Bundle(arguments) }
        val chat = supportFragmentManager.findFragmentByTag(CHAT_HOST_TAG)
        currentFocus?.let {
            androidx.core.view.WindowCompat.getInsetsController(window, it).hide(WindowInsetsCompat.Type.ime())
            it.clearFocus()
        }
        showSettingsDetailPane(true)
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            setCustomAnimations(R.animator.pane_content_enter, R.animator.pane_content_exit)
            if (current != null) remove(current)
            if (chat != null) setMaxLifecycle(chat, Lifecycle.State.STARTED)
            add(R.id.settingsDetailPane, detail, SETTINGS_DETAIL_TAG)
            setPrimaryNavigationFragment(detail)
        }
        prioritizeSettingsBack()
        return true
    }

    internal fun closeSettingsDetail() {
        val detail = supportFragmentManager.findFragmentByTag(SETTINGS_DETAIL_TAG) ?: return
        if (supportFragmentManager.isStateSaved) return
        val chat = supportFragmentManager.findFragmentByTag(CHAT_HOST_TAG)
        showSettingsDetailPane(false)
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            remove(detail)
            if (chat != null) setMaxLifecycle(chat, Lifecycle.State.RESUMED)
            setPrimaryNavigationFragment(chat ?: navHostFragment)
        }
    }

    private fun showSettingsDetailPane(open: Boolean) {
        chatPanes.settingsDetailOpen = open
        settingsDetailPane.isVisible = open
        // Keep the chat view and its composer attached while the setting covers them.
        chatPane.visibility = if (open) View.INVISIBLE else View.VISIBLE
        if (!open) settingsBack.remove()
    }

    private fun prioritizeSettingsBack() {
        settingsBack.remove()
        settingsBack.isEnabled = true
        onBackPressedDispatcher.addCallback(settingsBack)
    }

    private fun updateMainPaneLifecycle() {
        if (isDestroyed || supportFragmentManager.isStateSaved ||
            (!chatPanes.chatOpen && !chatPanes.settingsDetailOpen)
        ) return
        val covered = !chatPanes.isWideWindow
        supportFragmentManager.commitNow {
            setMaxLifecycle(navHostFragment, if (covered) Lifecycle.State.STARTED else Lifecycle.State.RESUMED)
        }
    }

    fun onMainSectionSelected(isChats: Boolean) {
        chatPanes.isChatListSelected = isChats
    }

    internal val hasOpenChat get() = chatPanes.chatOpen
    val isAvatarChatPane get() = chatPanes.isAvatarOnly
    val isSplitChatLayout get() = chatPanes.isSplitChatLayout
    val isNavigationRailHidden get() = chatPanes.isNavigationRailHidden

    fun setNavigationRailHidden(hidden: Boolean) {
        chatPanes.navigationRailHidden = hidden
    }

    private fun CoroutineScope.loadData() {
        launch {
            WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity).collectLatest { info ->
                    chatPanes.separatingHinge = info.displayFeatures.filterIsInstance<FoldingFeature>()
                        .firstOrNull {
                            it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL
                        }?.bounds
                }
        }

        launch {
            fullscreenViewModel.fullscreenMode.collectLatest {
                window.updateBarsVisibility(systemBars = !it)
            }
        }

        launch {
            intentsViewModel.parsedIntent.collectLatest { parsedIntent ->
                parsedIntent?.handle {
                    when (it.action) {
                        IntentsViewModel.ParsedIntent.Action.MAIN -> {
                            // We don't need to do anything
                        }

                        IntentsViewModel.ParsedIntent.Action.OPEN_NOW_PLAYING -> {
                            navigateToNowPlayingFragment()
                        }

                        IntentsViewModel.ParsedIntent.Action.VIEW -> {
                            if (it.contents.isEmpty()) {
                                Log.i(LOG_TAG, "No content to view")
                                return@handle
                            }

                            val isSingleItem = it.contents.size == 1
                            if (!isSingleItem) {
                                Log.i(LOG_TAG, "Cannot handle multiple items")
                                return@handle
                            }

                            val content = it.contents.first()

                            when (content.type) {
                                MediaType.ALBUM -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_album,
                                    AlbumFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )

                                MediaType.ARTIST -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_artist,
                                    ArtistFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )

                                MediaType.AUDIO -> Log.i(LOG_TAG, "Audio not supported")

                                MediaType.GENRE -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_genre,
                                    GenreFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )

                                MediaType.PLAYLIST -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_playlist,
                                    PlaylistFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )
                            }
                        }
                    }
                }
            }
        }

        launch {
            nowPlayingViewModel.durationCurrentPositionMs.collectLatest {
                nowPlayingBar.updateDurationCurrentPositionMs(it.first, it.second)
            }
        }

        launch {
            nowPlayingViewModel.isPlaying.collectLatest {
                nowPlayingBar.updateIsPlaying(it)
            }
        }

        // The bottom bar stays hidden: PlaybackBarView under the top bars shows what plays

        launch {
            nowPlayingViewModel.mediaMetadata.collectLatest {
                nowPlayingBar.updateMediaMetadata(it)
            }
        }

        launch {
            nowPlayingViewModel.mediaArtwork.collectLatest {
                when (it) {
                    null -> {
                        // Do nothing
                    }

                    is Result.Success -> {
                        nowPlayingBar.updateMediaArtwork(it.data)
                    }

                    is Result.Failure -> {
                        Log.e(
                            LOG_TAG,
                            "Error while getting media artwork: ${it.error}",
                            it.throwable
                        )
                    }
                }
            }
        }
    }

    private fun navigateToNowPlayingFragment() = NowPlayingDialogFragment.open(this)

    companion object {
        private const val STATE_CHAT_ARGUMENTS = "open_chat_arguments"
        private const val SETTINGS_DETAIL_TAG = "settings_detail_pane"
        private const val CHAT_HOST_TAG = "chat_pane_nav_host"
        private val LOG_TAG = MainActivity::class.simpleName!!

        /**
         * Open now playing fragment.
         * Type: [Boolean]
         */
        const val EXTRA_OPEN_NOW_PLAYING = "extra_now_playing"
    }
}

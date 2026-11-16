/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.slideDown
import com.github.borz7zy.shadowgram.ext.slideUp
import com.github.borz7zy.shadowgram.ext.updateBarsVisibility
import com.github.borz7zy.shadowgram.fragments.AlbumFragment
import com.github.borz7zy.shadowgram.fragments.ArtistFragment
import com.github.borz7zy.shadowgram.fragments.GenreFragment
import com.github.borz7zy.shadowgram.fragments.PlaylistFragment
import com.github.borz7zy.shadowgram.models.MediaType
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.ui.views.NowPlayingBar
import com.github.borz7zy.shadowgram.viewmodels.FullscreenViewModel
import com.github.borz7zy.shadowgram.viewmodels.IntentsViewModel
import com.github.borz7zy.shadowgram.viewmodels.NowPlayingViewModel
import kotlin.reflect.cast

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    // View models
    private val fullscreenViewModel by viewModels<FullscreenViewModel>()
    private val intentsViewModel by viewModels<IntentsViewModel>()
    private val nowPlayingViewModel by viewModels<NowPlayingViewModel>()

    // Views
    private val frameLayout by lazy { findViewById<FrameLayout>(R.id.linearLayout) }
    private val nowPlayingBar by lazy { findViewById<NowPlayingBar>(R.id.nowPlayingBar) }

    // NavController
    private val navHostFragment by lazy {
        NavHostFragment::class.cast(
            supportFragmentManager.findFragmentById(R.id.navHostFragment)
        )
    }
    private val navController by lazy { navHostFragment.navController }

    // Intents
    private val intentListener = Consumer<Intent> { intentsViewModel.onIntent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout) { _, windowInsets ->
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
    }

    override fun onDestroy() {
        removeOnNewIntentListener(intentListener)

        super.onDestroy()
    }

    private fun CoroutineScope.loadData() {
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

    private fun navigateToNowPlayingFragment() = navController.navigate(
        R.id.fragment_now_playing,
        null,
        NavOptions.Builder()
            .setPopUpTo(R.id.fragment_now_playing, true)
            .build(),
    )

    companion object {
        private val LOG_TAG = MainActivity::class.simpleName!!

        /**
         * Open now playing fragment.
         * Type: [Boolean]
         */
        const val EXTRA_OPEN_NOW_PLAYING = "extra_now_playing"
    }
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.ext.updatePadding
import com.github.borz7zy.shadowgram.models.ActivityTab
import com.github.borz7zy.shadowgram.models.Album
import com.github.borz7zy.shadowgram.models.Artist
import com.github.borz7zy.shadowgram.models.Audio
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.Genre
import com.github.borz7zy.shadowgram.models.Playlist
import com.github.borz7zy.shadowgram.ui.recyclerview.SimpleListAdapter
import com.github.borz7zy.shadowgram.ui.recyclerview.UniqueItemDiffCallback
import com.github.borz7zy.shadowgram.ui.views.ActivityTabView
import com.github.borz7zy.shadowgram.utils.PermissionsChecker
import com.github.borz7zy.shadowgram.utils.PermissionsUtils
import com.github.borz7zy.shadowgram.viewmodels.ActivityViewModel

/**
 * User activity, notifications and recommendations.
 */
class ActivityFragment : Fragment(R.layout.fragment_activity) {
    // View models
    private val viewModel by viewModels<ActivityViewModel>()

    // Views
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)

    // RecyclerView
    private val adapter by lazy {
        object : SimpleListAdapter<ActivityTab, ActivityTabView>(
            UniqueItemDiffCallback(),
            ::ActivityTabView,
        ) {
            override fun ViewHolder.onBindView(item: ActivityTab) {
                view.setOnItemClickListener { mediaItem ->
                    when (mediaItem) {
                        is Album -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_album,
                            AlbumFragment.createBundle(mediaItem.uri)
                        )

                        is Artist -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_artist,
                            ArtistFragment.createBundle(mediaItem.uri)
                        )

                        is Audio -> viewModel.playAudio(listOf(mediaItem), 0)

                        is Genre -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_genre,
                            GenreFragment.createBundle(mediaItem.uri)
                        )

                        is Playlist -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_playlist,
                            PlaylistFragment.createBundle(mediaItem.uri)
                        )
                    }
                }
                view.setOnItemLongClickListener { mediaItem ->
                    findNavController().navigateSafe(
                        R.id.action_mainFragment_to_fragment_media_item_bottom_sheet_dialog,
                        MediaItemBottomSheetDialogFragment.createBundle(mediaItem.uri)
                    )
                    true
                }

                view.setActivityTab(item)
            }
        }
    }

    // Permissions
    private val permissionsChecker = PermissionsChecker(
        this, PermissionsUtils.mainPermissions
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsChecker.withPermissionsGranted {
                    loadData()
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private suspend fun loadData() {
        viewModel.activity.collectLatest {
            linearProgressIndicator.setProgressCompat(it)

            when (it) {
                is FlowResult.Loading -> {
                    // Do nothing
                }

                is FlowResult.Success -> {
                    val data = it.data

                    adapter.submitList(data)

                    val isEmpty = it.data.isEmpty()
                    recyclerView.isVisible = !isEmpty
                    noElementsLinearLayout.isVisible = isEmpty
                }

                is FlowResult.Failure -> {
                    Log.e(LOG_TAG, "Failed to load activity, error: ${it.error}", it.throwable)

                    recyclerView.isVisible = false
                    noElementsLinearLayout.isVisible = true
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = ActivityFragment::class.simpleName!!
    }
}

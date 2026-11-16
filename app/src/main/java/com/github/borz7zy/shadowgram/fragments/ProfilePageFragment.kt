/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.ProfileItem
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import com.github.borz7zy.shadowgram.ui.recyclerview.ProfileItemAdapter
import com.github.borz7zy.shadowgram.viewmodels.PhotoViewerViewModel
import com.github.borz7zy.shadowgram.viewmodels.ProfilePageViewModel
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A tab of a profile, its own list: a grid of media cells or rows. The voice tab has voice and
 * video messages, picked with a toggle on top.
 */
class ProfilePageFragment : Fragment(R.layout.fragment_profile_page) {
    // View models
    private val viewModel by viewModels<ProfilePageViewModel>()

    // Views
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val voiceToggleGroup by getViewProperty<MaterialButtonToggleGroup>(R.id.voiceToggleGroup)

    // RecyclerView
    private val adapter = ProfileItemAdapter { item, image ->
        item.track?.let {
            MessagePlayer.play(it)
            return@ProfileItemAdapter
        }
        item.document?.let {
            Downloads.onTap(requireContext(), it) { viewModel.toggleDownload(it) }
            return@ProfileItemAdapter
        }
        when (viewModel.kind.value) {
            ProfilePage.Kind.GROUPS -> findNavController().navigateSafe(
                R.id.action_profileFragment_to_chatActivity,
                ChatFragment.createBundle(item.chatId),
            )

            ProfilePage.Kind.MEMBERS -> findNavController().navigateSafe(
                R.id.action_profileFragment_to_profileActivity,
                ProfileFragment.createBundle(item.chatId),
            )

            ProfilePage.Kind.MEDIA -> openViewer(item, image)

            // ponytail: links don't open yet
            else -> {}
        }
    }

    private val viewerAnchor by activityViewModels<PhotoViewerViewModel.Anchor>()

    /** The cell whose picture the viewer shows meanwhile, hidden here; null for none. */
    private var viewerKey: String? = null

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val layoutManager = recyclerView.layoutManager as GridLayoutManager
            if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                viewModel.loadMore()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets: the list goes under the navigation bar
        recyclerView.doOnAttach {
            val insets = ViewCompat.getRootWindowInsets(it)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars()) ?: return@doOnAttach
            it.updatePadding(bottom = it.paddingTop + insets.bottom)
        }

        recyclerView.layoutManager = GridLayoutManager(requireContext(), SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = when (adapter.kind.isGrid) {
                    true -> 1
                    false -> SPAN_COUNT
                }
            }
        }
        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(onScrollListener)

        val isVoice = viewModel.kind.value == ProfilePage.Kind.VOICE_NOTES
                || viewModel.kind.value == ProfilePage.Kind.VIDEO_NOTES
        voiceToggleGroup.isVisible = isVoice
        if (isVoice) {
            voiceToggleGroup.check(
                when (viewModel.kind.value) {
                    ProfilePage.Kind.VIDEO_NOTES -> R.id.videoNotesMaterialButton
                    else -> R.id.voiceNotesMaterialButton
                }
            )
            voiceToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    viewModel.kind.value = when (checkedId) {
                        R.id.videoNotesMaterialButton -> ProfilePage.Kind.VIDEO_NOTES
                        else -> ProfilePage.Kind.VOICE_NOTES
                    }
                }
            }
        }

        // The viewer is the profile's dialog, its results go to the profile; one tab has media
        val profileFragmentManager = requireParentFragment().parentFragmentManager
        if (viewModel.kind.value == ProfilePage.Kind.MEDIA) {
            profileFragmentManager.setFragmentResultListener(
                PhotoViewerDialogFragment.REQUEST_KEY_SHOWN, viewLifecycleOwner
            ) { _, bundle ->
                // The viewer went to another one: that one is hidden now
                pictureOf(viewerKey)?.alpha = 1f
                viewerKey = bundle.getLong(PhotoViewerDialogFragment.KEY_MESSAGE_ID).toString()
                pictureOf(viewerKey)?.alpha = 0f
            }
            profileFragmentManager.setFragmentResultListener(
                PhotoViewerDialogFragment.REQUEST_KEY, viewLifecycleOwner
            ) { _, _ ->
                pictureOf(viewerKey)?.alpha = 1f
                viewerKey = null
                viewerAnchor.drawable = null
                viewerAnchor.locate = null
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.items.collectLatest(::setItems)
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.removeOnScrollListener(onScrollListener)
        recyclerView.adapter = null

        super.onDestroyView()
    }

    /** The media loaded so far, from [image] of [item]: its key is its message id. */
    private fun openViewer(item: ProfileItem, image: ImageView) {
        val messageId = item.key.toLongOrNull() ?: return
        if (viewerKey != null) {
            return
        }
        viewerKey = item.key
        viewerAnchor.drawable = image.drawable?.constantState?.newDrawable(resources) ?: image.drawable
        viewerAnchor.frame = PhotoViewerDialogFragment.frameOf(image)
        viewerAnchor.locate = { id -> pictureOf(id.toString())?.let(PhotoViewerDialogFragment::frameOf) }
        findNavController().navigateSafe(
            R.id.action_profileFragment_to_photoViewerDialogFragment,
            PhotoViewerDialogFragment.createBundle(
                requireArguments().getLong(ProfilePageViewModel.ARG_CHAT_ID),
                messageId,
                adapter.currentList.mapNotNull { it.key.toLongOrNull() }.toLongArray(),
            ),
        )
    }

    /** The picture of the cell [key] on screen, null if it isn't. */
    private fun pictureOf(key: String?): ImageView? {
        val position = adapter.currentList.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return null
        return recyclerView.findViewHolderForAdapterPosition(position)
            ?.itemView?.findViewById<ImageView>(R.id.imageView)
    }

    private fun setItems(result: FlowResult<ProfilePageViewModel.Items, Nothing>) {
        linearProgressIndicator.setProgressCompat(result)

        if (result !is FlowResult.Success) {
            return
        }

        val (kind, items) = result.data
        if (kind != adapter.kind) {
            // Another kind is another list, with other cells
            adapter.kind = kind
            adapter.submitList(null)
        }
        adapter.submitList(items)

        noElementsLinearLayout.isVisible = items.isEmpty()
    }

    companion object {
        private const val SPAN_COUNT = 3
        private const val LOAD_MORE_THRESHOLD = 15

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         *
         * @param chatId The chat of the profile
         * @param kind What the tab shows
         */
        fun createBundle(chatId: Long, kind: ProfilePage.Kind) = Bundle().apply {
            putLong(ProfilePageViewModel.ARG_CHAT_ID, chatId)
            putString(ProfilePageViewModel.ARG_KIND, kind.name)
        }
    }
}

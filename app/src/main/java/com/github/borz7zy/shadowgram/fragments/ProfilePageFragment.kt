/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
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
import com.github.borz7zy.rustcore.RustMedia
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.MainActivity
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.ProfileItem
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import com.github.borz7zy.shadowgram.ui.recyclerview.ProfileItemAdapter
import com.github.borz7zy.shadowgram.viewmodels.PhotoViewerViewModel
import com.github.borz7zy.shadowgram.viewmodels.ProfilePageViewModel
import com.github.borz7zy.shadowgram.viewmodels.ProfileViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

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
    private val recyclerView by getViewProperty<RecyclerListView>(R.id.recyclerView)
    private val voiceToggleGroup by getViewProperty<MaterialButtonToggleGroup>(R.id.voiceToggleGroup)
    private val giftCollectionsScrollView by getViewProperty<HorizontalScrollView>(R.id.giftCollectionsScrollView)
    private val giftCollectionsGroup by getViewProperty<ChipGroup>(R.id.giftCollectionsGroup)
    private val giftRetryButton by getViewProperty<MaterialButton>(R.id.giftRetryButton)

    // RecyclerView
    private val profileViewModel by viewModels<ProfileViewModel>({ requireParentFragment() })

    private val adapter = ProfileItemAdapter(::showPinActions) { item, image ->
        item.track?.let {
            MessagePlayer.play(it)
            return@ProfileItemAdapter
        }
        item.document?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                Downloads.onTap(requireContext(), it) { viewModel.toggleDownload(it) }
            }
            return@ProfileItemAdapter
        }
        when (viewModel.kind.value) {
            ProfilePage.Kind.GROUPS -> (activity as? MainActivity)?.openChat(ChatFragment.createBundle(item.chatId))
                ?: findNavController().navigateSafe(
                    R.id.action_profileFragment_to_chatActivity,
                    ChatFragment.createBundle(item.chatId),
                )

            ProfilePage.Kind.MEMBERS -> findNavController().navigateSafe(
                R.id.action_profileFragment_to_profileActivity,
                ProfileFragment.createBundle(item.chatId),
            )

            ProfilePage.Kind.MEDIA -> openViewer(item, image)
            ProfilePage.Kind.POSTS, ProfilePage.Kind.ARCHIVED_POSTS -> {
                val storyId = item.key.toLongOrNull() ?: return@ProfileItemAdapter
                findNavController().navigateSafe(
                    R.id.action_profileFragment_to_photoViewerDialogFragment,
                    PhotoViewerDialogFragment.createBundle(
                        requireArguments().getLong(ProfilePageViewModel.ARG_CHAT_ID),
                        storyId,
                        longArrayOf(storyId),
                        isStory = true,
                    ),
                )
            }

            // ponytail: links don't open yet
            else -> {}
        }
    }

    private fun showPinActions(item: ProfileItem): Boolean {
        val actions = buildList<Pair<Int, suspend () -> Result<TdApi.Ok, TdApi.Error>>> {
            when (val action = item.profileAction) {
                is ProfileItem.ProfileAction.Story -> {
                    if (action.posted && action.canPin && viewModel.kind.value == ProfilePage.Kind.POSTS) {
                        add((if (item.isPinned) R.string.message_unpin else R.string.message_pin) to {
                            profileViewModel.setProfileStoryPinned(action.id, !item.isPinned)
                        })
                    }
                    if (action.canChangeVisibility) {
                        add((if (action.posted) R.string.profile_hide_item else R.string.profile_show_item) to {
                            profileViewModel.setStoryOnProfile(action.id, !action.posted)
                        })
                    }
                }
                is ProfileItem.ProfileAction.Gift -> {
                    if (action.upgraded && action.saved) {
                        add((if (item.isPinned) R.string.message_unpin else R.string.message_pin) to {
                            profileViewModel.setProfileGiftPinned(action.id, !item.isPinned)
                        })
                    }
                    add((if (action.saved) R.string.profile_hide_item else R.string.profile_show_item) to {
                        profileViewModel.setGiftOnProfile(action.id, !action.saved)
                    })
                }
                null -> Unit
            }
        }
        if (actions.isEmpty()) return false
        MaterialAlertDialogBuilder(requireContext())
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = actions[index].second()
                    if (result is Result.Failure) android.widget.Toast.makeText(
                        requireContext(), result.error.message, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
        return true
    }

    private val viewerAnchor by activityViewModels<PhotoViewerViewModel.Anchor>()

    /** The cell whose picture the viewer shows meanwhile, hidden here; null for none. */
    private var viewerKey: String? = null

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            loadNextPageIfNeeded(recyclerView)
        }
    }

    private fun loadNextPageIfNeeded(list: RecyclerView) {
        val layoutManager = list.layoutManager as GridLayoutManager
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible != RecyclerView.NO_POSITION && lastVisible >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
            viewModel.loadMore()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bottomPadding = recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { list, insets ->
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            list.updatePadding(bottom = bottomPadding + bottom)
            insets
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

        if (viewModel.kind.value == ProfilePage.Kind.GIFTS) {
            recyclerView.itemAnimator = null
            recyclerView.setHasFixedSize(true)
            giftRetryButton.setOnClickListener { viewModel.loadMore(retry = true) }
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.giftCollections.collect(::setGiftCollections)
                }
            }
        }

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
                viewerAnchor.restore = null
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.items.collectLatest(::setItems) }
                launch { profileViewModel.contentRevision.collect(viewModel::refresh) }
            }
        }
    }

    override fun onDestroyView() {
        if (viewerKey != null) {
            pictureOf(viewerKey)?.alpha = 1f
            viewerAnchor.locate = null
            viewerAnchor.restore = null
        }
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
        viewerAnchor.restore = {
            pictureOf(viewerKey)?.alpha = 1f
            recyclerView
        }
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
            if (viewModel.kind.value == ProfilePage.Kind.GIFTS) adapter.submitList(null)
            noElementsLinearLayout.isVisible = false
            giftRetryButton.isVisible = false
            return
        }

        val (kind, items, collectionId, giftLoadFailed) = result.data
        if (kind == ProfilePage.Kind.GIFTS && collectionId != viewModel.selectedGiftCollectionId.value) return
        if (kind != adapter.kind) {
            // Another kind is another list, with other cells
            adapter.kind = kind
            adapter.submitList(null)
        }
        adapter.submitList(items) {
            if (kind == ProfilePage.Kind.GIFTS && !giftLoadFailed && view != null) {
                recyclerView.doOnLayout { loadNextPageIfNeeded(it as RecyclerView) }
            }
        }

        giftRetryButton.isVisible = giftLoadFailed
        noElementsLinearLayout.isVisible = items.isEmpty() && !giftLoadFailed
    }

    private fun setGiftCollections(collections: List<TdApi.GiftCollection>) {
        giftCollectionsScrollView.isVisible = collections.isNotEmpty()
        if (collections.isEmpty() || giftCollectionsGroup.childCount > 0) return

        addGiftCollectionChip(0, getString(R.string.profile_gifts_all))
        collections.forEach { collection ->
            val chip = addGiftCollectionChip(collection.id, collection.name)
            if (collection.icon != null) {
                val size = resources.getDimensionPixelSize(R.dimen.profile_gift_collection_icon_size)
                viewLifecycleOwner.lifecycleScope.launch {
                    val path = viewModel.giftCollectionIcon(collection) ?: return@launch
                    val bitmap = withContext(Dispatchers.IO) { RustMedia.decode(File(path), size, size) }
                        ?: return@launch
                    chip.chipIcon = BitmapDrawable(resources, bitmap)
                }
            }
        }
    }

    private fun addGiftCollectionChip(collectionId: Int, name: String): Chip {
        val chip = layoutInflater.inflate(
            R.layout.profile_gift_collection_chip, giftCollectionsGroup, false,
        ) as Chip
        chip.id = View.generateViewId()
        chip.text = name
        chip.isChecked = collectionId == viewModel.selectedGiftCollectionId.value
        giftCollectionsGroup.addView(chip)
        if (chip.isChecked) {
            chip.doOnLayout { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height)) }
        }
        chip.setOnClickListener {
            if (collectionId != viewModel.selectedGiftCollectionId.value) {
                viewModel.selectGiftCollection(collectionId)
                adapter.submitList(null)
                recyclerView.scrollToPosition(0)
            }
        }
        return chip
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

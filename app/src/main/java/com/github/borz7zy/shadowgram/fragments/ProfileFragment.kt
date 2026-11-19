/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.transition.TransitionInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.borz7zy.rustcore.RustMedia
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.MainActivity
import com.github.borz7zy.shadowgram.ProfileActivity
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.navigateBackInPane
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import com.github.borz7zy.shadowgram.ui.text.subtitleText
import com.github.borz7zy.shadowgram.ui.views.AvatarView
import com.github.borz7zy.shadowgram.ui.views.CustomEmojiView
import com.github.borz7zy.shadowgram.ui.views.ProfileCoverView
import com.github.borz7zy.shadowgram.viewmodels.PhotoViewerViewModel
import com.github.borz7zy.shadowgram.viewmodels.ProfileViewModel
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The profile of a user, group or channel, laid out like the contacts app: the big avatar, the
 * name, action buttons and details, then the shared media tabs, each its own page.
 */
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    // View models
    private val viewModel by viewModels<ProfileViewModel>()

    // Views
    private val aboutLabelTextView by getViewProperty<TextView>(R.id.aboutLabelTextView)
    private val aboutLinearLayout by getViewProperty<LinearLayout>(R.id.aboutLinearLayout)
    private val aboutTextView by getViewProperty<TextView>(R.id.aboutTextView)
    private val appBarLayout by getViewProperty<AppBarLayout>(R.id.appBarLayout)
    private val avatarView by getViewProperty<AvatarView>(R.id.avatarView)
    private val callLinearLayout by getViewProperty<LinearLayout>(R.id.callLinearLayout)
    private val infoCardView by getViewProperty<MaterialCardView>(R.id.infoCardView)
    private val messageMaterialButton by getViewProperty<MaterialButton>(R.id.messageMaterialButton)
    private val muteMaterialButton by getViewProperty<MaterialButton>(R.id.muteMaterialButton)
    private val muteTextView by getViewProperty<TextView>(R.id.muteTextView)
    private val headerLinearLayout by getViewProperty<LinearLayout>(R.id.headerLinearLayout)
    private val titleLinearLayout by getViewProperty<LinearLayout>(R.id.titleLinearLayout)
    private val emojiStatusView by getViewProperty<CustomEmojiView>(R.id.emojiStatusView)
    private val profileCoverView by getViewProperty<ProfileCoverView>(R.id.profileCoverView)
    private val phoneLinearLayout by getViewProperty<LinearLayout>(R.id.phoneLinearLayout)
    private val phoneTextView by getViewProperty<TextView>(R.id.phoneTextView)
    private val subtitleTextView by getViewProperty<TextView>(R.id.subtitleTextView)
    private val tabLayout by getViewProperty<TabLayout>(R.id.tabLayout)
    private val titleTextView by getViewProperty<TextView>(R.id.titleTextView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val usernameLabelTextView by getViewProperty<TextView>(R.id.usernameLabelTextView)
    private val usernameLinearLayout by getViewProperty<LinearLayout>(R.id.usernameLinearLayout)
    private val usernameTextView by getViewProperty<TextView>(R.id.usernameTextView)
    private val videoCallLinearLayout by getViewProperty<LinearLayout>(R.id.videoCallLinearLayout)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    private val startTransition = Runnable {
        if (activity is MainActivity) startPostponedEnterTransition()
        else (activity as? ProfileActivity)?.supportStartPostponedEnterTransition()
    }

    private val viewerAnchor by activityViewModels<PhotoViewerViewModel.Anchor>()

    private var isCollapsed = false

    /** The status bar's height: the cover goes up under it. */
    private var statusBarHeight = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (activity is MainActivity) {
            postponeEnterTransition()
            enterTransition = TransitionInflater.from(requireContext()).inflateTransition(R.transition.profile_window)
            returnTransition = TransitionInflater.from(requireContext()).inflateTransition(R.transition.profile_window)
            sharedElementEnterTransition = TransitionInflater.from(requireContext()).inflateTransition(android.R.transition.move)
            sharedElementReturnTransition = TransitionInflater.from(requireContext()).inflateTransition(android.R.transition.move)
        }

        // Insets: the header scrolls away under the status bar
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            statusBarHeight = insets.top
            profileCoverView.translationY = -insets.top.toFloat()
            headerLinearLayout.requestLayout()
            windowInsets
        }

        toolbar.setNavigationOnClickListener {
            navigateBackInPane()
        }

        // The name moves to the top bar once the header is scrolled away
        appBarLayout.addOnOffsetChangedListener { appBar, offset ->
            isCollapsed = appBar.totalScrollRange > 0 && -offset >= appBar.totalScrollRange
            toolbar.title = if (isCollapsed) titleTextView.text else null
            bindCoverColors()
        }

        // The cover goes down to the name, the pattern around the avatar
        headerLinearLayout.addOnLayoutChangeListener { header, _, _, _, _, _, _, _, _ ->
            val height = statusBarHeight + header.top +
                    subtitleTextView.bottom.coerceAtLeast(titleLinearLayout.bottom) +
                    resources.getDimensionPixelSize(R.dimen.profile_cover_bottom_padding)
            if (profileCoverView.layoutParams.height != height) {
                profileCoverView.post {
                    profileCoverView.updateLayoutParams { this.height = height }
                }
            }
            profileCoverView.setAvatar(
                header.left + avatarView.left + avatarView.width / 2f,
                statusBarHeight + header.top + avatarView.top + avatarView.height / 2f,
                avatarView.width / 2f,
                (statusBarHeight + header.top + titleLinearLayout.top).toFloat(),
            )
        }

        avatarView.setOnClickListener { openPhotos() }
        parentFragmentManager.setFragmentResultListener(
            PhotoViewerDialogFragment.REQUEST_KEY_PROFILE_SHOWN, viewLifecycleOwner,
        ) { _, _ -> avatarView.alpha = 0f }
        parentFragmentManager.setFragmentResultListener(
            PhotoViewerDialogFragment.REQUEST_KEY_PROFILE_CLOSED, viewLifecycleOwner,
        ) { _, _ -> avatarView.alpha = 1f }

        messageMaterialButton.setOnClickListener {
            when (requireArguments().getBoolean(ARG_IS_FROM_CHAT)) {
                true -> navigateBackInPane()
                false -> (activity as? MainActivity)?.openChat(ChatFragment.createBundle(viewModel.chatId))
                    ?: findNavController().navigateSafe(
                        R.id.action_profileFragment_to_chatActivity,
                        ChatFragment.createBundle(viewModel.chatId),
                    )
            }
        }

        muteMaterialButton.setOnClickListener { viewModel.toggleMute() }

        // Don't hold the opening animation forever if the chat never shows up
        view.postDelayed(startTransition, TRANSITION_TIMEOUT_MS)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.header, viewModel.photoPath, viewModel.animationFileId, ::Triple)
                        .collectLatest { (header, photoPath, animationFileId) ->
                            header ?: return@collectLatest

                            // The big photo and its big animation, the small ones until they're there
                            avatarView.setChat(
                                header.chat.copy(
                                    photoPath = photoPath ?: header.chat.photoPath,
                                    animationFileId = animationFileId.takeIf { it != 0 }
                                        ?: header.chat.animationFileId,
                                )
                            )
                            titleTextView.text = header.chat.title
                            emojiStatusView.setEmojiStatus(header.emojiStatusId, header.isPremium)
                            // Without one the name stays centered
                            emojiStatusView.isVisible = header.emojiStatusId != 0L || header.isPremium
                            profileCoverView.setColors(header.profileColors, header.profilePatternColor)
                            bindCoverColors()

                            val subtitle = requireContext().subtitleText(header.subtitle)
                            subtitleTextView.text = subtitle
                            subtitleTextView.isVisible = subtitle != null

                            val isMuted = header.chat.isMuted
                            muteMaterialButton.setIconResource(
                                if (isMuted) R.drawable.ic_notifications else R.drawable.ic_notifications_off
                            )
                            val muteText = getString(
                                if (isMuted) R.string.profile_unmute else R.string.profile_mute
                            )
                            muteTextView.text = muteText
                            muteMaterialButton.contentDescription = muteText

                            // The avatar is in place, the shared one can fly to it
                            avatarView.doOnPreDraw { startTransition.run() }
                        }
                }

                launch {
                    viewModel.info.collectLatest { it?.let(::setInfo) }
                }

                launch {
                    viewModel.header.mapNotNull { it?.profileEmojiId }.distinctUntilChanged()
                        .collectLatest(::loadCoverEmoji)
                }
            }
        }
    }


    /** White over the profile's colors, the theme's own over the surface. */
    private fun bindCoverColors() {
        val isOnCover = profileCoverView.hasColors
        val onSurface = MaterialColors.getColor(titleTextView, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(
            titleTextView, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        titleTextView.setTextColor(if (isOnCover) Color.WHITE else onSurface)
        subtitleTextView.setTextColor(if (isOnCover) COVER_SUBTITLE_COLOR else onSurfaceVariant)
        toolbar.setNavigationIconTint(if (isOnCover && !isCollapsed) Color.WHITE else onSurface)
        // An inline profile shares the window with the main pane; its cover must not recolor
        // the system status bar of that entire window.
        if (activity is ProfileActivity) {
            WindowCompat.getInsetsController(requireActivity().window, requireView()).isAppearanceLightStatusBars =
                if (isOnCover && !isCollapsed) false else isLightTheme()
        }
    }

    private fun isLightTheme() = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** The first frame of the pattern's emoji. */
    private suspend fun loadCoverEmoji(emojiId: Long) {
        profileCoverView.setEmoji(null)
        if (emojiId == 0L) return

        val size = resources.getDimensionPixelSize(R.dimen.profile_cover_emoji_size)
        val file = viewModel.coverEmoji(emojiId) ?: return
        val bitmap = withContext(Dispatchers.IO) {
            RustMedia.decode(File(file.path), size, size)
        }
        profileCoverView.setEmoji(bitmap)
    }

    /** The profile photos, from the avatar: the current one first, the older ones after it. */
    private fun openPhotos() {
        if (!viewModel.hasPhoto) {
            return
        }
        val navController = findNavController()
        val action = R.id.action_profileFragment_to_photoViewerDialogFragment
        if (navController.currentDestination?.getAction(action) == null) return

        val avatar = avatarView
        val drawable = avatar.photoDrawable ?: return
        fun frame(): PhotoViewerViewModel.Frame {
            val location = IntArray(2).also(avatar::getLocationOnScreen)
            return PhotoViewerViewModel.Frame(
                Rect(location[0], location[1], location[0] + avatar.width, location[1] + avatar.height),
                FloatArray(4) { avatar.radius },
            )
        }
        viewerAnchor.drawable = drawable.constantState?.newDrawable(resources) ?: drawable
        viewerAnchor.frame = frame()
        viewerAnchor.restore = {
            avatar.alpha = 1f
            avatar
        }
        viewerAnchor.locate = { id ->
            if (id == PhotoViewerViewModel.CURRENT_PROFILE_PHOTO_ID && avatar.isAttachedToWindow
                && avatar.getGlobalVisibleRect(Rect())
            ) frame() else null
        }
        navController.navigate(
            action,
            PhotoViewerDialogFragment.createProfileBundle(viewModel.chatId),
        )
    }

    override fun onDestroyView() {
        view?.removeCallbacks(startTransition)
        isCollapsed = false
        view?.takeIf { activity is ProfileActivity }?.let {
            WindowCompat.getInsetsController(requireActivity().window, it).isAppearanceLightStatusBars =
                isLightTheme()
        }

        super.onDestroyView()
    }

    private fun setInfo(info: ProfileViewModel.Info) {
        callLinearLayout.isVisible = info.isPrivate
        videoCallLinearLayout.isVisible = info.isPrivate

        phoneLinearLayout.isVisible = info.phoneNumber != null
        phoneTextView.text = info.phoneNumber?.let {
            PhoneNumberUtils.formatNumber("+$it", null) ?: "+$it"
        }

        usernameLinearLayout.isVisible = info.username != null
        usernameTextView.text = info.username?.let {
            if (info.isPrivate) "@$it" else "t.me/$it"
        }
        usernameLabelTextView.setText(
            if (info.isPrivate) R.string.profile_username else R.string.profile_link
        )

        aboutLinearLayout.isVisible = info.about != null
        aboutTextView.text = info.about
        aboutLabelTextView.setText(
            if (info.isPrivate) R.string.profile_bio else R.string.profile_description
        )

        infoCardView.isVisible = info.phoneNumber != null || info.username != null
                || info.about != null

        // Each tab is its own page with its own list, set up once per view
        if (viewPager2.adapter == null) {
            val tabs = info.tabs
            viewPager2.adapter = object : FragmentStateAdapter(this) {
                override fun getItemCount() = tabs.size

                override fun createFragment(position: Int) = ProfilePageFragment().apply {
                    arguments = ProfilePageFragment.createBundle(viewModel.chatId, tabs[position])
                }
            }
            TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
                tab.setText(tabs[position].titleResId)
            }.attach()
        }
    }

    private val ProfilePage.Kind.titleResId
        get() = when (this) {
            ProfilePage.Kind.MEMBERS -> R.string.profile_tab_members
            ProfilePage.Kind.POSTS -> R.string.profile_tab_posts
            ProfilePage.Kind.SAVED -> R.string.profile_tab_saved
            ProfilePage.Kind.GIFTS -> R.string.profile_tab_gifts
            ProfilePage.Kind.MEDIA -> R.string.profile_tab_media
            ProfilePage.Kind.FILES -> R.string.profile_tab_files
            ProfilePage.Kind.LINKS -> R.string.profile_tab_links
            ProfilePage.Kind.MUSIC -> R.string.profile_tab_music
            ProfilePage.Kind.VOICE_NOTES, ProfilePage.Kind.VIDEO_NOTES -> R.string.profile_tab_voice
            ProfilePage.Kind.GIFS -> R.string.profile_tab_gifs
            ProfilePage.Kind.GROUPS -> R.string.profile_tab_groups
            ProfilePage.Kind.POLLS -> R.string.profile_tab_polls
        }

    companion object {
        private const val ARG_IS_FROM_CHAT = "is_from_chat"

        private const val TRANSITION_TIMEOUT_MS = 500L

        /** The status under the name, over the profile's colors. */
        private const val COVER_SUBTITLE_COLOR = 0xCCFFFFFF.toInt()

        /**
         * Create a [Bundle] to use as the arguments for this fragment or its activity.
         *
         * @param chatId The chat, or the user (its private chat has the same id)
         * @param isFromChat Opened from that chat, "Message" goes back to it
         */
        fun createBundle(chatId: Long, isFromChat: Boolean = false) = Bundle().apply {
            putLong(ProfileViewModel.ARG_CHAT_ID, chatId)
            putBoolean(ARG_IS_FROM_CHAT, isFromChat)
        }
    }
}

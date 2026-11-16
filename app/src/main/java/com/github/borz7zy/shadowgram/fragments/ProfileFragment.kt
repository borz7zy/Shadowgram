/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import com.github.borz7zy.shadowgram.ui.text.subtitleText
import com.github.borz7zy.shadowgram.ui.views.AvatarView
import com.github.borz7zy.shadowgram.viewmodels.ProfileViewModel
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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

    private val startTransition = Runnable { activity?.supportStartPostponedEnterTransition() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets: the header scrolls away under the status bar
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            windowInsets
        }

        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // The name moves to the top bar once the header is scrolled away
        appBarLayout.addOnOffsetChangedListener { appBar, offset ->
            val isCollapsed = appBar.totalScrollRange > 0 && -offset >= appBar.totalScrollRange
            toolbar.title = if (isCollapsed) titleTextView.text else null
        }

        messageMaterialButton.setOnClickListener {
            when (requireArguments().getBoolean(ARG_IS_FROM_CHAT)) {
                true -> requireActivity().onBackPressedDispatcher.onBackPressed()
                false -> findNavController().navigateSafe(
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
                    combine(viewModel.header, viewModel.photoPath, ::Pair)
                        .collectLatest { (header, photoPath) ->
                            header ?: return@collectLatest

                            avatarView.setChat(
                                header.chat.copy(photoPath = photoPath ?: header.chat.photoPath)
                            )
                            titleTextView.text = header.chat.title

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
            }
        }
    }

    override fun onDestroyView() {
        view?.removeCallbacks(startTransition)

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

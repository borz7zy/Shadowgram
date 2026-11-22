/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.transition.TransitionInflater
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.borz7zy.rustcore.RustMedia
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.MainActivity
import com.github.borz7zy.shadowgram.ProfileActivity
import com.github.borz7zy.shadowgram.SettingsActivity
import com.github.borz7zy.shadowgram.calls.CallActivity
import com.github.borz7zy.shadowgram.models.Result
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.TimeUnit

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
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    private val actions by getViewProperty<View>(R.id.profileActions)
    private val callButton by getViewProperty<MaterialButton>(R.id.profileCallButton)
    private val birthdayRow by getViewProperty<View>(R.id.profileBirthdayRow)
    private val birthdayValue by getViewProperty<TextView>(R.id.profileBirthdayValue)
    private val channelRow by getViewProperty<View>(R.id.profilePersonalChannelRow)
    private val channelValue by getViewProperty<TextView>(R.id.profilePersonalChannelValue)

    private var tabMediator: TabLayoutMediator? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewLifecycleOwner.lifecycleScope.launch {
            when (val result = viewModel.setPhoto(uri)) {
                is Result.Success -> Unit
                is Result.Failure -> showError(result.error.message)
                null -> showError(getString(R.string.profile_photo_failed))
            }
        }
    }

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

        if (!viewModel.isOwnTab && activity is MainActivity) {
            postponeEnterTransition(TRANSITION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val inflater = TransitionInflater.from(requireContext())
            enterTransition = inflater.inflateTransition(R.transition.profile_window)
            returnTransition = inflater.inflateTransition(R.transition.profile_window)
            sharedElementEnterTransition = inflater.inflateTransition(android.R.transition.move)
            sharedElementReturnTransition = inflater.inflateTransition(android.R.transition.move)
            handleBackWhileOpening()
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

        if (viewModel.isOwnTab) {
            toolbar.navigationIcon = null
        } else {
            toolbar.setNavigationOnClickListener { goBack() }
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
        val viewerManager = if (viewModel.isOwnTab) requireParentFragment().parentFragmentManager
            else parentFragmentManager
        viewerManager.setFragmentResultListener(
            PhotoViewerDialogFragment.REQUEST_KEY_PROFILE_SHOWN, viewLifecycleOwner,
        ) { _, _ -> avatarView.alpha = 0f }
        viewerManager.setFragmentResultListener(
            PhotoViewerDialogFragment.REQUEST_KEY_PROFILE_CLOSED, viewLifecycleOwner,
        ) { _, _ -> avatarView.alpha = 1f }

        messageMaterialButton.setOnClickListener {
            when (requireArguments().getBoolean(ARG_IS_FROM_CHAT)) {
                true -> goBack()
                false -> openChat(viewModel.chatId)
            }
        }

        muteMaterialButton.setOnClickListener { viewModel.toggleMute() }
        callButton.setOnClickListener {
            val id = viewModel.info.value?.callUserId ?: return@setOnClickListener
            startActivity(CallActivity.outgoing(requireContext(), id, titleTextView.text.toString()))
        }
        channelRow.setOnClickListener {
            viewModel.info.value?.personalChannel?.let { openChat(it.id) }
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.profile_new_photo -> pickPhoto.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                R.id.profile_edit -> ProfileEditorDialog.show(this, ProfileEditorDialog.Page.OVERVIEW)
                R.id.profile_settings -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
                R.id.profile_edit_color -> ProfileEditorDialog.show(this, ProfileEditorDialog.Page.COLOR)
                R.id.profile_edit_username -> ProfileEditorDialog.show(this, ProfileEditorDialog.Page.USERNAME)
                R.id.profile_copy_link -> copyLink()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        // The separate activity postpones its window transition instead of the fragment's.
        if (activity is ProfileActivity) view.postDelayed(startTransition, TRANSITION_TIMEOUT_MS)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.header, viewModel.avatar, viewModel.info, ::Triple)
                        .collectLatest { (header, photo, info) ->
                            header ?: return@collectLatest

                            // The big photo and its big animation, the small ones until they're there
                            avatarView.setChat(
                                header.chat.copy(
                                    photoPath = photo.path ?: header.chat.photoPath,
                                    animationFileId = photo.animationId.takeIf { it != 0 }
                                        ?: header.chat.animationFileId,
                                )
                            )
                            val name = info?.ownUser?.let {
                                listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ")
                            }
                            titleTextView.text = name?.takeIf { it.isNotEmpty() } ?: header.chat.title
                            if (isCollapsed) toolbar.title = titleTextView.text
                            emojiStatusView.setEmojiStatus(header.emojiStatusId, header.isPremium)
                            // Without one the name stays centered
                            emojiStatusView.isVisible = header.emojiStatusId != 0L || header.isPremium
                            profileCoverView.setColors(header.profileColors, header.profilePatternColor)
                            bindCoverColors()

                            val subtitle = if (info?.ownUser != null || viewModel.isOwnTab) null
                            else requireContext().subtitleText(header.subtitle)
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
                    viewModel.tabs.collect { it?.let(::setTabs) }
                }

                launch {
                    viewModel.header.map { it?.profileEmojiId ?: 0L }.distinctUntilChanged()
                        .collectLatest(::loadCoverEmoji)
                }
            }
        }
    }

    private fun goBack() {
        startPostponedEnterTransition()
        navigateBackInPane()
    }

    private fun handleBackWhileOpening() {
        val navController = findNavController()
        val owner = viewLifecycleOwner
        // Pop the profile explicitly while its entry transition is still being applied. A gesture
        // started during that transition keeps this callback until it commits or is cancelled.
        val callback = object : OnBackPressedCallback(true), DefaultLifecycleObserver {
            private var gesturing = false

            override fun onResume(owner: LifecycleOwner) {
                if (!gesturing) isEnabled = false
            }

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                gesturing = true
            }

            override fun handleOnBackCancelled() {
                gesturing = false
                if (owner.lifecycle.currentState == Lifecycle.State.RESUMED) isEnabled = false
            }

            override fun handleOnBackPressed() {
                gesturing = false
                if (navController.currentBackStackEntry?.id == tag) {
                    startPostponedEnterTransition()
                    navController.popBackStack()
                }
            }
        }
        owner.lifecycle.addObserver(callback)
        requireActivity().onBackPressedDispatcher.addCallback(owner, callback)
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
        val iconColor = if (isOnCover && !isCollapsed) Color.WHITE else onSurface
        toolbar.setNavigationIconTint(iconColor)
        toolbar.menu.findItem(R.id.profile_more)?.iconTintList = ColorStateList.valueOf(iconColor)
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
        if (!viewModel.avatar.value.hasPhoto) {
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
        tabMediator?.detach()
        tabMediator = null
        viewPager2.adapter = null
        isCollapsed = false
        view?.takeIf { activity is ProfileActivity }?.let {
            WindowCompat.getInsetsController(requireActivity().window, it).isAppearanceLightStatusBars =
                isLightTheme()
        }

        super.onDestroyView()
    }

    private fun setInfo(info: ProfileViewModel.Info) {
        val own = info.ownUser != null
        actions.isVisible = !viewModel.isOwnTab && !own
        callLinearLayout.isVisible = info.callUserId != null
        if (own && toolbar.menu.findItem(R.id.profile_more) == null) {
            toolbar.inflateMenu(R.menu.own_profile)
            bindCoverColors()
        }

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

        birthdayRow.isVisible = info.birthdate != null
        birthdayValue.text = info.birthdate?.let(::formatBirthdate)
        channelRow.isVisible = info.personalChannel != null
        channelValue.text = info.personalChannel?.title
        infoCardView.isVisible = listOf(
            phoneLinearLayout, usernameLinearLayout, aboutLinearLayout, birthdayRow, channelRow
        ).any { it.isVisible }
    }

    private fun setTabs(tabs: List<ProfilePage.Kind>) {
        tabLayout.isVisible = tabs.isNotEmpty()
        if (tabs.isEmpty() || viewPager2.adapter != null) return
        viewPager2.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size

            override fun createFragment(position: Int) = ProfilePageFragment().apply {
                arguments = ProfilePageFragment.createBundle(viewModel.chatId, tabs[position])
            }
        }
        tabMediator = TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
            tab.setText(tabs[position].titleResId)
        }.also { it.attach() }
    }

    private fun openChat(chatId: Long) {
        val arguments = ChatFragment.createBundle(chatId)
        (activity as? MainActivity)?.openChat(arguments)
            ?: findNavController().navigateSafe(R.id.action_profileFragment_to_chatActivity, arguments)
    }

    private fun copyLink() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = viewModel.request(TdApi.GetUserLink(false, false, false))) {
                is Result.Success -> {
                    val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.profile_link), result.data.url))
                    if (android.os.Build.VERSION.SDK_INT < 33) {
                        Toast.makeText(requireContext(), R.string.profile_link_copied, Toast.LENGTH_SHORT).show()
                    }
                }
                is Result.Failure -> showError(result.error.message)
            }
        }
    }

    private fun showError(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    private val ProfilePage.Kind.titleResId
        get() = when (this) {
            ProfilePage.Kind.MEMBERS -> R.string.profile_tab_members
            ProfilePage.Kind.POSTS -> R.string.profile_tab_posts
            ProfilePage.Kind.ARCHIVED_POSTS -> R.string.profile_tab_archived_posts
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
        fun createOwnProfile() = ProfileFragment().apply {
            arguments = bundleOf(ProfileViewModel.ARG_OWN_TAB to true)
        }

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

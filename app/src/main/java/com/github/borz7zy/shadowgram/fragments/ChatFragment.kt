/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.text.Spanned
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.withTranslation
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.core.view.descendants
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionInflater
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.MessageSelectionActions
import com.github.borz7zy.shadowgram.models.selectedMessagesText
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.ui.visibleBoundsOnScreen
import com.github.borz7zy.shadowgram.models.ChatHeader
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.ContactItem
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.models.MessageRow
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.InputContents
import com.github.borz7zy.shadowgram.telegram.StickersStore
import com.github.borz7zy.shadowgram.ui.album.AlbumView
import com.github.borz7zy.shadowgram.ui.composer.AttachPanelView
import com.github.borz7zy.shadowgram.ui.composer.AttachmentStripView
import com.github.borz7zy.shadowgram.ui.composer.GalleryPanelView
import com.github.borz7zy.shadowgram.ui.composer.KeyboardListener
import com.github.borz7zy.shadowgram.ui.composer.KeyboardPanels
import com.github.borz7zy.shadowgram.ui.composer.RecentEmoji
import com.github.borz7zy.shadowgram.ui.composer.StickerKeyboardView
import com.github.borz7zy.shadowgram.ui.recyclerview.MessageListAdapter
import com.github.borz7zy.shadowgram.ui.recyclerview.SenderAvatarDecoration
import com.github.borz7zy.shadowgram.ui.text.CustomEmojiSpan
import com.github.borz7zy.shadowgram.ui.text.MentionSpan
import com.github.borz7zy.shadowgram.ui.text.attachCustomEmoji
import com.github.borz7zy.shadowgram.ui.text.composerContents
import com.github.borz7zy.shadowgram.ui.text.customEmojiRanges
import com.github.borz7zy.shadowgram.ui.text.forComposer
import com.github.borz7zy.shadowgram.ui.text.mentionRanges
import com.github.borz7zy.shadowgram.ui.text.removeEmojiUnderCustom
import com.github.borz7zy.shadowgram.ui.text.stringResId
import com.github.borz7zy.shadowgram.ui.views.ChatHeaderView
import com.github.borz7zy.shadowgram.ui.views.PinnedIndicatorView
import com.github.borz7zy.shadowgram.ui.views.TopRoundedLayout
import com.github.borz7zy.shadowgram.videonote.VideoNoteCamera
import com.github.borz7zy.shadowgram.videonote.VideoNoteDialogFragment
import com.github.borz7zy.shadowgram.voicenote.VoiceNoteDialogFragment
import com.github.borz7zy.shadowgram.viewmodels.ChatViewModel
import com.github.borz7zy.shadowgram.viewmodels.MessageMenuViewModel
import com.github.borz7zy.shadowgram.viewmodels.PhotoViewerViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearancePathProvider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * An open chat or forum topic, laid out like Google Messages: the top bar on the surface
 * container, the messages and the composer on a rounded surface card below it.
 */
class ChatFragment : Fragment(R.layout.fragment_chat) {
    // View models
    private val viewModel by viewModels<ChatViewModel>()

    // Views
    private val chatHeaderView by getViewProperty<ChatHeaderView>(R.id.chatHeaderView)
    private val composerLinearLayout by getViewProperty<LinearLayout>(R.id.composerLinearLayout)
    private val selectionActionsLayout by getViewProperty<View>(R.id.selectionActionsLayout)
    private val selectionForwardButton by getViewProperty<MaterialButton>(R.id.selectionForwardButton)
    private val selectionReplyButton by getViewProperty<MaterialButton>(R.id.selectionReplyButton)
    private val channelMuteButton by getViewProperty<MaterialButton>(R.id.channelMuteButton)
    private val channelMuteContainer by getViewProperty<View>(R.id.channelMuteContainer)
    private val replyLinearLayout by getViewProperty<LinearLayout>(R.id.replyLinearLayout)
    private val composerContextIcon by getViewProperty<ImageView>(R.id.composerContextIcon)
    private val replyTitleTextView by getViewProperty<TextView>(R.id.replyTitleTextView)
    private val replyTextTextView by getViewProperty<TextView>(R.id.replyTextTextView)
    private val cancelReplyMaterialButton by getViewProperty<MaterialButton>(R.id.cancelReplyMaterialButton)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val messageEditText by getViewProperty<TextInputEditText>(R.id.messageEditText)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val sendMaterialButton by getViewProperty<MaterialButton>(R.id.sendMaterialButton)
    private val pinnedLinearLayout by getViewProperty<LinearLayout>(R.id.pinnedLinearLayout)
    private val suggestionsRecyclerView by getViewProperty<RecyclerView>(R.id.suggestionsRecyclerView)
    private val reactionsFrameLayout by getViewProperty<View>(R.id.reactionsFrameLayout)
    private val reactionsFloatingActionButton by getViewProperty<View>(R.id.reactionsFloatingActionButton)
    private val reactionsCountTextView by getViewProperty<TextView>(R.id.reactionsCountTextView)
    private val mentionsFrameLayout by getViewProperty<View>(R.id.mentionsFrameLayout)
    private val mentionsFloatingActionButton by getViewProperty<View>(R.id.mentionsFloatingActionButton)
    private val mentionsCountTextView by getViewProperty<TextView>(R.id.mentionsCountTextView)
    private val scrollDownFrameLayout by getViewProperty<View>(R.id.scrollDownFrameLayout)
    private val scrollDownFloatingActionButton by getViewProperty<View>(R.id.scrollDownFloatingActionButton)
    private val scrollDownCountTextView by getViewProperty<TextView>(R.id.scrollDownCountTextView)
    private val pinnedIndicatorView by getViewProperty<PinnedIndicatorView>(R.id.pinnedIndicatorView)
    private val pinnedTitleTextView by getViewProperty<TextView>(R.id.pinnedTitleTextView)
    private val pinnedTextView by getViewProperty<TextView>(R.id.pinnedTextView)
    private val pinnedListMaterialButton by getViewProperty<MaterialButton>(R.id.pinnedListMaterialButton)
    private val sendMenuLinearLayout by getViewProperty<LinearLayout>(R.id.sendMenuLinearLayout)
    private val sendMenuScrimView by getViewProperty<View>(R.id.sendMenuScrimView)
    private val sendLaterMaterialButton by getViewProperty<MaterialButton>(R.id.sendLaterMaterialButton)
    private val sendWhenOnlineMaterialButton by getViewProperty<MaterialButton>(R.id.sendWhenOnlineMaterialButton)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val videoNoteMaterialButton by getViewProperty<MaterialButton>(R.id.videoNoteMaterialButton)
    private val attachMaterialButton by getViewProperty<MaterialButton>(R.id.attachMaterialButton)
    private val emojiMaterialButton by getViewProperty<MaterialButton>(R.id.emojiMaterialButton)
    private val scheduledMaterialButton by getViewProperty<MaterialButton>(R.id.scheduledMaterialButton)
    private val botCommandsMaterialButton by getViewProperty<MaterialButton>(R.id.botCommandsMaterialButton)
    private val panelCardView by getViewProperty<TopRoundedLayout>(R.id.panelCardView)
    private val stickerKeyboardView by getViewProperty<StickerKeyboardView>(R.id.stickerKeyboardView)
    private val galleryPanelView by getViewProperty<GalleryPanelView>(R.id.galleryPanelView)
    private val attachmentPanel by getViewProperty<View>(R.id.attachmentPanel)
    private val attachPanelView by getViewProperty<AttachPanelView>(R.id.attachPanelView)
    private val attachmentStripView by getViewProperty<AttachmentStripView>(R.id.attachmentStripView)

    // Panels under the composer, set up with the view
    private var panels: KeyboardPanels? = null

    /** The sticker keyboard loads the account's stickers the first time it opens. */
    private var isCollectingStickers = false
    private var composerBound = false
    private var renderedEdit: ChatViewModel.Edit? = null
    private var cameraOutputJob: Job? = null

    /**
     * Redraws emoji whose sprite just loaded; Emoji keeps it weakly. Every text of the rows, not
     * just the message's: reactions, sender names and the reply quote draw them too.
     */
    private val emojiInvalidator = Runnable {
        messageEditText.invalidate()
        replyTextTextView.invalidate()
        pinnedTextView.invalidate()
        adapter.invalidateEmoji()
    }

    // Pickers and permissions
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            attach(uris, asFiles = false)
            panels?.hide()
        }
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            attach(uris, asFiles = true)
            panels?.hide()
        }
    }

    private val pickEditMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> replaceFromPicker(uri, asFile = false) }

    private val pickEditFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> replaceFromPicker(uri, asFile = true) }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { taken ->
        val photo = viewModel.pendingPhoto
        viewModel.pendingPhoto = null
        if (taken && photo != null) {
            val (uri, editId) = photo
            if (editId == null) attach(listOf(uri), asFiles = false)
            else viewModel.replaceEditedAttachment(editId, InputContents.Picked(uri, asFile = false))
            panels?.hide()
        }
    }

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> result.data?.data?.let(::sendContact) }

    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { galleryPanelView.reload() }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && view != null) launchAttachmentCamera()
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.any { it }) sendLocation() }

    // RecyclerView
    private val adapter = MessageListAdapter(
        onCancelUpload = { viewModel.cancelUpload(it) },
        trackOf = { message, playable ->
            val chatTitle = viewModel.header.value?.chat?.title.orEmpty()
            MessagePlayer.Track(
                chatId = requireArguments().getLong(ChatViewModel.ARG_CHAT_ID),
                messageId = message.id,
                fileId = playable.fileId,
                isVideo = playable.isVideo,
                title = when {
                    playable.isMusic -> playable.title
                    message.isOutgoing -> getString(R.string.playback_you)
                    else -> message.senderName ?: chatTitle
                },
                subtitle = if (playable.isMusic) playable.performer else chatTitle,
                duration = playable.duration,
                isMusic = playable.isMusic,
                artworkPath = playable.artworkPath,
            )
        },
        callbacks = object : MessageListAdapter.Callbacks {
            override fun onMessageClick(message: MessageItem, bubble: View) {
                if (viewModel.selectedMessages.value.isNotEmpty()) viewModel.toggleMessageSelection(message)
                else openMenu(message, bubble)
            }

            override fun onMessageLongClick(message: MessageItem) {
                this@ChatFragment.view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                viewModel.toggleMessageSelection(message)
            }

            override fun onSenderClick(senderId: Long) = openProfile(senderId)

            override fun onMediaClick(message: MessageItem, view: ImageView) {
                if (viewModel.selectedMessages.value.isNotEmpty()) viewModel.toggleMessageSelection(message)
                else openViewer(message, view)
            }

            override fun onDocumentClick(message: MessageItem) {
                if (viewModel.selectedMessages.value.isNotEmpty()) { viewModel.toggleMessageSelection(message); return }
                val document = message.document ?: return
                viewLifecycleOwner.lifecycleScope.launch {
                    Downloads.onTap(requireContext(), document) { viewModel.toggleDownload(document) }
                }
            }

            override fun onMentionClick(userId: Long, username: String) {
                if (userId != 0L) {
                    openProfile(userId)
                    return
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.resolveUsername(username)?.let(::openProfile)
                }
            }

            override fun onReplyClick(message: MessageItem) {
                if (viewModel.selectedMessages.value.isNotEmpty()) { viewModel.toggleMessageSelection(message); return }
                val reply = message.reply ?: return
                jumpTo(reply.messageId, reply.quote, reply.quotePosition)
            }

            override fun onReactionClick(message: MessageItem, reaction: MessageItem.Reaction) {
                if (viewModel.selectedMessages.value.isNotEmpty()) { viewModel.toggleMessageSelection(message); return }
                if (!reaction.isPaid) {
                    viewModel.toggleReaction(message.id, reaction.emoji, reaction.customEmojiId)
                }
            }
        },
    )

    private val menuAnchor by activityViewModels<MessageMenuViewModel.Anchor>()
    private val viewerAnchor by activityViewModels<PhotoViewerViewModel.Anchor>()

    /** The photo whose picture the viewer shows meanwhile, hidden here. */
    private var viewerMessageId = 0L

    private var isSendMenuOpen = false

    private val sendMenuOnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSendMenuOpen(false)
    }

    /** A reply's original to scroll to and flash once its rows are in. */
    private var pendingJump: MessageListAdapter.Highlight? = null

    /** The message whose menu is open. */
    private var menuMessage: MessageItem? = null
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.clearMessageSelection()
    }

    private lateinit var avatarDecoration: SenderAvatarDecoration

    /** Taps on the avatars the decoration draws, they aren't views. */
    private val avatarTouchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        private var senderId: Long? = null

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> senderId = avatarDecoration.senderAt(e.x, e.y)
                MotionEvent.ACTION_MOVE -> if (rv.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    senderId = null
                }

                MotionEvent.ACTION_UP -> senderId?.let {
                    senderId = null
                    openProfile(it)
                    return true
                }
            }
            return false
        }
    }

    /** Newest incoming message already reported as viewed. */
    private var lastViewedMessageId = 0L

    /**
     * Messages are only reported as viewed once the list shows where the chat opens, never the
     * newest ones it shows for a frame before that.
     */
    private var canView = false

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager

            // Reversed: the end of the adapter is the top of the screen, the oldest messages
            if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                viewModel.loadOlder()
            }
            if (layoutManager.findFirstVisibleItemPosition() <= LOAD_MORE_THRESHOLD) {
                viewModel.loadNewer()
            }

            viewVisibleMessages()
            updatePinnedBar()

            // Scrolling down away from the newest brings the way back to it, up hides it
            when {
                isAtNewest() -> setScrollDownShown(false)
                dy > SCROLL_SLOP_PX -> setScrollDownShown(true)
                dy < -SCROLL_SLOP_PX -> setScrollDownShown(false)
            }
        }
    }

    private var isScrollDownShown = false

    /** Back to the newest was asked for while reading old messages, they're reloaded first. */
    private var isScrollToNewestPending = false

    /** The pinned message the bar shows, the one a tap on it goes to. */
    private var shownPinnedId = 0L
    private var emojiInitialized = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (viewModel.normalDraft == null) {
            savedInstanceState?.getString(STATE_DRAFT)?.let { text ->
                viewModel.normalDraft = ChatViewModel.ComposerDraft(
                    TdApi.FormattedText(text, emptyArray()),
                    savedInstanceState.getInt(STATE_DRAFT_START, text.length),
                    savedInstanceState.getInt(STATE_DRAFT_END, text.length),
                )
            }
        }
        val emojiReady = (requireContext().applicationContext as ShadowgramApplication).emojiReady
        exitTransition = TransitionInflater.from(requireContext()).inflateTransition(R.transition.profile_window)
        reenterTransition = TransitionInflater.from(requireContext()).inflateTransition(R.transition.profile_window)
        chatHeaderView.avatarView.transitionName = getString(R.string.transition_profile_avatar)

        // Insets: the composer follows the keyboard, or stays above the navigation bar and a
        // panel that took the keyboard's place
        panels = KeyboardPanels(
            this,
            composerLinearLayout,
            panelCardView,
            mapOf(
                KeyboardPanels.Panel.STICKERS to stickerKeyboardView,
                KeyboardPanels.Panel.ATTACH to attachmentPanel,
            ),
            messageEditText,
            ::onPanelChanged,
        )
        setUpComposer()
        listOf(selectionActionsLayout, channelMuteContainer).forEach { footer ->
            val bottom = footer.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(footer) { target, insets ->
                target.updatePadding(bottom = bottom + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
                insets
            }
        }
        if (viewModel.isScheduled) {
            composerLinearLayout.isVisible = false
            // Without the composer the list goes down to the navigation bar
            val paddingBottom = recyclerView.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
                v.updatePadding(
                    bottom = paddingBottom + if (viewModel.edit.value == null) {
                        windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                    } else 0
                )
                windowInsets
            }
        }

        toolbar.setNavigationOnClickListener {
            if (viewModel.selectedMessages.value.isNotEmpty()) viewModel.clearMessageSelection()
            else requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.selection_forward, R.id.selection_forward_more -> forwardSelection()
                R.id.selection_copy -> copySelection()
                R.id.selection_edit -> viewModel.selectedMessages.value.keys.singleOrNull()?.let {
                    viewModel.clearMessageSelection()
                    viewModel.startEditing(it)
                }
                R.id.selection_delete -> deleteSelection()
                R.id.selection_favorite -> viewModel.favoriteSelectedSticker()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        selectionForwardButton.setOnClickListener { forwardSelection() }
        selectionReplyButton.setOnClickListener {
            val message = viewModel.selectedMessages.value.values.singleOrNull() ?: return@setOnClickListener
            viewModel.clearMessageSelection()
            renderMessageSelection()
            replyTo(message, null, 0)
        }
        channelMuteButton.setOnClickListener { viewModel.toggleMute() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        // Keep the profile in this NavHost, so the list and navigation on the left remain.
        chatHeaderView.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_chatFragment_to_profileFragment,
                ProfileFragment.createBundle(
                    requireArguments().getLong(ChatViewModel.ARG_CHAT_ID), isFromChat = true
                ),
                navigatorExtras = FragmentNavigatorExtras(
                    chatHeaderView.avatarView to getString(R.string.transition_profile_avatar)
                ),
            )
        }

        // All the messages, searched; the pinned ones listed
        chatHeaderView.setOnLongClickListener {
            openSearch(isPinned = false)
            true
        }
        pinnedListMaterialButton.setOnClickListener { openSearch(isPinned = true) }
        scrollDownFloatingActionButton.setOnClickListener { scrollToNewest() }
        mentionsFloatingActionButton.setOnClickListener { goToUnread(isReaction = false) }
        reactionsFloatingActionButton.setOnClickListener { goToUnread(isReaction = true) }
        setScrollDownShown(false)
        pinnedLinearLayout.setOnClickListener { jumpTo(shownPinnedId) }
        setFragmentResultListener(ChatSearchFragment.REQUEST_KEY) { _, bundle ->
            jumpTo(bundle.getLong(ChatSearchFragment.KEY_MESSAGE_ID))
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
        }
        recyclerView.adapter = adapter
        suggestionsRecyclerView.adapter = suggestionsAdapter
        avatarDecoration = SenderAvatarDecoration(recyclerView, adapter)
        recyclerView.addItemDecoration(avatarDecoration)
        recyclerView.addOnItemTouchListener(avatarTouchListener)
        recyclerView.addOnScrollListener(onScrollListener)
        // Rotated: the list restores its own position
        canView = viewModel.isPositioned

        messageEditText.doAfterTextChanged {
            if (emojiInitialized) updateComposerEmoji()
            updateSendButton()
            // Not the text a rotation puts back
            if (isResumed && viewModel.edit.value == null) {
                viewModel.onTyping(it.isNullOrEmpty())
            }
            viewModel.mentionQuery.value = mentionQuery()
            viewModel.composerText.value = if (viewModel.edit.value == null) it?.toString().orEmpty() else ""
        }

        updateRecordMode()
        videoNoteMaterialButton.setOnClickListener {
            viewModel.isVideoRecordMode = !viewModel.isVideoRecordMode
            if (viewModel.isVideoRecordMode) VideoNoteCamera.prewarm(requireContext())
            updateRecordMode()
        }
        videoNoteMaterialButton.setOnLongClickListener {
            panels?.hide()
            if (viewModel.isVideoRecordMode) {
                findNavController().navigateSafe(
                    R.id.action_chatFragment_to_videoNoteDialogFragment, requireArguments()
                )
            } else if (!parentFragmentManager.isStateSaved &&
                parentFragmentManager.findFragmentByTag(VoiceNoteDialogFragment.REQUEST_KEY) == null
            ) {
                VoiceNoteDialogFragment().showNow(parentFragmentManager, VoiceNoteDialogFragment.REQUEST_KEY)
            }
            true
        }

        setFragmentResultListener(PhotoViewerDialogFragment.REQUEST_KEY_SHOWN) { _, bundle ->
            // The viewer went to another photo: that one is hidden now
            setViewerMediaHidden(viewerMessageId, false)
            viewerMessageId = bundle.getLong(PhotoViewerDialogFragment.KEY_MESSAGE_ID)
            setViewerMediaHidden(viewerMessageId, true)
        }
        setFragmentResultListener(PhotoViewerDialogFragment.REQUEST_KEY) { _, _ ->
            setViewerMediaHidden(viewerMessageId, false)
            viewerMessageId = 0L
            viewerAnchor.drawable = null
            viewerAnchor.locate = null
            viewerAnchor.restore = null
        }
        setFragmentResultListener(MessageMenuDialogFragment.REQUEST_KEY_SHOWN) { _, _ ->
            menuMessage?.let { setMenuBubbleHidden(it.id, true) }
        }
        setFragmentResultListener(MessageMenuDialogFragment.REQUEST_KEY) { _, bundle ->
            onMenuResult(bundle)
        }
        cancelReplyMaterialButton.setOnClickListener {
            if (viewModel.edit.value != null) viewModel.cancelEditing() else viewModel.setReply(null)
        }

        setFragmentResultListener(VoiceNoteDialogFragment.REQUEST_KEY) { _, bundle ->
            bundle.getString(VoiceNoteDialogFragment.KEY_PATH)?.let {
                viewModel.sendVoiceNote(
                    File(it), bundle.getInt(VoiceNoteDialogFragment.KEY_DURATION),
                    bundle.getByteArray(VoiceNoteDialogFragment.KEY_WAVEFORM) ?: byteArrayOf(),
                )
                recyclerView.scrollToPosition(0)
            }
        }

        setFragmentResultListener(VideoNoteDialogFragment.REQUEST_KEY) { _, bundle ->
            bundle.getString(VideoNoteDialogFragment.KEY_PATH)?.let {
                viewModel.sendVideoNote(File(it))
                recyclerView.scrollToPosition(0)
            }
        }

        sendMaterialButton.setOnClickListener { send(null) }
        // When it goes, like the new chat menu
        sendMaterialButton.setOnLongClickListener {
            if (viewModel.edit.value != null) return@setOnLongClickListener false
            val subtitle = viewModel.header.value?.subtitle
            // Only a user who isn't online now can come online
            sendWhenOnlineMaterialButton.tag = subtitle is ChatHeader.Subtitle.Status
                    && subtitle.lastSeen.kind != ContactItem.LastSeen.Kind.ONLINE
            setSendMenuOpen(true)
            true
        }
        sendMenuScrimView.setOnClickListener { setSendMenuOpen(false) }
        sendLaterMaterialButton.setOnClickListener {
            setSendMenuOpen(false)
            pickScheduleDate { send(TdApi.MessageSchedulingStateSendAtDate(it, 0)) }
        }
        sendWhenOnlineMaterialButton.setOnClickListener {
            setSendMenuOpen(false)
            send(TdApi.MessageSchedulingStateSendWhenOnline())
        }
        isSendMenuOpen = false
        sendMenuOnBackPressedCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, sendMenuOnBackPressedCallback
        )

        renderComposer(viewModel.edit.value, viewModel.replyTo.value)
        viewLifecycleOwner.lifecycleScope.launch {
            emojiReady.await()
            Emoji.registerInvalidator(emojiInvalidator)
            emojiInitialized = true
            updateComposerEmoji()
            renderComposer(viewModel.edit.value, viewModel.replyTo.value)
            updatePinnedBar()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.header.collectLatest {
                        it ?: return@collectLatest
                        chatHeaderView.setHeader(it)
                        renderMessageSelection()
                        scheduledMaterialButton.isVisible = it.chat.hasScheduledMessages && viewModel.edit.value == null
                        bindJumpButtons(it.chat)
                        // A protected chat: no screenshots, no screen recording
                        when (it.chat.hasProtectedContent) {
                            true -> requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            false -> requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }

                launch {
                    emojiReady.await()
                    viewModel.rows.collectLatest(::setRows)
                }
                launch { viewModel.selectedMessages.collectLatest {
                    adapter.setSelection(it.keys)
                    if (it.isNotEmpty()) {
                        panels?.hide()
                        WindowCompat.getInsetsController(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
                    }
                    renderMessageSelection()
                } }
                launch { viewModel.selectionProperties.collectLatest { renderMessageSelection() } }
                launch { viewModel.editableSelectionId.collectLatest { renderMessageSelection() } }
                launch { viewModel.selectionBusy.collectLatest { renderMessageSelection() } }
                launch { viewModel.actionErrors.collectLatest { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

                launch {
                    viewModel.pinned.collectLatest { updatePinnedBar() }
                }

                launch {
                    viewModel.suggestions.collectLatest(::setSuggestions)
                }

                launch {
                    viewModel.hasBotCommands.collectLatest { updateSendButton() }
                }

                launch {
                    viewModel.sendFailures.collect(::onSendFailed)
                }

                launch {
                    combine(viewModel.edit, viewModel.replyTo, ::Pair).collect { (edit, reply) ->
                        renderComposer(edit, reply)
                    }
                }
            }
        }
    }

    private fun updateComposerEmoji() {
        val text = messageEditText.text ?: return
        Emoji.replaceEmoji(text, messageEditText.paint.fontMetricsInt, false)
        text.removeEmojiUnderCustom()
        messageEditText.attachCustomEmoji()
    }

    private fun rememberComposer() {
        val text = messageEditText.text?.composerContents() ?: TdApi.FormattedText("", emptyArray())
        val draft = ChatViewModel.ComposerDraft(text, messageEditText.selectionStart, messageEditText.selectionEnd)
        val id = renderedEdit?.target?.message?.id
        when {
            id == null -> viewModel.normalDraft = draft
            id == viewModel.edit.value?.target?.message?.id -> viewModel.editDraft = draft
        }
    }

    private fun renderComposer(edit: ChatViewModel.Edit?, reply: ChatViewModel.Reply?) {
        val previous = renderedEdit
        val initialized = composerBound
        val modeChanged = previous?.target !== edit?.target
        if (modeChanged && initialized) rememberComposer()
        renderedEdit = edit
        composerBound = true
        if (!initialized || modeChanged) {
            if (modeChanged) {
                setSendMenuOpen(false)
                panels?.hide()
            }
            val draft = if (edit == null) viewModel.normalDraft else viewModel.editDraft
            val text = draft?.text ?: edit?.target?.text
            messageEditText.setText(text?.forComposer(messageEditText))
            val length = messageEditText.length()
            val start = draft?.start?.coerceIn(0, length) ?: length
            val end = draft?.end?.coerceIn(0, length) ?: length
            messageEditText.setSelection(start, end)
            if (viewModel.isScheduled) ViewCompat.requestApplyInsets(recyclerView)
        }

        val saving = edit?.isSaving == true
        val editing = edit != null
        replyLinearLayout.isVisible = editing || reply != null
        composerContextIcon.setImageResource(if (editing) R.drawable.ic_edit else R.drawable.ic_reply)
        val cancelLabel = if (editing) R.string.message_edit_cancel else R.string.message_cancel_reply
        cancelReplyMaterialButton.contentDescription = getString(cancelLabel)
        cancelReplyMaterialButton.tooltipText = cancelReplyMaterialButton.contentDescription
        cancelReplyMaterialButton.isEnabled = !saving
        replyTitleTextView.text = when {
            saving -> getString(R.string.message_edit_saving)
            editing -> getString(R.string.message_editing)
            reply != null -> getString(R.string.message_replying_to, reply.name)
            else -> null
        }
        val preview = if (edit != null) {
            edit.target.text.text.takeIf { it.isNotBlank() }
                ?: edit.target.attachment?.let { getString(it.label) }
        } else reply?.text
        replyTextTextView.isVisible = !preview.isNullOrEmpty()
        replyTextTextView.text = if (emojiInitialized && preview != null) {
            Emoji.replaceEmoji(preview, replyTextTextView.paint.fontMetricsInt, false)
        } else preview

        if (!initialized || previous != edit) {
            val canReplace = edit?.target?.canReplaceMedia == true
            attachMaterialButton.isVisible = !editing || canReplace
            attachMaterialButton.isEnabled = !saving
            messageEditText.isEnabled = !saving
            emojiMaterialButton.isEnabled = !saving
            sendMaterialButton.setIconResource(if (editing) R.drawable.ic_check else R.drawable.ic_send)
            sendMaterialButton.contentDescription = getString(
                if (editing) R.string.message_edit_save else R.string.chat_send
            )
            sendMaterialButton.tooltipText = sendMaterialButton.contentDescription
            scheduledMaterialButton.isVisible = !editing && viewModel.header.value?.chat?.hasScheduledMessages == true
            galleryPanelView.singleSelection = editing
            val actions = edit?.let(::editAttachmentActions) ?: AttachPanelView.Action.entries.toSet()
            attachPanelView.showActions(actions)
            galleryPanelView.isVisible = AttachPanelView.Action.GALLERY in actions
            attachMaterialButton.contentDescription = getString(
                if (editing) R.string.message_replace_attachment else R.string.chat_attach
            )
            attachMaterialButton.tooltipText = attachMaterialButton.contentDescription
            if (saving) panels?.hide()
            if (edit == null) {
                attachmentStripView.setItems(viewModel.attachments)
            } else {
                val attachment = edit.target.attachment
                val name = attachment?.let { it.name?.takeIf(String::isNotBlank) ?: getString(it.label) }
                val replacement = edit.replacement
                val action: (() -> Unit)? = when {
                    saving || !canReplace -> null
                    replacement != null -> {
                        { viewModel.replaceEditedAttachment(edit.target.message.id, null) }
                    }
                    else -> {
                        { panels?.show(KeyboardPanels.Panel.ATTACH) }
                    }
                }
                attachmentStripView.showEditAttachment(
                    name = name.takeIf { replacement == null },
                    preview = replacement?.uri ?: edit.previewPath?.let { Uri.fromFile(File(it)) },
                    isReplacement = replacement != null,
                    action = action,
                )
            }
        }
        updateSendButton()
        renderMessageSelection()
        if (modeChanged && edit != null && !saving) {
            composerLinearLayout.doOnLayout { if (it.isShown) panels?.showKeyboard() }
        }
    }

    /** Put what TDLib refused back in the composer, unless something new is there already. */
    private fun onSendFailed(failed: ChatViewModel.Failed) {
        Toast.makeText(
            requireContext(),
            when (val error = failed.error) {
                null -> getString(R.string.chat_send_unreadable)
                else -> getString(R.string.chat_send_failed, error)
            },
            Toast.LENGTH_LONG,
        ).show()
        if (viewModel.edit.value != null) {
            if (failed.text != null && viewModel.normalDraft?.text?.text.isNullOrEmpty()) {
                viewModel.normalDraft = ChatViewModel.ComposerDraft(
                    TdApi.FormattedText(failed.text, emptyArray()), failed.text.length, failed.text.length,
                )
            }
            if (failed.attachments.isNotEmpty() && viewModel.attachments.isEmpty()) {
                viewModel.attachments = failed.attachments
            }
            return
        }
        if (failed.text != null && messageEditText.text.isNullOrEmpty()) {
            messageEditText.setText(failed.text)
        }
        if (failed.attachments.isNotEmpty() && viewModel.attachments.isEmpty()) {
            // Through onChanged, as if picked again
            attachmentStripView.add(failed.attachments)
        }
    }

    override fun onStart() {
        super.onStart()

        viewModel.setOpen(true)
    }

    override fun onStop() {
        viewModel.setOpen(false)

        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (composerBound) rememberComposer()
        viewModel.normalDraft?.let { draft ->
            outState.putString(STATE_DRAFT, draft.text.text)
            outState.putInt(STATE_DRAFT_START, draft.start)
            outState.putInt(STATE_DRAFT_END, draft.end)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        if (composerBound) rememberComposer()
        composerBound = false
        renderedEdit = null
        isCollectingStickers = false
        menuMessage?.let { setMenuBubbleHidden(it.id, false) }
        menuMessage = null
        menuAnchor.locate = null
        menuAnchor.viewport = null
        menuAnchor.restore = null
        setViewerMediaHidden(viewerMessageId, false)
        viewerMessageId = 0L
        viewerAnchor.locate = null
        viewerAnchor.restore = null
        recyclerView.removeOnItemTouchListener(avatarTouchListener)
        if (emojiInitialized) {
            Emoji.unregisterInvalidator(emojiInvalidator)
            emojiInitialized = false
        }
        galleryPanelView.stop()
        panels = null
        recyclerView.removeOnScrollListener(onScrollListener)
        suggestionsRecyclerView.adapter = null
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private fun setUpComposer() {
        // Open the gallery with the other attachment sources in the same panel.
        attachMaterialButton.setOnClickListener {
            val panels = panels ?: return@setOnClickListener
            val edit = viewModel.edit.value
            when {
                panels.current == KeyboardPanels.Panel.ATTACH -> panels.hide()
                edit != null && editAttachmentActions(edit) == setOf(AttachPanelView.Action.FILES) ->
                    openAttachmentFiles()
                else -> panels.show(KeyboardPanels.Panel.ATTACH)
            }
        }
        emojiMaterialButton.setOnClickListener { panels?.toggle(KeyboardPanels.Panel.STICKERS) }
        // A slash brings up the commands over the composer, as typing one would
        botCommandsMaterialButton.setOnClickListener {
            messageEditText.setText("/")
            messageEditText.setSelection(1)
        }
        scheduledMaterialButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_chatFragment_to_scheduledChatFragment,
                createBundle(
                    requireArguments().getLong(ChatViewModel.ARG_CHAT_ID),
                    requireArguments().getInt(ChatViewModel.ARG_TOPIC_ID),
                    isScheduled = true,
                ),
            )
        }

        stickerKeyboardView.listener = object : KeyboardListener {
            override fun onEmoji(code: String) {
                RecentEmoji.add(requireContext(), code)
                val text = messageEditText.text ?: return
                val start = messageEditText.selectionStart.coerceAtLeast(0)
                val end = messageEditText.selectionEnd.coerceAtLeast(start)
                text.replace(start, end, code)
            }

            override fun onCustomEmoji(sticker: StickersStore.Sticker) {
                val text = messageEditText.text ?: return
                val start = messageEditText.selectionStart.coerceAtLeast(0)
                val end = messageEditText.selectionEnd.coerceAtLeast(start)
                val emoji = sticker.emoji.ifEmpty { CUSTOM_EMOJI_FALLBACK }
                text.replace(start, end, emoji)
                text.setSpan(
                    CustomEmojiSpan(sticker.customEmojiId, messageEditText.paint.fontMetricsInt),
                    start,
                    start + emoji.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text.removeEmojiUnderCustom()
                messageEditText.attachCustomEmoji()
            }

            override fun onBackspace() {
                messageEditText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                messageEditText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }

            override fun onSticker(sticker: StickersStore.Sticker) {
                if (viewModel.edit.value != null) return
                viewModel.sendSticker(sticker)
                recyclerView.scrollToPosition(0)
            }

            override fun onGif(gif: StickersStore.Gif) {
                if (viewModel.edit.value != null) return
                viewModel.sendGif(gif)
                recyclerView.scrollToPosition(0)
            }

            override fun onDownload(fileId: Int) = viewModel.download(fileId)

            override fun onGifQuery(query: String) {
                viewModel.gifQuery.value = query
            }
        }

        galleryPanelView.listener = object : GalleryPanelView.Listener {
            override fun onRequestMediaPermission() =
                mediaPermission.launch(GalleryPanelView.mediaPermissions())

            override fun onBrowse() {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                val edit = viewModel.edit.value
                if (edit == null) {
                    pickMedia.launch(request)
                } else if (!edit.isSaving && edit.target.canReplaceMedia) {
                    viewModel.editPickerMessageId = edit.target.message.id
                    pickEditMedia.launch(request)
                }
            }

            override fun onConfirm(selection: List<Uri>) {
                attachFromGallery(selection)
                panels?.hide()
            }
        }

        attachmentStripView.setItems(viewModel.attachments)
        attachmentStripView.isGrouped = viewModel.isGrouped
        attachmentStripView.onChanged = {
            viewModel.attachments = it
            updateSendButton()
        }
        attachmentStripView.onGroupedChanged = { viewModel.isGrouped = it }
        updateSendButton()

        attachPanelView.onAction = actionListener@ { action ->
            val edit = viewModel.edit.value
            if (edit != null && (edit.isSaving || action !in editAttachmentActions(edit))) {
                return@actionListener
            }
            when (action) {
                AttachPanelView.Action.GALLERY -> galleryPanelView.showAlbums()

                AttachPanelView.Action.CAMERA -> launchAttachmentCamera()

                AttachPanelView.Action.FILES -> openAttachmentFiles()

                AttachPanelView.Action.LOCATION -> locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )

                AttachPanelView.Action.CONTACT -> pickContact.launch(
                    Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
                )
            }
        }
    }

    private fun openAttachmentFiles() {
        val edit = viewModel.edit.value
        if (edit == null) pickFiles.launch(arrayOf("*/*"))
        else if (!edit.isSaving && AttachPanelView.Action.FILES in editAttachmentActions(edit)) {
            viewModel.editPickerMessageId = edit.target.message.id
            pickEditFile.launch(arrayOf(if (edit.target.isAudioAlbum) "audio/*" else "*/*"))
        }
    }

    private fun launchAttachmentCamera() {
        val edit = viewModel.edit.value
        if (edit != null && (edit.isSaving || AttachPanelView.Action.CAMERA !in editAttachmentActions(edit))) return
        if (viewModel.pendingPhoto != null || cameraOutputJob?.isActive == true) return
        val context = requireContext().applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        cameraOutputJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "outgoing/${System.nanoTime()}").apply { mkdirs() }
                    FileProvider.getUriForFile(context, "${context.packageName}.files", File(directory, "photo.jpg"))
                }
                viewModel.pendingPhoto = uri to edit?.target?.message?.id
                takePhoto.launch(uri)
            } catch (e: ActivityNotFoundException) {
                showAttachmentCameraError(e)
            } catch (e: SecurityException) {
                showAttachmentCameraError(e)
            } catch (e: IllegalArgumentException) {
                showAttachmentCameraError(e)
            }
        }
    }

    private fun showAttachmentCameraError(error: Exception) {
        viewModel.pendingPhoto = null
        Log.w("ChatFragment", "Camera attachment unavailable", error)
        Toast.makeText(requireContext(), R.string.attachment_camera_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun onPanelChanged(panel: KeyboardPanels.Panel?) {
        emojiMaterialButton.setIconResource(
            if (panel == KeyboardPanels.Panel.STICKERS) R.drawable.ic_keyboard else R.drawable.ic_mood
        )
        emojiMaterialButton.contentDescription = getString(
            if (panel == KeyboardPanels.Panel.STICKERS) R.string.chat_keyboard else R.string.chat_emoji
        )
        val isAttaching = panel == KeyboardPanels.Panel.ATTACH
        attachMaterialButton.setIconResource(
            if (isAttaching) R.drawable.ic_close else R.drawable.ic_add_circle
        )
        attachMaterialButton.contentDescription = getString(
            when {
                isAttaching -> R.string.chat_attach_close
                viewModel.edit.value != null -> R.string.message_replace_attachment
                else -> R.string.chat_attach
            }
        )
        attachMaterialButton.tooltipText = attachMaterialButton.contentDescription

        if (isAttaching && galleryPanelView.isVisible) galleryPanelView.reload()
        else galleryPanelView.stop()

        if (panel == KeyboardPanels.Panel.STICKERS && !isCollectingStickers) {
            isCollectingStickers = true
            val stickerKeyboardView = stickerKeyboardView
            viewLifecycleOwner.lifecycleScope.launch {
                (requireContext().applicationContext as ShadowgramApplication).emojiReady.await()
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(viewModel.stickers, viewModel.foundGifs, viewModel.edit) { stickers, gifs, edit ->
                        Triple(stickers, gifs, edit != null)
                    }.collectLatest { (stickers, gifs, editing) ->
                        stickerKeyboardView.setStickers(stickers, gifs, emojiOnly = editing)
                    }
                }
            }
        }
    }

    /** The profile of a sender, a user or a channel. */
    private fun openProfile(senderId: Long) {
        // The private chat of a user has the user id
        findNavController().navigateSafe(
            R.id.action_chatFragment_to_profileFragment,
            ProfileFragment.createBundle(senderId),
        )
    }

    /** Selection replaces the normal header and composer without discarding the draft. */
    private fun renderMessageSelection() {
        val selected = viewModel.selectedMessages.value
        val selecting = selected.isNotEmpty()
        val chat = viewModel.header.value?.chat
        val readOnlyChannel = chat?.isChannel == true && !chat.canPostMessages
        selectionBackCallback.isEnabled = selecting
        chatHeaderView.isVisible = !selecting
        toolbar.title = if (selecting) getString(R.string.selection_count, selected.size) else null
        toolbar.setNavigationIcon(if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back)
        toolbar.setNavigationContentDescription(if (selecting) R.string.selection_cancel else R.string.chat_back)
        val editing = viewModel.edit.value != null
        composerLinearLayout.isVisible = !selecting && (editing || (!viewModel.isScheduled && !readOnlyChannel))
        selectionActionsLayout.isVisible = selecting
        channelMuteContainer.isVisible = !selecting && !editing && !viewModel.isScheduled && readOnlyChannel
        channelMuteButton.setText(if (chat?.isMuted == true) R.string.chat_unmute else R.string.chat_mute)
        messageEditText.setHint(if (chat?.isChannel == true && chat.canPostMessages) R.string.chat_publication else R.string.chat_message_hint)
        if (!selecting) {
            if (toolbar.menu.size() > 0) toolbar.menu.clear()
            return
        }
        if (toolbar.menu.findItem(R.id.selection_forward) == null) {
            toolbar.menu.clear()
            toolbar.inflateMenu(R.menu.chat_message_selection)
        }
        val actions = MessageSelectionActions.of(selected.values, viewModel.selectionProperties.value,
            chat?.hasProtectedContent == true, !readOnlyChannel && !viewModel.isScheduled)
        toolbar.menu.findItem(R.id.selection_forward).isVisible = actions.forward
        toolbar.menu.findItem(R.id.selection_forward_more).isVisible = actions.forward
        toolbar.menu.findItem(R.id.selection_copy).isVisible = actions.copy
        toolbar.menu.findItem(R.id.selection_edit).isVisible =
            selected.keys.singleOrNull()?.let { it == viewModel.editableSelectionId.value } == true
        toolbar.menu.findItem(R.id.selection_delete).isVisible = actions.deleteSelf || actions.deleteEveryone
        toolbar.menu.findItem(R.id.selection_favorite).isVisible = actions.favoriteSticker
        selectionForwardButton.isEnabled = actions.forward && !viewModel.selectionBusy.value
        selectionReplyButton.isEnabled = !viewModel.selectionBusy.value
        toolbar.menu.setGroupEnabled(0, !viewModel.selectionBusy.value)
        toolbar.menu.findItem(R.id.selection_forward).iconTintList = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
        )
        selectionReplyButton.isVisible = actions.reply
    }

    private fun forwardSelection() {
        if (viewModel.selectionBusy.value) return
        val ids = viewModel.selectedMessages.value.keys.sorted().toLongArray()
        if (viewModel.header.value?.chat?.hasProtectedContent == true || ids.isEmpty() || ids.any { viewModel.selectionProperties.value[it]?.canBeForwarded != true }) return
        val navController = findNavController()
        navController.navigateSafe(
            R.id.action_chatFragment_to_fragment_chat_picker,
            ChatPickerFragment.createForwardBundle(requireArguments().getLong(ChatViewModel.ARG_CHAT_ID), ids),
        )
        if (navController.currentDestination?.id == R.id.chatPickerFragment) {
            viewModel.clearMessageSelection()
        }
    }

    private fun copySelection() {
        if (viewModel.selectionBusy.value) return
        val selected = viewModel.selectedMessages.value
        val properties = viewModel.selectionProperties.value
        val chat = viewModel.header.value?.chat
        if (chat?.hasProtectedContent == true || selected.isEmpty() ||
            selected.keys.any { properties[it]?.canBeCopied != true }
        ) return

        val context = requireContext()
        val ownName = getString(R.string.playback_you)
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                val date = DateFormat.getDateFormat(context)
                val time = DateFormat.getTimeFormat(context)
                val messages = selected.values.sortedWith(compareBy({ it.date }, { it.id }))
                selectedMessagesText(messages, { message ->
                    message.senderName ?: if (message.isOutgoing) ownName else chat?.title.orEmpty()
                }, { seconds -> "${date.format(seconds * 1000L)} ${time.format(seconds * 1000L)}" })
            }
            if (viewModel.header.value?.chat?.hasProtectedContent == true ||
                viewModel.selectedMessages.value !== selected
            ) return@launch
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.selection_copy), text))
        }
    }

    private fun deleteSelection() {
        if (viewModel.selectionBusy.value) return
        val messages = viewModel.selectedMessages.value
        if (messages.isEmpty()) return
        val properties = viewModel.selectionProperties.value
        val canSelf = messages.keys.all { properties[it]?.canBeDeletedOnlyForSelf == true }
        val canAll = messages.keys.all { properties[it]?.canBeDeletedForAllUsers == true }
        if (!canSelf && !canAll) return
        var revoke = !canSelf
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (canSelf) R.string.selection_delete else R.string.selection_delete_everyone)
            .setMessage(getString(R.string.selection_delete_confirm, messages.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.selection_delete) { _, _ -> viewModel.deleteSelectedMessages(revoke) }
        if (canAll && canSelf) dialog.setMultiChoiceItems(arrayOf(getString(R.string.selection_delete_everyone)), booleanArrayOf(false)) { _, _, checked -> revoke = checked }
        dialog.show()
    }

    /** Lift [bubble] over the blurred chat, with the reactions and the actions of [message]. */
    private fun openMenu(message: MessageItem, bubble: View) {
        if (menuMessage != null || message.state == MessageItem.State.PENDING) {
            return
        }
        val bubbleBounds = bubble.boundsOnScreen()
        val (bitmap, bounds) = snapshot(message, bubble, bubbleBounds)
        menuAnchor.bitmap = bitmap
        menuAnchor.bounds = bounds
        // The bubble may have moved by the time the menu closes, the avatar goes along
        val messageId = message.id
        val dx = bounds.left - bubbleBounds.left
        val dy = bounds.top - bubbleBounds.top
        menuAnchor.locate = {
            bubbleOf(messageId)?.boundsOnScreen()?.let {
                Rect(it.left + dx, it.top + dy, it.left + dx + bounds.width(), it.top + dy + bounds.height())
            }
        }
        menuAnchor.text = selectableTextOf(message, bubble)
        menuAnchor.viewport = { recyclerView.visibleBoundsOnScreen() }
        menuAnchor.restore = {
            setMenuBubbleHidden(messageId, false)
            recyclerView
        }
        menuMessage = message
        findNavController().navigateSafe(
            R.id.action_chatFragment_to_messageMenuDialogFragment,
            MessageMenuDialogFragment.createBundle(
                requireArguments().getLong(ChatViewModel.ARG_CHAT_ID),
                message.id,
                message.text?.takeIf { it.isNotBlank() },
                viewModel.isScheduled,
                viewModel.pinned.value.items.any { it.messageId == message.id },
            ),
        )
    }

    /** The text of [bubble] as it's laid out, for the menu to select a part of it over its picture. */
    private fun selectableTextOf(message: MessageItem, bubble: View): MessageMenuViewModel.SelectableText? {
        val textView = bubble.findViewById<TextView>(R.id.messageTextView)
            ?.takeIf { it.isShown && !it.text.isNullOrEmpty() } ?: return null
        val textSize = textView.textSize
        val typeface = textView.typeface
        val lineSpacingExtra = textView.lineSpacingExtra
        val lineSpacingMultiplier = textView.lineSpacingMultiplier
        val letterSpacing = textView.letterSpacing
        val breakStrategy = textView.breakStrategy
        val hyphenationFrequency = textView.hyphenationFrequency
        val includeFontPadding = textView.includeFontPadding
        val padding = Rect(textView.paddingLeft, textView.paddingTop, textView.paddingRight, textView.paddingBottom)
        return MessageMenuViewModel.SelectableText(
            text = textView.text,
            bounds = textView.boundsOnScreen(),
            shift = (textView.text.length - message.text.orEmpty().length).coerceAtLeast(0),
        ) {
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize)
            it.typeface = typeface
            it.setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            it.letterSpacing = letterSpacing
            it.breakStrategy = breakStrategy
            it.hyphenationFrequency = hyphenationFrequency
            it.includeFontPadding = includeFontPadding
            it.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        }
    }

    /** A picture of [bubble] and where it is, with the sender's avatar when it's drawn beside it. */
    private fun snapshot(message: MessageItem, bubble: View, bubbleBounds: Rect): Pair<Bitmap, Rect> {
        val picture = bubble.drawToBitmap().also {
            it.drawTexturesOf(bubble)
            it.clipToShapeOf(bubble)
        }
        val (avatarRect, sender) = avatarDecoration.avatarBeside(bubble.parent as View, message.senderId)
            ?: return picture to bubbleBounds
        val list = IntArray(2).also(recyclerView::getLocationOnScreen)
        val avatarBounds = Rect(avatarRect).apply { offset(list[0], list[1]) }
        val bounds = Rect(bubbleBounds).apply { union(avatarBounds) }
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        avatarDecoration.draw(canvas, sender, avatarBounds.left - bounds.left, avatarBounds.top - bounds.top)
        canvas.drawBitmap(picture, (bubbleBounds.left - bounds.left).toFloat(), (bubbleBounds.top - bounds.top).toFloat(), null)
        return bitmap to bounds
    }

    /**
     * Draw the frames of the videos playing in [bubble], a video's preview: a bitmap's canvas
     * doesn't draw what a TextureView shows.
     */
    private fun Bitmap.drawTexturesOf(bubble: View) {
        val origin = IntArray(2).also(bubble::getLocationOnScreen)
        val canvas = Canvas(this)
        fun visit(view: View) {
            if (!view.isShown || view.alpha == 0f) {
                return
            }
            if (view is android.view.TextureView) {
                val frame = view.takeIf { it.isAvailable }?.bitmap ?: return
                val location = IntArray(2).also(view::getLocationOnScreen)
                canvas.drawBitmap(frame, (location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat(), null)
                frame.recycle()
                // What is over it (a duration) again on top
                val parent = view.parent as? android.view.ViewGroup ?: return
                parent.children.drop(parent.indexOfChild(view) + 1).filter { it.isShown }.forEach { above ->
                    val at = IntArray(2).also(above::getLocationOnScreen)
                    canvas.withTranslation((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat()) {
                        above.draw(this)
                    }
                }
                return
            }
            (view as? android.view.ViewGroup)?.children?.forEach(::visit)
        }
        visit(bubble)
    }

    /**
     * Cut away what is outside the shape of [bubble]: drawn to a bitmap, the card doesn't clip
     * its photos to its corners, only the screen does.
     */
    private fun Bitmap.clipToShapeOf(bubble: View) {
        val card = bubble as? MaterialCardView ?: return
        val path = Path()
        ShapeAppearancePathProvider.getInstance().calculatePath(
            card.shapeAppearanceModel, 1f, RectF(0f, 0f, width.toFloat(), height.toFloat()), path
        )
        path.fillType = Path.FillType.INVERSE_WINDING
        Canvas(this).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        })
    }

    private fun View.boundsOnScreen(): Rect {
        val location = IntArray(2).also(::getLocationOnScreen)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    /** Open the chat's photos at [message], from its picture [view]. */
    private fun openViewer(message: MessageItem, view: ImageView) {
        if (viewerMessageId != 0L) {
            return
        }
        // A video's preview flies from the frame it's at
        val preview = (view.parent as? android.view.ViewGroup)?.children
            ?.filterIsInstance<com.github.borz7zy.shadowgram.ui.views.AnimationView>()
            ?.firstOrNull { it.isVisible && it.alpha == 1f && it.isAvailable }
            ?.bitmap
        viewerAnchor.drawable = preview?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
            ?: view.drawable?.constantState?.newDrawable(resources) ?: view.drawable
        viewerAnchor.frame = PhotoViewerDialogFragment.frameOf(view)
        viewerAnchor.locate = { messageId -> pictureOf(messageId)?.let(PhotoViewerDialogFragment::frameOf) }
        viewerAnchor.restore = {
            setViewerMediaHidden(viewerMessageId, false)
            recyclerView
        }
        viewerMessageId = message.id
        findNavController().navigateSafe(
            R.id.action_chatFragment_to_photoViewerDialogFragment,
            PhotoViewerDialogFragment.createBundle(
                requireArguments().getLong(ChatViewModel.ARG_CHAT_ID),
                message.id,
                albumOf(message.id),
            ),
        )
    }

    /** Every message of the album [messageId] is in, or just it. */
    private fun albumIdsOf(messageId: Long): LongArray {
        val row = adapter.currentList.firstOrNull { row ->
            row is MessageRow.Bubble && row.album.any { it.id == messageId }
        } as? MessageRow.Bubble
        return row?.album?.map { it.id }?.sorted()?.toLongArray() ?: longArrayOf(messageId)
    }

    /** The photos of the message [messageId] is in: its album's, or just it. */
    private fun albumOf(messageId: Long): LongArray {
        val row = adapter.currentList.firstOrNull { row ->
            row is MessageRow.Bubble && row.album.any { it.id == messageId }
        } as? MessageRow.Bubble
        return row?.album
            ?.filter {
                it.kind == ChatItem.MessagePreview.Kind.PHOTO || it.kind == ChatItem.MessagePreview.Kind.VIDEO
                        || it.kind == ChatItem.MessagePreview.Kind.ANIMATION
            }
            ?.map { it.id }?.sorted()?.toLongArray()
            ?: longArrayOf(messageId)
    }

    /** The picture of a photo message on screen, in its bubble or album; null if it isn't. */
    private fun pictureOf(messageId: Long): ImageView? {
        val position = adapter.currentList.indexOfFirst { row ->
            row is MessageRow.Bubble && (row.message.id == messageId || row.album.any { it.id == messageId })
        }
        val itemView = recyclerView.findViewHolderForAdapterPosition(position)?.itemView ?: return null
        val album = itemView.findViewById<AlbumView>(R.id.albumView)
        return when {
            album.isVisible -> album.imageOf(messageId)
            else -> itemView.findViewById<ImageView>(R.id.mediaImageView)?.takeIf { it.isVisible }
        }
    }

    /** What holds a picture with what is drawn over it (a video's preview), hidden together. */
    private val ImageView.frameView get() = parent as? View

    private fun setViewerMediaHidden(messageId: Long, isHidden: Boolean) {
        adapter.hiddenMediaMessageId = if (isHidden) messageId else 0L
        pictureOf(messageId)?.frameView?.alpha = if (isHidden) 0f else 1f
    }

    /** The bubble of a message on screen, null if it isn't. */
    private fun bubbleOf(messageId: Long): View? {
        val position = adapter.currentList.indexOfFirst {
            (it as? MessageRow.Bubble)?.message?.id == messageId
        }
        return recyclerView.findViewHolderForAdapterPosition(position)
            ?.itemView?.findViewById(R.id.bubbleCardView)
    }

    /** Hide or show again the bubble of the lifted message, wherever it is. */
    private fun setMenuBubbleHidden(messageId: Long, isHidden: Boolean) {
        adapter.hiddenMessageId = if (isHidden) messageId else 0L
        bubbleOf(messageId)?.alpha = if (isHidden) 0f else 1f
    }

    private fun onMenuResult(bundle: Bundle) {
        val message = menuMessage
        menuMessage = null
        message?.let { setMenuBubbleHidden(it.id, false) }
        menuAnchor.bitmap = null
        menuAnchor.locate = null
        menuAnchor.viewport = null
        menuAnchor.restore = null
        menuAnchor.text = null

        if (bundle.getString(MessageMenuDialogFragment.KEY_ACTION) == MessageMenuDialogFragment.ACTION_EDIT) {
            val id = bundle.getLong(MessageMenuDialogFragment.KEY_MESSAGE_ID)
            if (id != 0L) viewModel.startEditing(id)
            return
        }
        message ?: return

        when (bundle.getString(MessageMenuDialogFragment.KEY_ACTION)) {
            MessageMenuDialogFragment.ACTION_REPLY -> replyTo(message, null, 0)

            MessageMenuDialogFragment.ACTION_QUOTE -> replyTo(
                message,
                bundle.getString(MessageMenuDialogFragment.KEY_QUOTE),
                bundle.getInt(MessageMenuDialogFragment.KEY_QUOTE_POSITION),
            )

            MessageMenuDialogFragment.ACTION_REACTION -> viewModel.toggleReaction(
                message.id,
                bundle.getString(MessageMenuDialogFragment.KEY_EMOJI),
                bundle.getLong(MessageMenuDialogFragment.KEY_CUSTOM_EMOJI_ID),
            )

            MessageMenuDialogFragment.ACTION_SEND_NOW -> viewModel.sendNow(message.id)

            MessageMenuDialogFragment.ACTION_PROFILE_AUDIO -> viewLifecycleOwner.lifecycleScope.launch {
                val notice = when (val result = viewModel.addMessageAudioToProfile(message.id)) {
                    is Result.Success -> getString(R.string.profile_music_added)
                    is Result.Failure -> result.error.message
                    null -> getString(R.string.profile_music_invalid)
                }
                Toast.makeText(requireContext(), notice, Toast.LENGTH_SHORT).show()
            }

            MessageMenuDialogFragment.ACTION_PIN -> viewModel.setPinned(
                message.id, viewModel.pinned.value.items.none { it.messageId == message.id }
            )

            MessageMenuDialogFragment.ACTION_FORWARD -> findNavController().navigateSafe(
                R.id.action_messageMenuDialogFragment_to_fragment_chat_picker,
                ChatPickerFragment.createForwardBundle(
                    requireArguments().getLong(ChatViewModel.ARG_CHAT_ID), albumIdsOf(message.id)
                ),
            )

            MessageMenuDialogFragment.ACTION_DELETE -> confirmDelete(
                message.id, bundle.getBoolean(MessageMenuDialogFragment.KEY_CAN_REVOKE)
            )
        }
    }

    /** Reply to [message], to its [quote] when there's one, and type it. */
    private fun replyTo(message: MessageItem, quote: String?, quotePosition: Int) {
        if (viewModel.edit.value?.isSaving == true) return
        if (viewModel.edit.value != null) viewModel.cancelEditing()
        viewModel.setReply(
            ChatViewModel.Reply(
                message.id,
                when {
                    message.isOutgoing -> getString(R.string.playback_you)
                    else -> message.senderName ?: viewModel.header.value?.chat?.title.orEmpty()
                },
                quote ?: message.text?.takeIf { it.isNotBlank() } ?: getString(message.kind.stringResId),
                quote,
                quotePosition,
            )
        )
        composerLinearLayout.doOnLayout { composer ->
            if (composer.isShown) panels?.showKeyboard()
        }
    }

    /** Go to [messageId] in the chat and flash it, marking its [quote] when there's one. */
    private fun jumpTo(messageId: Long, quote: String? = null, quotePosition: Int = 0) {
        if (messageId == 0L) {
            return
        }
        pendingJump = MessageListAdapter.Highlight(messageId, quote, quotePosition)
        viewModel.loadAt(messageId)
        jumpIfLoaded()
    }

    private fun openSearch(isPinned: Boolean) {
        findNavController().navigateSafe(
            R.id.action_chatFragment_to_chatSearchFragment,
            ChatSearchFragment.createBundle(
                requireArguments().getLong(ChatViewModel.ARG_CHAT_ID),
                requireArguments().getInt(ChatViewModel.ARG_TOPIC_ID),
                isPinned,
            ),
        )
    }

    /**
     * Show the newest pinned message above what's on screen, like Telegram: one on screen hands
     * the bar to the one before it, above them all it starts over from the newest.
     */
    private fun updatePinnedBar() {
        if (!emojiInitialized) return
        val pinnedMessages = viewModel.pinned.value
        val pinned = pinnedMessages.items
        pinnedLinearLayout.isVisible = pinned.isNotEmpty()
        if (pinned.isEmpty()) {
            return
        }
        // Reversed: the last visible position is the oldest message on screen
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val top = layoutManager.findLastVisibleItemPosition()
        val oldestOnScreen = (top downTo 0).firstNotNullOfOrNull {
            (adapter.currentList.getOrNull(it) as? MessageRow.Bubble)?.message?.id
        } ?: Long.MAX_VALUE
        val index = pinned.indexOfFirst { it.messageId < oldestOnScreen }.coerceAtLeast(0)
        val item = pinned[index]
        shownPinnedId = item.messageId

        pinnedTitleTextView.text = when (pinnedMessages.total) {
            1 -> getString(R.string.chat_pinned_message)
            // Counted from the oldest, like Telegram
            else -> getString(R.string.chat_pinned_message_number, pinnedMessages.total - index)
        }
        pinnedTextView.text = Emoji.replaceEmoji(
            item.text?.takeIf { it.isNotBlank() } ?: getString(item.kind.stringResId),
            pinnedTextView.paint.fontMetricsInt,
            false,
        )
        pinnedIndicatorView.set(pinnedMessages.total, index)
    }

    /** Scroll to the reply's original and flash it, once the history around it is loaded. */
    private fun jumpIfLoaded() {
        // The diff may finish after the view is gone
        view ?: return
        val jump = pendingJump ?: return
        val position = adapter.currentList.indexOfFirst {
            it is MessageRow.Bubble && (it.message.id == jump.messageId || it.album.any { item -> item.id == jump.messageId })
        }
        if (position < 0) {
            return
        }
        pendingJump = null
        // Reversed: the offset is from the bottom, a third of the way up
        (recyclerView.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(position, recyclerView.height / 3)
        adapter.highlight(jump)
    }

    private fun confirmDelete(messageId: Long, canRevoke: Boolean) {
        var revoke = canRevoke
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.message_delete_title)
            .apply {
                if (canRevoke) {
                    setMultiChoiceItems(
                        arrayOf(getString(R.string.message_delete_for_all)), booleanArrayOf(true)
                    ) { _, _, isChecked -> revoke = isChecked }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.message_delete) { _, _ ->
                viewModel.deleteMessage(messageId, revoke)
            }
            .show()
    }

    private fun updateRecordMode() {
        val video = viewModel.isVideoRecordMode
        videoNoteMaterialButton.setIconResource(if (video) R.drawable.ic_videocam else R.drawable.ic_mic)
        val mode = getString(if (video) R.string.chat_video_note else R.string.chat_voice_note)
        val hint = getString(R.string.chat_record_mode, mode)
        videoNoteMaterialButton.contentDescription = hint
        videoNoteMaterialButton.tooltipText = hint
    }

    private fun updateSendButton() {
        val edit = viewModel.edit.value
        if (edit != null) {
            sendMaterialButton.isEnabled = !edit.isSaving && (edit.target.isCaption ||
                edit.replacement != null || !messageEditText.text.isNullOrBlank())
            sendMaterialButton.isVisible = true
            videoNoteMaterialButton.isVisible = false
            botCommandsMaterialButton.isVisible = false
            return
        }
        val canSend = !messageEditText.text.isNullOrBlank() || viewModel.attachments.isNotEmpty()
        sendMaterialButton.isEnabled = canSend
        sendMaterialButton.isVisible = canSend
        videoNoteMaterialButton.isVisible = !canSend
        botCommandsMaterialButton.isVisible = viewModel.hasBotCommands.value && messageEditText.text.isNullOrEmpty()
    }

    /** Picked from a system picker or shot: waits above the composer until sent. */
    private fun attach(uris: List<Uri>, asFiles: Boolean) {
        val picked = uris.map { InputContents.Picked(it, asFiles) }
        if (viewModel.edit.value == null && renderedEdit == null) {
            attachmentStripView.add(picked)
        } else {
            viewModel.attachments = (viewModel.attachments + picked).distinctBy { it.uri }
        }
    }

    private fun attachFromGallery(uris: List<Uri>) {
        val edit = viewModel.edit.value
        if (edit == null) {
            attach(uris, asFiles = false)
        } else {
            uris.singleOrNull()?.let {
                viewModel.replaceEditedAttachment(edit.target.message.id, InputContents.Picked(it, asFile = false))
            }
        }
    }

    private fun replaceFromPicker(uri: Uri?, asFile: Boolean) {
        val id = viewModel.editPickerMessageId ?: return
        viewModel.editPickerMessageId = null
        if (uri != null) {
            viewModel.replaceEditedAttachment(id, InputContents.Picked(uri, asFile))
            panels?.hide()
        }
    }

    private fun editAttachmentActions(edit: ChatViewModel.Edit): Set<AttachPanelView.Action> = when {
        !edit.target.canReplaceMedia -> emptySet()
        edit.target.isAudioAlbum || edit.target.isDocumentAlbum ->
            setOf(AttachPanelView.Action.FILES)
        edit.target.isVisualAlbum -> setOf(AttachPanelView.Action.GALLERY, AttachPanelView.Action.CAMERA)
        else -> setOf(AttachPanelView.Action.GALLERY, AttachPanelView.Action.CAMERA, AttachPanelView.Action.FILES)
    }

    /** Send what the composer has: the picked files with the text as the caption, or the text. */
    private fun send(scheduling: TdApi.MessageSchedulingState?) {
        val edit = viewModel.edit.value
        if (edit != null) {
            if (scheduling == null && renderedEdit?.target === edit.target) {
                messageEditText.text?.composerContents()?.let(viewModel::saveEdit)
            }
            return
        }
        if (renderedEdit != null) return
        val raw = messageEditText.text?.toString().orEmpty()
        val text = raw.trim().takeIf { it.isNotEmpty() }
        // Custom emoji offsets in the trimmed text
        val trimmed = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val customEmoji = messageEditText.text?.customEmojiRanges().orEmpty()
            .map { it.copy(offset = it.offset - trimmed) }
            .filter { text != null && it.offset >= 0 && it.offset + it.length <= text.length }
        val mentions = messageEditText.text?.mentionRanges().orEmpty()
            .map { it.copy(offset = it.offset - trimmed) }
            .filter { text != null && it.offset >= 0 && it.offset + it.length <= text.length }
        when {
            // Picked files go with the text as the caption of the first
            viewModel.attachments.isNotEmpty() -> {
                viewModel.send(viewModel.attachments, viewModel.isGrouped, text, scheduling)
                attachmentStripView.clear()
            }

            text.isNullOrEmpty() -> return
            else -> viewModel.send(text, customEmoji, mentions, scheduling)
        }
        notifySchedule(scheduling)
        messageEditText.text = null
        recyclerView.scrollToPosition(0)
    }

    /** A scheduled message doesn't show in the chat, say when it goes. */
    private fun notifySchedule(scheduling: TdApi.MessageSchedulingState?) {
        val message = when (scheduling) {
            is TdApi.MessageSchedulingStateSendAtDate ->
                getString(R.string.chat_scheduled, formatDate(scheduling.sendDate))

            is TdApi.MessageSchedulingStateSendWhenOnline ->
                getString(R.string.chat_scheduled_when_online)

            else -> return
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    /** Slide the send options out of the send button, or back in. */
    private fun setSendMenuOpen(open: Boolean) {
        if (isSendMenuOpen == open) {
            return
        }
        isSendMenuOpen = open
        sendMenuOnBackPressedCallback.isEnabled = open

        val scrimView = sendMenuScrimView
        if (open) {
            scrimView.isVisible = true
        }
        scrimView.animate()
            .alpha(if (open) 1f else 0f)
            .withEndAction { scrimView.isVisible = isSendMenuOpen }

        // The closest one first, like the new chat menu
        sendMenuLinearLayout.children
            .filter { it != sendWhenOnlineMaterialButton || it.tag == true }
            .toList()
            .asReversed()
            .forEachIndexed { index, action ->
                val offset = action.resources.displayMetrics.density *
                        SEND_MENU_ACTION_OFFSET_DP * (index + 1)
                if (open) {
                    action.alpha = 0f
                    action.translationY = offset
                    action.isVisible = true
                }
                action.animate()
                    .alpha(if (open) 1f else 0f)
                    .translationY(if (open) 0f else offset)
                    .withEndAction { action.isVisible = isSendMenuOpen }
            }
    }

    private fun formatDate(unixTime: Int) = DateUtils.formatDateTime(
        requireContext(),
        unixTime * 1000L,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    /** Pick a day and a time in the future, [onPicked] gets it as unix time. */
    private fun pickScheduleDate(onPicked: (Int) -> Unit) {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setCalendarConstraints(
                CalendarConstraints.Builder()
                    .setValidator(DateValidatorPointForward.now())
                    .build()
            )
            .build()
        datePicker.addOnPositiveButtonClickListener { utcDay ->
            val now = Calendar.getInstance()
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(
                    if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H
                    else TimeFormat.CLOCK_12H
                )
                .setHour(now.get(Calendar.HOUR_OF_DAY))
                .setMinute(now.get(Calendar.MINUTE))
                .build()
            timePicker.addOnPositiveButtonClickListener {
                // The date picker gives midnight UTC of the picked day, the time is local
                val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDay }
                val at = Calendar.getInstance().apply {
                    set(
                        day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH),
                        timePicker.hour, timePicker.minute, 0,
                    )
                }
                // Telegram needs it in the future
                val seconds = (at.timeInMillis / 1000).toInt()
                    .coerceAtLeast((System.currentTimeMillis() / 1000).toInt() + MIN_SCHEDULE_DELAY_S)
                onPicked(seconds)
            }
            timePicker.show(childFragmentManager, "schedule_time")
        }
        datePicker.show(childFragmentManager, "schedule_date")
    }

    @SuppressLint("MissingPermission") // Asked for right before, see locationPermission
    private fun sendLocation() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val location = withContext(Dispatchers.IO) {
                try {
                    val manager = context.getSystemService(LocationManager::class.java)
                    val provider = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                            LocationManager.FUSED_PROVIDER

                        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                            LocationManager.GPS_PROVIDER

                        else -> LocationManager.NETWORK_PROVIDER
                    }
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val executor = Dispatchers.IO.asExecutor()
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { executor.execute { signal.cancel() } }
                        LocationManagerCompat.getCurrentLocation(
                            manager, provider, signal, executor,
                        ) { continuation.resume(it) }
                    }
                } catch (e: SecurityException) {
                    Log.w("ChatFragment", "Location permission changed", e)
                    null
                }
            }
            when (location) {
                null -> Toast.makeText(context, R.string.chat_location_failed, Toast.LENGTH_LONG).show()
                else -> {
                    viewModel.sendLocation(location.latitude, location.longitude, location.accuracy.toDouble())
                    panels?.hide()
                    recyclerView.scrollToPosition(0)
                }
            }
        }
    }

    private fun sendContact(uri: Uri) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val resolver = requireContext().contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val (number, name) = withContext(Dispatchers.IO) {
                try {
                    resolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty()
                        else null
                    }
                } catch (e: SecurityException) {
                    Log.w("ChatFragment", "Contact is no longer accessible", e)
                    null
                }
            } ?: return@launch
            viewModel.sendContact(number, name)
            panels?.hide()
            recyclerView.scrollToPosition(0)
        }
    }

    private fun setRows(result: FlowResult<ChatViewModel.Rows, Nothing>) {
        linearProgressIndicator.setProgressCompat(result)

        if (result !is FlowResult.Success) {
            return
        }

        val rows = result.data.rows
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        // Only once the list shows where the chat opens, before that it's at the bottom for
        // a frame and following new messages would undo the positioning
        // Not laid out yet (the view was just made again): it is wherever it was put, not at the bottom
        val wasAtBottom = canView && result.data.isNewestLoaded && layoutManager.childCount > 0
                && layoutManager.findFirstCompletelyVisibleItemPosition() <= 0

        // The diff runs in background and may complete after this view is gone,
        // so the callback must not look views up again
        val recyclerView = recyclerView
        adapter.submitList(rows) {
            when {
                !viewModel.isPositioned -> {
                    viewModel.isPositioned = true
                    positionAtUnread(recyclerView, rows)
                }

                // A reply's original was asked for: there, rather than anywhere else
                pendingJump != null -> jumpIfLoaded()

                // Back to the newest, reloaded for it
                isScrollToNewestPending && result.data.isNewestLoaded && rows.isNotEmpty() -> {
                    isScrollToNewestPending = false
                    recyclerView.scrollToPosition(0)
                }

                // Follow new messages, unless reading older ones
                wasAtBottom -> recyclerView.scrollToPosition(0)
            }
        }

        noElementsLinearLayout.isVisible = rows.isEmpty()

        // A page of newer messages may not fill the screen, nothing scrolls to ask for the next
        if (!result.data.isNewestLoaded && layoutManager.childCount > 0
            && layoutManager.findFirstVisibleItemPosition() <= LOAD_MORE_THRESHOLD
        ) {
            viewModel.loadNewer()
        }
    }

    /** Where the @word before the cursor starts, null when the cursor isn't in one. */
    private fun mentionStart(): Int? {
        val text = messageEditText.text ?: return null
        val cursor = messageEditText.selectionStart.takeIf { it == messageEditText.selectionEnd } ?: return null
        var start = cursor - 1
        while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '_')) {
            start--
        }
        return start.takeIf { it >= 0 && text[it] == '@' && (it == 0 || text[it - 1].isWhitespace()) }
    }

    /** The @word being typed, without the @; null when there's none. */
    private fun mentionQuery() = mentionStart()?.let {
        messageEditText.text?.substring(it + 1, messageEditText.selectionStart)
    }

    /** Put the member in place of the @word: by username, or by name linked to them. */
    private fun mention(suggestion: ChatViewModel.Action.Mention) {
        val text = messageEditText.text ?: return
        val start = mentionStart() ?: return
        val end = messageEditText.selectionStart
        when (val username = suggestion.username) {
            null -> {
                text.replace(start, end, suggestion.name + " ")
                text.setSpan(
                    MentionSpan(
                        suggestion.userId,
                        MaterialColors.getColor(messageEditText, androidx.appcompat.R.attr.colorPrimary),
                    ),
                    start,
                    start + suggestion.name.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            else -> text.replace(start, end, "@$username ")
        }
    }

    /** The rows over the composer, a few rows high at most. */
    private fun setSuggestions(suggestions: List<ChatViewModel.Suggestion>) {
        val isShown = suggestions.isNotEmpty()
        suggestionsRecyclerView.isVisible = isShown
        suggestionsAdapter.submitList(suggestions)
        val rowHeight = resources.getDimensionPixelSize(R.dimen.chat_mention_row_height)
        suggestionsRecyclerView.updateLayoutParams {
            height = when {
                suggestions.size > MENTION_MAX_ROWS -> (rowHeight * (MENTION_MAX_ROWS + 0.5f)).toInt()
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        if (isShown) {
            suggestionsRecyclerView.scrollToPosition(0)
        }
    }

    private val suggestionsAdapter = SuggestionsAdapter {
        when (val action = it.action) {
            is ChatViewModel.Action.Mention -> mention(action)
            is ChatViewModel.Action.Command -> {
                if (viewModel.edit.value != null) return@SuggestionsAdapter
                viewModel.send(action.text)
                messageEditText.text = null
            }

            is ChatViewModel.Action.InlineResult -> {
                if (viewModel.edit.value != null) return@SuggestionsAdapter
                viewModel.sendInlineResult(action)
                messageEditText.text = null
            }
        }
    }

    /** A row over the composer: the avatar or thumbnail, the name, a line under it. */
    private class SuggestionsAdapter(
        private val onClick: (ChatViewModel.Suggestion) -> Unit,
    ) : androidx.recyclerview.widget.ListAdapter<ChatViewModel.Suggestion, SuggestionsAdapter.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<ChatViewModel.Suggestion>() {
            override fun areItemsTheSame(oldItem: ChatViewModel.Suggestion, newItem: ChatViewModel.Suggestion) =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: ChatViewModel.Suggestion, newItem: ChatViewModel.Suggestion) =
                oldItem == newItem
        }
    ) {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val avatarView: com.github.borz7zy.shadowgram.ui.views.AvatarView = view.findViewById(R.id.avatarView)
            val nameTextView: TextView = view.findViewById(R.id.nameTextView)
            val textTextView: TextView = view.findViewById(R.id.textTextView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            android.view.LayoutInflater.from(parent.context).inflate(R.layout.chat_search_item, parent, false).apply {
                findViewById<View>(R.id.dateTextView).isVisible = false
            }
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val suggestion = getItem(position)
            holder.itemView.setOnClickListener { onClick(suggestion) }
            holder.avatarView.setChat(
                ChatItem(
                    id = suggestion.avatarId,
                    title = suggestion.name,
                    accentColorId = suggestion.accentColorId,
                    photoPath = suggestion.photoPath,
                    minithumbnail = null,
                    isPinned = false,
                    order = 0L,
                    lastMessage = null,
                    draft = null,
                    typing = null,
                    unreadCount = 0,
                    unreadMentionCount = 0,
                    isMarkedAsUnread = false,
                    isMuted = false,
                )
            )
            holder.nameTextView.text = suggestion.name
            holder.textTextView.isVisible = suggestion.text != null
            holder.textTextView.text = suggestion.text
        }
    }

    /** At the newest message, nothing newer to scroll to. */
    private fun isAtNewest(): Boolean {
        val rows = (viewModel.rows.value as? FlowResult.Success)?.data ?: return true
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return !isScrollDownShown
        return rows.isNewestLoaded && first == 0
    }

    private fun setScrollDownShown(isShown: Boolean) {
        val isShown = isShown && !viewModel.isScheduled
        if (isScrollDownShown == isShown) {
            return
        }
        isScrollDownShown = isShown
        val view = scrollDownFrameLayout
        if (isShown) {
            view.isVisible = true
            view.scaleX = 0.6f
            view.scaleY = 0.6f
            view.alpha = 0f
        }
        view.animate().cancel()
        view.animate()
            .alpha(if (isShown) 1f else 0f)
            .scaleX(if (isShown) 1f else 0.6f)
            .scaleY(if (isShown) 1f else 0.6f)
            .withEndAction { view.isVisible = isScrollDownShown }
    }

    /** To the newest message: scrolled to when it's loaded, reloaded first when it isn't. */
    private fun scrollToNewest() {
        val rows = (viewModel.rows.value as? FlowResult.Success)?.data
        setScrollDownShown(false)
        if (rows?.isNewestLoaded != true) {
            isScrollToNewestPending = true
            viewModel.showNewest()
            return
        }
        // A long way: most of it at once, the end of it smoothly
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstVisibleItemPosition() > SMOOTH_SCROLL_MAX_ITEMS) {
            recyclerView.scrollToPosition(SMOOTH_SCROLL_MAX_ITEMS)
        }
        recyclerView.smoothScrollToPosition(0)
    }

    /** The oldest unread mention or unseen reaction, viewed so the count goes down. */
    private fun goToUnread(isReaction: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val messageId = viewModel.firstUnread(isReaction) ?: return@launch
            jumpTo(messageId)
            viewModel.view(longArrayOf(messageId))
        }
    }

    /** The mentions and reactions buttons while there are unread ones, the unread count on the way down. */
    private fun bindJumpButtons(chat: ChatItem) {
        val isScheduled = viewModel.isScheduled
        fun bind(frame: View, count: TextView, unread: Int) {
            frame.isVisible = !isScheduled && unread > 0
            count.isVisible = unread > 0
            count.text = unread.toString()
        }
        bind(mentionsFrameLayout, mentionsCountTextView, chat.unreadMentionCount)
        bind(reactionsFrameLayout, reactionsCountTextView, chat.unreadReactionCount)
        scrollDownCountTextView.isVisible = chat.unreadCount > 0
        val count = chat.unreadCount.toString()
        if (scrollDownCountTextView.text.toString() != count) scrollDownCountTextView.text = count
    }

    /**
     * Open with the unread messages separator at the top of the screen, or at the newest message
     * when everything is read. Viewing starts after that layout.
     */
    private fun positionAtUnread(recyclerView: RecyclerView, rows: List<MessageRow>) {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val unread = rows.indexOf(MessageRow.UnreadSeparator)

        when (unread) {
            -1 -> layoutManager.scrollToPosition(0)
            // Reversed: the offset is from the bottom edge, leave the separator's height above it
            else -> layoutManager.scrollToPositionWithOffset(
                unread,
                recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom
                        - recyclerView.toPx(UNREAD_SEPARATOR_HEIGHT_DP),
            )
        }

        recyclerView.doOnNextLayout {
            canView = true
            viewVisibleMessages()
        }
        recyclerView.requestLayout()
    }

    /**
     * Report the incoming messages on screen as viewed, TDLib marks them as read.
     */
    private fun viewVisibleMessages() {
        if (!canView) {
            return
        }
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) {
            return
        }

        val ids = (first..last).mapNotNull {
            (adapter.currentList.getOrNull(it) as? MessageRow.Bubble)?.message
                ?.takeIf { message -> !message.isOutgoing && message.id > lastViewedMessageId }
                ?.id
        }
        if (ids.isEmpty()) {
            return
        }

        lastViewedMessageId = ids.max()
        viewModel.view(ids.toLongArray())
    }

    companion object {
        private const val STATE_DRAFT = "chat_draft"
        private const val STATE_DRAFT_START = "chat_draft_start"
        private const val STATE_DRAFT_END = "chat_draft_end"

        private const val LOAD_MORE_THRESHOLD = 15
        private const val SCROLL_SLOP_PX = 8
        private const val SMOOTH_SCROLL_MAX_ITEMS = 15
        private const val MENTION_MAX_ROWS = 4

        /** Typed for a custom emoji that names no emoji. */
        private const val CUSTOM_EMOJI_FALLBACK = "⭐"
        private const val MIN_SCHEDULE_DELAY_S = 60
        private const val SEND_MENU_ACTION_OFFSET_DP = 24
        private const val UNREAD_SEPARATOR_HEIGHT_DP = 56

        /**
         * Create a [Bundle] to use as the arguments for this fragment or its activity.
         *
         * @param chatId The chat to open
         * @param topicId The forum topic to open, 0 for the whole chat
         * @param isScheduled Show the scheduled messages instead
         */
        fun createBundle(chatId: Long, topicId: Int = 0, isScheduled: Boolean = false) = Bundle().apply {
            putLong(ChatViewModel.ARG_CHAT_ID, chatId)
            putInt(ChatViewModel.ARG_TOPIC_ID, topicId)
            putBoolean(ChatViewModel.ARG_SCHEDULED, isScheduled)
        }
    }
}

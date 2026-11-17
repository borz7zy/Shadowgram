/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.text.Spanned
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.core.view.children
import androidx.core.graphics.withTranslation
import androidx.core.view.doOnNextLayout
import androidx.core.view.drawToBitmap
import androidx.core.view.descendants
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.ActivityNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.models.MessageRow
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.InputContents
import com.github.borz7zy.shadowgram.telegram.StickersStore
import com.github.borz7zy.shadowgram.ui.composer.AttachPanelView
import com.github.borz7zy.shadowgram.ui.composer.AttachmentStripView
import com.github.borz7zy.shadowgram.ui.composer.GalleryPanelView
import com.github.borz7zy.shadowgram.ui.composer.KeyboardListener
import com.github.borz7zy.shadowgram.ui.composer.KeyboardPanels
import com.github.borz7zy.shadowgram.ui.composer.RecentEmoji
import com.github.borz7zy.shadowgram.ui.composer.StickerKeyboardView
import com.github.borz7zy.shadowgram.ui.recyclerview.MessageListAdapter
import com.github.borz7zy.shadowgram.ui.recyclerview.SenderAvatarDecoration
import com.github.borz7zy.shadowgram.ui.views.ChatHeaderView
import com.github.borz7zy.shadowgram.ui.views.TopRoundedLayout
import com.github.borz7zy.shadowgram.videonote.VideoNoteCamera
import com.github.borz7zy.shadowgram.videonote.VideoNoteDialogFragment
import com.github.borz7zy.shadowgram.ui.text.CustomEmojiSpan
import com.github.borz7zy.shadowgram.ui.text.attachCustomEmoji
import com.github.borz7zy.shadowgram.ui.text.customEmojiRanges
import com.github.borz7zy.shadowgram.ui.text.removeEmojiUnderCustom
import com.github.borz7zy.shadowgram.ui.text.stringResId
import com.github.borz7zy.shadowgram.viewmodels.ChatViewModel
import com.github.borz7zy.shadowgram.viewmodels.MessageMenuViewModel
import com.github.borz7zy.shadowgram.viewmodels.PhotoViewerViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.ShapeAppearancePathProvider
import com.github.borz7zy.shadowgram.ui.album.AlbumView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
    private val replyLinearLayout by getViewProperty<LinearLayout>(R.id.replyLinearLayout)
    private val replyTitleTextView by getViewProperty<TextView>(R.id.replyTitleTextView)
    private val replyTextTextView by getViewProperty<TextView>(R.id.replyTextTextView)
    private val cancelReplyMaterialButton by getViewProperty<MaterialButton>(R.id.cancelReplyMaterialButton)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val messageEditText by getViewProperty<TextInputEditText>(R.id.messageEditText)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val sendMaterialButton by getViewProperty<MaterialButton>(R.id.sendMaterialButton)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val videoNoteMaterialButton by getViewProperty<MaterialButton>(R.id.videoNoteMaterialButton)
    private val attachMaterialButton by getViewProperty<MaterialButton>(R.id.attachMaterialButton)
    private val emojiMaterialButton by getViewProperty<MaterialButton>(R.id.emojiMaterialButton)
    private val galleryMaterialButton by getViewProperty<MaterialButton>(R.id.galleryMaterialButton)
    private val panelCardView by getViewProperty<TopRoundedLayout>(R.id.panelCardView)
    private val stickerKeyboardView by getViewProperty<StickerKeyboardView>(R.id.stickerKeyboardView)
    private val galleryPanelView by getViewProperty<GalleryPanelView>(R.id.galleryPanelView)
    private val attachPanelView by getViewProperty<AttachPanelView>(R.id.attachPanelView)
    private val attachmentStripView by getViewProperty<AttachmentStripView>(R.id.attachmentStripView)

    // Panels under the composer, set up with the view
    private var panels: KeyboardPanels? = null

    /** The sticker keyboard loads the account's stickers the first time it opens. */
    private var isCollectingStickers = false

    /**
     * Redraws emoji whose sprite just loaded; Emoji keeps it weakly. Every text of the rows, not
     * just the message's: reactions, sender names and the reply quote draw them too.
     */
    private val emojiInvalidator = Runnable {
        messageEditText.invalidate()
        replyTextTextView.invalidate()
        recyclerView.descendants.filterIsInstance<TextView>().forEach(View::invalidate)
    }

    // Pickers and permissions
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> attach(uris, asFiles = false) }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> attach(uris, asFiles = true) }

    private var photoUri: Uri? = null
    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { isTaken -> photoUri?.takeIf { isTaken }?.let { attach(listOf(it), asFiles = false) } }

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> result.data?.data?.let(::sendContact) }

    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { galleryPanelView.reload() }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { galleryPanelView.reload() }

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
            override fun onMessageClick(message: MessageItem, bubble: View) = openMenu(message, bubble)

            override fun onSenderClick(senderId: Long) = openProfile(senderId)

            override fun onMediaClick(message: MessageItem, view: ImageView) = openViewer(message, view)

            override fun onDocumentClick(message: MessageItem) {
                val document = message.document ?: return
                Downloads.onTap(requireContext(), document) { viewModel.toggleDownload(document) }
            }

            override fun onReactionClick(message: MessageItem, reaction: MessageItem.Reaction) {
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

    /** The message whose menu is open. */
    private var menuMessage: MessageItem? = null

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
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets: the composer follows the keyboard, or stays above the navigation bar and a
        // panel that took the keyboard's place
        panels = KeyboardPanels(
            this,
            composerLinearLayout,
            panelCardView,
            mapOf(
                KeyboardPanels.Panel.STICKERS to stickerKeyboardView,
                KeyboardPanels.Panel.GALLERY to galleryPanelView,
                KeyboardPanels.Panel.ATTACH to attachPanelView,
            ),
            messageEditText,
            ::onPanelChanged,
        )
        setUpComposer()

        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // The profile of the chat, of the whole group for a topic. The avatar flies into its
        // header while the messages and the composer give way to the profile content
        chatHeaderView.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_chatFragment_to_profileActivity,
                ProfileFragment.createBundle(
                    requireArguments().getLong(ChatViewModel.ARG_CHAT_ID), isFromChat = true
                ),
                navigatorExtras = ActivityNavigatorExtras(
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        requireActivity(),
                        chatHeaderView.avatarView,
                        getString(R.string.transition_profile_avatar),
                    )
                ),
            )
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
        }
        recyclerView.adapter = adapter
        avatarDecoration = SenderAvatarDecoration(recyclerView, adapter)
        recyclerView.addItemDecoration(avatarDecoration)
        recyclerView.addOnItemTouchListener(avatarTouchListener)
        recyclerView.addOnScrollListener(onScrollListener)
        // Rotated: the list restores its own position
        canView = viewModel.isPositioned

        messageEditText.doAfterTextChanged {
            // Emoji drawn from Telegram's sprites, set on the text in place
            it?.let { text ->
                Emoji.replaceEmoji(text, messageEditText.paint.fontMetricsInt, false)
                // Custom emoji stay drawn over the plain ones they stand for
                text.removeEmojiUnderCustom()
                messageEditText.attachCustomEmoji()
            }
            updateSendButton()
        }

        // CameraX and both cameras' capabilities, so the recorder opens at once
        VideoNoteCamera.prewarm(requireContext())

        videoNoteMaterialButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_chatFragment_to_videoNoteDialogFragment, requireArguments()
            )
        }

        setFragmentResultListener(PhotoViewerDialogFragment.REQUEST_KEY_SHOWN) { _, bundle ->
            // The viewer went to another photo: that one is hidden now
            pictureOf(viewerMessageId)?.frameView?.alpha = 1f
            viewerMessageId = bundle.getLong(PhotoViewerDialogFragment.KEY_MESSAGE_ID)
            pictureOf(viewerMessageId)?.frameView?.alpha = 0f
        }
        setFragmentResultListener(PhotoViewerDialogFragment.REQUEST_KEY) { _, _ ->
            pictureOf(viewerMessageId)?.frameView?.alpha = 1f
            viewerMessageId = 0L
            viewerAnchor.drawable = null
            viewerAnchor.locate = null
        }
        setFragmentResultListener(MessageMenuDialogFragment.REQUEST_KEY_SHOWN) { _, _ ->
            menuMessage?.let { setMenuBubbleHidden(it.id, true) }
        }
        setFragmentResultListener(MessageMenuDialogFragment.REQUEST_KEY) { _, bundle ->
            onMenuResult(bundle)
        }
        cancelReplyMaterialButton.setOnClickListener { viewModel.setReply(null) }

        setFragmentResultListener(VideoNoteDialogFragment.REQUEST_KEY) { _, bundle ->
            bundle.getString(VideoNoteDialogFragment.KEY_PATH)?.let {
                viewModel.sendVideoNote(File(it))
                recyclerView.scrollToPosition(0)
            }
        }

        sendMaterialButton.setOnClickListener {
            val raw = messageEditText.text?.toString().orEmpty()
            val text = raw.trim().takeIf { it.isNotEmpty() }
            // Custom emoji offsets in the trimmed text
            val trimmed = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val customEmoji = messageEditText.text?.customEmojiRanges().orEmpty()
                .map { it.copy(offset = it.offset - trimmed) }
                .filter { text != null && it.offset >= 0 && it.offset + it.length <= text.length }
            notifySchedule()
            when {
                // Picked files go with the text as the caption of the first
                viewModel.attachments.isNotEmpty() -> {
                    viewModel.send(viewModel.attachments, viewModel.isGrouped, caption = text)
                    attachmentStripView.clear()
                }

                text.isNullOrEmpty() -> return@setOnClickListener
                else -> viewModel.send(text, customEmoji)
            }
            messageEditText.text = null
            recyclerView.scrollToPosition(0)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.header.collectLatest { it?.let(chatHeaderView::setHeader) }
                }

                launch {
                    viewModel.rows.collectLatest(::setRows)
                }

                launch {
                    viewModel.sendFailures.collect(::onSendFailed)
                }

                launch {
                    viewModel.replyTo.collectLatest { reply ->
                        replyLinearLayout.isVisible = reply != null
                        reply ?: return@collectLatest
                        replyTitleTextView.text = getString(R.string.message_replying_to, reply.name)
                        replyTextTextView.text = Emoji.replaceEmoji(
                            reply.text, replyTextTextView.paint.fontMetricsInt, false
                        )
                    }
                }

                launch {
                    viewModel.scheduleAt.collectLatest { at ->
                        messageEditText.hint = when (at) {
                            null -> getString(R.string.chat_message_hint)
                            else -> getString(R.string.chat_scheduled_hint, formatDate(at))
                        }
                        attachPanelView.setScheduled(at != null)
                    }
                }
            }
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

    override fun onDestroyView() {
        menuMessage?.let { setMenuBubbleHidden(it.id, false) }
        menuMessage = null
        menuAnchor.locate = null
        recyclerView.removeOnItemTouchListener(avatarTouchListener)
        Emoji.unregisterInvalidator(emojiInvalidator)
        galleryPanelView.stop()
        panels = null
        recyclerView.removeOnScrollListener(onScrollListener)
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private fun setUpComposer() {
        Emoji.registerInvalidator(emojiInvalidator)

        attachMaterialButton.setOnClickListener { panels?.toggle(KeyboardPanels.Panel.ATTACH) }
        emojiMaterialButton.setOnClickListener { panels?.toggle(KeyboardPanels.Panel.STICKERS) }
        galleryMaterialButton.setOnClickListener { panels?.toggle(KeyboardPanels.Panel.GALLERY) }

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
                notifySchedule()
                viewModel.sendSticker(sticker)
                recyclerView.scrollToPosition(0)
            }

            override fun onGif(gif: StickersStore.Gif) {
                notifySchedule()
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

            override fun onRequestCameraPermission() =
                cameraPermission.launch(Manifest.permission.CAMERA)

            override fun onFolders() = pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )

            override fun onPhotoTaken(file: File) = attach(listOf(Uri.fromFile(file)), asFiles = false)

            override fun onConfirm(selection: List<Uri>) {
                attach(selection, asFiles = false)
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

        attachPanelView.onAction = { action ->
            when (action) {
                AttachPanelView.Action.CAMERA -> {
                    val directory = File(requireContext().cacheDir, "outgoing/${System.nanoTime()}")
                        .apply { mkdirs() }
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.files",
                        File(directory, "photo.jpg"),
                    )
                    photoUri = uri
                    takePhoto.launch(uri)
                }

                AttachPanelView.Action.FILES -> pickFiles.launch(arrayOf("*/*"))

                AttachPanelView.Action.LOCATION -> locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )

                AttachPanelView.Action.CONTACT -> pickContact.launch(
                    Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
                )

                AttachPanelView.Action.SCHEDULE -> when (viewModel.scheduleAt.value) {
                    null -> pickScheduleDate()
                    else -> {
                        viewModel.scheduleAt.value = null
                        Toast.makeText(requireContext(), R.string.chat_schedule_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun onPanelChanged(panel: KeyboardPanels.Panel?) {
        emojiMaterialButton.setIconResource(
            if (panel == KeyboardPanels.Panel.STICKERS) R.drawable.ic_keyboard else R.drawable.ic_mood
        )
        emojiMaterialButton.contentDescription = getString(
            if (panel == KeyboardPanels.Panel.STICKERS) R.string.chat_keyboard else R.string.chat_emoji
        )
        attachMaterialButton.setIconResource(
            if (panel == KeyboardPanels.Panel.ATTACH) R.drawable.ic_close else R.drawable.ic_add_circle
        )
        attachMaterialButton.contentDescription = getString(
            if (panel == KeyboardPanels.Panel.ATTACH) R.string.chat_attach_close else R.string.chat_attach
        )

        when (panel) {
            KeyboardPanels.Panel.GALLERY -> galleryPanelView.start(viewLifecycleOwner)
            else -> galleryPanelView.stop()
        }

        if (panel == KeyboardPanels.Panel.STICKERS && !isCollectingStickers) {
            isCollectingStickers = true
            val stickerKeyboardView = stickerKeyboardView
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(viewModel.stickers, viewModel.foundGifs, ::Pair).collectLatest { (stickers, gifs) ->
                        stickerKeyboardView.setStickers(stickers, gifs)
                    }
                }
            }
        }
    }

    /** The profile of a sender, a user or a channel. */
    private fun openProfile(senderId: Long) {
        // The private chat of a user has the user id
        findNavController().navigateSafe(
            R.id.action_chatFragment_to_profileActivity,
            ProfileFragment.createBundle(senderId),
        )
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
        menuMessage = message
        findNavController().navigateSafe(
            R.id.action_chatFragment_to_messageMenuDialogFragment,
            MessageMenuDialogFragment.createBundle(
                requireArguments().getLong(ChatViewModel.ARG_CHAT_ID),
                message.id,
                message.text?.takeIf { it.isNotBlank() },
            ),
        )
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

    /** The photos of the message [messageId] is in: its album's, or just it. */
    private fun albumOf(messageId: Long): LongArray {
        val row = adapter.currentList.firstOrNull { row ->
            row is MessageRow.Bubble && row.album.any { it.id == messageId }
        } as? MessageRow.Bubble
        return row?.album
            ?.filter {
                it.kind == ChatItem.MessagePreview.Kind.PHOTO || it.kind == ChatItem.MessagePreview.Kind.VIDEO
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
        val message = menuMessage ?: return
        menuMessage = null
        setMenuBubbleHidden(message.id, false)
        menuAnchor.bitmap = null
        menuAnchor.locate = null

        when (bundle.getString(MessageMenuDialogFragment.KEY_ACTION)) {
            MessageMenuDialogFragment.ACTION_REPLY -> {
                viewModel.setReply(
                    ChatViewModel.Reply(
                        message.id,
                        when {
                            message.isOutgoing -> getString(R.string.playback_you)
                            else -> message.senderName ?: viewModel.header.value?.chat?.title.orEmpty()
                        },
                        message.text?.takeIf { it.isNotBlank() }
                            ?: getString(message.kind.stringResId),
                    )
                )
                messageEditText.requestFocus()
                androidx.core.view.WindowCompat.getInsetsController(requireActivity().window, messageEditText)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }

            MessageMenuDialogFragment.ACTION_REACTION -> viewModel.toggleReaction(
                message.id,
                bundle.getString(MessageMenuDialogFragment.KEY_EMOJI),
                bundle.getLong(MessageMenuDialogFragment.KEY_CUSTOM_EMOJI_ID),
            )

            MessageMenuDialogFragment.ACTION_DELETE -> confirmDelete(
                message.id, bundle.getBoolean(MessageMenuDialogFragment.KEY_CAN_REVOKE)
            )
        }
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

    private fun updateSendButton() {
        val canSend = !messageEditText.text.isNullOrBlank() || viewModel.attachments.isNotEmpty()
        sendMaterialButton.isEnabled = canSend
        sendMaterialButton.isVisible = canSend
        videoNoteMaterialButton.isVisible = !canSend
    }

    /** Picked from a system picker or shot: waits above the composer until sent. */
    private fun attach(uris: List<Uri>, asFiles: Boolean) {
        attachmentStripView.add(uris.map { InputContents.Picked(it, asFiles) })
    }

    /** A scheduled message doesn't show in the chat, say when it goes. */
    private fun notifySchedule() {
        val at = viewModel.scheduleAt.value ?: return
        Toast.makeText(
            requireContext(), getString(R.string.chat_scheduled, formatDate(at)), Toast.LENGTH_LONG
        ).show()
    }

    private fun formatDate(unixTime: Int) = DateUtils.formatDateTime(
        requireContext(),
        unixTime * 1000L,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    private fun pickScheduleDate() {
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
                viewModel.scheduleAt.value = seconds
                panels?.hide()
            }
            timePicker.show(childFragmentManager, "schedule_time")
        }
        datePicker.show(childFragmentManager, "schedule_date")
    }

    @SuppressLint("MissingPermission") // Asked for right before, see locationPermission
    private fun sendLocation() {
        val context = requireContext()
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER

            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            null as CancellationSignal?,
            ContextCompat.getMainExecutor(context),
        ) { location: Location? ->
            if (!isAdded) {
                return@getCurrentLocation
            }
            when (location) {
                null -> Toast.makeText(context, R.string.chat_location_failed, Toast.LENGTH_LONG).show()
                else -> {
                    notifySchedule()
                    viewModel.sendLocation(
                        location.latitude, location.longitude, location.accuracy.toDouble()
                    )
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
        requireContext().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                notifySchedule()
                viewModel.sendContact(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty())
                panels?.hide()
                recyclerView.scrollToPosition(0)
            }
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
        val wasAtBottom = canView && result.data.isNewestLoaded
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

                // Follow new messages, unless reading older ones
                wasAtBottom -> recyclerView.scrollToPosition(0)
            }
        }

        noElementsLinearLayout.isVisible = rows.isEmpty()
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
        private const val LOAD_MORE_THRESHOLD = 15

        /** Typed for a custom emoji that names no emoji. */
        private const val CUSTOM_EMOJI_FALLBACK = "⭐"
        private const val MIN_SCHEDULE_DELAY_S = 60
        private const val UNREAD_SEPARATOR_HEIGHT_DP = 56

        /**
         * Create a [Bundle] to use as the arguments for this fragment or its activity.
         *
         * @param chatId The chat to open
         * @param topicId The forum topic to open, 0 for the whole chat
         */
        fun createBundle(chatId: Long, topicId: Int = 0) = Bundle().apply {
            putLong(ChatViewModel.ARG_CHAT_ID, chatId)
            putInt(ChatViewModel.ARG_TOPIC_ID, topicId)
        }
    }
}

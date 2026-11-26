/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.RectEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView.DiffAdapter
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.ui.MessageMenuLayout
import com.github.borz7zy.shadowgram.ui.finishOverlayReturn
import com.github.borz7zy.shadowgram.ui.views.CustomEmojiView
import com.github.borz7zy.shadowgram.viewmodels.MessageMenuViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.motion.MotionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The menu of a tapped message, like iOS: the chat blurs, the bubble lifts to where its
 * reactions fit above it and its actions below, and goes back where it was when dismissed.
 *
 * The chat hands over a picture of the bubble and where it was through
 * [MessageMenuViewModel.Anchor], and gets the picked action back as a fragment result.
 */
class MessageMenuDialogFragment : DialogFragment() {
    // View models
    private val viewModel by viewModels<MessageMenuViewModel>()
    private val anchor by activityViewModels<MessageMenuViewModel.Anchor>()

    // Views
    private val actionsLinearLayout by getViewProperty<LinearLayout>(R.id.actionsLinearLayout)
    private val actionsScrollView by getViewProperty<NestedScrollView>(R.id.actionsScrollView)
    private val editMaterialButton by getViewProperty<MaterialButton>(R.id.editMaterialButton)
    private val bubbleImageView by getViewProperty<ImageView>(R.id.bubbleImageView)
    private val bubblePreviewImageView by getViewProperty<ImageView>(R.id.bubblePreviewImageView)
    private val bubbleScrollView by getViewProperty<NestedScrollView>(R.id.bubbleScrollView)
    private val bubbleContentLayout by getViewProperty<FrameLayout>(R.id.bubbleContentLayout)
    private val copyLinkMaterialButton by getViewProperty<MaterialButton>(R.id.copyLinkMaterialButton)
    private val copyMaterialButton by getViewProperty<MaterialButton>(R.id.copyMaterialButton)
    private val deleteMaterialButton by getViewProperty<MaterialButton>(R.id.deleteMaterialButton)
    private val dimView by getViewProperty<View>(R.id.messageMenuDimView)
    private val sendNowMaterialButton by getViewProperty<MaterialButton>(R.id.sendNowMaterialButton)
    private val profileAudioMaterialButton by getViewProperty<MaterialButton>(R.id.profileAudioMaterialButton)
    private val pinMaterialButton by getViewProperty<MaterialButton>(R.id.pinMaterialButton)
    private val forwardMaterialButton by getViewProperty<MaterialButton>(R.id.forwardMaterialButton)
    private val expandMaterialButton by getViewProperty<MaterialButton>(R.id.expandMaterialButton)
    private val reactionsCardView by getViewProperty<MaterialCardView>(R.id.reactionsCardView)
    private val reactionsLinearLayout by getViewProperty<LinearLayout>(R.id.reactionsLinearLayout)
    private val reactionsRecyclerView by getViewProperty<RecyclerListView>(R.id.reactionsRecyclerView)
    private val replyMaterialButton by getViewProperty<MaterialButton>(R.id.replyMaterialButton)
    private val saveMaterialButton by getViewProperty<MaterialButton>(R.id.saveMaterialButton)
    private val rootFrameLayout by getViewProperty<MessageMenuLayout>(R.id.rootFrameLayout)
    private val transitionClipLayout by getViewProperty<FrameLayout>(R.id.transitionClipLayout)

    private val rowAdapter = ReactionsAdapter(::selectReaction)
    private val gridAdapter = ReactionsAdapter(::selectReaction)
    private var gridReactions: Pair<List<MessageMenuViewModel.Reaction>, List<Any>>? = null

    private fun selectReaction(reaction: MessageMenuViewModel.Reaction) {
        close(bundleOf(
            KEY_ACTION to ACTION_REACTION,
            KEY_EMOJI to reaction.emoji,
            KEY_CUSTOM_EMOJI_ID to reaction.customEmojiId,
        ))
    }

    /** The bubble's text over its picture, selectable to quote or copy a part; null for none. */
    private var textView: TextView? = null

    private var canQuote = false
    private var canCopyPart = false

    private var isExpanded = false
    private var isClosing = false
    private var isLifted = false
    private var bubbleAnimator: ValueAnimator? = null
    private var cancelReturnHandoff: (() -> Unit)? = null

    /** Where the bubble was, in this window. */
    private var source = Rect()

    override fun getTheme() = R.style.Theme_Shadowgram_MessageMenu

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as ComponentDialog).apply {
            window?.let {
                // A protected chat keeps the lifted bubble out of screenshots too
                if (requireActivity().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                    it.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            onBackPressedDispatcher.addCallback(this@MessageMenuDialogFragment) {
                if (isExpanded && !isClosing) setExpanded(false) else close(null)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_message_menu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val snapshot = anchor.snapshot
        val bounds = anchor.bounds
        // Recreated without the chat's picture (process death): nothing to lift
        if (snapshot == null || bounds == null || savedInstanceState != null) {
            close(null, animate = false)
            return
        }
        for (image in listOf(bubbleImageView, bubblePreviewImageView)) {
            image.setImageDrawable(snapshot())
            image.updateLayoutParams {
                width = bounds.width()
                height = bounds.height()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootFrameLayout) { _, insets ->
            rootFrameLayout.systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            insets
        }

        rootFrameLayout.setOnClickListener { close(null) }
        bubblePreviewImageView.setOnClickListener { close(null) }

        val text = requireArguments().getString(MessageMenuViewModel.ARG_TEXT)
        replyMaterialButton.setOnClickListener { close(bundleOf(KEY_ACTION to ACTION_REPLY)) }
        editMaterialButton.setOnClickListener {
            close(bundleOf(
                KEY_ACTION to ACTION_EDIT,
                KEY_MESSAGE_ID to requireArguments().getLong(MessageMenuViewModel.ARG_MESSAGE_ID),
            ))
        }
        copyMaterialButton.setOnClickListener {
            requireContext().getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(null, text))
            Toast.makeText(requireContext(), R.string.message_copied, Toast.LENGTH_SHORT).show()
            close(null)
        }
        copyLinkMaterialButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val link = viewModel.link() ?: return@launch
                requireContext().getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(null, link))
                Toast.makeText(requireContext(), R.string.message_link_copied, Toast.LENGTH_SHORT)
                    .show()
                close(null)
            }
        }
        sendNowMaterialButton.setOnClickListener { close(bundleOf(KEY_ACTION to ACTION_SEND_NOW)) }
        profileAudioMaterialButton.setOnClickListener { close(bundleOf(KEY_ACTION to ACTION_PROFILE_AUDIO)) }
        pinMaterialButton.setOnClickListener { close(bundleOf(KEY_ACTION to ACTION_PIN)) }
        forwardMaterialButton.setOnClickListener { close(bundleOf(KEY_ACTION to ACTION_FORWARD)) }
        saveMaterialButton.setOnClickListener {
            viewModel.saveToDownloads()
            close(null)
        }
        deleteMaterialButton.setOnClickListener {
            val properties = viewModel.state.value.properties
            close(bundleOf(
                KEY_ACTION to ACTION_DELETE,
                KEY_CAN_REVOKE to (properties?.canBeDeletedForAllUsers == true),
            ))
        }

        reactionsRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        reactionsRecyclerView.adapter = rowAdapter
        reactionsRecyclerView.itemAnimator = null
        expandMaterialButton.isEnabled = false
        expandMaterialButton.setOnClickListener { setExpanded(!isExpanded) }

        // The picture shows where the bubble is at once, the chat hides its own once it's drawn
        view.doOnPreDraw { place(bounds) }
        view.viewTreeObserver.registerFrameCommitCallback {
            if (!isClosing) setFragmentResult(REQUEST_KEY_SHOWN, Bundle())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Give the actions time to arrive before choosing the lift's destination.
            withTimeoutOrNull(PROPERTIES_TIMEOUT_MS) { viewModel.state.first { it.isLoaded } }
            bind(viewModel.state.value, text)
            view.doOnNextLayout { lift() }
            view.requestLayout()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { bind(it, text) }
            }
        }
    }

    override fun onDestroyView() {
        cancelReturnHandoff?.invoke()
        TransitionManager.endTransitions(rootFrameLayout)
        dimView.animate().cancel()
        cancelBubbleAnimation()
        bubbleImageView.animate().cancel()
        anchor.restore?.invoke()
        reactionsRecyclerView.adapter = null
        textView = null

        super.onDestroyView()
    }

    private fun bind(state: MessageMenuViewModel.State, text: String?) {
        if (isClosing) return
        val properties = state.properties
        // Scheduled messages have no audience yet to reply or react to them.
        val isScheduled = requireArguments().getBoolean(ARG_SCHEDULED)
        canQuote = !isScheduled && properties?.canBeReplied == true
        canCopyPart = properties?.canBeCopied == true
        bindSelectableText()
        sendNowMaterialButton.isVisible = isScheduled
        replyMaterialButton.isVisible = !isScheduled && properties?.canBeReplied == true
        editMaterialButton.isVisible = state.canEdit
        // Protected content can't be copied or forwarded: nothing until TDLib says it can
        copyMaterialButton.isVisible = !text.isNullOrEmpty() && properties?.canBeCopied == true
        copyLinkMaterialButton.isVisible = properties?.canGetLink == true
        forwardMaterialButton.isVisible = properties?.canBeForwarded == true
        val isPinned = requireArguments().getBoolean(ARG_IS_PINNED)
        profileAudioMaterialButton.isVisible = !isScheduled && state.canAddProfileAudio
        pinMaterialButton.isVisible = !isScheduled && properties?.canBePinned == true
        pinMaterialButton.setText(if (isPinned) R.string.message_unpin else R.string.message_pin)
        saveMaterialButton.isVisible = state.saveable != null
        deleteMaterialButton.isVisible = properties?.canBeDeletedOnlyForSelf == true
                || properties?.canBeDeletedForAllUsers == true
        actionsScrollView.isVisible = !isExpanded && actionsLinearLayout.children.any { it.isVisible }

        rowAdapter.submitList(state.topReactions)
        if (isExpanded) {
            val reactions = state.allReactions to state.customReactions
            if (gridReactions != reactions) {
                gridReactions = reactions
                gridAdapter.submitList(reactions.first + reactions.second)
            }
        }
        val canExpand = isExpanded || state.allReactions.size > state.topReactions.size ||
            state.customReactions.isNotEmpty()
        // Its place is kept while they load
        reactionsCardView.isVisible = !isScheduled &&
            (state.topReactions.isNotEmpty() || !state.areReactionsLoaded || canExpand)
        expandMaterialButton.isVisible = canExpand
    }

    private fun setExpanded(expanded: Boolean) {
        if (isClosing || !isLifted || expanded == isExpanded) return
        TransitionManager.beginDelayedTransition(rootFrameLayout, TransitionSet().apply {
            addTransition(ChangeBounds().apply {
                resizeClip = true
                addTarget(reactionsCardView)
                addTarget(bubbleScrollView)
            })
            addTransition(Fade().addTarget(actionsScrollView))
            duration = REACTIONS_TRANSITION_MS
            interpolator = interpolator()
        })
        isExpanded = expanded
        reactionsRecyclerView.swapAdapter(if (expanded) gridAdapter else rowAdapter, true)
        reactionsRecyclerView.layoutManager = if (expanded) {
            GridLayoutManager(requireContext(), 1).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) =
                        if (gridAdapter.currentList.getOrNull(position) is String) spanCount else 1
                }
            }
        } else {
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }
        reactionsLinearLayout.orientation = if (expanded) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        expandMaterialButton.updateLayoutParams<LinearLayout.LayoutParams> {
            gravity = if (expanded) Gravity.END else Gravity.CENTER_VERTICAL
        }
        expandMaterialButton.setIconResource(if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down)
        val label = getString(if (expanded) R.string.message_fewer_reactions else R.string.message_more_reactions)
        expandMaterialButton.contentDescription = label
        expandMaterialButton.tooltipText = label
        rootFrameLayout.isExpanded = expanded
        bind(viewModel.state.value, requireArguments().getString(MessageMenuViewModel.ARG_TEXT))
    }

    /** The picture over the bubble, where it is; the chat blurs behind it. */
    private fun place(bounds: Rect) {
        if (isClosing) return
        rootFrameLayout.isVisible = true
        source = bounds.inWindow()
        rootFrameLayout.source = source
        bubbleImageView.translationX = source.left.toFloat()
        bubbleImageView.translationY = source.top.toFloat()
        transitionClipLayout.clipBounds = anchor.viewport?.invoke()?.inWindow()
        actionsLinearLayout.gravity = if (source.centerX() > rootFrameLayout.width / 2) Gravity.RIGHT else Gravity.LEFT
        setBackgroundBlur(true)
        dimView.animate().alpha(DIM_AMOUNT).setDuration(duration()).start()
    }

    private fun bindSelectableText() {
        val selectable = canQuote || canCopyPart
        textView?.isVisible = selectable
        if (isLifted && selectable && textView == null) {
            val text = anchor.text ?: return
            val bounds = anchor.bounds ?: return
            setUpText(text, bounds)
        }
    }

    /**
     * A text view laid out like the bubble's over its picture, transparent: the picture shows the
     * text, this one selects it. A long press selects, the selection's bar quotes the part.
     */
    private fun setUpText(text: MessageMenuViewModel.SelectableText, bounds: Rect) {
        textView = TextView(requireContext()).apply {
            text.style(this)
            setTextColor(android.graphics.Color.TRANSPARENT)
            setText(text.text)
            setTextIsSelectable(true)
            isVisible = canQuote || canCopyPart
            // A tap without a selection is a tap on the bubble
            setOnClickListener { if (!hasSelection()) close(null) }
            customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    menu.add(android.view.Menu.NONE, MENU_QUOTE, 0, R.string.message_quote)
                    return true
                }

                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    if (!canQuote) {
                        menu.removeItem(MENU_QUOTE)
                    }
                    // Protected content: the part can only be quoted, not copied or shared
                    if (!canCopyPart) {
                        (0 until menu.size()).map { menu.getItem(it).itemId }
                            .filter { it != MENU_QUOTE && it != android.R.id.selectAll }
                            .forEach(menu::removeItem)
                    }
                    return true
                }

                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                    if (item.itemId != MENU_QUOTE) {
                        return false
                    }
                    quote(text)
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
            }
        }
        // The selection scrolls with the snapshot at the original text size.
        bubbleContentLayout.addView(
            textView,
            FrameLayout.LayoutParams(text.bounds.width(), text.bounds.height(), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = text.bounds.left - bounds.left
                topMargin = text.bounds.top - bounds.top
            },
        )
    }

    /** Reply with the selected part of the message's own text. */
    private fun quote(text: MessageMenuViewModel.SelectableText) {
        val textView = textView ?: return
        val start = (minOf(textView.selectionStart, textView.selectionEnd) - text.shift).coerceAtLeast(0)
        val end = maxOf(textView.selectionStart, textView.selectionEnd) - text.shift
        val own = text.text.toString().substring(text.shift)
        if (end <= start || end > own.length) {
            return
        }
        close(bundleOf(
            KEY_ACTION to ACTION_QUOTE,
            KEY_QUOTE to own.substring(start, end),
            KEY_QUOTE_POSITION to start,
        ))
    }

    /** A rect on screen, in this window. */
    private fun Rect.inWindow(): Rect {
        val location = IntArray(2).also(rootFrameLayout::getLocationOnScreen)
        return Rect(this).apply { offset(-location[0], -location[1]) }
    }

    /** Lift the snapshot, then replace it with the scrollable preview at the same position. */
    private fun lift() {
        if (isClosing) return
        if (source.isEmpty) {
            rootFrameLayout.doOnNextLayout { lift() }
            return
        }
        val openingClip = transitionClipLayout.clipBounds ?: windowBounds()
        bubbleScrollView.scrollTo(0, (openingClip.top - source.top).coerceAtLeast(0))
        val previewBounds = previewBounds()

        reactionsCardView.pivotX = if (source.centerX() > rootFrameLayout.width / 2) reactionsCardView.width.toFloat() else 0f
        reactionsCardView.pivotY = reactionsCardView.height.toFloat()
        reactionsCardView.scaleX = POP_SCALE
        reactionsCardView.scaleY = POP_SCALE
        reactionsCardView.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(POP_DELAY_MS)
            .setDuration(duration()).setInterpolator(interpolator()).start()

        actionsScrollView.translationY = -rootFrameLayout.toPx(ACTION_OFFSET_DP).toFloat()
        actionsScrollView.animate().alpha(1f).translationY(0f).setStartDelay(POP_DELAY_MS)
            .setDuration(duration()).setInterpolator(interpolator()).withLayer().start()

        animateBubble(previewBounds.left, previewBounds.top - bubbleScrollView.scrollY, previewBounds, followPreview = true) {
            bubbleImageView.isInvisible = true
            bubbleScrollView.isVisible = true
            isLifted = true
            expandMaterialButton.isEnabled = true
            bindSelectableText()
            reactionsRecyclerView.children.forEach {
                it.findViewById<CustomEmojiView>(R.id.customEmojiView)?.isAnimationPaused = false
            }
        }
    }

    private fun previewBounds() = with(bubbleScrollView) { Rect(left, top, right, bottom) }

    private fun windowBounds() = Rect(0, 0, rootFrameLayout.width, rootFrameLayout.height)

    private fun cancelBubbleAnimation() {
        val animator = bubbleAnimator
        bubbleAnimator = null
        animator?.cancel()
    }

    private fun animateBubble(x: Int, y: Int, clip: Rect, followPreview: Boolean = false, onEnd: () -> Unit) {
        cancelBubbleAnimation()
        val image = bubbleImageView
        val startX = image.translationX
        val startY = image.translationY
        val startClip = transitionClipLayout.clipBounds ?: windowBounds()
        val clipEvaluator = RectEvaluator(Rect())
        val destination = Rect(clip)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = duration()
            interpolator = interpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                if (followPreview) {
                    with(bubbleScrollView) { destination.set(left, top, right, bottom) }
                }
                val targetX = if (followPreview) destination.left else x
                val targetY = if (followPreview) destination.top - bubbleScrollView.scrollY else y
                image.translationX = startX + (targetX - startX) * fraction
                image.translationY = startY + (targetY - startY) * fraction
                transitionClipLayout.clipBounds = clipEvaluator.evaluate(fraction, startClip, destination)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bubbleAnimator !== animation) return
                    bubbleAnimator = null
                    onEnd()
                }
            })
        }
        bubbleAnimator = animator
        animator.start()
    }

    /**
     * Put the bubble back where it is now, the chat may have moved it meanwhile, then hand the
     * chat [result]: what was picked, null for nothing. The chat shows its own bubble again on
     * any result.
     */
    private fun close(result: Bundle?, animate: Boolean = true) {
        if (isClosing) {
            return
        }
        isClosing = true
        if (view != null) TransitionManager.endTransitions(rootFrameLayout)
        // The picture alone goes back, the selection with its handles goes now
        textView?.isVisible = false

        val finish = {
            if (view != null) {
                dimView.animate().cancel()
                dimView.alpha = 0f
            }
            setBackgroundBlur(false)
            cancelReturnHandoff = finishOverlayReturn(anchor.restore?.invoke()) {
                setFragmentResult(REQUEST_KEY, result ?: Bundle())
                dismissAllowingStateLoss()
            }
        }
        if (!animate || view == null || source.isEmpty) {
            finish()
            return
        }

        reactionsCardView.animate().alpha(0f).scaleX(POP_SCALE).scaleY(POP_SCALE).setStartDelay(0)
            .setDuration(duration() / 2).start()
        actionsScrollView.animate().alpha(0f).setStartDelay(0).setDuration(duration() / 2).start()

        cancelBubbleAnimation()
        val viewport = anchor.viewport?.invoke()?.inWindow() ?: windowBounds()
        if (bubbleScrollView.isVisible) {
            val previewBounds = previewBounds()
            bubbleImageView.translationX = previewBounds.left.toFloat()
            bubbleImageView.translationY = (previewBounds.top - bubbleScrollView.scrollY).toFloat()
            transitionClipLayout.clipBounds = previewBounds
            bubbleScrollView.isInvisible = true
            bubbleImageView.isVisible = true
        }
        val clippedStart = transitionClipLayout.clipBounds ?: windowBounds()
        if (!clippedStart.intersect(viewport)) {
            val x = clippedStart.centerX().coerceIn(viewport.left, viewport.right)
            val y = clippedStart.centerY().coerceIn(viewport.top, viewport.bottom)
            clippedStart.set(x, y, x, y)
        }
        transitionClipLayout.clipBounds = clippedStart
        val target = anchor.locate?.invoke()?.inWindow()?.takeIf { Rect.intersects(it, viewport) }
        if (target == null) {
            bubbleImageView.animate().alpha(0f).setDuration(duration()).withEndAction(finish).start()
        } else {
            animateBubble(target.left, target.top, viewport, onEnd = finish)
        }
        setBackgroundBlur(false)
        dimView.animate().alpha(0f).setDuration(duration()).start()
    }

    /** Let the compositor blur the window behind the menu, when supported. */
    private fun setBackgroundBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val window = dialog?.window ?: return
        if (enabled) {
            window.attributes = window.attributes.apply {
                blurBehindRadius = resources.getDimensionPixelSize(R.dimen.message_menu_blur_radius)
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    private fun duration() = MotionUtils.resolveThemeDuration(
        requireContext(), com.google.android.material.R.attr.motionDurationMedium4, 400
    ).toLong()

    private fun interpolator() = MotionUtils.resolveThemeInterpolator(
        requireContext(),
        com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
        android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f),
    )

    /** The reactions to pick, animated; the custom emoji sets under titles in the grid. */
    private inner class ReactionsAdapter(
        private val onClick: (MessageMenuViewModel.Reaction) -> Unit,
    ) : DiffAdapter<Any, RecyclerView.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(oldItem: Any, newItem: Any) = oldItem == newItem

            override fun areContentsTheSame(oldItem: Any, newItem: Any) = true
        }
    ) {
        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is String -> TYPE_TITLE
            else -> TYPE_REACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(
                    when (viewType) {
                        TYPE_TITLE -> R.layout.message_menu_reaction_title
                        else -> R.layout.message_menu_reaction_item
                    },
                    parent,
                    false,
                )
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is String -> (holder.itemView as TextView).text = item
                is MessageMenuViewModel.Reaction -> {
                    val emojiView = holder.itemView.findViewById<CustomEmojiView>(R.id.customEmojiView)
                    emojiView.isAnimationPaused = !isLifted
                    when (val emoji = item.emoji) {
                        null -> emojiView.setCustomEmoji(item.customEmojiId, item.fallbackEmoji)
                        else -> emojiView.setEmojiReaction(emoji)
                    }
                    holder.itemView.contentDescription = item.fallbackEmoji
                    holder.itemView.setOnClickListener { onClick(item) }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            holder.itemView.findViewById<CustomEmojiView>(R.id.customEmojiView)?.clear()
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            holder.itemView.findViewById<CustomEmojiView>(R.id.customEmojiView)?.isAnimationPaused = !isLifted
        }
    }

    companion object {
        const val REQUEST_KEY = "message_menu"

        /** The menu shows the bubble's picture, the chat can hide the bubble. */
        const val REQUEST_KEY_SHOWN = "message_menu_shown"
        const val KEY_ACTION = "action"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_EMOJI = "emoji"
        const val KEY_CUSTOM_EMOJI_ID = "custom_emoji_id"
        const val KEY_CAN_REVOKE = "can_revoke"

        const val ACTION_REPLY = "reply"
        const val ACTION_EDIT = "edit"
        const val ACTION_DELETE = "delete"
        const val ACTION_FORWARD = "forward"
        const val ACTION_SEND_NOW = "send_now"
        const val ACTION_QUOTE = "quote"
        const val ACTION_PROFILE_AUDIO = "profile_audio"
        const val ACTION_PIN = "pin"
        const val KEY_QUOTE = "quote"
        const val KEY_QUOTE_POSITION = "quote_position"

        private const val MENU_QUOTE = 1

        private const val ARG_SCHEDULED = "scheduled"
        private const val ARG_IS_PINNED = "is_pinned"
        const val ACTION_REACTION = "reaction"

        private const val PROPERTIES_TIMEOUT_MS = 300L
        private const val REACTIONS_TRANSITION_MS = 240L
        private const val TYPE_TITLE = 0
        private const val TYPE_REACTION = 1
        private const val POP_SCALE = 0.6f
        private const val POP_DELAY_MS = 60L
        private const val ACTION_OFFSET_DP = 12
        private const val DIM_AMOUNT = 0.35f

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         *
         * @param text What "Copy" copies, null for none
         * @param isScheduled A scheduled message: "Send now" and no reactions
         * @param isPinned "Unpin" rather than "Pin"
         */
        fun createBundle(
            chatId: Long,
            messageId: Long,
            text: String?,
            isScheduled: Boolean,
            isPinned: Boolean,
        ) = bundleOf(
            ARG_IS_PINNED to isPinned,
            MessageMenuViewModel.ARG_CHAT_ID to chatId,
            MessageMenuViewModel.ARG_MESSAGE_ID to messageId,
            MessageMenuViewModel.ARG_TEXT to text,
            ARG_SCHEDULED to isScheduled,
        )
    }
}

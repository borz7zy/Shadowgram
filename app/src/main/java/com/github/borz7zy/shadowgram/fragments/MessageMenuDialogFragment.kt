/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.RenderEffect
import android.graphics.Shader
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
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
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
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.ui.finishOverlayReturn
import com.github.borz7zy.shadowgram.ui.views.CustomEmojiView
import com.github.borz7zy.shadowgram.viewmodels.MessageMenuViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.motion.MotionUtils
import kotlinx.coroutines.flow.collectLatest
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
    private val bubbleImageView by getViewProperty<ImageView>(R.id.bubbleImageView)
    private val copyLinkMaterialButton by getViewProperty<MaterialButton>(R.id.copyLinkMaterialButton)
    private val copyMaterialButton by getViewProperty<MaterialButton>(R.id.copyMaterialButton)
    private val deleteMaterialButton by getViewProperty<MaterialButton>(R.id.deleteMaterialButton)
    private val sendNowMaterialButton by getViewProperty<MaterialButton>(R.id.sendNowMaterialButton)
    private val pinMaterialButton by getViewProperty<MaterialButton>(R.id.pinMaterialButton)
    private val forwardMaterialButton by getViewProperty<MaterialButton>(R.id.forwardMaterialButton)
    private val expandMaterialButton by getViewProperty<MaterialButton>(R.id.expandMaterialButton)
    private val reactionsCardView by getViewProperty<MaterialCardView>(R.id.reactionsCardView)
    private val reactionsRecyclerView by getViewProperty<RecyclerView>(R.id.reactionsRecyclerView)
    private val replyMaterialButton by getViewProperty<MaterialButton>(R.id.replyMaterialButton)
    private val saveMaterialButton by getViewProperty<MaterialButton>(R.id.saveMaterialButton)
    private val rootFrameLayout by getViewProperty<FrameLayout>(R.id.rootFrameLayout)
    private val transitionClipLayout by getViewProperty<FrameLayout>(R.id.transitionClipLayout)

    private val adapter = ReactionsAdapter { close(bundleOf(KEY_ACTION to ACTION_REACTION).apply {
        putString(KEY_EMOJI, it.emoji)
        putLong(KEY_CUSTOM_EMOJI_ID, it.customEmojiId)
    }) }

    /** The bubble's text over its picture, selectable to quote or copy a part; null for none. */
    private var textView: TextView? = null

    /** Where [textView] is in the picture. */
    private var textOffset = android.graphics.Point()

    private var canQuote = false
    private var canCopyPart = false

    private var isExpanded = false
    private var isClosing = false
    private var backgroundAnimator: ValueAnimator? = null
    private var cancelReturnHandoff: (() -> Unit)? = null

    /** Where the bubble was, in this window. */
    private var source = android.graphics.Rect()

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
            // Back puts the bubble back first
            onBackPressedDispatcher.addCallback(this@MessageMenuDialogFragment) { close(null) }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_message_menu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bitmap = anchor.bitmap
        val bounds = anchor.bounds
        // Recreated without the chat's picture (process death): nothing to lift
        if (bitmap == null || bounds == null || savedInstanceState != null) {
            close(null, animate = false)
            return
        }
        bubbleImageView.setImageBitmap(bitmap)
        anchor.text?.let { setUpText(it, bounds) }
        // The text goes wherever the picture goes
        bubbleImageView.animate().setUpdateListener { syncText() }

        rootFrameLayout.setOnClickListener { close(null) }
        bubbleImageView.setOnClickListener { close(null) }

        val text = requireArguments().getString(MessageMenuViewModel.ARG_TEXT)
        replyMaterialButton.setOnClickListener { close(bundleOf(KEY_ACTION to ACTION_REPLY)) }
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
        reactionsRecyclerView.adapter = adapter
        expandMaterialButton.setOnClickListener { expand() }

        // The picture shows where the bubble is at once, the chat hides its own once it's drawn
        view.doOnPreDraw { place(bounds) }
        view.viewTreeObserver.registerFrameCommitCallback {
            if (!isClosing) setFragmentResult(REQUEST_KEY_SHOWN, Bundle())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Lift once it's known what the menu has, the reactions come in after
            withTimeoutOrNull(PROPERTIES_TIMEOUT_MS) { viewModel.state.first { it.isLoaded } }
            bind(viewModel.state.value, text)
            view.doOnPreDraw { lift() }
            view.requestLayout()

            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { bind(it, text) }
            }
        }
    }

    override fun onDestroyView() {
        cancelReturnHandoff?.invoke()
        backgroundAnimator?.cancel()
        bubbleImageView.animate().cancel()
        anchor.restore?.invoke()
        reactionsRecyclerView.adapter = null
        setChatBlur(0f)

        super.onDestroyView()
    }

    private fun bind(state: MessageMenuViewModel.State, text: String?) {
        val properties = state.properties
        // A scheduled message can only go now or be deleted, nobody saw it to react to it
        val isScheduled = requireArguments().getBoolean(ARG_SCHEDULED)
        canQuote = !isScheduled && properties?.canBeReplied == true
        canCopyPart = properties?.canBeCopied == true
        textView?.isVisible = !isClosing && (canQuote || canCopyPart)
        sendNowMaterialButton.isVisible = isScheduled
        replyMaterialButton.isVisible = !isScheduled && properties?.canBeReplied == true
        // Protected content can't be copied or forwarded: nothing until TDLib says it can
        copyMaterialButton.isVisible = !text.isNullOrEmpty() && properties?.canBeCopied == true
        copyLinkMaterialButton.isVisible = properties?.canGetLink == true
        forwardMaterialButton.isVisible = properties?.canBeForwarded == true
        val isPinned = requireArguments().getBoolean(ARG_IS_PINNED)
        pinMaterialButton.isVisible = !isScheduled && properties?.canBePinned == true
        pinMaterialButton.setText(if (isPinned) R.string.message_unpin else R.string.message_pin)
        saveMaterialButton.isVisible = state.saveable != null
        deleteMaterialButton.isVisible = properties?.canBeDeletedOnlyForSelf == true
                || properties?.canBeDeletedForAllUsers == true
        actionsLinearLayout.isVisible = !isExpanded && (sendNowMaterialButton.isVisible
                || replyMaterialButton.isVisible || copyMaterialButton.isVisible
                || copyLinkMaterialButton.isVisible || forwardMaterialButton.isVisible
                || pinMaterialButton.isVisible
                || saveMaterialButton.isVisible
                || deleteMaterialButton.isVisible)

        val reactions = if (isExpanded) state.allReactions else state.topReactions
        adapter.submitList(
            when (isExpanded) {
                // The custom emoji sets follow, under their titles
                true -> reactions + state.customPacks.flatMap { (title, pack) -> listOf(title) + pack }
                false -> reactions
            }
        )
        // Its place is kept while they load
        reactionsCardView.isVisible = !isScheduled && (reactions.isNotEmpty() || !state.areReactionsLoaded)
        expandMaterialButton.isVisible = !isExpanded && (state.allReactions.size > state.topReactions.size
                || state.customPacks.isNotEmpty())
    }

    /** All the reactions, a grid in place of the row. */
    private fun expand() {
        isExpanded = true
        reactionsRecyclerView.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (adapter.currentList.getOrNull(position) is String) GRID_COLUMNS else 1
            }
        }
        reactionsRecyclerView.layoutParams = reactionsRecyclerView.layoutParams.apply {
            height = rootFrameLayout.toPx(GRID_HEIGHT_DP)
        }
        // The grid takes the actions' room, like iOS
        bind(viewModel.state.value, requireArguments().getString(MessageMenuViewModel.ARG_TEXT))
    }

    private fun insets() = ViewCompat.getRootWindowInsets(rootFrameLayout)
        ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        ?: androidx.core.graphics.Insets.NONE

    /** The picture over the bubble, where it is; the chat blurs behind it. */
    private fun place(bounds: android.graphics.Rect) {
        if (isClosing) return
        rootFrameLayout.isVisible = true
        source = bounds.inWindow()
        bubbleImageView.translationX = source.left.toFloat()
        bubbleImageView.translationY = source.top.toFloat()
        syncText()
        animateBackground(0f, 1f)
    }

    /**
     * A text view laid out like the bubble's over its picture, transparent: the picture shows the
     * text, this one selects it. A long press selects, the selection's bar quotes the part.
     */
    private fun setUpText(text: MessageMenuViewModel.SelectableText, bounds: android.graphics.Rect) {
        textOffset = android.graphics.Point(text.bounds.left - bounds.left, text.bounds.top - bounds.top)
        textView = TextView(requireContext()).apply {
            text.style(this)
            setTextColor(android.graphics.Color.TRANSPARENT)
            setText(text.text)
            setTextIsSelectable(true)
            isVisible = false
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
        // Under the reactions and the actions
        rootFrameLayout.addView(
            textView,
            rootFrameLayout.indexOfChild(transitionClipLayout) + 1,
            FrameLayout.LayoutParams(text.bounds.width(), text.bounds.height()),
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

    /** Keep the text view over the text in the picture while it moves and scales. */
    private fun syncText() {
        val textView = textView ?: return
        textView.translationX = bubbleImageView.translationX + textOffset.x
        textView.translationY = bubbleImageView.translationY + textOffset.y
        textView.pivotX = bubbleImageView.pivotX - textOffset.x
        textView.pivotY = bubbleImageView.pivotY - textOffset.y
        textView.scaleX = bubbleImageView.scaleX
        textView.scaleY = bubbleImageView.scaleY
    }

    /** A rect on screen, in this window. */
    private fun android.graphics.Rect.inWindow(): android.graphics.Rect {
        val location = IntArray(2).also(rootFrameLayout::getLocationOnScreen)
        return android.graphics.Rect(this).apply { offset(-location[0], -location[1]) }
    }

    /** Lift the bubble to where its reactions fit above and its actions below. */
    private fun lift() {
        if (isClosing) {
            return
        }
        val insets = insets()
        val margin = rootFrameLayout.toPx(MARGIN_DP)
        val gap = rootFrameLayout.toPx(GAP_DP)
        val barHeight = if (reactionsCardView.isVisible) reactionsCardView.height + gap else 0
        val menuHeight = if (actionsLinearLayout.isVisible) actionsLinearLayout.height + gap else 0

        // The bubble moves the least it can for both to fit, and shrinks if even that isn't enough
        val top = insets.top + margin + barHeight
        val bottom = rootFrameLayout.height - insets.bottom - margin - menuHeight
        val height = source.height()
        val scale = if (height > bottom - top) ((bottom - top).toFloat() / height).coerceAtLeast(MIN_SCALE) else 1f
        val targetTop = source.top.coerceAtMost(bottom - (height * scale).toInt()).coerceAtLeast(top)

        // Lifted on the side it's on, outgoing ones on the right
        val isEnd = source.centerX() > rootFrameLayout.width / 2
        bubbleImageView.pivotX = if (isEnd) source.width().toFloat() else 0f
        bubbleImageView.pivotY = 0f

        fun alignX(view: View) = when (isEnd) {
            true -> (source.right - view.width).toFloat()
            false -> source.left.toFloat()
        }.coerceIn(margin.toFloat(), (rootFrameLayout.width - margin - view.width).toFloat().coerceAtLeast(margin.toFloat()))

        // It grows as the reactions come, and upwards when expanded: kept on its side, its
        // bottom above the bubble, on screen
        val barBottom = targetTop - gap
        fun placeBar(view: View) {
            view.translationX = alignX(view)
            view.translationY = (barBottom - view.height).toFloat()
                .coerceAtLeast((insets.top + margin).toFloat())
        }
        placeBar(reactionsCardView)
        reactionsCardView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> placeBar(view) }
        reactionsCardView.pivotX = if (isEnd) reactionsCardView.width.toFloat() else 0f
        reactionsCardView.pivotY = reactionsCardView.height.toFloat()
        reactionsCardView.scaleX = POP_SCALE
        reactionsCardView.scaleY = POP_SCALE
        reactionsCardView.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(POP_DELAY_MS)
            .setDuration(duration()).setInterpolator(interpolator()).start()

        // The actions slide out of the bubble, the closest one first, like the new chat menu
        actionsLinearLayout.gravity = if (isEnd) Gravity.END else Gravity.START
        actionsLinearLayout.alpha = 1f
        actionsLinearLayout.translationX = alignX(actionsLinearLayout)
        actionsLinearLayout.translationY = targetTop + height * scale + gap
        actionsLinearLayout.children.filter { it.isVisible }.forEachIndexed { index, action ->
            action.alpha = 0f
            action.translationY = -rootFrameLayout.toPx(ACTION_OFFSET_DP) * (index + 1f)
            action.animate().alpha(1f).translationY(0f)
                .setStartDelay(POP_DELAY_MS + index * ACTION_STAGGER_MS)
                .setDuration(duration()).setInterpolator(interpolator()).start()
        }

        bubbleImageView.animate().translationY(targetTop.toFloat()).scaleX(scale).scaleY(scale)
            .setDuration(duration()).setInterpolator(interpolator()).start()
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
        // The picture alone goes back, the selection with its handles goes now
        textView?.isVisible = false

        val finish = {
            backgroundAnimator?.cancel()
            setChatBlur(0f)
            dialog?.window?.let { window ->
                window.attributes = window.attributes.apply { dimAmount = 0f }
            }
            cancelReturnHandoff = finishOverlayReturn(anchor.restore?.invoke()) {
                setFragmentResult(REQUEST_KEY, result ?: Bundle())
                dismissAllowingStateLoss()
            }
        }
        if (!animate || view == null) {
            finish()
            return
        }

        reactionsCardView.animate().alpha(0f).scaleX(POP_SCALE).scaleY(POP_SCALE).setStartDelay(0)
            .setDuration(duration() / 2).start()
        actionsLinearLayout.animate().alpha(0f).setStartDelay(0).setDuration(duration() / 2).start()

        val viewport = anchor.viewport?.invoke()
        transitionClipLayout.clipBounds = viewport?.inWindow()
        val target = anchor.locate?.invoke()
            ?.takeIf { viewport == null || android.graphics.Rect.intersects(it, viewport) }
            ?.inWindow()
        bubbleImageView.animate().apply {
            when (target) {
                // Scrolled away: it fades where it is
                null -> alpha(0f)
                else -> translationX(target.left.toFloat()).translationY(target.top.toFloat())
                    .scaleX(1f).scaleY(1f)
            }
        }
            .setStartDelay(0).setDuration(duration()).setInterpolator(interpolator())
            .withEndAction { finish() }
            .start()
        animateBackground((dialog?.window?.attributes?.dimAmount ?: 0f) / DIM_AMOUNT, 0f)
    }

    /** Blur where the device can, dim everywhere. */
    private fun animateBackground(from: Float, to: Float) {
        val window = dialog?.window ?: return
        val blurRadius = resources.getDimension(R.dimen.message_menu_blur_radius)
        backgroundAnimator?.cancel()
        backgroundAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = duration()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                window.attributes = window.attributes.apply { dimAmount = DIM_AMOUNT * fraction }
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setChatBlur(blurRadius * fraction)
            }
            start()
        }
    }

    /**
     * Blur the chat's own views: blur behind windows is off on many devices (One UI, Nothing OS,
     * battery saver), drawing the app blurred works on all since Android 12.
     */
    private fun setChatBlur(radius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        activity?.findViewById<View>(android.R.id.content)?.setRenderEffect(
            if (radius >= 1f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
        )
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
    private class ReactionsAdapter(
        private val onClick: (MessageMenuViewModel.Reaction) -> Unit,
    ) : androidx.recyclerview.widget.ListAdapter<Any, RecyclerView.ViewHolder>(
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
                    when (val emoji = item.emoji) {
                        null -> emojiView.setCustomEmoji(item.customEmojiId)
                        else -> emojiView.setEmojiReaction(emoji)
                    }
                    holder.itemView.contentDescription = item.emoji
                    holder.itemView.setOnClickListener { onClick(item) }
                }
            }
        }

        companion object {
            const val TYPE_TITLE = 0
            const val TYPE_REACTION = 1
        }
    }

    companion object {
        const val REQUEST_KEY = "message_menu"

        /** The menu shows the bubble's picture, the chat can hide the bubble. */
        const val REQUEST_KEY_SHOWN = "message_menu_shown"
        const val KEY_ACTION = "action"
        const val KEY_EMOJI = "emoji"
        const val KEY_CUSTOM_EMOJI_ID = "custom_emoji_id"
        const val KEY_CAN_REVOKE = "can_revoke"

        const val ACTION_REPLY = "reply"
        const val ACTION_DELETE = "delete"
        const val ACTION_FORWARD = "forward"
        const val ACTION_SEND_NOW = "send_now"
        const val ACTION_QUOTE = "quote"
        const val ACTION_PIN = "pin"
        const val KEY_QUOTE = "quote"
        const val KEY_QUOTE_POSITION = "quote_position"

        private const val MENU_QUOTE = 1

        private const val ARG_SCHEDULED = "scheduled"
        private const val ARG_IS_PINNED = "is_pinned"
        const val ACTION_REACTION = "reaction"

        private const val PROPERTIES_TIMEOUT_MS = 300L
        private const val MARGIN_DP = 12
        private const val GAP_DP = 8
        private const val GRID_COLUMNS = 7
        private const val GRID_HEIGHT_DP = 240
        private const val MIN_SCALE = 0.4f
        private const val POP_SCALE = 0.6f
        private const val POP_DELAY_MS = 60L
        private const val ACTION_OFFSET_DP = 12
        private const val ACTION_STAGGER_MS = 30L
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

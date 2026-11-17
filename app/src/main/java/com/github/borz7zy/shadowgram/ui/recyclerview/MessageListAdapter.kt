/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.recyclerview

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.annotation.SuppressLint
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.models.MessageRow
import com.github.borz7zy.shadowgram.ui.text.attachCustomEmoji
import com.github.borz7zy.shadowgram.ui.text.setCustomEmoji
import com.github.borz7zy.shadowgram.ui.text.stringResId
import com.github.borz7zy.shadowgram.ui.views.AnimationView
import com.github.borz7zy.shadowgram.ui.views.AvatarView
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.playback.TdFileDataSource
import com.github.borz7zy.shadowgram.ui.album.AlbumView
import com.github.borz7zy.shadowgram.ui.views.CustomEmojiView
import com.github.borz7zy.shadowgram.ui.views.DocumentView
import com.github.borz7zy.shadowgram.ui.views.StickerView
import com.github.borz7zy.shadowgram.ui.views.VideoNoteView
import com.github.borz7zy.shadowgram.ui.views.VoiceNoteView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.File

/**
 * Bubbles and separators of an open chat, newest first for a reversed layout manager.
 *
 * Every bubble carries its time, outgoing ones their state too. Pictures are decoded by RustCore:
 * photos and WebP stickers through Coil, WebM and TGS stickers by [StickerView].
 */
class MessageListAdapter(
    private val onCancelUpload: (messageId: Long) -> Unit,
    /** What to play for a voice or video message: who sent it, where. */
    private val trackOf: (MessageItem, MessageItem.Playable) -> MessagePlayer.Track,
    private val callbacks: Callbacks,
) : ListAdapter<MessageRow, RecyclerView.ViewHolder>(DiffCallback) {
    /** The message the menu lifted, its bubble hidden meanwhile; 0 for none. */
    var hiddenMessageId = 0L

    /**
     * A message to flash once it's bound: a reply's original shown in the chat.
     *
     * @param quote The part of its text to mark too, null for none
     * @param quotePosition Where [quote] starts in its text, UTF-16
     */
    data class Highlight(val messageId: Long, val quote: String?, val quotePosition: Int)

    private var highlight: Highlight? = null

    /** Flash the bubble of [highlight] when it's on screen, once. */
    fun highlight(highlight: Highlight) {
        this.highlight = highlight
        val position = currentList.indexOfFirst {
            it is MessageRow.Bubble && (it.message.id == highlight.messageId
                    || it.album.any { item -> item.id == highlight.messageId })
        }
        if (position >= 0) {
            notifyItemChanged(position, PAYLOAD_HIGHLIGHT)
        }
    }

    /**
     * An emoji sprite loaded: every row draws its texts again, the ones kept aside while
     * scrolling too, which come back without being bound otherwise.
     */
    fun invalidateEmoji() = notifyItemRangeChanged(0, itemCount, PAYLOAD_EMOJI)

    /** [highlight] when it's for [row], taken: it flashes once. */
    private fun takeHighlight(row: MessageRow.Bubble): Highlight? = highlight
        ?.takeIf { h -> row.message.id == h.messageId || row.album.any { it.id == h.messageId } }
        ?.also { highlight = null }

    interface Callbacks {
        /** Tapped: the message menu, from where [bubble] is. */
        fun onMessageClick(message: MessageItem, bubble: View)

        /** The sender's name was tapped. */
        fun onSenderClick(senderId: Long)

        fun onReactionClick(message: MessageItem, reaction: MessageItem.Reaction)

        /** A photo was tapped, in its bubble or album: the viewer, from [view]. */
        fun onMediaClick(message: MessageItem, view: ImageView)

        /** The button of [MessageItem.document]: download, cancel or open it. */
        fun onDocumentClick(message: MessageItem)

        /** The reply plate: show the replied message in the chat. */
        fun onReplyClick(message: MessageItem)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is MessageRow.Bubble -> TYPE_BUBBLE
        is MessageRow.Separator, MessageRow.UnreadSeparator -> TYPE_SEPARATOR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BUBBLE -> BubbleViewHolder(
                inflater.inflate(R.layout.message_bubble_item, parent, false),
                onCancelUpload,
                trackOf,
                callbacks,
                { hiddenMessageId },
                ::takeHighlight,
            )

            else -> SeparatorViewHolder(
                inflater.inflate(R.layout.message_separator_item, parent, false) as TextView
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is MessageRow.Bubble -> (holder as BubbleViewHolder).bind(row)
            is MessageRow.Separator -> (holder as SeparatorViewHolder).bind(row)
            MessageRow.UnreadSeparator -> (holder as SeparatorViewHolder).bindUnread()
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // Only the upload progress changed: leave the picture alone, no crossfade
        val row = getItem(position)
        when {
            payloads.isNotEmpty() && payloads.all { it == PAYLOAD_UPLOAD } && row is MessageRow.Bubble ->
                (holder as BubbleViewHolder).bindUpload(row.message)

            // Only the reactions changed: the rest stays as it is
            payloads.isNotEmpty() && payloads.all { it == PAYLOAD_REACTIONS } && row is MessageRow.Bubble ->
                (holder as BubbleViewHolder).bindReactions(row.message)

            // Only the document's download went on
            payloads.isNotEmpty() && payloads.all { it == PAYLOAD_DOCUMENT } && row is MessageRow.Bubble ->
                (holder as BubbleViewHolder).bindDocument(row.message)

            payloads.isNotEmpty() && payloads.all { it == PAYLOAD_HIGHLIGHT } && row is MessageRow.Bubble ->
                (holder as BubbleViewHolder).bindHighlight(row)

            payloads.isNotEmpty() && payloads.all { it == PAYLOAD_EMOJI } -> holder.itemView.invalidateTexts()

            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    private class SeparatorViewHolder(val view: TextView) : RecyclerView.ViewHolder(view) {
        private val colorOnSurfaceVariant = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        private val colorPrimary = MaterialColors.getColor(
            view, androidx.appcompat.R.attr.colorPrimary
        )

        fun bindUnread() {
            view.setText(R.string.chat_unread_messages)
            view.setTextColor(colorPrimary)
        }

        fun bind(row: MessageRow.Separator) {
            val context = view.context
            val millis = row.date * 1000L
            val now = System.currentTimeMillis()

            val day = when {
                DateUtils.isToday(millis) || DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) ->
                    DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.DAY_IN_MILLIS)

                now - millis < DateUtils.WEEK_IN_MILLIS -> DateUtils.formatDateTime(
                    context, millis, DateUtils.FORMAT_SHOW_WEEKDAY
                )

                else -> DateUtils.formatDateTime(
                    context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                )
            }

            view.text = context.getString(
                R.string.chat_separator, day, DateFormat.getTimeFormat(context).format(millis)
            )
            view.setTextColor(colorOnSurfaceVariant)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Only watches touches, the click handles them
    private class BubbleViewHolder(
        val view: View,
        private val onCancelUpload: (messageId: Long) -> Unit,
        private val trackOf: (MessageItem, MessageItem.Playable) -> MessagePlayer.Track,
        private val callbacks: Callbacks,
        private val hiddenMessageId: () -> Long,
        private val takeHighlight: (MessageRow.Bubble) -> Highlight?,
    ) : RecyclerView.ViewHolder(view) {
        private val forwardLinearLayout = view.findViewById<LinearLayout>(R.id.forwardLinearLayout)
        private val forwardAvatarView = view.findViewById<AvatarView>(R.id.forwardAvatarView)
        private val forwardTextView = view.findViewById<TextView>(R.id.forwardTextView)
        private val replyPlateLinearLayout = view.findViewById<LinearLayout>(R.id.replyPlateLinearLayout)
        private val replyBarView = view.findViewById<View>(R.id.replyBarView)
        private val replyNameTextView = view.findViewById<TextView>(R.id.replyNameTextView)
        private val replyTextView = view.findViewById<TextView>(R.id.replyTextView)

        /** The flash of a highlighted bubble, over it until it fades. */
        private var highlightAnimator: android.animation.ValueAnimator? = null
        private var highlightFlash: android.graphics.drawable.Drawable? = null
        private val albumView = view.findViewById<AlbumView>(R.id.albumView)
        private val senderLinearLayout = view.findViewById<LinearLayout>(R.id.senderLinearLayout)
        private val senderEmojiStatusView = view.findViewById<CustomEmojiView>(R.id.senderEmojiStatusView)
        private val reactionsChipGroup = view.findViewById<ChipGroup>(R.id.reactionsChipGroup)

        private var message: MessageItem? = null

        /** Where the last touch on the text went down, to tell link taps from message taps. */
        private var textTouchX = 0f
        private var textTouchY = 0f
        private val videoNoteView = view.findViewById<VideoNoteView>(R.id.videoNoteView)
        private val voiceNoteView = view.findViewById<VoiceNoteView>(R.id.voiceNoteView)
        private val documentView = view.findViewById<DocumentView>(R.id.documentView)
        private val filesLinearLayout = view.findViewById<LinearLayout>(R.id.filesLinearLayout)
        private val animationView = view.findViewById<AnimationView>(R.id.animationView)
        private val bubbleCardView = view.findViewById<MaterialCardView>(R.id.bubbleCardView)
        private val mediaFrameLayout = view.findViewById<FrameLayout>(R.id.mediaFrameLayout)
        private val mediaImageView = view.findViewById<ImageView>(R.id.mediaImageView)
        private val durationTextView = view.findViewById<TextView>(R.id.durationTextView)
        private val messageTextView = view.findViewById<TextView>(R.id.messageTextView)
        private val metaTextView = view.findViewById<TextView>(R.id.metaTextView)
        private val senderTextView = view.findViewById<TextView>(R.id.senderTextView)
        private val stickerView = view.findViewById<StickerView>(R.id.stickerView)
        private val uploadLinearLayout = view.findViewById<LinearLayout>(R.id.uploadLinearLayout)
        private val uploadProgressIndicator =
            view.findViewById<CircularProgressIndicator>(R.id.uploadProgressIndicator)
        private val uploadTextView = view.findViewById<TextView>(R.id.uploadTextView)
        private val cancelUploadMaterialButton =
            view.findViewById<MaterialButton>(R.id.cancelUploadMaterialButton)

        private var messageId = 0L

        private val bigCorner = view.toPx(20).toFloat()
        private val smallCorner = view.toPx(4).toFloat()
        private val avatarSpace = view.toPx(SenderAvatarDecoration.AVATAR_SPACE_DP)
        private val stickerSize = view.toPx(STICKER_SIZE_DP)
        private val videoNoteSize = view.toPx(VIDEO_NOTE_SIZE_DP)
        private val iconSize = view.toPx(14)

        // Like Google Messages: bubbles leave a quarter of the row to the other side
        private val maxBubbleWidth = view.resources.displayMetrics.widthPixels * 3 / 4

        // Photos take the row but the list's padding and an avatar's room, big enough to make out
        private val maxPhotoWidth = (view.resources.displayMetrics.widthPixels - view.toPx(PHOTO_SIDE_SPACE_DP))
            .coerceAtMost(view.toPx(PHOTO_MAX_WIDTH_DP))

        private val accentColors = view.resources.obtainTypedArray(R.array.chat_accent_colors)
            .use { IntArray(it.length()) { i -> it.getColor(i, 0) } }

        private val outgoingColors = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorPrimaryContainer
        ) to MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimaryContainer)

        private val incomingColors = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSurfaceContainerHighest
        ) to MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)

        private val colorOnSurfaceVariant = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        private val colorError = MaterialColors.getColor(
            view, androidx.appcompat.R.attr.colorError
        )

        private val chosenColors = MaterialColors.getColor(
            view, androidx.appcompat.R.attr.colorPrimary
        ) to MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary)

        private val reactionColors = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSecondaryContainer
        ) to MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSecondaryContainer)

        init {
            messageTextView.maxWidth = maxBubbleWidth
            cancelUploadMaterialButton.setOnClickListener { onCancelUpload(messageId) }
            documentView.onActionClick = { message?.let(callbacks::onDocumentClick) }

            bubbleCardView.setOnClickListener {
                message?.let { callbacks.onMessageClick(it, bubbleCardView) }
            }
            // The empty sides of the row are the message too
            view.setOnClickListener { bubbleCardView.performClick() }
            // A photo opens the viewer, the rest of the bubble the menu
            mediaImageView.setOnClickListener {
                val message = message ?: return@setOnClickListener
                when (message.kind) {
                    MessagePreview.Kind.PHOTO, MessagePreview.Kind.VIDEO, MessagePreview.Kind.ANIMATION ->
                        callbacks.onMediaClick(message, mediaImageView)
                    else -> bubbleCardView.performClick()
                }
            }
            albumView.onItemClick = { item, image ->
                when (item.kind) {
                    MessagePreview.Kind.PHOTO, MessagePreview.Kind.VIDEO, MessagePreview.Kind.ANIMATION ->
                        callbacks.onMediaClick(item, image)
                    else -> bubbleCardView.performClick()
                }
            }
            senderLinearLayout.setOnClickListener {
                message?.let { callbacks.onSenderClick(it.senderId) }
            }
            // Their profile, unless they hid it: then it's the message like the rest
            forwardLinearLayout.setOnClickListener {
                val originId = message?.forward?.originId ?: 0L
                when (originId) {
                    0L -> bubbleCardView.performClick()
                    else -> callbacks.onSenderClick(originId)
                }
            }
            replyPlateLinearLayout.setOnClickListener {
                val message = message ?: return@setOnClickListener
                when (message.reply?.let { it.messageId != 0L && !it.isDeleted }) {
                    true -> callbacks.onReplyClick(message)
                    else -> bubbleCardView.performClick()
                }
            }
            replyTextView.maxWidth = maxBubbleWidth
            replyNameTextView.maxWidth = maxBubbleWidth
            forwardTextView.maxWidth = maxBubbleWidth
            // Links take their taps, the rest of the text opens the menu like the bubble
            messageTextView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    textTouchX = event.x
                    textTouchY = event.y
                }
                false
            }
            messageTextView.setOnClickListener {
                if (!isOnLink()) {
                    bubbleCardView.performClick()
                }
            }
        }

        private fun isOnLink(): Boolean {
            val layout = messageTextView.layout ?: return false
            val text = messageTextView.text as? Spanned ?: return false
            val line = layout.getLineForVertical(
                (textTouchY - messageTextView.totalPaddingTop + messageTextView.scrollY).toInt()
            )
            val offset = layout.getOffsetForHorizontal(
                line, textTouchX - messageTextView.totalPaddingLeft + messageTextView.scrollX
            )
            return text.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
        }

        fun bindReactions(message: MessageItem) {
            this.message = message
            val reactions = message.reactions
            reactionsChipGroup.isVisible = reactions.isNotEmpty()

            while (reactionsChipGroup.childCount > reactions.size) {
                reactionsChipGroup.removeViewAt(reactionsChipGroup.childCount - 1)
            }
            while (reactionsChipGroup.childCount < reactions.size) {
                reactionsChipGroup.addView(
                    LayoutInflater.from(view.context)
                        .inflate(R.layout.message_reaction_item, reactionsChipGroup, false)
                )
            }

            reactions.forEachIndexed { index, reaction ->
                val card = reactionsChipGroup.getChildAt(index) as MaterialCardView
                val (background, foreground) = if (reaction.isChosen) chosenColors else reactionColors
                card.setCardBackgroundColor(background)
                card.setOnClickListener { callbacks.onReactionClick(message, reaction) }

                val customEmojiView = card.findViewById<CustomEmojiView>(R.id.customEmojiView)
                val emojiTextView = card.findViewById<TextView>(R.id.emojiTextView)
                val countTextView = card.findViewById<TextView>(R.id.countTextView)
                when {
                    reaction.customEmojiId != 0L -> customEmojiView.setCustomEmoji(reaction.customEmojiId)
                    else -> customEmojiView.clear()
                }
                emojiTextView.isVisible = reaction.customEmojiId == 0L
                emojiTextView.text = when {
                    reaction.emoji != null -> Emoji.replaceEmoji(
                        reaction.emoji, emojiTextView.paint.fontMetricsInt, false
                    )

                    // Paid reactions are stars
                    else -> PAID_REACTION
                }
                countTextView.text = reaction.count.toString()
                countTextView.setTextColor(foreground)
            }
        }

        fun bind(row: MessageRow.Bubble) {
            val message = row.message
            this.message = message
            bubbleCardView.alpha = if (message.id == hiddenMessageId()) 0f else 1f
            val context = view.context
            val isOutgoing = message.isOutgoing
            val media = message.media
            messageId = message.id
            // Stickers and video notes float without a bubble
            val isSticker = media?.type == MessageItem.Media.Type.STICKER
                    || media?.type == MessageItem.Media.Type.ANIMATED_STICKER
                    || media?.type == MessageItem.Media.Type.VIDEO_NOTE

            (view as LinearLayout).gravity = if (isOutgoing) Gravity.END else Gravity.START
            view.updatePaddingRelative(start = if (row.hasAvatar) avatarSpace else 0)
            view.updateLayoutParams<RecyclerView.LayoutParams> {
                topMargin = view.toPx(if (row.isGroupStart) 8 else 2)
            }

            // Colors: stickers float without a bubble
            val (background, foreground) = if (isOutgoing) outgoingColors else incomingColors
            bubbleCardView.setCardBackgroundColor(if (isSticker) Color.TRANSPARENT else background)
            val textColor = if (isSticker) colorOnSurfaceVariant else foreground
            messageTextView.setTextColor(foreground)
            messageTextView.setLinkTextColor(foreground)

            // Runs of messages get tight corners on the side of the sender, where they touch
            val isRtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val senderSideIsRight = isOutgoing != isRtl
            val top = if (row.isGroupStart) bigCorner else smallCorner
            val bottom = if (row.isGroupEnd) bigCorner else smallCorner
            bubbleCardView.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(if (senderSideIsRight) bigCorner else top)
                .setBottomLeftCornerSize(if (senderSideIsRight) bigCorner else bottom)
                .setTopRightCornerSize(if (senderSideIsRight) top else bigCorner)
                .setBottomRightCornerSize(if (senderSideIsRight) bottom else bigCorner)
                .build()
            // The card only clips to its shape when all the corners match, a photo would poke
            // out of the tight ones. ponytail: path outlines clip since Android 13, older ones
            // keep square photo corners in runs; clip in dispatchDraw if that matters
            bubbleCardView.clipToOutline = true

            // Sender, inside the first bubble of a run, with their emoji status
            senderLinearLayout.isVisible = row.showSender && message.senderName != null && !isSticker
            if (senderLinearLayout.isVisible) {
                senderTextView.text = message.senderName
                senderTextView.setTextColor(
                    accentColors[message.senderAccentColorId.mod(accentColors.size)]
                )
                senderEmojiStatusView.setEmojiStatus(message.senderEmojiStatusId, message.senderIsPremium)
            }

            // Music and files have no picture to tile, they stack instead of the mosaic
            val files = row.album.takeIf { album -> album.all { it.media == null } }.orEmpty()
            val pictures = row.album.takeIf { files.isEmpty() }.orEmpty()

            bindMedia(media, message.uploadProgress != null)
            bindPlayable(message, media)
            bindDocument(message)
            bindAlbum(pictures)
            bindFiles(files)
            bindUpload(message)

            // Photos fill their bubble: the text and reactions wrap under them, not widen it
            bubbleCardView.updateLayoutParams {
                width = when {
                    pictures.isNotEmpty() -> albumView.contentWidth
                    media?.type == MessageItem.Media.Type.PHOTO
                            || media?.type == MessageItem.Media.Type.ANIMATION
                            || media?.type == MessageItem.Media.Type.VIDEO -> photoSize(media).first
                    else -> ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }

            // Text: media without a caption has none, other kinds say what they are
            val text = when {
                message.kind == MessagePreview.Kind.TEXT -> message.text.orEmpty()
                // The player or the file says what it is
                message.playable != null || message.document != null -> message.text.orEmpty()
                media != null -> message.text.takeUnless { isSticker }.orEmpty()
                else -> SpannableStringBuilder().apply {
                    append(context.getString(message.kind.stringResId), StyleSpan(Typeface.ITALIC), 0)
                    message.text?.let { append('\n').append(it) }
                }
            }
            // Emoji drawn from Telegram's sprites, custom ones over them where the text has any
            val fontMetrics = messageTextView.paint.fontMetricsInt
            var spanned = Emoji.replaceEmoji(text, fontMetrics, true)
            if (message.customEmoji.isNotEmpty()) {
                // Text without plain emoji comes back as is
                if (spanned !is Spannable) {
                    spanned = android.text.SpannableString(spanned)
                }
                // Kinds that say what they are put the text after a line
                val shift = if (text is SpannableStringBuilder) text.length - message.text.orEmpty().length else 0
                spanned.setCustomEmoji(message.customEmoji, fontMetrics, shift)
            }
            messageTextView.text = spanned
            messageTextView.attachCustomEmoji()
            messageTextView.isVisible = text.isNotEmpty()
            bindForward(message.forward.takeUnless { isSticker }, foreground)
            bindReply(message.reply.takeUnless { isSticker }, foreground)
            val hasHeader = senderLinearLayout.isVisible || forwardLinearLayout.isVisible
                    || replyPlateLinearLayout.isVisible
            messageTextView.updatePaddingRelative(top = view.toPx(if (hasHeader) 4 else 8))

            bindReactions(message)

            bindMeta(message, textColor)

            bindHighlight(row)
        }

        /** "Forwarded from" whom, with their avatar. */
        private fun bindForward(forward: MessageItem.Forward?, foreground: Int) {
            forwardLinearLayout.isVisible = forward != null
            forward ?: return
            val context = view.context
            val template = context.getString(R.string.message_forwarded_from, "\u0000")
            val start = template.indexOf('\u0000')
            forwardTextView.text = SpannableStringBuilder(template.replace("\u0000", forward.name)).apply {
                if (start >= 0) {
                    setSpan(
                        StyleSpan(Typeface.BOLD), start, start + forward.name.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            forwardTextView.setTextColor(foreground)
            forwardAvatarView.setChat(
                com.github.borz7zy.shadowgram.models.ChatItem(
                    id = forward.originId,
                    title = forward.name,
                    accentColorId = forward.accentColorId,
                    photoPath = forward.photoPath,
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
        }

        /** The plate of the replied message: who, and what it says or the part quoted. */
        private fun bindReply(reply: MessageItem.Reply?, foreground: Int) {
            replyPlateLinearLayout.isVisible = reply != null
            reply ?: return
            val context = view.context
            val accent = accentColors[reply.accentColorId.mod(accentColors.size)]
            replyPlateLinearLayout.backgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, REPLY_ALPHA))
            replyBarView.backgroundTintList = ColorStateList.valueOf(accent)
            replyNameTextView.setTextColor(accent)
            replyTextView.setTextColor(foreground)

            replyNameTextView.text = when {
                reply.isDeleted -> context.getString(R.string.message_reply_deleted)
                else -> reply.name ?: "…"
            }
            val quote = reply.quote
            replyTextView.isVisible = !reply.isDeleted
            replyTextView.maxLines = if (quote != null) QUOTE_MAX_LINES else 1
            replyTextView.setTypeface(null, if (quote != null) Typeface.ITALIC else Typeface.NORMAL)
            val text = quote ?: reply.text?.takeIf { it.isNotBlank() }
                ?: context.getString(reply.kind.stringResId)
            replyTextView.text = Emoji.replaceEmoji(text, replyTextView.paint.fontMetricsInt, false)
        }

        /** Flash the bubble, and mark the quote in its text, when it's the highlighted one. */
        fun bindHighlight(row: MessageRow.Bubble) {
            val highlight = takeHighlight(row) ?: return
            highlightAnimator?.cancel()

            // Over the bubble's own drawing, its card keeps its ripple in the foreground
            val color = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
            val flash = android.graphics.drawable.ColorDrawable(color).apply {
                alpha = 0
                setBounds(0, 0, bubbleCardView.width, bubbleCardView.height)
            }
            highlightFlash?.let(bubbleCardView.overlay::remove)
            highlightFlash = flash
            bubbleCardView.overlay.add(flash)

            // The quote stays marked a while longer than the flash
            val quoteSpan = highlight.quote?.let { quote -> markQuote(quote, highlight.quotePosition) }

            highlightAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = HIGHLIGHT_DURATION_MS
                addUpdateListener { flash.alpha = ((it.animatedValue as Float) * HIGHLIGHT_ALPHA).toInt() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (highlightAnimator === animation) {
                            highlightAnimator = null
                        }
                        bubbleCardView.overlay.remove(flash)
                        if (highlightFlash === flash) {
                            highlightFlash = null
                        }
                    }
                })
                start()
            }
            quoteSpan?.let { span ->
                messageTextView.postDelayed({
                    (messageTextView.text as? Spannable)?.removeSpan(span)
                }, QUOTE_HIGHLIGHT_DURATION_MS)
            }
        }

        /**
         * Mark [quote] in the shown text, where [position] says or the first place it's in.
         *
         * @return The mark, to take off later; null when the text doesn't have it
         */
        private fun markQuote(quote: String, position: Int): Any? {
            val text = messageTextView.text as? Spannable ?: return null
            val message = message ?: return null
            // Kinds that say what they are put the text after a line
            val shift = text.length - message.text.orEmpty().length
            val string = text.toString()
            val start = (shift + position).takeIf { string.startsWith(quote, it) }
                ?: string.indexOf(quote).takeIf { it >= 0 }
                ?: return null
            val color = MaterialColors.getColor(view, com.google.android.material.R.attr.colorTertiaryContainer)
            return android.text.style.BackgroundColorSpan(color).also {
                text.setSpan(it, start, start + quote.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /** An album's pictures in place of the one picture of [MessageRow.Bubble.message]. */
        private fun bindAlbum(album: List<MessageItem>) {
            albumView.isVisible = album.isNotEmpty()
            if (album.isEmpty()) {
                return
            }
            mediaFrameLayout.isVisible = true
            durationTextView.isVisible = false
            mediaImageView.dispose()
            mediaImageView.isVisible = false
            stickerView.setSticker(null, 0)
            stickerView.isVisible = false
            animationView.setVideo(null as Uri?)
            animationView.isVisible = false
            mediaFrameLayout.clipToOutline = false
            mediaFrameLayout.updateLayoutParams {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            albumView.setItems(album, maxPhotoWidth)
        }

        /**
         * A music or file album: a player or file row per message, the views kept while their
         * kinds stay, so progress updates don't make them again.
         */
        private fun bindFiles(album: List<MessageItem>) {
            filesLinearLayout.isVisible = album.isNotEmpty()
            if (album.isNotEmpty()) {
                // The stack stands for the bubble's own player or file row
                voiceNoteView.isVisible = false
                documentView.isVisible = false
            }
            val context = view.context
            val width = context.resources.getDimensionPixelSize(R.dimen.voice_note_width)
            album.forEachIndexed { i, item ->
                val playable = item.playable?.takeUnless { it.isVideo }
                val kind = if (playable != null) VoiceNoteView::class.java else DocumentView::class.java
                var child = filesLinearLayout.getChildAt(i)
                if (child?.javaClass != kind) {
                    child?.let { filesLinearLayout.removeViewAt(i) }
                    child = when (playable) {
                        null -> DocumentView(context).apply { setPaddingRelative(view.toPx(8), view.toPx(8), view.toPx(12), 0) }
                        else -> VoiceNoteView(context).apply { setPaddingRelative(view.toPx(8), view.toPx(8), 0, 0) }
                    }
                    filesLinearLayout.addView(child, i, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                when (child) {
                    is VoiceNoteView -> child.bind(trackOf(item, playable!!))
                    is DocumentView -> {
                        val document = item.document
                        child.isVisible = document != null
                        document?.let(child::bind)
                        child.onActionClick = { callbacks.onDocumentClick(item) }
                    }
                }
            }
            while (filesLinearLayout.childCount > album.size) {
                filesLinearLayout.removeViewAt(album.size)
            }
        }

        /** Voice messages get a player, video messages play in place of their still. */
        private fun bindPlayable(message: MessageItem, media: MessageItem.Media?) {
            val playable = message.playable
            val isVideoNote = playable?.isVideo == true && media?.type == MessageItem.Media.Type.VIDEO_NOTE
            val isVoiceNote = playable != null && !playable.isVideo

            videoNoteView.isVisible = isVideoNote
            voiceNoteView.isVisible = isVoiceNote
            when {
                isVideoNote -> {
                    // The view clips itself and leaves room for its ring
                    mediaImageView.dispose()
                    mediaImageView.isVisible = false
                    mediaFrameLayout.clipToOutline = false
                    mediaFrameLayout.updateLayoutParams {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    videoNoteView.bind(trackOf(message, playable!!), media!!.path, media.minithumbnail)
                }

                isVoiceNote -> voiceNoteView.bind(trackOf(message, playable!!))
            }
        }

        fun bindDocument(message: MessageItem) {
            this.message = message
            val document = message.document
            documentView.isVisible = document != null
            document?.let(documentView::bind)
        }

        fun bindUpload(message: MessageItem) {
            val progress = message.uploadProgress
            uploadLinearLayout.isVisible = progress != null
            if (progress != null) {
                uploadProgressIndicator.setProgressCompat(progress, true)
                uploadTextView.text = view.context.getString(
                    when (message.isProcessing) {
                        true -> R.string.chat_processing_progress
                        false -> R.string.chat_upload_progress
                    },
                    progress,
                )
            }
        }

        /**
         * @param isUploading A file without a picture (voice, document) still shows the upload
         */
        private fun bindMedia(media: MessageItem.Media?, isUploading: Boolean) {
            mediaFrameLayout.isVisible = media != null || isUploading
            // Hidden while the viewer showed it, the row may hold another message now
            mediaFrameLayout.alpha = 1f
            durationTextView.isVisible = media?.type == MessageItem.Media.Type.VIDEO
            if (media == null) {
                mediaImageView.dispose()
                mediaImageView.isVisible = false
                stickerView.setSticker(null, 0)
                stickerView.isVisible = false
                animationView.setVideo(null as Uri?)
                animationView.isVisible = false
                mediaFrameLayout.clipToOutline = false
                mediaFrameLayout.updateLayoutParams {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                return
            }

            val isVideoNote = media.type == MessageItem.Media.Type.VIDEO_NOTE
            mediaFrameLayout.outlineProvider = if (isVideoNote) ovalOutline else ViewOutlineProvider.BACKGROUND
            mediaFrameLayout.clipToOutline = isVideoNote

            // GIFs play over their still, videos too, muted, as their preview
            val isAnimation = media.type == MessageItem.Media.Type.ANIMATION
            val isVideo = media.type == MessageItem.Media.Type.VIDEO
            animationView.isVisible = isAnimation || isVideo
            animationView.setVideo(
                when {
                    isAnimation -> media.videoPath?.let { Uri.fromFile(File(it)) }
                    isVideo -> TdFileDataSource.uriOf(media.videoFileId)
                    else -> null
                }
            )
            if (isVideo) {
                durationTextView.text = DateUtils.formatElapsedTime(media.duration.toLong())
            }

            val (width, height) = when (media.type) {
                MessageItem.Media.Type.PHOTO, MessageItem.Media.Type.ANIMATION,
                MessageItem.Media.Type.VIDEO -> photoSize(media)

                MessageItem.Media.Type.VIDEO_NOTE -> videoNoteSize to videoNoteSize

                else -> stickerSize to stickerSize
            }
            mediaFrameLayout.updateLayoutParams {
                this.width = width
                this.height = height
            }


            val isAnimatedSticker = media.type == MessageItem.Media.Type.ANIMATED_STICKER
            stickerView.isVisible = isAnimatedSticker
            mediaImageView.isVisible = !isAnimatedSticker
            mediaImageView.scaleType = when (media.type) {
                MessageItem.Media.Type.PHOTO, MessageItem.Media.Type.ANIMATION,
                MessageItem.Media.Type.VIDEO, MessageItem.Media.Type.VIDEO_NOTE ->
                    ImageView.ScaleType.CENTER_CROP

                else -> ImageView.ScaleType.FIT_CENTER
            }

            if (isAnimatedSticker) {
                mediaImageView.dispose()
                stickerView.setSticker(media.path?.let(::File), stickerSize)
                return
            }

            stickerView.setSticker(null, 0)
            when (val data = media.path?.let(::File) ?: media.minithumbnail) {
                null -> {
                    mediaImageView.dispose()
                    mediaImageView.setImageDrawable(null)
                }

                else -> mediaImageView.load(data) {
                    size(width, height)
                }
            }
        }

        /**
         * A single photo, GIF or video in full, its proportions kept: as wide as a bubble gets,
         * narrower when that would make it too tall.
         */
        private fun photoSize(media: MessageItem.Media): Pair<Int, Int> {
            val maxHeight = view.toPx(PHOTO_MAX_HEIGHT_DP)
            if (media.width <= 0 || media.height <= 0) {
                return maxPhotoWidth to maxPhotoWidth.coerceAtMost(maxHeight)
            }
            var width = maxPhotoWidth
            var height = width * media.height / media.width
            if (height > maxHeight) {
                height = maxHeight
                width = height * media.width / media.height
            }
            return width.coerceAtLeast(view.toPx(PHOTO_MIN_WIDTH_DP)) to
                    height.coerceAtLeast(view.toPx(PHOTO_MIN_HEIGHT_DP))
        }

        private fun bindMeta(message: MessageItem, color: Int) {
            val time = DateFormat.getTimeFormat(view.context).format(message.date * 1000L)
            metaTextView.text = time

            val iconResId = when {
                message.state == MessageItem.State.FAILED -> R.drawable.ic_error
                !message.isOutgoing -> null
                message.state == MessageItem.State.PENDING -> R.drawable.ic_schedule
                message.state == MessageItem.State.READ -> R.drawable.ic_done_all
                else -> R.drawable.ic_done
            }
            val metaColor = when (message.state) {
                MessageItem.State.FAILED -> colorError
                else -> ColorUtils.setAlphaComponent(color, META_ALPHA)
            }

            metaTextView.setTextColor(metaColor)
            metaTextView.setCompoundDrawablesRelative(
                null,
                null,
                iconResId?.let { view.context.getDrawable(it) }
                    ?.apply { setBounds(0, 0, iconSize, iconSize) },
                null,
            )
            metaTextView.compoundDrawableTintList = ColorStateList.valueOf(metaColor)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<MessageRow>() {
        override fun areItemsTheSame(oldItem: MessageRow, newItem: MessageRow) =
            oldItem.areItemsTheSame(newItem)

        override fun areContentsTheSame(oldItem: MessageRow, newItem: MessageRow) =
            oldItem.areContentsTheSame(newItem)

        override fun getChangePayload(oldItem: MessageRow, newItem: MessageRow): Any? = when {
            oldItem is MessageRow.Bubble && newItem is MessageRow.Bubble && oldItem.copy(
                message = oldItem.message.copy(reactions = newItem.message.reactions)
            ) == newItem -> PAYLOAD_REACTIONS

            oldItem is MessageRow.Bubble && newItem is MessageRow.Bubble && oldItem.copy(
                message = oldItem.message.copy(
                    uploadProgress = newItem.message.uploadProgress,
                    isProcessing = newItem.message.isProcessing,
                )
            ) == newItem -> PAYLOAD_UPLOAD

            oldItem is MessageRow.Bubble && newItem is MessageRow.Bubble && oldItem.copy(
                message = oldItem.message.copy(document = newItem.message.document)
            ) == newItem -> PAYLOAD_DOCUMENT

            else -> null
        }
    }

    private companion object {
        const val PAYLOAD_UPLOAD = "upload"
        const val PAYLOAD_REACTIONS = "reactions"
        const val PAYLOAD_DOCUMENT = "document"
        const val PAYLOAD_HIGHLIGHT = "highlight"
        const val PAYLOAD_EMOJI = "emoji"

        private fun View.invalidateTexts() {
            invalidate()
            (this as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    group.getChildAt(i).invalidateTexts()
                }
            }
        }

        /** The reply plate's background, of its sender's accent color. */
        const val REPLY_ALPHA = 0x33
        const val QUOTE_MAX_LINES = 3
        const val HIGHLIGHT_DURATION_MS = 1_200L
        const val HIGHLIGHT_ALPHA = 90
        const val QUOTE_HIGHLIGHT_DURATION_MS = 2_500L
        const val PAID_REACTION = "⭐"

        val ovalOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }

        const val TYPE_BUBBLE = 0
        const val TYPE_SEPARATOR = 1

        const val STICKER_SIZE_DP = 160
        const val VIDEO_NOTE_SIZE_DP = 200
        const val PHOTO_MAX_WIDTH_DP = 420
        const val PHOTO_MIN_HEIGHT_DP = 80
        const val PHOTO_MIN_WIDTH_DP = 120
        const val PHOTO_MAX_HEIGHT_DP = 480

        /** The list's padding on both sides, an avatar's room and a little air. */
        const val PHOTO_SIDE_SPACE_DP = 12 * 2 + SenderAvatarDecoration.AVATAR_SPACE_DP + 16

        const val META_ALPHA = 0xB3
    }
}

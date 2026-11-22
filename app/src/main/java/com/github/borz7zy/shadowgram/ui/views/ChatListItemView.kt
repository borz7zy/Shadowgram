/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.models.ChatRow
import com.github.borz7zy.shadowgram.ui.text.singleLine
import com.github.borz7zy.shadowgram.ui.text.stringResId
import com.github.borz7zy.shadowgram.ui.text.typingText
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.util.Calendar

/**
 * A chat list row. [setRow] only touches the child views whose content changed, so that
 * frequent updates (typing, new messages, unread counters) don't flicker.
 *
 * Selected rows are checked, in selection mode pinned rows show a drag handle instead of the pin.
 */
class ChatListItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {
    // Views
    private val avatarView by lazy { findViewById<AvatarView>(R.id.avatarView) }
    private val checkImageView by lazy { findViewById<ImageView>(R.id.checkImageView) }
    private val dateTextView by lazy { findViewById<TextView>(R.id.dateTextView) }
    private val emojiStatusView by lazy { findViewById<CustomEmojiView>(R.id.emojiStatusView) }
    private val mentionTextView by lazy { findViewById<TextView>(R.id.mentionTextView) }
    private val mutedImageView by lazy { findViewById<ImageView>(R.id.mutedImageView) }
    private val pinnedImageView by lazy { findViewById<ImageView>(R.id.pinnedImageView) }
    private val subtitleTextView by lazy { findViewById<TextView>(R.id.subtitleTextView) }
    private val textLinearLayout by lazy { findViewById<View>(R.id.textLinearLayout) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.titleTextView) }
    private val unreadTextView by lazy { findViewById<TextView>(R.id.unreadTextView) }
    private val avatarUnreadTextView by lazy { findViewById<TextView>(R.id.avatarUnreadTextView) }
    private val avatarMutedImageView by lazy { findViewById<ImageView>(R.id.avatarMutedImageView) }
    private val avatarMentionTextView by lazy { findViewById<TextView>(R.id.avatarMentionTextView) }
    private val onlineView by lazy { findViewById<View>(R.id.onlineView) }

    private val colorPrimary = MaterialColors.getColor(
        this, androidx.appcompat.R.attr.colorPrimary
    )
    private val colorOutline = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOutline
    )

    private var row: ChatRow? = null

    /**
     * 0 for a full row, 1 for just the avatar: the chat list collapsing into a rail while a forum
     * is open. Everything but the avatar fades out on the way.
     */
    var collapse = 0f
        set(value) {
            if (field == value) return
            field = value
            textLinearLayout.alpha = (1f - value * 2f).coerceIn(0f, 1f)
            textLinearLayout.isVisible = value < 1f
            updateAvatarIndicators()
        }

    init {
        setCardBackgroundColor(context.getColorStateList(R.color.chat_list_item_background))
        setCardForegroundColor(android.content.res.ColorStateList.valueOf(0))
        cardElevation = 0f
        radius = 0f
        strokeWidth = 0
        isCheckable = true
        checkedIcon = null

        inflate(context, R.layout.chat_list_item, this)
    }

    fun setRow(row: ChatRow) {
        val oldRow = this.row?.takeIf { it.chat.id == row.chat.id }
        this.row = row

        if (oldRow?.isSelected != row.isSelected || oldRow.isOpenForum != row.isOpenForum) {
            isChecked = row.isSelected || row.isOpenForum
            checkImageView.isVisible = row.isSelected
        }

        setChat(row.chat, oldRow?.chat, row.isSelectionMode)
        updateAvatarIndicators()
    }

    private fun updateAvatarIndicators() {
        val row = row ?: return
        val chat = row.chat
        val compact = collapse >= .5f
        avatarUnreadTextView.isVisible = compact && (chat.unreadCount > 0 || chat.isMarkedAsUnread)
        avatarUnreadTextView.text = when {
            chat.unreadCount > 99 -> "99+"
            chat.unreadCount > 0 -> chat.unreadCount.toString()
            else -> ""
        }
        avatarUnreadTextView.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (chat.isMuted) colorOutline else colorPrimary
        )
        avatarUnreadTextView.setTextColor(MaterialColors.getColor(this,
            if (chat.isMuted) com.google.android.material.R.attr.colorSurface
            else com.google.android.material.R.attr.colorOnPrimary))
        avatarMutedImageView.isVisible = compact && chat.isMuted
        avatarMentionTextView.isVisible = compact && chat.unreadMentionCount > 0
        onlineView.isVisible = chat.isOnline && !row.isSelected
        // The hidden title/counters must remain available to TalkBack in the avatar rail.
        contentDescription = if (compact) listOfNotNull(
            chat.displayTitle(),
            if (chat.isOnline) context.getString(R.string.last_seen_online) else null,
            if (chat.isMuted) context.getString(R.string.chat_notifications_muted) else null,
            if (chat.unreadCount > 0) context.getString(R.string.chat_unread_count, chat.unreadCount)
            else if (chat.isMarkedAsUnread) context.getString(R.string.chat_marked_unread) else null,
        ).joinToString(", ") else null
    }

    private fun ChatItem.displayTitle() =
        if (isSavedMessages) context.getString(R.string.chat_saved_messages) else title

    private fun setChat(chat: ChatItem, old: ChatItem?, isSelectionMode: Boolean) {
        if (old?.title != chat.title || old?.isSavedMessages != chat.isSavedMessages) {
            titleTextView.text = chat.displayTitle()
        }

        avatarView.setChat(chat)

        if (old == null || old.emojiStatusId != chat.emojiStatusId || old.isPremium != chat.isPremium) {
            emojiStatusView.isVisible = chat.emojiStatusId != 0L || chat.isPremium
            emojiStatusView.setEmojiStatus(chat.emojiStatusId, chat.isPremium)
        }

        val date = chat.lastMessage?.date
        if (old == null || old.lastMessage?.date != date) {
            dateTextView.text = date?.let { formatDate(it) }
        }

        if (old == null || old.typing != chat.typing || old.draft != chat.draft
            || old.lastMessage != chat.lastMessage
        ) {
            subtitleTextView.text = subtitle(chat)
        }

        if (old?.isMuted != chat.isMuted) {
            mutedImageView.isVisible = chat.isMuted
            unreadTextView.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (chat.isMuted) colorOutline else colorPrimary
            )
        }

        if (old == null || old.unreadCount != chat.unreadCount
            || old.isMarkedAsUnread != chat.isMarkedAsUnread
        ) {
            unreadTextView.text = when (chat.unreadCount) {
                0 -> null
                else -> chat.unreadCount.toString()
            }
            unreadTextView.isVisible = chat.unreadCount > 0 || chat.isMarkedAsUnread
        }

        if (old?.unreadMentionCount != chat.unreadMentionCount) {
            mentionTextView.isVisible = chat.unreadMentionCount > 0
        }

        // In selection mode pinned chats can be reordered, the pin becomes a drag handle
        val pinnedIconResId = when {
            !chat.isPinned -> null
            isSelectionMode -> R.drawable.ic_drag_reorder
            unreadTextView.isVisible || mentionTextView.isVisible -> null
            else -> R.drawable.ic_push_pin
        }
        if (pinnedImageView.tag != pinnedIconResId) {
            pinnedImageView.tag = pinnedIconResId
            pinnedIconResId?.let { pinnedImageView.setImageResource(it) }
            pinnedImageView.isVisible = pinnedIconResId != null
        }
    }

    private fun subtitle(chat: ChatItem): CharSequence {
        chat.typing?.let { return context.typingText(it) }

        chat.draft?.let {
            return resources.getString(R.string.chat_draft, it.singleLine())
        }

        val lastMessage = chat.lastMessage ?: return ""
        val content = lastMessage.content()

        return when {
            lastMessage.isOutgoing -> resources.getString(R.string.chat_sender_you, content)
            lastMessage.senderName != null -> resources.getString(
                R.string.chat_sender, lastMessage.senderName, content
            )

            else -> content
        }
    }

    private fun MessagePreview.content(): String {
        val text = text?.singleLine()

        return when (kind) {
            MessagePreview.Kind.TEXT -> text.orEmpty()
            MessagePreview.Kind.STICKER -> listOfNotNull(
                text, resources.getString(R.string.message_sticker)
            ).joinToString(" ")

            else -> text ?: resources.getString(kind.stringResId)
        }
    }

    private fun formatDate(unixTime: Int): String {
        val millis = unixTime * 1000L
        val now = System.currentTimeMillis()

        return when {
            DateUtils.isToday(millis) -> DateFormat.getTimeFormat(context).format(millis)
            now - millis < DateUtils.WEEK_IN_MILLIS -> DateUtils.formatDateTime(
                context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
            )

            isSameYear(millis, now) -> DateUtils.formatDateTime(
                context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                        or DateUtils.FORMAT_NO_YEAR
            )

            else -> DateFormat.getDateFormat(context).format(millis)
        }
    }

    companion object {
        private fun isSameYear(a: Long, b: Long) = Calendar.getInstance().run {
            timeInMillis = a
            val year = get(Calendar.YEAR)
            timeInMillis = b
            year == get(Calendar.YEAR)
        }
    }
}

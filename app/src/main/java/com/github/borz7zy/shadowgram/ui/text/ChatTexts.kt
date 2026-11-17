/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.text

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ChatHeader
import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.models.ChatItem.Typing
import com.github.borz7zy.shadowgram.models.ContactItem.LastSeen

/**
 * Texts shared by the chat list, the contacts and the open chat.
 */

fun String.singleLine() = replace('\n', ' ')

fun String.initials() = split(' ')
    .filter { it.isNotBlank() }
    .take(2)
    .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    .joinToString("")
    .uppercase()

fun Context.typingText(typing: Typing): String = typing.senderName?.let {
    getString(typing.kind.groupStringResId, it)
} ?: getString(typing.kind.stringResId)

/** The line under the title of a chat or topic, null for none. */
fun Context.subtitleText(subtitle: ChatHeader.Subtitle): String? = when (subtitle) {
    ChatHeader.Subtitle.None -> null
    ChatHeader.Subtitle.Bot -> getString(R.string.chat_bot)
    is ChatHeader.Subtitle.Status -> formatLastSeen(subtitle.lastSeen)
    is ChatHeader.Subtitle.Typing -> typingText(subtitle.typing)
    is ChatHeader.Subtitle.Members -> resources.getQuantityString(
        when (subtitle.isChannel) {
            true -> R.plurals.chat_subscribers
            false -> R.plurals.chat_members
        },
        subtitle.count,
        subtitle.count,
    )

    is ChatHeader.Subtitle.TopicMessages -> resources.getQuantityString(
        R.plurals.topic_messages, subtitle.count, subtitle.count
    )

    ChatHeader.Subtitle.Scheduled -> getString(R.string.chat_scheduled_messages)
}

fun Context.formatLastSeen(lastSeen: LastSeen): String = when (lastSeen.kind) {
    LastSeen.Kind.ONLINE -> getString(R.string.last_seen_online)
    LastSeen.Kind.OFFLINE -> {
        val millis = lastSeen.time * 1000L

        when (DateUtils.isToday(millis)) {
            true -> getString(R.string.last_seen_at, DateFormat.getTimeFormat(this).format(millis))
            false -> getString(
                R.string.last_seen_date,
                DateUtils.formatDateTime(
                    this, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                ),
            )
        }
    }

    LastSeen.Kind.RECENTLY -> getString(R.string.last_seen_recently)
    LastSeen.Kind.LAST_WEEK -> getString(R.string.last_seen_last_week)
    LastSeen.Kind.LAST_MONTH -> getString(R.string.last_seen_last_month)
    LastSeen.Kind.LONG_AGO -> getString(R.string.last_seen_long_ago)
}

val MessagePreview.Kind.stringResId
    get() = when (this) {
        MessagePreview.Kind.TEXT, MessagePreview.Kind.OTHER -> R.string.message_other
        MessagePreview.Kind.PHOTO -> R.string.message_photo
        MessagePreview.Kind.VIDEO -> R.string.message_video
        MessagePreview.Kind.VIDEO_NOTE -> R.string.message_video_note
        MessagePreview.Kind.VOICE_NOTE -> R.string.message_voice_note
        MessagePreview.Kind.AUDIO -> R.string.message_audio
        MessagePreview.Kind.DOCUMENT -> R.string.message_document
        MessagePreview.Kind.ANIMATION -> R.string.message_animation
        MessagePreview.Kind.STICKER -> R.string.message_sticker
        MessagePreview.Kind.POLL -> R.string.message_poll
        MessagePreview.Kind.LOCATION -> R.string.message_location
        MessagePreview.Kind.CONTACT -> R.string.message_contact
        MessagePreview.Kind.CALL -> R.string.message_call
    }

private val Typing.Kind.stringResId
    get() = when (this) {
        Typing.Kind.TYPING, Typing.Kind.OTHER -> R.string.typing
        Typing.Kind.RECORDING_VOICE_NOTE -> R.string.typing_recording_voice_note
        Typing.Kind.RECORDING_VIDEO -> R.string.typing_recording_video
        Typing.Kind.SENDING_MEDIA -> R.string.typing_sending_media
        Typing.Kind.CHOOSING_STICKER -> R.string.typing_choosing_sticker
    }

private val Typing.Kind.groupStringResId
    get() = when (this) {
        Typing.Kind.TYPING, Typing.Kind.OTHER -> R.string.typing_group
        Typing.Kind.RECORDING_VOICE_NOTE -> R.string.typing_recording_voice_note_group
        Typing.Kind.RECORDING_VIDEO -> R.string.typing_recording_video_group
        Typing.Kind.SENDING_MEDIA -> R.string.typing_sending_media_group
        Typing.Kind.CHOOSING_STICKER -> R.string.typing_choosing_sticker_group
    }

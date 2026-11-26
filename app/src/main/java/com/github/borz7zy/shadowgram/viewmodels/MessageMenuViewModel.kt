/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.graphics.drawable.Drawable
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.MessageEdits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/**
 * What the menu of a message can do with it and the reactions it can get.
 */
class MessageMenuViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val chatId = savedStateHandle.get<Long>(ARG_CHAT_ID) ?: 0L
    private val messageId = savedStateHandle.get<Long>(ARG_MESSAGE_ID) ?: 0L

    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient()
    }

    /** Where the tapped bubble was and what it looked like, handed over by the chat. */
    class Anchor : ViewModel() {
        var snapshot: (() -> Drawable)? = null
        var bounds: Rect? = null

        /** Where the bubble is now on screen, null once it's off it. */
        var locate: (() -> Rect?)? = null

        var viewport: (() -> Rect?)? = null

        /** Restore the real bubble before removing the dialog's last frame. */
        var restore: (() -> android.view.View?)? = null

        /** The bubble's text, selectable over its picture to quote or copy a part; null for none. */
        var text: SelectableText? = null
    }

    /**
     * @param text What the bubble's text view shows
     * @param bounds Where that view is on screen
     * @param shift Where the message's own text starts in [text], after what kinds say they are
     * @param style Gives a text view the same size, font and spacing, so the text lays out the
     *   same over the picture
     */
    class SelectableText(
        val text: CharSequence,
        val bounds: Rect,
        val shift: Int,
        val style: (android.widget.TextView) -> Unit,
    )

    /**
     * @param emoji A plain emoji, null for a custom one
     */
    data class Reaction(val emoji: String?, val customEmojiId: Long, val fallbackEmoji: String? = emoji)

    /**
     * @param properties What can be done with the message, null while loading or if it's gone
     * @param topReactions The row above the bubble
     * @param allReactions The grid it expands to
     */
    data class State(
        val isLoaded: Boolean = false,
        val areReactionsLoaded: Boolean = false,
        val properties: TdApi.MessageProperties? = null,
        val canEdit: Boolean = false,
        val canAddProfileAudio: Boolean = false,
        /** The file "Save to Downloads" saves, null for none. */
        val saveable: Downloads.Saveable? = null,
        val topReactions: List<Reaction> = listOf(),
        val allReactions: List<Reaction> = listOf(),
        /** The account's custom emoji sets, interleaved with their titles. */
        val customReactions: List<Any> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val client = client.await()
            val propertiesRequest = async { client.send(TdApi.GetMessageProperties(chatId, messageId)) }
            val messageRequest = async { client.send(TdApi.GetMessage(chatId, messageId)) }
            val properties = (propertiesRequest.await() as? Result.Success)?.data
            val message = (messageRequest.await() as? Result.Success)?.data
            val editTarget = withContext(Dispatchers.Default) {
                properties != null && message != null && MessageEdits.target(message, properties) != null
            }
            _state.update {
                it.copy(
                    isLoaded = true,
                    properties = properties,
                    canEdit = editTarget,
                    canAddProfileAudio = message?.content is TdApi.MessageAudio && properties?.canBeSaved == true,
                    saveable = message?.takeIf { properties?.canBeSaved == true }?.let(Downloads::saveableOf),
                )
            }
        }

        viewModelScope.launch {
            val client = client.await()
            val available = (client.send(
                TdApi.GetMessageAvailableReactions(chatId, messageId, ROW_SIZE)
            ) as? Result.Success)?.data
            if (available == null) {
                _state.update { it.copy(areReactionsLoaded = true) }
                return@launch
            }
            val top = available.topReactions.mapNotNull { it.type.toReaction() }
            val all = (top + available.recentReactions.mapNotNull { it.type.toReaction() } +
                    available.popularReactions.mapNotNull { it.type.toReaction() }).distinct()
            _state.update {
                it.copy(areReactionsLoaded = true, topReactions = top.take(ROW_SIZE), allReactions = all)
            }
            if (available.allowCustomEmoji) {
                launch {
                    client.stickers.snapshots().collect { snapshot ->
                        val reactions = withContext(Dispatchers.Default) {
                            buildList {
                                if (!snapshot.isPremium) return@buildList
                                for (pack in snapshot.emojiPacks) {
                                    add(pack.title)
                                    for (sticker in pack.stickers) {
                                        add(Reaction(null, sticker.customEmojiId, sticker.emoji))
                                    }
                                }
                            }
                        }
                        _state.update { it.copy(customReactions = reactions) }
                    }
                }
            }
        }
    }

    /** Copy the message's file to Downloads, on its own once the menu is gone. */
    fun saveToDownloads() {
        val saveable = _state.value.saveable ?: return
        viewModelScope.launch { Downloads.save(getApplication(), client.await(), saveable) }
    }

    /** A link to the message, for public chats and the ones it can be shared from. */
    suspend fun link(): String? = (client.await().send(
        TdApi.GetMessageLink(chatId, messageId, 0, 0, "", false, false)
    ) as? Result.Success)?.data?.link

    private fun TdApi.ReactionType.toReaction() = when (this) {
        is TdApi.ReactionTypeEmoji -> Reaction(emoji, 0L)
        is TdApi.ReactionTypeCustomEmoji -> Reaction(null, customEmojiId)
        else -> null
    }

    companion object {
        const val ARG_CHAT_ID = "chat_id"
        const val ARG_MESSAGE_ID = "message_id"
        const val ARG_TEXT = "text"

        /** Reactions in the row, like the official apps. */
        private const val ROW_SIZE = 7
    }
}

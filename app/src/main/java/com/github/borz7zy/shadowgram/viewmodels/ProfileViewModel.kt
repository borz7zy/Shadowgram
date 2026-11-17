/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * The profile of a user, group or channel: its header, details and tabs. Kept across
 * configuration changes, so nothing is reloaded.
 */
class ProfileViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    val chatId = savedStateHandle.get<Long>(ARG_CHAT_ID) ?: 0L

    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient()
    }

    /**
     * @param isPrivate A user rather than a group or channel
     * @param phoneNumber Users only, without the plus
     * @param username The public link name, without the at sign
     * @param about The bio of a user, or the description of a group or channel
     * @param tabs The tabs, in order
     */
    data class Info(
        val isPrivate: Boolean,
        val phoneNumber: String?,
        val username: String?,
        val about: String?,
        val tabs: List<ProfilePage.Kind>,
    )

    private val _info = MutableStateFlow<Info?>(null)
    val info = _info.asStateFlow()

    /** It has a photo, the avatar opens the photo viewer. */
    var hasPhoto = false
        private set

    /** The animated version of the photo, a file to stream; 0 for none. */
    private val _animationFileId = MutableStateFlow(0)
    val animationFileId = _animationFileId.asStateFlow()

    /** The big photo, once downloaded. */
    private val _photoPath = MutableStateFlow<String?>(null)
    val photoPath = _photoPath.asStateFlow()

    val header = flow { emitAll(client.await().chats.header(chatId, 0)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val client = client.await()

            // A contact may have no chat yet, its private chat has the user id
            val chat = (client.send(TdApi.GetChat(chatId)) as? Result.Success)?.data
                ?: (client.send(TdApi.CreatePrivateChat(chatId, false)) as? Result.Success)?.data
                ?: return@launch

            hasPhoto = chat.photo != null
            launch {
                val photo = chat.photo?.big ?: return@launch
                val file = client.send(TdApi.DownloadFile(photo.id, PHOTO_PRIORITY, 0, 0, true))
                _photoPath.value = (file as? Result.Success)?.data?.local
                    ?.takeIf { it.isDownloadingCompleted }?.path
            }

            if (chat.photo?.hasAnimation == true) {
                launch { _animationFileId.value = fullPhoto(chat.type)?.animation?.file?.id ?: 0 }
            }

            _info.value = when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> userInfo(type.userId)
                is TdApi.ChatTypeSecret -> userInfo(type.userId)
                is TdApi.ChatTypeBasicGroup -> Info(
                    false,
                    null,
                    null,
                    (client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) as? Result.Success)
                        ?.data?.description,
                    CHAT_TABS,
                )

                is TdApi.ChatTypeSupergroup -> Info(
                    false,
                    null,
                    (client.send(TdApi.GetSupergroup(type.supergroupId)) as? Result.Success)
                        ?.data?.usernames?.activeUsernames?.firstOrNull(),
                    (client.send(TdApi.GetSupergroupFullInfo(type.supergroupId)) as? Result.Success)
                        ?.data?.description,
                    CHAT_TABS,
                )

                else -> Info(false, null, null, null, CHAT_TABS)
            }
        }
    }

    /** The current photo in full, with its animation: only the full infos have it. */
    private suspend fun fullPhoto(type: TdApi.ChatType): TdApi.ChatPhoto? {
        val client = client.await()
        return when (type) {
            is TdApi.ChatTypePrivate -> (client.send(TdApi.GetUserFullInfo(type.userId)) as? Result.Success)
                ?.data?.let { it.personalPhoto ?: it.photo ?: it.publicPhoto }

            is TdApi.ChatTypeSupergroup -> (client.send(TdApi.GetSupergroupFullInfo(type.supergroupId)) as? Result.Success)
                ?.data?.photo

            is TdApi.ChatTypeBasicGroup -> (client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) as? Result.Success)
                ?.data?.photo

            else -> null
        }
    }

    // ponytail: details are read once, follow updateUserFullInfo and the like if they should
    // change while the profile is open
    private suspend fun userInfo(userId: Long): Info {
        val client = client.await()
        val user = (client.send(TdApi.GetUser(userId)) as? Result.Success)?.data
        val fullInfo = (client.send(TdApi.GetUserFullInfo(userId)) as? Result.Success)?.data
        return Info(
            true,
            user?.phoneNumber?.takeIf { it.isNotEmpty() },
            user?.usernames?.activeUsernames?.firstOrNull(),
            fullInfo?.bio?.text?.takeIf { it.isNotBlank() },
            USER_TABS,
        )
    }

    /** Mute the chat for good, or unmute it. */
    fun toggleMute() {
        viewModelScope.launch {
            val client = client.await()
            val chat = (client.send(TdApi.GetChat(chatId)) as? Result.Success)?.data ?: return@launch
            val isMuted = header.value?.chat?.isMuted ?: return@launch
            val settings = chat.notificationSettings.apply {
                useDefaultMuteFor = false
                muteFor = if (isMuted) 0 else MUTE_FOREVER
            }
            client.send(TdApi.SetChatNotificationSettings(chatId, settings))
        }
    }

    companion object {
        const val ARG_CHAT_ID = "chat_id"

        private const val PHOTO_PRIORITY = 16

        /** Telegram mutes for good past a year. */
        private const val MUTE_FOREVER = Int.MAX_VALUE

        private val USER_TABS = listOf(
            ProfilePage.Kind.POSTS,
            ProfilePage.Kind.SAVED,
            ProfilePage.Kind.GIFTS,
            ProfilePage.Kind.MEDIA,
            ProfilePage.Kind.FILES,
            ProfilePage.Kind.LINKS,
            ProfilePage.Kind.MUSIC,
            ProfilePage.Kind.VOICE_NOTES,
            ProfilePage.Kind.GIFS,
            ProfilePage.Kind.GROUPS,
            ProfilePage.Kind.POLLS,
        )

        private val CHAT_TABS = listOf(ProfilePage.Kind.MEMBERS) +
                USER_TABS - ProfilePage.Kind.GROUPS
    }
}

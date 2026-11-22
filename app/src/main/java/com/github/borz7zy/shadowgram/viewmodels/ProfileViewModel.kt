/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.github.borz7zy.shadowgram.telegram.EmojiStickers
import com.github.borz7zy.shadowgram.telegram.InputContents
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import com.github.borz7zy.shadowgram.telegram.giftOwner
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import org.drinkless.tdlib.TdApi

/** A user, group or channel profile backed by the current account's TDLib client. */
class ProfileViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val requestedChatId = savedStateHandle.get<Long>(ARG_CHAT_ID) ?: 0L
    val isOwnTab = savedStateHandle.get<Boolean>(ARG_OWN_TAB) == true

    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient()
    }
    private val me = viewModelScope.async { client.await().send(TdApi.GetMe()).getOrNull() }
    private val chat = viewModelScope.async {
        val client = client.await()
        if (isOwnTab) {
            val myId = me.await()?.id ?: return@async null
            client.send(TdApi.CreatePrivateChat(myId, false)).getOrNull()
        } else {
            client.send(TdApi.GetChat(requestedChatId)).getOrNull()
                ?: client.send(TdApi.CreatePrivateChat(requestedChatId, false)).getOrNull()
        }
    }

    data class Info(
        val isPrivate: Boolean,
        val phoneNumber: String? = null,
        val username: String? = null,
        val about: String? = null,
        val ownUser: TdApi.User? = null,
        val callUserId: Long? = null,
        val birthdate: TdApi.Birthdate? = null,
        val personalChannel: TdApi.Chat? = null,
        val firstProfileAudio: TdApi.Audio? = null,
    )

    private val _info = MutableStateFlow<Info?>(null)
    val info = _info.asStateFlow()
    private val _tabs = MutableStateFlow<List<ProfilePage.Kind>?>(null)
    val tabs = _tabs.asStateFlow()
    private val _contentRevision = MutableStateFlow(0)
    val contentRevision = _contentRevision.asStateFlow()
    private val contentMutation = Mutex()

    data class Avatar(val hasPhoto: Boolean = false, val path: String? = null, val animationId: Int = 0)

    private val _avatar = MutableStateFlow(Avatar())
    val avatar = _avatar.asStateFlow()
    var chatId = requestedChatId
        private set

    val header = flow {
        chat.await()?.let { emitAll(client.await().chats.header(it.id, 0)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val client = client.await()
            val chat = chat.await() ?: return@launch
            chatId = chat.id
            val type = chat.type
            val userId = when (type) {
                is TdApi.ChatTypePrivate -> type.userId
                is TdApi.ChatTypeSecret -> type.userId
                else -> null
            }
            val own = type is TdApi.ChatTypePrivate && userId == me.await()?.id

            launch {
                updates(client) { update ->
                    when (update) {
                        is TdApi.UpdateUser -> update.user.id == userId
                        is TdApi.UpdateUserFullInfo -> update.userId == userId
                        is TdApi.UpdateBasicGroupFullInfo -> (type as? TdApi.ChatTypeBasicGroup)?.basicGroupId ==
                            update.basicGroupId
                        is TdApi.UpdateSupergroup -> (type as? TdApi.ChatTypeSupergroup)?.supergroupId ==
                            update.supergroup.id
                        is TdApi.UpdateSupergroupFullInfo -> (type as? TdApi.ChatTypeSupergroup)?.supergroupId ==
                            update.supergroupId
                        is TdApi.UpdateChatTitle -> update.chatId == info.value?.personalChannel?.id
                        else -> false
                    }
                }.collectLatest {
                    _info.value = if (userId != null) userInfo(client, userId, own) else groupInfo(client, type)
                }
            }

            launch {
                updates(client) { it is TdApi.UpdateChatPhoto && it.chatId == chat.id }.collectLatest {
                    val photo = client.send(TdApi.GetChat(chat.id)).getOrNull()?.photo
                    _avatar.value = Avatar(hasPhoto = photo != null)
                    if (photo != null) coroutineScope {
                        val animation = async {
                            if (photo.hasAnimation) fullPhoto(client, type)?.animation?.file?.id ?: 0 else 0
                        }
                        val path = client.send(TdApi.DownloadFile(photo.big.id, PHOTO_PRIORITY, 0, 0, true))
                            .getOrNull()?.local?.takeIf { it.isDownloadingCompleted }?.path
                        _avatar.value = Avatar(true, path, animation.await())
                    }
                }
            }

            launch {
                if (isOwnTab) {
                    _tabs.value = OWN_TABS
                    return@launch
                }
                val candidates = when (type) {
                    is TdApi.ChatTypePrivate -> if (own) MEDIA_TABS else USER_TABS
                    is TdApi.ChatTypeSecret -> MEDIA_TABS
                    is TdApi.ChatTypeBasicGroup, is TdApi.ChatTypeSupergroup -> CHAT_TABS
                    else -> emptyList()
                }
                val availableTabs = candidates.map { kind ->
                    async {
                        val present = hasItems(client, chat, kind) ||
                            kind == ProfilePage.Kind.VOICE_NOTES &&
                            hasItems(client, chat, ProfilePage.Kind.VIDEO_NOTES)
                        kind.takeIf { present }
                    }
                }.awaitAll().filterNotNull()
                _tabs.value = if (own) OWN_TABS + availableTabs else availableTabs
            }
        }
    }

    private fun updates(client: TelegramClient, relevant: (TdApi.Object) -> Boolean) = callbackFlow {
        val listener: (TdApi.Object) -> Unit = { if (relevant(it)) trySend(Unit) }
        client.addUpdateListener(listener)
        trySend(Unit)
        awaitClose { client.removeUpdateListener(listener) }
    }.buffer(Channel.CONFLATED)

    private suspend fun hasItems(client: TelegramClient, chat: TdApi.Chat, kind: ProfilePage.Kind) =
        ProfilePage(client, getApplication(), chat.id, kind).hasItems(chat)

    private suspend fun userInfo(client: TelegramClient, userId: Long, own: Boolean): Info {
        val full = client.send(TdApi.GetUserFullInfo(userId)).getOrNull()
        val user = client.send(TdApi.GetUser(userId)).getOrNull()
        val channel = full?.personalChatId?.takeIf { it != 0L }?.let {
            client.send(TdApi.GetChat(it)).getOrNull()
        }
        return Info(
            isPrivate = true,
            phoneNumber = user?.phoneNumber?.takeIf { it.isNotEmpty() },
            username = user?.usernames?.activeUsernames?.firstOrNull(),
            about = full?.bio?.text?.takeIf { it.isNotBlank() },
            ownUser = user.takeIf { own },
            callUserId = userId.takeIf { !own && full?.canBeCalled == true },
            birthdate = full?.birthdate,
            personalChannel = channel,
            firstProfileAudio = full?.firstProfileAudio,
        )
    }

    private suspend fun groupInfo(client: TelegramClient, type: TdApi.ChatType) = when (type) {
        is TdApi.ChatTypeBasicGroup -> Info(
            isPrivate = false,
            about = client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)).getOrNull()?.description
                ?.takeIf { it.isNotBlank() },
        )

        is TdApi.ChatTypeSupergroup -> Info(
            isPrivate = false,
            username = client.send(TdApi.GetSupergroup(type.supergroupId)).getOrNull()?.usernames
                ?.activeUsernames?.firstOrNull(),
            about = client.send(TdApi.GetSupergroupFullInfo(type.supergroupId)).getOrNull()?.description
                ?.takeIf { it.isNotBlank() },
        )

        else -> Info(false)
    }

    private suspend fun fullPhoto(client: TelegramClient, type: TdApi.ChatType): TdApi.ChatPhoto? = when (type) {
        is TdApi.ChatTypePrivate -> client.send(TdApi.GetUserFullInfo(type.userId)).getOrNull()
            ?.let { it.personalPhoto ?: it.photo ?: it.publicPhoto }

        is TdApi.ChatTypeSupergroup -> client.send(TdApi.GetSupergroupFullInfo(type.supergroupId)).getOrNull()?.photo
        is TdApi.ChatTypeBasicGroup -> client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)).getOrNull()?.photo
        else -> null
    }

    suspend fun coverEmoji(id: Long) = EmojiStickers.customEmoji(client.await(), id)

    suspend fun profileColors() = client.await().chats.profileAccentColors()

    suspend fun suitablePersonalChannels(): Result<List<TdApi.Chat>, TdApi.Error> {
        val client = client.await()
        return when (val result = client.send(TdApi.GetSuitablePersonalChats())) {
            is Result.Failure -> result
            is Result.Success -> coroutineScope {
                Result.Success(result.data.chatIds.map { id ->
                    async { client.send(TdApi.GetChat(id)).getOrNull() }
                }.awaitAll().filterNotNull())
            }
        }
    }

    suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>) = client.await().send(function)

    suspend fun profileAudios(): Result<List<TdApi.Audio>, TdApi.Error> {
        val userId = when (val type = chat.await()?.type) {
            is TdApi.ChatTypePrivate -> type.userId
            is TdApi.ChatTypeSecret -> type.userId
            else -> return Result.Success(emptyList())
        }
        val audios = ArrayList<TdApi.Audio>()
        do {
            val page = when (val result = request(TdApi.GetUserProfileAudios(userId, audios.size, 100))) {
                is Result.Success -> result.data
                is Result.Failure -> return result
            }
            audios.addAll(page.audios)
        } while (page.audios.isNotEmpty() && audios.size < page.totalCount)
        _info.update { it?.copy(firstProfileAudio = audios.firstOrNull()) }
        return Result.Success(audios)
    }

    suspend fun addProfileAudio(uri: Uri): Result<TdApi.Ok, TdApi.Error>? = withContext(NonCancellable) {
        val content = try {
            InputContents.fromUri(getApplication(), uri, asFile = false, caption = null, preferAudio = true)
        } catch (_: RuntimeException) {
            null
        } as? TdApi.InputMessageAudio ?: return@withContext null
        val result = request(TdApi.AddProfileAudio(content.audio))
        if (result is Result.Failure) {
            val local = content.audio.audio as? TdApi.InputFileLocal
            withContext(Dispatchers.IO) { local?.let { File(it.path).delete() } }
        }
        result
    }

    suspend fun setProfileStoryPinned(storyId: Int, pinned: Boolean) = updateContent {
        when (val stories = request(TdApi.GetChatPostedToChatPageStories(chatId, 0, 1))) {
            is Result.Failure -> stories
            is Result.Success -> {
                val ids = stories.data.pinnedStoryIds.filter { it != storyId }
                request(TdApi.SetChatPinnedStories(chatId, (if (pinned) listOf(storyId) + ids else ids).toIntArray()))
            }
        }
    }

    suspend fun setStoryOnProfile(storyId: Int, visible: Boolean) =
        updateContent { request(TdApi.ToggleStoryIsPostedToChatPage(chatId, storyId, visible)) }

    suspend fun setGiftOnProfile(id: String, visible: Boolean) =
        updateContent { request(TdApi.ToggleGiftIsSaved(id, visible)) }

    suspend fun setProfileGiftPinned(id: String, pinned: Boolean): Result<TdApi.Ok, TdApi.Error> = updateContent {
        val owner = chat.await()?.giftOwner()
            ?: return@updateContent Result.Failure(TdApi.Error(400, "Gift owner unavailable"))
        val current = LinkedHashSet<String>()
        val offsets = HashSet<String>()
        var offset = ""
        val maxPinned = (request(TdApi.GetOption("pinned_gift_count_max")).getOrNull() as? TdApi.OptionValueInteger)
            ?.value?.takeIf { it > 0 } ?: Long.MAX_VALUE
        do {
            offsets += offset
            val response = request(TdApi.GetReceivedGifts().apply {
                businessConnectionId = ""
                ownerId = owner
                excludeUnsaved = true
                excludeUnlimited = true
                excludeUpgradable = true
                excludeNonUpgradable = true
                this.offset = offset
                limit = 100
            })
            val page = when (response) {
                is Result.Success -> response.data
                is Result.Failure -> return@updateContent response
            }
            page.gifts.filter { it.isPinned && it.receivedGiftId.isNotEmpty() }
                .forEach { current += it.receivedGiftId }
            offset = page.nextOffset
        } while (offset.isNotEmpty() && offset !in offsets && current.size < maxPinned)
        current.remove(id)
        val ids = if (pinned) listOf(id) + current else current.toList()
        request(TdApi.SetPinnedGifts(owner, ids.toTypedArray()))
    }

    private suspend fun updateContent(action: suspend () -> Result<TdApi.Ok, TdApi.Error>) =
        contentMutation.withLock {
            action().also { result ->
                if (result is Result.Success) _contentRevision.update { it + 1 }
            }
        }

    suspend fun setPhoto(uri: Uri): Result<TdApi.Ok, TdApi.Error>? = withContext(NonCancellable) {
        val file = InputContents.profilePhoto(getApplication(), uri) ?: return@withContext null
        val result = request(TdApi.SetProfilePhoto(TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(file.path)), false))
        if (result is Result.Failure) withContext(Dispatchers.IO) { file.delete() }
        result
    }

    fun toggleMute() {
        viewModelScope.launch {
            val client = client.await()
            val chat = client.send(TdApi.GetChat(chatId)).getOrNull() ?: return@launch
            val isMuted = header.value?.chat?.isMuted ?: return@launch
            val settings = chat.notificationSettings.apply {
                useDefaultMuteFor = false
                muteFor = if (isMuted) 0 else Int.MAX_VALUE
            }
            client.send(TdApi.SetChatNotificationSettings(chatId, settings))
        }
    }

    companion object {
        const val ARG_CHAT_ID = "chat_id"
        const val ARG_OWN_TAB = "own_tab"
        private const val PHOTO_PRIORITY = 16

        private val OWN_TABS = listOf(
            ProfilePage.Kind.POSTS,
            ProfilePage.Kind.ARCHIVED_POSTS,
            ProfilePage.Kind.GIFTS,
        )
        private val MEDIA_TABS = listOf(
            ProfilePage.Kind.MEDIA,
            ProfilePage.Kind.FILES,
            ProfilePage.Kind.LINKS,
            ProfilePage.Kind.MUSIC,
            ProfilePage.Kind.VOICE_NOTES,
            ProfilePage.Kind.GIFS,
            ProfilePage.Kind.POLLS,
        )
        private val USER_TABS = listOf(
            ProfilePage.Kind.POSTS,
            ProfilePage.Kind.SAVED,
            ProfilePage.Kind.GIFTS,
        ) + MEDIA_TABS + ProfilePage.Kind.GROUPS
        private val CHAT_TABS = listOf(
            ProfilePage.Kind.MEMBERS,
            ProfilePage.Kind.POSTS,
            ProfilePage.Kind.SAVED,
            ProfilePage.Kind.GIFTS,
        ) + MEDIA_TABS
    }
}

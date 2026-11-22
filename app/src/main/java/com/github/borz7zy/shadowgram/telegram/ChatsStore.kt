/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.models.ChatHeader
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.ChatItem.MessagePreview
import com.github.borz7zy.shadowgram.models.ChatItem.Typing
import com.github.borz7zy.shadowgram.models.ChatListKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

/**
 * Chats of an account, kept up to date from TDLib updates.
 *
 * Updates are applied on the TDLib thread under [lock], every change bumps [version]. Collectors
 * build immutable snapshots on [Dispatchers.Default]; since [version] is a StateFlow, bursts of
 * updates are conflated into a single snapshot.
 *
 * ponytail: every snapshot scans all chats, fine for thousands of chats. Keep per-list sorted
 * indexes if profiling ever says so.
 *
 * @param send Sends a request to TDLib, the handler is called on the TDLib thread
 */
class ChatsStore(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit) -> Unit,
) {
    private class LoadState {
        var isLoading = false
        var isFirstPageLoaded = false
        var isComplete = false
    }

    /**
     * @param items The chats, in display order
     * @param isLoaded Whether TDLib answered at least once, so an empty list really is empty
     */
    data class ChatListSnapshot(
        val items: List<ChatItem>,
        val isLoaded: Boolean,
    )

    private val lock = Any()

    // Guarded by lock
    private val chats = HashMap<Long, TdApi.Chat>()
    private val users = HashMap<Long, TdApi.User>()
    private val supergroups = HashMap<Long, TdApi.Supergroup>()
    private val basicGroups = HashMap<Long, TdApi.BasicGroup>()
    private val supergroupMemberCounts = HashMap<Long, Int>()
    private val scopeMuteFor = HashMap<Int, Int>()

    /** Chat actions by chat then sender, with the forum topic they happen in (0 for none). */
    private val chatActions = HashMap<Long, LinkedHashMap<Long, Pair<Int, TdApi.ChatAction>>>()

    /** Topics of the forums whose topics were asked for, by chat then topic. */
    private val topics = HashMap<Long, HashMap<Int, TdApi.ForumTopic>>()
    private val loadedForumIds = HashSet<Long>()
    private val photoFileIdToChatIds = HashMap<Int, MutableSet<Long>>()
    private val requestedPhotoFileIds = HashSet<Int>()
    private val loadStates = HashMap<ChatListKey, LoadState>()
    private var chatFolders = arrayOf<TdApi.ChatFolderInfo>()
    private var mainChatListPosition = 0

    /**
     * The small animated photos of the chats that have one, by chat; only full infos have them,
     * so each is asked for once, 0 while it's on its way or there's none.
     */
    private val animatedPhotoFileIds = HashMap<Long, Int>()

    /** Telegram's profile colors, by id: the light and the dark theme's backgrounds. */
    private val profileColors = HashMap<Int, ChatHeader.ProfileColors>()
    private var availableProfileColors = emptyList<TdApi.ProfileAccentColor>()
    private var myUserId = 0L

    fun profileAccentColors(): List<TdApi.ProfileAccentColor> = synchronized(lock) {
        availableProfileColors
    }

    private val version = MutableStateFlow(0L)

    /**
     * The chat lists to show as tabs, in order.
     */
    fun folders(): Flow<List<ChatFolder>> = version
        .map {
            synchronized(lock) {
                val folders = chatFolders.map {
                    ChatFolder(ChatListKey.Folder(it.id), it.name.text.text)
                }.toMutableList()

                folders.add(
                    mainChatListPosition.coerceIn(0, folders.size),
                    ChatFolder(ChatListKey.Main, null),
                )

                folders.toList()
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * The chats of a chat list, starts loading it when collected.
     */
    fun chatList(key: ChatListKey): Flow<ChatListSnapshot> = version
        .map { snapshot(key) }
        .distinctUntilChanged()
        .onStart { loadMore(key) }
        .flowOn(Dispatchers.Default)

    /**
     * Ask TDLib for more chats of a chat list. Does nothing when a request is already running
     * or everything has been loaded.
     */
    fun loadMore(key: ChatListKey) {
        synchronized(lock) {
            val loadState = loadStates.getOrPut(key, ::LoadState)
            if (loadState.isLoading || loadState.isComplete) {
                return
            }
            loadState.isLoading = true
        }

        send(TdApi.LoadChats(key.toTdApi(), PAGE_SIZE)) { result ->
            synchronized(lock) {
                loadStates.getValue(key).apply {
                    isLoading = false
                    isFirstPageLoaded = true
                    // 404 means that all the chats have been loaded
                    isComplete = result is TdApi.Error && result.code == 404
                }
            }
            version.update { it + 1 }
        }
    }

    /**
     * Change the order of the pinned chats of a chat list. The new order is shown right away and
     * reverted if TDLib refuses it.
     *
     * @param chatIds All the pinned chats of the list, in the new order
     * @return Whether TDLib accepted the new order
     */
    suspend fun setPinnedChats(key: ChatListKey, chatIds: List<Long>): Boolean {
        // Reuse the current orders of these chats, so that the rest of the list doesn't move
        val previousOrders = synchronized(lock) {
            val positions = chatIds.mapNotNull { chatId ->
                chats[chatId]?.positions?.firstOrNull { ChatListKey.of(it.list) == key }
            }
            if (positions.size != chatIds.size || positions.any { !it.isPinned }) {
                return false
            }

            val previousOrders = chatIds.zip(positions.map { it.order }).toMap()
            val orders = positions.map { it.order }.sortedDescending()

            chatIds.zip(orders).forEach { (chatId, order) ->
                chats.getValue(chatId).setPosition(TdApi.ChatPosition(key.toTdApi(), order, true, null))
            }

            previousOrders
        }
        version.update { it + 1 }

        val result = suspendCancellableCoroutine { continuation ->
            send(TdApi.SetPinnedChats(key.toTdApi(), chatIds.toLongArray())) {
                continuation.resume(it)
            }
        }

        if (result is TdApi.Error) {
            synchronized(lock) {
                previousOrders.forEach { (chatId, order) ->
                    chats[chatId]?.setPosition(TdApi.ChatPosition(key.toTdApi(), order, true, null))
                }
            }
            version.update { it + 1 }

            return false
        }

        return true
    }

    /**
     * Apply a TDLib update. Must be called for every update, in order.
     */
    fun onUpdate(update: TdApi.Object) {
        val changed = synchronized(lock) { apply(update) }

        if (changed) {
            version.update { it + 1 }
        }
    }

    private fun apply(update: TdApi.Object): Boolean {
        when (update) {
            is TdApi.UpdateNewChat -> {
                chats[update.chat.id] = update.chat
                trackPhoto(update.chat)
            }

            is TdApi.UpdateChatTitle -> chat(update.chatId) { title = update.title }

            is TdApi.UpdateChatPhoto -> chat(update.chatId) {
                photo = update.photo
                trackPhoto(this)
                // Asked for again with the new photo
                animatedPhotoFileIds.remove(id)
            }

            is TdApi.UpdateChatAccentColors -> chat(update.chatId) {
                accentColorId = update.accentColorId
                profileAccentColorId = update.profileAccentColorId
                profileBackgroundCustomEmojiId = update.profileBackgroundCustomEmojiId
                // TDLib can deliver the chat update before the matching user update.
                profileUser()?.let { user ->
                    user.profileAccentColorId = update.profileAccentColorId
                    user.profileBackgroundCustomEmojiId = update.profileBackgroundCustomEmojiId
                }
            }

            is TdApi.UpdateProfileAccentColors -> {
                val colors = update.colors.associateBy { it.id }
                availableProfileColors = update.availableAccentColorIds.asSequence().mapNotNull(colors::get).toList()
                update.colors.forEach {
                    profileColors[it.id] = ChatHeader.ProfileColors(
                        it.lightThemeColors.backgroundColors.map { color -> color or OPAQUE },
                        it.darkThemeColors.backgroundColors.map { color -> color or OPAQUE },
                    )
                }
            }

            is TdApi.UpdateOption -> {
                if (update.name != "my_id") return false
                myUserId = (update.value as? TdApi.OptionValueInteger)?.value ?: 0L
            }

            is TdApi.UpdateChatLastMessage -> chat(update.chatId) {
                lastMessage = update.lastMessage
                update.positions.forEach { setPosition(it) }
            }

            is TdApi.UpdateChatPosition -> chat(update.chatId) { setPosition(update.position) }

            is TdApi.UpdateChatDraftMessage -> chat(update.chatId) {
                draftMessage = update.draftMessage
                update.positions.forEach { setPosition(it) }
            }

            is TdApi.UpdateChatReadInbox -> chat(update.chatId) {
                lastReadInboxMessageId = update.lastReadInboxMessageId
                unreadCount = update.unreadCount
            }

            is TdApi.UpdateChatReadOutbox -> chat(update.chatId) {
                lastReadOutboxMessageId = update.lastReadOutboxMessageId
            }

            is TdApi.UpdateChatUnreadMentionCount -> chat(update.chatId) {
                unreadMentionCount = update.unreadMentionCount
            }

            is TdApi.UpdateMessageMentionRead -> chat(update.chatId) {
                unreadMentionCount = update.unreadMentionCount
            }

            is TdApi.UpdateChatUnreadReactionCount -> chat(update.chatId) {
                unreadReactionCount = update.unreadReactionCount
            }

            is TdApi.UpdateMessageUnreadReactions -> chat(update.chatId) {
                unreadReactionCount = update.unreadReactionCount
            }

            is TdApi.UpdateChatIsMarkedAsUnread -> chat(update.chatId) {
                isMarkedAsUnread = update.isMarkedAsUnread
            }

            is TdApi.UpdateChatHasScheduledMessages -> chat(update.chatId) {
                hasScheduledMessages = update.hasScheduledMessages
            }

            is TdApi.UpdateChatHasProtectedContent -> chat(update.chatId) {
                hasProtectedContent = update.hasProtectedContent
            }

            is TdApi.UpdateChatNotificationSettings -> chat(update.chatId) {
                notificationSettings = update.notificationSettings
            }
            is TdApi.UpdateScopeNotificationSettings -> scopeMuteFor[update.scope.constructor] = update.notificationSettings.muteFor
            is TdApi.UpdateChatPermissions -> chat(update.chatId) { permissions = update.permissions }

            is TdApi.UpdateMessageContent -> chat(update.chatId) {
                lastMessage?.takeIf { it.id == update.messageId }?.content = update.newContent
            }

            is TdApi.UpdateChatAction -> {
                    val actions = chatActions.getOrPut(update.chatId, ::LinkedHashMap)
                val senderId = update.senderId.id

                when (update.action) {
                    is TdApi.ChatActionCancel -> actions.remove(senderId)
                    else -> actions[senderId] = update.topicId.forumTopicId to update.action
                }

                if (actions.isEmpty()) {
                    chatActions.remove(update.chatId)
                }
            }

            is TdApi.UpdateUser -> users[update.user.id] = update.user

            is TdApi.UpdateChatEmojiStatus -> chat(update.chatId) {
                emojiStatus = update.emojiStatus
                profileUser()?.emojiStatus = update.emojiStatus
            }

            is TdApi.UpdateUserStatus -> users[update.userId]?.status = update.status

            is TdApi.UpdateSupergroup -> supergroups[update.supergroup.id] = update.supergroup

            is TdApi.UpdateSupergroupFullInfo ->
                supergroupMemberCounts[update.supergroupId] = update.supergroupFullInfo.memberCount

            is TdApi.UpdateBasicGroup -> basicGroups[update.basicGroup.id] = update.basicGroup

            is TdApi.UpdateChatViewAsTopics -> chat(update.chatId) {
                viewAsTopics = update.viewAsTopics
            }

            is TdApi.UpdateForumTopicInfo -> {
                val topic = topics[update.info.chatId]?.get(update.info.forumTopicId)
                    ?: return false
                topic.info = update.info
            }

            is TdApi.UpdateForumTopic -> {
                val topic = topics[update.chatId]?.get(update.forumTopicId) ?: return false
                topic.isPinned = update.isPinned
                topic.lastReadInboxMessageId = update.lastReadInboxMessageId
                topic.lastReadOutboxMessageId = update.lastReadOutboxMessageId
                topic.unreadMentionCount = update.unreadMentionCount
                topic.notificationSettings = update.notificationSettings
                // The unread count isn't part of the update
                refreshTopic(update.chatId, update.forumTopicId)
            }

            is TdApi.UpdateNewMessage -> {
                val message = update.message
                val topicId = message.topicId.forumTopicId
                if (topicId == 0 || message.chatId !in topics) {
                    return false
                }
                // Last message, order and unread count of the topic all change
                refreshTopic(message.chatId, topicId)
                return false
            }

            is TdApi.UpdateChatFolders -> {
                chatFolders = update.chatFolders
                mainChatListPosition = update.mainChatListPosition
            }

            is TdApi.UpdateFile -> {
                val chatIds = photoFileIdToChatIds[update.file.id] ?: return false

                chatIds.forEach { chatId ->
                    chats[chatId]?.photo?.takeIf { it.small.id == update.file.id }?.small =
                        update.file
                }
            }

            else -> return false
        }

        return true
    }

    /**
     * The topics of a forum, starts loading them when collected.
     *
     * ponytail: only the first 100 topics, page with the next offsets once forums with more
     * show up.
     */
    fun forumTopics(chatId: Long): Flow<ChatListSnapshot> = version
        .map { topicsSnapshot(chatId) }
        .distinctUntilChanged()
        .onStart { loadTopics(chatId) }
        .flowOn(Dispatchers.Default)

    internal fun notificationTitle(chatId: Long): String? = synchronized(lock) { chats[chatId]?.title }

    /**
     * The top bar of a chat, or of one of its topics when [topicId] isn't 0.
     */
    fun header(chatId: Long, topicId: Int): Flow<ChatHeader?> = version
        .map { synchronized(lock) { headerOf(chatId, topicId) } }
        .distinctUntilChanged()
        .onStart {
            if (topicId != 0) {
                refreshTopic(chatId, topicId)
            }
            loadMemberCount(chatId)
        }
        .flowOn(Dispatchers.Default)

    /** Read the current TDLib user and apply it in order with the other TDLib updates. */
    suspend fun readProfileUser(userId: Long): TdApi.User? = suspendCancellableCoroutine { continuation ->
        send(TdApi.GetUser(userId)) { result ->
            val user = result as? TdApi.User
            user?.let { onUpdate(TdApi.UpdateUser(it)) }
            continuation.resume(user)
        }
    }

    /**
     * A message sender as shown in groups.
     *
     * @param photo Its small photo, may still need downloading
     */
    data class Sender(
        val name: String?,
        val accentColorId: Int,
        val photo: TdApi.File?,
        /** Its emoji status, a custom emoji id, 0 for none. */
        val emojiStatusId: Long = 0L,
        val isPremium: Boolean = false,
    )

    fun sender(sender: TdApi.MessageSender): Sender = synchronized(lock) {
        when (sender) {
            is TdApi.MessageSenderUser -> users[sender.userId]?.let {
                Sender(
                    "${it.firstName} ${it.lastName}".trim(),
                    it.accentColorId,
                    it.profilePhoto?.small,
                    it.emojiStatus.customEmojiId(),
                    it.isPremium,
                )
            }

            is TdApi.MessageSenderChat -> chats[sender.chatId]?.let {
                Sender(it.title, it.accentColorId, it.photo?.small, it.emojiStatus.customEmojiId())
            }

            else -> null
        } ?: Sender(null, 0, null)
    }

    /**
     * How far a chat, or one of its topics, has been read on both sides.
     *
     * @param isGroup Whether sender names are shown
     */
    data class ReadState(
        val isGroup: Boolean,
        val unreadCount: Int,
        val lastReadInboxMessageId: Long,
        val lastReadOutboxMessageId: Long,
    )

    /** A user's public username, null without one. */
    fun username(userId: Long): String? = synchronized(lock) {
        users[userId]?.usernames?.activeUsernames?.firstOrNull()
    }

    /** The newest message of a chat or topic as TDLib knows it, 0 when it doesn't. */
    fun lastMessageId(chatId: Long, topicId: Int): Long = synchronized(lock) {
        when (topicId) {
            0 -> chats[chatId]?.lastMessage?.id
            else -> topics[chatId]?.get(topicId)?.lastMessage?.id
        } ?: 0L
    }

    fun readState(chatId: Long, topicId: Int): ReadState = synchronized(lock) {
        val chat = chats[chatId] ?: return@synchronized ReadState(false, 0, 0L, 0L)
        val topic = topics[chatId]?.get(topicId)?.takeIf { topicId != 0 }
        when (topic) {
            null -> ReadState(
                chat.isGroup,
                chat.unreadCount,
                chat.lastReadInboxMessageId,
                chat.lastReadOutboxMessageId,
            )

            else -> ReadState(
                chat.isGroup,
                topic.unreadCount,
                topic.lastReadInboxMessageId,
                topic.lastReadOutboxMessageId,
            )
        }
    }

    private fun loadTopics(chatId: Long) {
        send(TdApi.GetForumTopics(chatId, "", 0, 0, 0, TOPICS_LIMIT)) { result ->
            synchronized(lock) {
                val map = topics.getOrPut(chatId, ::HashMap)
                if (result is TdApi.ForumTopics) {
                    result.topics.forEach { map[it.info.forumTopicId] = it }
                }
                loadedForumIds.add(chatId)
            }
            version.update { it + 1 }
        }
    }

    private fun refreshTopic(chatId: Long, topicId: Int) {
        send(TdApi.GetForumTopic(chatId, topicId)) { result ->
            if (result !is TdApi.ForumTopic) {
                return@send
            }
            synchronized(lock) {
                topics.getOrPut(chatId, ::HashMap)[topicId] = result
            }
            version.update { it + 1 }
        }
    }

    /**
     * Member counts of big supergroups only come with their full info.
     */
    private fun loadMemberCount(chatId: Long) {
        val supergroupId = synchronized(lock) {
            (chats[chatId]?.type as? TdApi.ChatTypeSupergroup)?.supergroupId
        } ?: return
        send(TdApi.GetSupergroupFullInfo(supergroupId)) {}
    }

    private fun topicsSnapshot(chatId: Long) = synchronized(lock) {
        val chat = chats[chatId]
        val items = when (chat) {
            null -> listOf()
            else -> topics[chatId]?.values.orEmpty()
                .filter { !it.info.isHidden || it.info.isGeneral }
                .map { it.toChatItem(chat) }
                .sortedWith(
                    compareByDescending<ChatItem> { it.isPinned }.thenByDescending { it.order }
                )
        }

        ChatListSnapshot(items, chatId in loadedForumIds)
    }

    private fun TdApi.ForumTopic.toChatItem(chat: TdApi.Chat) = ChatItem(
        id = info.forumTopicId.toLong(),
        title = info.name,
        accentColorId = chat.accentColorId,
        photoPath = null,
        minithumbnail = null,
        isPinned = isPinned,
        order = order,
        lastMessage = lastMessage?.toPreview(chat),
        draft = (draftMessage?.content as? TdApi.DraftMessageContentText)?.text?.text
            ?.takeIf { it.isNotBlank() },
        typing = chat.typing(info.forumTopicId),
        unreadCount = unreadCount,
        unreadMentionCount = unreadMentionCount,
        isMarkedAsUnread = false,
        isMuted = !notificationSettings.useDefaultMuteFor && notificationSettings.muteFor > 0,
        topicIconColor = info.icon.color or 0xFF000000.toInt(),
        hasProtectedContent = chat.hasProtectedContent,
        hasScheduledMessages = chat.hasScheduledMessages,
    )

    private fun headerOf(chatId: Long, topicId: Int): ChatHeader? {
        val chat = chats[chatId] ?: return null

        if (topicId != 0) {
            val topic = topics[chatId]?.get(topicId) ?: return null
            val typing = chat.typing(topicId)
            return ChatHeader(
                topic.toChatItem(chat),
                when (typing) {
                    null -> ChatHeader.Subtitle.None
                    else -> ChatHeader.Subtitle.Typing(typing)
                },
            )
        }

        val item = chat.positions.firstOrNull()?.let { chat.toChatItem(it) }
            ?: chat.toChatItem(TdApi.ChatPosition(TdApi.ChatListMain(), 0, false, null))
        val typing = chat.typing(null)

        val subtitle = when {
            typing != null -> ChatHeader.Subtitle.Typing(typing)
            else -> when (val type = chat.type) {
                is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> {
                    val userId = (type as? TdApi.ChatTypePrivate)?.userId
                        ?: (type as TdApi.ChatTypeSecret).userId
                    val user = users[userId]
                    when {
                        user == null -> ChatHeader.Subtitle.None
                        user.type is TdApi.UserTypeBot -> ChatHeader.Subtitle.Bot
                        else -> ChatHeader.Subtitle.Status(user.status.toLastSeen())
                    }
                }

                is TdApi.ChatTypeBasicGroup -> basicGroups[type.basicGroupId]?.let {
                    ChatHeader.Subtitle.Members(it.memberCount, false)
                } ?: ChatHeader.Subtitle.None

                is TdApi.ChatTypeSupergroup -> supergroups[type.supergroupId]?.let {
                    ChatHeader.Subtitle.Members(
                        supergroupMemberCounts[type.supergroupId]?.takeIf { c -> c > 0 }
                            ?: it.memberCount,
                        it.isChannel,
                    )
                } ?: ChatHeader.Subtitle.None

                else -> ChatHeader.Subtitle.None
            }
        }

        val user = chat.profileUser()
        val emojiStatus = if (user != null) user.emojiStatus else chat.emojiStatus
        val collectible = emojiStatus?.type as? TdApi.EmojiStatusTypeUpgradedGift
        val backdrop = collectible?.backdropColors
        val collectibleColors = backdrop?.let {
            val colors = listOf(it.edgeColor or OPAQUE, it.centerColor or OPAQUE)
            ChatHeader.ProfileColors(colors, colors)
        }
        val profileAccentColorId = user?.profileAccentColorId ?: chat.profileAccentColorId
        return ChatHeader(
            item,
            subtitle,
            emojiStatusId = emojiStatus.customEmojiId(),
            isPremium = user?.isPremium == true,
            profileColors = collectibleColors ?: profileColors[profileAccentColorId],
            profileEmojiId = collectible?.symbolCustomEmojiId
                ?: user?.profileBackgroundCustomEmojiId ?: chat.profileBackgroundCustomEmojiId,
            profilePatternColor = backdrop?.symbolColor?.or(OPAQUE),
        )
    }

    /**
     * The first action going on in the chat, only the ones of [topicId] unless it's null.
     */
    private fun TdApi.Chat.typing(topicId: Int?) = chatActions[id]?.entries
        ?.firstOrNull { (_, action) -> topicId == null || action.first == topicId }
        ?.let { (senderId, action) ->
            Typing(
                senderName = when (type) {
                    is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> null
                    else -> users[senderId]?.firstName ?: chats[senderId]?.title
                },
                kind = action.second.toTypingKind(),
            )
        }

    private val TdApi.Chat.isGroup
        get() = when (val type = type) {
            is TdApi.ChatTypeBasicGroup -> true
            is TdApi.ChatTypeSupergroup -> !type.isChannel
            else -> false
        }

    private fun TdApi.Chat.profileUser() = when (val type = type) {
        is TdApi.ChatTypePrivate -> users[type.userId]
        is TdApi.ChatTypeSecret -> users[type.userId]
        else -> null
    }

    private inline fun chat(chatId: Long, block: TdApi.Chat.() -> Unit) {
        chats[chatId]?.block()
    }

    private fun TdApi.Chat.setPosition(position: TdApi.ChatPosition) {
        val key = ChatListKey.of(position.list)
        val others = positions.filter { ChatListKey.of(it.list) != key }

        positions = when (position.order) {
            0L -> others
            else -> others + position
        }.toTypedArray()
    }

    /**
     * Download the small photo of the chat if needed, its [TdApi.UpdateFile] fills the path.
     */
    private fun trackPhoto(chat: TdApi.Chat) {
        val file = chat.photo?.small ?: return

        photoFileIdToChatIds.getOrPut(file.id, ::HashSet).add(chat.id)

        if (!file.local.isDownloadingCompleted && requestedPhotoFileIds.add(file.id)) {
            send(TdApi.DownloadFile(file.id, PHOTO_DOWNLOAD_PRIORITY, 0, 0, false)) {}
        }
    }

    private fun snapshot(key: ChatListKey) = synchronized(lock) {
        val items = chats.values.mapNotNull { chat ->
            chat.positions.firstOrNull { ChatListKey.of(it.list) == key }?.let { position ->
                val item = chat.toChatItem(position)
                val ownChat = myUserId != 0L && (chat.type as? TdApi.ChatTypePrivate)?.userId == myUserId
                if (ownChat) item.copy(isSavedMessages = true, isOnline = false, emojiStatusId = 0, isPremium = false)
                else item
            }
        }.sortedWith(compareByDescending<ChatItem> { it.order }.thenByDescending { it.id })

        ChatListSnapshot(
            items,
            loadStates[key]?.isFirstPageLoaded == true || items.isNotEmpty(),
        )
    }

    private fun TdApi.Chat.toChatItem(position: TdApi.ChatPosition) = ChatItem(
        id = id,
        title = title,
        accentColorId = accentColorId,
        photoPath = photo?.small?.local?.takeIf { it.isDownloadingCompleted }?.path,
        minithumbnail = photo?.minithumbnail?.data,
        isPinned = position.isPinned,
        order = position.order,
        lastMessage = lastMessage?.toPreview(this),
        draft = (draftMessage?.content as? TdApi.DraftMessageContentText)?.text?.text
            ?.takeIf { it.isNotBlank() },
        typing = typing(null),
        unreadCount = unreadCount,
        unreadMentionCount = unreadMentionCount,
        isMarkedAsUnread = isMarkedAsUnread,
        isMuted = if (!notificationSettings.useDefaultMuteFor) notificationSettings.muteFor > 0 else {
            val scope = when (val kind = type) {
                is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> TdApi.NotificationSettingsScopePrivateChats.CONSTRUCTOR
                is TdApi.ChatTypeSupergroup -> if (kind.isChannel) TdApi.NotificationSettingsScopeChannelChats.CONSTRUCTOR else TdApi.NotificationSettingsScopeGroupChats.CONSTRUCTOR
                else -> TdApi.NotificationSettingsScopeGroupChats.CONSTRUCTOR
            }
            (scopeMuteFor[scope] ?: 0) > 0
        },
        isForum = viewAsTopics,
        hasProtectedContent = hasProtectedContent,
        hasScheduledMessages = hasScheduledMessages,
        unreadReactionCount = unreadReactionCount,
        animationFileId = animatedPhotoFileId(),
        emojiStatusId = (privateUser()?.emojiStatus ?: emojiStatus).customEmojiId(),
        isPremium = privateUser()?.isPremium == true,
        isOnline = privateUser()?.let {
            it.type is TdApi.UserTypeRegular && it.status is TdApi.UserStatusOnline
        } == true,
        isChannel = (type as? TdApi.ChatTypeSupergroup)?.isChannel == true,
        canPostMessages = when (val kind = type) {
            is TdApi.ChatTypeSupergroup -> if (!kind.isChannel) true else when (val status = supergroups[kind.supergroupId]?.status) {
                is TdApi.ChatMemberStatusCreator -> true
                is TdApi.ChatMemberStatusAdministrator -> status.rights.canPostMessages
                else -> false
            }
            else -> true
        },
        clearsCacheOnly = (type as? TdApi.ChatTypeSupergroup)?.let { kind ->
            kind.isChannel || supergroups[kind.supergroupId]?.usernames?.activeUsernames?.isNotEmpty() == true
        } == true,
    )

    private fun TdApi.Chat.privateUser() = when (val type = type) {
        is TdApi.ChatTypePrivate -> users[type.userId]
        is TdApi.ChatTypeSecret -> users[type.userId]
        else -> null
    }

    /**
     * The small animated version of the chat's photo, asking TDLib for the full info the first
     * time it's seen; 0 meanwhile or without one. Under the lock.
     */
    private fun TdApi.Chat.animatedPhotoFileId(): Int {
        if (photo?.hasAnimation != true) {
            return 0
        }
        animatedPhotoFileIds[id]?.let { return it }
        animatedPhotoFileIds[id] = 0
        val chatId = id
        val onPhoto: (TdApi.ChatPhoto?) -> Unit = { fullPhoto ->
            val animation = fullPhoto?.smallAnimation ?: fullPhoto?.animation
            if (animation != null) {
                synchronized(lock) { animatedPhotoFileIds[chatId] = animation.file.id }
                version.update { it + 1 }
            }
        }
        when (val type = type) {
            is TdApi.ChatTypePrivate -> send(TdApi.GetUserFullInfo(type.userId)) {
                onPhoto((it as? TdApi.UserFullInfo)?.let { info -> info.personalPhoto ?: info.photo ?: info.publicPhoto })
            }

            is TdApi.ChatTypeSupergroup -> send(TdApi.GetSupergroupFullInfo(type.supergroupId)) {
                onPhoto((it as? TdApi.SupergroupFullInfo)?.photo)
            }

            is TdApi.ChatTypeBasicGroup -> send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) {
                onPhoto((it as? TdApi.BasicGroupFullInfo)?.photo)
            }

            else -> Unit
        }
        return 0
    }

    private fun TdApi.Message.toPreview(chat: TdApi.Chat): MessagePreview {
        val isGroup = chat.isGroup

        val (kind, text) = content.toPreviewContent()

        return MessagePreview(
            senderName = when {
                !isGroup || isOutgoing -> null
                else -> when (val sender = senderId) {
                    is TdApi.MessageSenderUser -> users[sender.userId]?.firstName
                    is TdApi.MessageSenderChat -> chats[sender.chatId]?.title
                    else -> null
                }
            },
            isOutgoing = isOutgoing,
            kind = kind,
            text = text,
            date = date,
        )
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val PHOTO_DOWNLOAD_PRIORITY = 1
        private const val TOPICS_LIMIT = 100

        /** TDLib's colors are RGB. */
        private const val OPAQUE = 0xFF000000.toInt()

        private val TdApi.MessageTopic?.forumTopicId
            get() = (this as? TdApi.MessageTopicForum)?.forumTopicId ?: 0

        private val TdApi.MessageSender.id
            get() = when (this) {
                is TdApi.MessageSenderUser -> userId
                is TdApi.MessageSenderChat -> chatId
                else -> 0L
            }

        private fun TdApi.ChatAction.toTypingKind() = when (this) {
            is TdApi.ChatActionTyping -> Typing.Kind.TYPING
            is TdApi.ChatActionRecordingVoiceNote -> Typing.Kind.RECORDING_VOICE_NOTE
            is TdApi.ChatActionRecordingVideo,
            is TdApi.ChatActionRecordingVideoNote -> Typing.Kind.RECORDING_VIDEO

            is TdApi.ChatActionUploadingPhoto,
            is TdApi.ChatActionUploadingVideo,
            is TdApi.ChatActionUploadingVideoNote,
            is TdApi.ChatActionUploadingVoiceNote,
            is TdApi.ChatActionUploadingDocument -> Typing.Kind.SENDING_MEDIA

            is TdApi.ChatActionChoosingSticker -> Typing.Kind.CHOOSING_STICKER
            else -> Typing.Kind.OTHER
        }
    }
}

/** The custom emoji an emoji status shows, 0 for none. */
internal fun TdApi.EmojiStatus?.customEmojiId(): Long = when (val type = this?.type) {
    is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
    is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
    else -> 0L
}

private fun TdApi.FormattedText?.orNull() = this?.text?.takeIf { it.isNotBlank() }

internal fun TdApi.MessageContent.toPreviewContent(): Pair<MessagePreview.Kind, String?> =
    when (this) {
        is TdApi.MessageText -> MessagePreview.Kind.TEXT to text.text
        is TdApi.MessageAnimatedEmoji -> MessagePreview.Kind.TEXT to emoji
        is TdApi.MessageDice -> MessagePreview.Kind.TEXT to emoji
        is TdApi.MessagePhoto -> MessagePreview.Kind.PHOTO to caption.orNull()
        is TdApi.MessagePaidMedia -> MessagePreview.Kind.OTHER to caption.orNull()
        is TdApi.MessageGame -> MessagePreview.Kind.OTHER to (game.text.orNull() ?: game.title)
        is TdApi.MessageVideo -> MessagePreview.Kind.VIDEO to caption.orNull()
        is TdApi.MessageVideoNote -> MessagePreview.Kind.VIDEO_NOTE to null
        is TdApi.MessageVoiceNote -> MessagePreview.Kind.VOICE_NOTE to caption.orNull()
        is TdApi.MessageAudio -> MessagePreview.Kind.AUDIO to
                (caption.orNull() ?: audio.title.takeIf { it.isNotBlank() })

        is TdApi.MessageDocument -> MessagePreview.Kind.DOCUMENT to
                (caption.orNull() ?: document.fileName.takeIf { it.isNotBlank() })

        is TdApi.MessageAnimation -> MessagePreview.Kind.ANIMATION to caption.orNull()
        is TdApi.MessageSticker -> MessagePreview.Kind.STICKER to sticker.emoji
        is TdApi.MessagePoll -> MessagePreview.Kind.POLL to poll.question.text
        is TdApi.MessageLocation, is TdApi.MessageVenue -> MessagePreview.Kind.LOCATION to null
        is TdApi.MessageContact -> MessagePreview.Kind.CONTACT to null
        is TdApi.MessageCall -> MessagePreview.Kind.CALL to null
        else -> MessagePreview.Kind.OTHER to null
    }

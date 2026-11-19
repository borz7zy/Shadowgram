/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ChatItem
import com.github.borz7zy.shadowgram.models.ProfileItem
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.ui.text.stringResId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * One tab of a profile: shared media of a kind, stories, gifts, members or groups in common,
 * loaded page by page. Downloads the pictures it shows and follows their [TdApi.UpdateFile].
 *
 * @param context Formats sizes, dates and durations
 * @param chatId The chat of the profile, for users their private chat (its id is the user id)
 * @param giftCollectionId The selected collection on the gifts tab, 0 for all gifts
 */
class ProfilePage(
    private val client: TelegramClient,
    private val context: Context,
    private val chatId: Long,
    val kind: Kind,
    private val giftCollectionId: Int = 0,
) {
    /**
     * @param isGrid Shown as a grid of pictures rather than rows
     */
    enum class Kind(val isGrid: Boolean, val filter: TdApi.SearchMessagesFilter?) {
        MEMBERS(false, null),
        POSTS(true, null),
        SAVED(false, null),
        GIFTS(true, null),
        MEDIA(true, TdApi.SearchMessagesFilterPhotoAndVideo()),
        FILES(false, TdApi.SearchMessagesFilterDocument()),
        LINKS(false, TdApi.SearchMessagesFilterUrl()),
        MUSIC(false, TdApi.SearchMessagesFilterAudio()),
        VOICE_NOTES(false, TdApi.SearchMessagesFilterVoiceNote()),
        VIDEO_NOTES(true, TdApi.SearchMessagesFilterVideoNote()),
        GIFS(true, TdApi.SearchMessagesFilterAnimation()),
        GROUPS(false, null),
        POLLS(false, TdApi.SearchMessagesFilterPoll()),
    }

    /**
     * @param isLoaded Whether TDLib answered at least once, so an empty page really is empty
     */
    data class Snapshot(
        val items: List<ProfileItem>,
        val isLoaded: Boolean,
        val giftLoadFailed: Boolean,
    )

    private val lock = Any()

    // Guarded by lock
    /** TDLib objects in display order, by [ProfileItem.key]. */
    private val entries = LinkedHashMap<String, TdApi.Object>()
    private var isLoading = false
    private var isComplete = false
    private var isLoaded = false
    private var giftLoadFailed = false
    private var nextMessageId = 0L
    private var nextStoryId = 0
    private var nextOffset = ""
    private val loadedGiftOffsets = HashSet<String>()
    private var nextMemberOffset = 0
    private var nextGroupId = 0L

    /** Latest state of the files this page asked TDLib to download, by file id. */
    private val files = HashMap<Int, TdApi.File>()

    private val version = MutableStateFlow(0L)

    private val onUpdate: (TdApi.Object) -> Unit = { update ->
        if (update is TdApi.UpdateFile && synchronized(lock) {
                files.containsKey(update.file.id).also { if (it) files[update.file.id] = update.file }
            }
        ) {
            version.update { it + 1 }
        }
    }

    /**
     * The entries, starts listening and loading when collected.
     */
    fun snapshots(): Flow<Snapshot> = callbackFlow {
        client.addUpdateListener(onUpdate)

        launch { loadMore(retry = true) }
        launch {
            version.map { snapshot() }.collect { send(it) }
        }

        awaitClose { client.removeUpdateListener(onUpdate) }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * Ask TDLib for the next page. Does nothing while a request is running or once all is loaded.
     */
    suspend fun loadMore(retry: Boolean = false) {
        synchronized(lock) {
            if (isLoading || isComplete || (giftLoadFailed && !retry)) {
                return
            }
            isLoading = true
            giftLoadFailed = false
        }
        version.update { it + 1 }

        try {
            val result = fetch()
            synchronized(lock) {
                if (result == null) {
                    giftLoadFailed = true
                } else {
                    val (page, isLast) = result
                    val added = page.count { (key, entry) -> entries.put(key, entry) == null }
                    isComplete = isLast || (kind != Kind.GIFTS && added == 0)
                    isLoaded = true
                }
            }
        } finally {
            synchronized(lock) { isLoading = false }
            version.update { it + 1 }
        }
    }

    /**
     * The next page as keyed entries, and whether it's the last one.
     */
    private suspend fun fetch(): Pair<List<Pair<String, TdApi.Object>>, Boolean>? {
        val chat = (client.send(TdApi.GetChat(chatId)) as? Result.Success)?.data
            ?: return if (kind == Kind.GIFTS) null else emptyList<Pair<String, TdApi.Object>>() to true

        return when (kind) {
            Kind.POSTS -> client.send(
                TdApi.GetChatPostedToChatPageStories(chatId, nextStoryId, PAGE_SIZE)
            ).page { stories ->
                // Starts from the given story, the last one comes again
                nextStoryId = stories.stories.lastOrNull()?.id ?: 0
                stories.stories.map { "${it.id}" to it } to (nextStoryId == 0)
            }

            Kind.SAVED -> client.send(
                // ponytail: a Saved Messages topic of a chat has the chat id as its id in TDLib,
                // look it up in updateSavedMessagesTopic if that ever changes
                TdApi.GetSavedMessagesTopicHistory(chatId, nextMessageId, 0, PAGE_SIZE)
            ).page { messages ->
                nextMessageId = messages.messages.lastOrNull()?.id ?: 0L
                messages.messages.map { "${it.id}" to it } to (nextMessageId == 0L)
            }

            Kind.GIFTS -> {
                val owner = chat.giftOwner() ?: return emptyList<Pair<String, TdApi.Object>>() to true
                fetchGifts(owner)
            }

            Kind.GROUPS -> {
                val userId = (chat.type as? TdApi.ChatTypePrivate)?.userId
                    ?: return listOf<Pair<String, TdApi.Object>>() to true
                client.send(TdApi.GetGroupsInCommon(userId, nextGroupId, PAGE_SIZE)).page { chats ->
                    nextGroupId = chats.chatIds.lastOrNull() ?: 0L
                    chats.chatIds.map { "$it" to TdApi.MessageSenderChat(it) } to (nextGroupId == 0L)
                }
            }

            Kind.MEMBERS -> when (val type = chat.type) {
                is TdApi.ChatTypeBasicGroup -> client.send(
                    TdApi.GetBasicGroupFullInfo(type.basicGroupId)
                ).page { info -> info.members.map { it.key() to it } to true }

                is TdApi.ChatTypeSupergroup -> client.send(
                    TdApi.GetSupergroupMembers(
                        type.supergroupId, TdApi.SupergroupMembersFilterRecent(),
                        nextMemberOffset, PAGE_SIZE,
                    )
                ).page { members ->
                    nextMemberOffset += members.members.size
                    members.members.map { it.key() to it } to
                            (nextMemberOffset >= members.totalCount)
                }

                else -> listOf<Pair<String, TdApi.Object>>() to true
            }

            else -> client.send(
                TdApi.SearchChatMessages(
                    chatId, null, "", null, nextMessageId, 0, PAGE_SIZE, kind.filter
                )
            ).page { found ->
                nextMessageId = found.nextFromMessageId
                found.messages.map { "${it.id}" to it } to (nextMessageId == 0L)
            }
        }
    }

    private suspend fun fetchGifts(owner: TdApi.MessageSender): Pair<List<Pair<String, TdApi.Object>>, Boolean>? {
        while (true) {
            val offset = nextOffset
            val response = client.send(TdApi.GetReceivedGifts().apply {
                businessConnectionId = ""
                ownerId = owner
                collectionId = giftCollectionId
                this.offset = offset
                limit = PAGE_SIZE
            })
            val gifts = (response as? Result.Success)?.data ?: return null
            val page = gifts.gifts.mapIndexed { index, gift ->
                val upgraded = gift.gift as? TdApi.SentGiftUpgraded
                // Another user's receivedGiftId can be empty, including for identical regular gifts.
                val key = gift.receivedGiftId.takeIf { it.isNotEmpty() }?.let { "received:$it" }
                    ?: upgraded?.gift?.id?.let { "upgraded:$it" }
                    ?: "gift:$offset:$index"
                key to gift
            }
            synchronized(lock) {
                loadedGiftOffsets.add(offset)
                nextOffset = gifts.nextOffset
                val isLast = nextOffset.isEmpty() || nextOffset in loadedGiftOffsets
                // Empty or overlapping pages may still have a cursor to more gifts.
                if (isLast || page.any { (key) -> key !in entries }) return page to isLast
            }
        }
    }

    private inline fun <T : TdApi.Object> Result<T, TdApi.Error>.page(
        block: (T) -> Pair<List<Pair<String, TdApi.Object>>, Boolean>,
    ) = when (this) {
        is Result.Success -> synchronized(lock) { block(data) }
        is Result.Failure -> listOf<Pair<String, TdApi.Object>>() to true
    }

    private fun TdApi.ChatMember.key() = when (val id = memberId) {
        is TdApi.MessageSenderUser -> "${id.userId}"
        is TdApi.MessageSenderChat -> "${id.chatId}"
        else -> "$id"
    }

    private fun snapshot(): Snapshot {
        val entries: List<Pair<String, TdApi.Object>>
        val isLoaded: Boolean
        val giftLoadFailed: Boolean
        synchronized(lock) {
            entries = this.entries.map { it.key to it.value }
            isLoaded = this.isLoaded
            giftLoadFailed = this.giftLoadFailed
        }

        // Senders take the chats store lock, never hold ours meanwhile
        return Snapshot(entries.mapNotNull { (key, entry) -> entry.toItem(key) }, isLoaded, giftLoadFailed)
    }

    private fun TdApi.Object.toItem(key: String): ProfileItem? = when (this) {
        is TdApi.Message -> toItem(key)
        is TdApi.Story -> when (val content = content) {
            is TdApi.StoryContentPhoto -> photoItem(key, content.photo)
            is TdApi.StoryContentVideo -> content.video.let {
                still(key, it.thumbnail, it.minithumbnail)
                    .copy(badge = DateUtils.formatElapsedTime(it.duration.toLong()))
            }

            else -> ProfileItem(key)
        }

        is TdApi.ReceivedGift -> when (val gift = gift) {
            is TdApi.SentGiftRegular -> stickerItem(key, gift.gift.sticker)
            is TdApi.SentGiftUpgraded -> stickerItem(key, gift.gift.model.sticker)
            else -> null
        }

        is TdApi.MessageSenderChat -> senderItem(key, this, null)
        is TdApi.ChatMember -> senderItem(
            key,
            memberId,
            tag.takeIf { it.isNotEmpty() } ?: when (status) {
                is TdApi.ChatMemberStatusCreator -> context.getString(R.string.profile_owner)
                is TdApi.ChatMemberStatusAdministrator -> context.getString(R.string.profile_admin)
                else -> null
            },
        )

        else -> null
    }

    private fun TdApi.Message.toItem(key: String): ProfileItem {
        val date = DateUtils.formatDateTime(
            context, date * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        )
        val content = content

        if (kind == Kind.SAVED) {
            val (previewKind, text) = content.toPreviewContent()
            return thumbnailOf(key, content).copy(
                title = text ?: context.getString(previewKind.stringResId),
                subtitle = date,
            )
        }

        return when (content) {
            is TdApi.MessageDocument -> content.document.let {
                val document = Downloads.documentOf(it, documentFileOf(it.document), canBeSaved)
                still(key, it.thumbnail, it.minithumbnail).copy(
                    title = it.fileName,
                    subtitle = context.getString(
                        R.string.chat_separator,
                        when {
                            document.isDownloading -> context.getString(
                                R.string.playback_time,
                                Formatter.formatShortFileSize(context, document.downloadedSize),
                                Formatter.formatShortFileSize(context, document.size),
                            )

                            else -> Formatter.formatShortFileSize(context, document.size)
                        },
                        date,
                    ),
                    document = document,
                )
            }

            is TdApi.MessageAudio -> content.audio.let {
                still(key, it.albumCoverThumbnail, it.albumCoverMinithumbnail).copy(
                    title = it.title.takeIf { title -> title.isNotBlank() } ?: it.fileName,
                    subtitle = listOf(it.performer, DateUtils.formatElapsedTime(it.duration.toLong()))
                        .filter { part -> part.isNotBlank() }
                        .joinToString(" • "),
                    track = MessagePlayer.trackOf(client, this),
                )
            }

            is TdApi.MessageVoiceNote -> ProfileItem(
                key,
                title = client.chats.sender(senderId).name,
                track = MessagePlayer.trackOf(client, this),
                subtitle = context.getString(
                    R.string.chat_separator,
                    date,
                    DateUtils.formatElapsedTime(content.voiceNote.duration.toLong()),
                ),
            )

            is TdApi.MessagePoll -> ProfileItem(
                key,
                title = content.poll.question.text,
                subtitle = context.resources.getQuantityString(
                    R.plurals.profile_votes, content.poll.totalVoterCount, content.poll.totalVoterCount
                ),
            )

            is TdApi.MessageText -> {
                val url = content.linkPreview?.url ?: content.text.firstUrl()
                ProfileItem(
                    key,
                    title = content.linkPreview?.title?.takeIf { it.isNotBlank() }
                        ?: content.linkPreview?.siteName?.takeIf { it.isNotBlank() }
                        ?: url ?: content.text.text,
                    subtitle = url,
                )
            }

            else -> {
                // Links can be in captions too
                val (_, text) = content.toPreviewContent()
                thumbnailOf(key, content).let {
                    when (kind) {
                        Kind.LINKS -> it.copy(title = text, subtitle = date)
                        else -> it
                    }
                }
            }
        }
    }

    /** The picture of a media message, with the duration of a video. */
    private fun TdApi.Message.thumbnailOf(key: String, content: TdApi.MessageContent) = when (content) {
        is TdApi.MessagePhoto -> photoItem(key, content.photo)
        is TdApi.MessageVideo -> still(key, content.video.thumbnail, content.video.minithumbnail)
            .copy(badge = DateUtils.formatElapsedTime(content.video.duration.toLong()))

        is TdApi.MessageAnimation ->
            still(key, content.animation.thumbnail, content.animation.minithumbnail)

        is TdApi.MessageVideoNote ->
            still(key, content.videoNote.thumbnail, content.videoNote.minithumbnail).copy(
                badge = DateUtils.formatElapsedTime(content.videoNote.duration.toLong()),
                track = MessagePlayer.trackOf(client, this),
            )

        is TdApi.MessageDocument ->
            still(key, content.document.thumbnail, content.document.minithumbnail)

        else -> ProfileItem(key)
    }

    private fun photoItem(key: String, photo: TdApi.Photo): ProfileItem {
        // Big enough for a cell, no bigger
        val size = photo.sizes.filter { it.width >= CELL_PHOTO_SIZE }.minByOrNull { it.width }
            ?: photo.sizes.maxByOrNull { it.width }
        return ProfileItem(
            key,
            imagePath = pathOf(size?.photo),
            minithumbnail = photo.minithumbnail?.data,
        )
    }

    private fun still(key: String, thumbnail: TdApi.Thumbnail?, minithumbnail: TdApi.Minithumbnail?) =
        ProfileItem(
            key,
            imagePath = thumbnail?.takeIf {
                it.format is TdApi.ThumbnailFormatJpeg || it.format is TdApi.ThumbnailFormatWebp
                        || it.format is TdApi.ThumbnailFormatPng
            }?.let { pathOf(it.file) },
            minithumbnail = minithumbnail?.data,
        )

    private fun stickerItem(key: String, sticker: TdApi.Sticker) = when (sticker.format) {
        is TdApi.StickerFormatWebp -> ProfileItem(key, imagePath = pathOf(sticker.sticker))
        else -> ProfileItem(key, stickerPath = pathOf(sticker.sticker))
    }

    private fun senderItem(key: String, sender: TdApi.MessageSender, subtitle: String?): ProfileItem {
        val (name, accentColorId, photo) = client.chats.sender(sender)
        val id = when (sender) {
            is TdApi.MessageSenderUser -> sender.userId
            is TdApi.MessageSenderChat -> sender.chatId
            else -> 0L
        }
        val title = name.orEmpty()
        return ProfileItem(
            key,
            title = title,
            subtitle = subtitle,
            avatar = ChatItem(
                id, title, accentColorId, pathOf(photo), null,
                false, 0L, null, null, null, 0, 0, false, false,
            ),
            chatId = id,
        )
    }

    private fun TdApi.FormattedText.firstUrl() = entities.firstNotNullOfOrNull {
        when (val type = it.type) {
            is TdApi.TextEntityTypeTextUrl -> type.url
            is TdApi.TextEntityTypeUrl -> text.substring(it.offset, it.offset + it.length)
            else -> null
        }
    }

    /** The latest state of a document's [file], without downloading it. */
    private fun documentFileOf(file: TdApi.File) = synchronized(lock) {
        files.getOrPut(file.id) { file }
    }

    /**
     * The local path of [file] once downloaded, asking TDLib for it the first time it's seen.
     */
    private fun pathOf(file: TdApi.File?): String? {
        file ?: return null
        val latest = synchronized(lock) {
            files[file.id] ?: run {
                files[file.id] = file
                null
            }
        }
        val current = latest ?: file

        if (current.local.isDownloadingCompleted) {
            return current.local.path
        }
        if (latest == null && current.local.canBeDownloaded) {
            val waitForGift = kind == Kind.GIFTS
            client.send(TdApi.DownloadFile(current.id, DOWNLOAD_PRIORITY, 0, 0, waitForGift)) { result ->
                if (waitForGift && result is TdApi.File && result.local.isDownloadingCompleted) {
                    // The collection may be inactive by the time the sticker finishes downloading.
                    synchronized(lock) { files[result.id] = result }
                    version.update { it + 1 }
                }
            }
        }
        return null
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val CELL_PHOTO_SIZE = 320
        private const val DOWNLOAD_PRIORITY = 8
    }
}

internal fun TdApi.Chat.giftOwner(): TdApi.MessageSender? = when (val type = type) {
    is TdApi.ChatTypePrivate -> TdApi.MessageSenderUser(type.userId)
    is TdApi.ChatTypeSecret -> TdApi.MessageSenderUser(type.userId)
    is TdApi.ChatTypeSupergroup -> if (type.isChannel) TdApi.MessageSenderChat(id) else null
    else -> null
}

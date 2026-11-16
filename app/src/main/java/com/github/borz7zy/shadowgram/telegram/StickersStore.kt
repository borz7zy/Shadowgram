/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

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
 * Stickers and GIFs of an account for the sticker keyboard: the installed sets (archived ones
 * left out), recent and favorite stickers, saved GIFs. Loaded the first time they are asked for,
 * kept up to date from TDLib updates.
 *
 * Same scheme as [ChatsStore]: updates are applied on the TDLib thread under [lock], every change
 * bumps [version] and collectors build snapshots on [Dispatchers.Default]. Files are downloaded
 * when the keyboard shows them ([download]), their paths come with the next snapshot.
 *
 * @param send Sends a request to TDLib, the handler is called on the TDLib thread
 */
class StickersStore(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit) -> Unit,
) {
    enum class Format {
        /** Decoded by RustCore through Coil. */
        WEBP,

        /** WebM or TGS (Lottie), played by StickerView. */
        ANIMATED,

        /** Anything else there is no decoder for yet. */
        UNSUPPORTED,
    }

    /**
     * @param path The downloaded sticker, null until then
     */
    data class Sticker(
        val fileId: Int,
        val setId: Long,
        val emoji: String,
        val format: Format,
        val width: Int,
        val height: Int,
        val path: String?,
        /** Set for custom emoji, what a message's text entity points at. */
        val customEmojiId: Long = 0L,
    )

    /**
     * @param stickers Empty until the set is loaded
     */
    data class Pack(
        val id: Long,
        val title: String,
        val stickers: List<Sticker>,
    )

    /**
     * A GIF, saved or found through @gif ([queryId] and [resultId] are then set, it's sent as
     * that inline result).
     *
     * @param path The downloaded MP4, null until then
     * @param thumbnailPath The downloaded still, null until then or when there is none
     */
    data class Gif(
        val fileId: Int,
        val thumbnailFileId: Int?,
        val width: Int,
        val height: Int,
        val duration: Int,
        val path: String?,
        val thumbnailPath: String?,
        val minithumbnail: ByteArray?,
        val queryId: Long = 0,
        val resultId: String? = null,
    ) {
        // Identity is enough for diffing: the byte array never changes for a file
        override fun equals(other: Any?) = other is Gif && fileId == other.fileId
                && path == other.path && thumbnailPath == other.thumbnailPath
                && resultId == other.resultId

        override fun hashCode() = fileId
    }

    /**
     * @param paths Every downloaded file asked for, by file id, for lists built elsewhere
     *   (GIF search results)
     */
    data class Snapshot(
        val packs: List<Pack>,
        val recent: List<Sticker>,
        val favorite: List<Sticker>,
        val gifs: List<Gif>,
        val paths: Map<Int, String>,
        /** The account's custom emoji sets. */
        val emojiPacks: List<Pack> = listOf(),
        /** Telegram Premium: custom emoji can be sent and reacted with. */
        val isPremium: Boolean = false,
    )

    private val lock = Any()

    // Guarded by lock
    private var isStarted = false
    private var setIds = longArrayOf()
    private var emojiSetIds = longArrayOf()
    private var isPremium = false
    private val sets = HashMap<Long, TdApi.StickerSet>()
    private var recent = arrayOf<TdApi.Sticker>()
    private var favorite = arrayOf<TdApi.Sticker>()
    private var animations = arrayOf<TdApi.Animation>()
    private val requested = HashSet<Int>()
    private val paths = HashMap<Int, String>()

    private val version = MutableStateFlow(0L)

    /** The stickers, loading starts with the first collector. */
    fun snapshots(): Flow<Snapshot> = version
        .onStart { start() }
        .map { snapshot() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * Ask TDLib for a file the keyboard shows, once. Its path comes with a later snapshot.
     */
    fun download(fileId: Int) {
        synchronized(lock) {
            if (!requested.add(fileId)) {
                return
            }
        }
        send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, false)) {
            if (it is TdApi.File) {
                onFile(it)
            }
        }
    }

    /**
     * Search GIFs through the @gif inline bot.
     *
     * @return The GIFs found, empty when the bot isn't there or nothing was found
     */
    suspend fun searchGifs(chatId: Long, query: String): List<Gif> {
        val bot = request(TdApi.SearchPublicChat(GIF_BOT)) as? TdApi.Chat ?: return listOf()
        val botUserId = (bot.type as? TdApi.ChatTypePrivate)?.userId ?: return listOf()
        val results = request(
            TdApi.GetInlineQueryResults(botUserId, chatId, null, query, "")
        ) as? TdApi.InlineQueryResults ?: return listOf()

        return results.results.mapNotNull { result ->
            (result as? TdApi.InlineQueryResultAnimation)?.let {
                it.animation.toGif(results.inlineQueryId, it.id)
            }
        }
    }

    private suspend fun request(function: TdApi.Function<*>) =
        suspendCancellableCoroutine { continuation ->
            send(function) { continuation.resume(it) }
        }

    // Called on the TDLib thread
    fun onUpdate(update: TdApi.Object) {
        // Comes once at start, before anything asks for stickers
        if (update is TdApi.UpdateOption && update.name == "is_premium") {
            synchronized(lock) { isPremium = (update.value as? TdApi.OptionValueBoolean)?.value == true }
            version.update { it + 1 }
        }

        if (!synchronized(lock) { isStarted }) {
            return
        }

        when (update) {
            is TdApi.UpdateInstalledStickerSets -> when (update.stickerType) {
                is TdApi.StickerTypeRegular -> onSetIds(update.stickerSetIds)
                is TdApi.StickerTypeCustomEmoji -> onSetIds(update.stickerSetIds, isEmoji = true)
                else -> Unit
            }

            is TdApi.UpdateRecentStickers -> if (!update.isAttached) loadRecent()
            is TdApi.UpdateFavoriteStickers -> loadFavorite()
            is TdApi.UpdateSavedAnimations -> loadAnimations()
            is TdApi.UpdateFile -> onFile(update.file)
        }
    }

    private fun start() {
        synchronized(lock) {
            if (isStarted) {
                return
            }
            isStarted = true
        }

        send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())) { result ->
            (result as? TdApi.StickerSets)?.let { sets -> onSetIds(sets.sets.map { it.id }.toLongArray()) }
        }
        send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeCustomEmoji())) { result ->
            (result as? TdApi.StickerSets)?.let { sets ->
                onSetIds(sets.sets.map { it.id }.toLongArray(), isEmoji = true)
            }
        }
        loadRecent()
        loadFavorite()
        loadAnimations()
    }

    private fun onSetIds(ids: LongArray, isEmoji: Boolean = false) {
        val missing = synchronized(lock) {
            when (isEmoji) {
                true -> emojiSetIds = ids
                false -> setIds = ids
            }
            ids.filter { it !in sets }
        }
        version.update { it + 1 }

        missing.forEach { id ->
            send(TdApi.GetStickerSet(id)) { result ->
                if (result is TdApi.StickerSet) {
                    synchronized(lock) { sets[id] = result }
                    version.update { it + 1 }
                }
            }
        }
    }

    private fun loadRecent() = send(TdApi.GetRecentStickers(false)) { result ->
        if (result is TdApi.Stickers) {
            synchronized(lock) { recent = result.stickers }
            version.update { it + 1 }
        }
    }

    private fun loadFavorite() = send(TdApi.GetFavoriteStickers()) { result ->
        if (result is TdApi.Stickers) {
            synchronized(lock) { favorite = result.stickers }
            version.update { it + 1 }
        }
    }

    private fun loadAnimations() = send(TdApi.GetSavedAnimations()) { result ->
        if (result is TdApi.Animations) {
            synchronized(lock) { animations = result.animations }
            version.update { it + 1 }
        }
    }

    private fun onFile(file: TdApi.File) {
        val changed = synchronized(lock) {
            file.id in requested && file.local.isDownloadingCompleted
                    && paths.put(file.id, file.local.path) != file.local.path
        }
        if (changed) {
            version.update { it + 1 }
        }
    }

    private fun snapshot(): Snapshot {
        val loaded: Map<Long, TdApi.StickerSet>
        val recent: Array<TdApi.Sticker>
        val favorite: Array<TdApi.Sticker>
        val animations: Array<TdApi.Animation>
        val paths: Map<Int, String>
        val titles: List<Pair<Long, String?>>
        val emojiTitles: List<Pair<Long, String?>>
        val isPremium: Boolean
        synchronized(lock) {
            titles = setIds.map { it to sets[it]?.title }
            emojiTitles = emojiSetIds.map { it to sets[it]?.title }
            isPremium = this.isPremium
            loaded = HashMap(sets)
            recent = this.recent
            favorite = this.favorite
            animations = this.animations
            paths = HashMap(this.paths)
        }

        fun packs(titles: List<Pair<Long, String?>>) = titles.map { (id, title) ->
            Pack(
                id = id,
                title = title.orEmpty(),
                stickers = loaded[id]?.stickers.orEmpty().map { it.toSticker(paths) },
            )
        }

        return Snapshot(
            packs = packs(titles),
            emojiPacks = packs(emojiTitles),
            isPremium = isPremium,
            recent = recent.map { it.toSticker(paths) },
            favorite = favorite.map { it.toSticker(paths) },
            gifs = animations.map { it.toGif(0, null, paths) },
            paths = paths,
        )
    }

    private fun TdApi.File.pathIn(paths: Map<Int, String>) =
        paths[id] ?: local.path.takeIf { local.isDownloadingCompleted }

    private fun TdApi.Sticker.toSticker(paths: Map<Int, String>) = Sticker(
        fileId = sticker.id,
        setId = setId,
        emoji = emoji,
        format = when (format) {
            is TdApi.StickerFormatWebp -> Format.WEBP
            is TdApi.StickerFormatWebm, is TdApi.StickerFormatTgs -> Format.ANIMATED
            else -> Format.UNSUPPORTED
        },
        width = width,
        height = height,
        path = sticker.pathIn(paths),
        customEmojiId = (fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId ?: 0L,
    )

    private fun TdApi.Animation.toGif(
        queryId: Long,
        resultId: String?,
        paths: Map<Int, String> = mapOf(),
    ): Gif {
        val still = thumbnail?.takeIf { it.format is TdApi.ThumbnailFormatJpeg }?.file
        return Gif(
            fileId = animation.id,
            thumbnailFileId = still?.id,
            width = width,
            height = height,
            duration = duration,
            path = animation.pathIn(paths),
            thumbnailPath = still?.pathIn(paths),
            minithumbnail = minithumbnail?.data,
            queryId = queryId,
            resultId = resultId,
        )
    }

    private companion object {
        const val GIF_BOT = "gif"
        const val DOWNLOAD_PRIORITY = 12
    }
}

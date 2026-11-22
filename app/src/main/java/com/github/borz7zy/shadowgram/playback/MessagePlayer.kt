/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.playback

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.haptics.HapticAudioSource
import com.github.borz7zy.shadowgram.haptics.HapticPlayback
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.github.borz7zy.shadowgram.services.PlaybackService
import com.github.borz7zy.shadowgram.services.TwelveRenderersFactory
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import com.github.borz7zy.shadowgram.ui.views.FloatingVideoNoteView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plays voice and video messages and music for the whole app, like the official clients: the
 * chat bubbles, the bar in the top bars, the floating video message and Twelve's now playing
 * screen (through [PlaybackService]'s media session, which wraps [player]) all show this player.
 *
 * Playing a message makes the playlist of its chat: all its music, or all its voice and video
 * messages, oldest first, loaded page by page around the played one while it plays. Files stream
 * while they download (see [TdFileDataSource]).
 *
 * Audio goes through Twelve's renderers and audio sink, video to the surface of whichever view
 * shows the video message now: its bubble while it's on screen, else the floating circle.
 *
 * Main thread only.
 */
@OptIn(UnstableApi::class)
object MessagePlayer {
    /**
     * @param title Who sent it
     * @param subtitle The chat it's in
     * @param duration Seconds, as Telegram says, until the player knows better
     * @param isMusic An audio file: [title] is the song's, [subtitle] its performer
     * @param artworkPath The downloaded album cover of music, if any
     */
    data class Track(
        val chatId: Long,
        val messageId: Long,
        val fileId: Int,
        val isVideo: Boolean,
        val title: String,
        val subtitle: String,
        val duration: Int,
        val isMusic: Boolean = false,
        val artworkPath: String? = null,
    ) {
        fun isOf(chatId: Long, messageId: Long) =
            this.chatId == chatId && this.messageId == messageId
    }

    /**
     * @param isPlaying Playing or about to, false while paused
     * @param isLoading The file is still downloading
     * @param speed The picked speed
     * @param isFast Held faster for now, see [holdFast]
     * @param hasInlineSurface The video shows in its bubble, the floating circle hides
     */
    data class State(
        val track: Track? = null,
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val speed: Float = 1f,
        val isFast: Boolean = false,
        val hasInlineSurface: Boolean = false,
    )

    fun interface Listener {
        fun onStateChanged(state: State)
    }

    /** Bubbles win over the floating circle. */
    const val PRIORITY_FLOATING = 0
    const val PRIORITY_INLINE = 1

    private const val LOG_TAG = "MessagePlayer"
    private const val PAGE_SIZE = 100
    private const val FAST_SPEED = 2f
    private val SPEEDS = floatArrayOf(1f, 1.5f, 2f)

    private lateinit var application: ShadowgramApplication
    private val scope = MainScope()
    private var playlistJob: Job? = null

    /** Keeps [PlaybackService] bound, so it can go foreground and play in the background. */
    private var sessionController: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    var state = State()
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Surfaces offered for the video, by owner, with their priority. */
    private val surfaces = LinkedHashMap<Any, Pair<Surface, Int>>()
    private var shownSurface: Surface? = null

    /** Paused while held, resumed on release. */
    private var isHeldPaused = false

    /** Also the player of [PlaybackService]'s media session. */
    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(
            application,
            TwelveRenderersFactory(application, false, {}, {}),
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdFileDataSource.Factory(application)))
            .setAudioAttributes(audioAttributes(false), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(playerListener)
                HapticPlayback(application.audioHaptics, this) { item ->
                    val track = item.track ?: return@HapticPlayback null
                    val local = client().send(TdApi.GetFile(track.fileId)).getOrNull()?.local
                        ?.takeIf { it.isDownloadingCompleted && it.path.isNotBlank() }
                        ?: return@HapticPlayback null
                    HapticAudioSource.media(application, Uri.fromFile(File(local.path)))
                }
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isHeldPaused) {
                update(state.copy(isPlaying = playWhenReady))
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            update(state.copy(isLoading = playbackState == Player.STATE_BUFFERING))
            if (playbackState == Player.STATE_ENDED) {
                stop()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val track = mediaItem?.track ?: return
            if (track.isMusic != state.track?.isMusic) {
                player.setAudioAttributes(audioAttributes(track.isMusic), true)
            }
            update(state.copy(track = track))
            scope.launch {
                // Marks it as listened to, like the official apps do when they play it
                client().send(TdApi.OpenMessageContent(track.chatId, track.messageId))
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Set from the now playing screen too
            if (!state.isFast) {
                update(state.copy(speed = playbackParameters.speed))
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(LOG_TAG, "Can't play ${state.track}", error)
            when (player.hasNextMediaItem()) {
                true -> {
                    player.seekToNextMediaItem()
                    player.prepare()
                }

                false -> stop()
            }
        }
    }

    private val MediaItem.track get() = localConfiguration?.tag as? Track

    private fun audioAttributes(isMusic: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (isMusic) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    private suspend fun client() = application.accountsRepository.activeClient()

    /**
     * Put the floating circle in every activity.
     */
    fun init(application: ShadowgramApplication) {
        this.application = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.findViewById<ViewGroup>(android.R.id.content)
                    ?.addView(FloatingVideoNoteView(activity))
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Where playback is, ms. */
    val position get() = if (state.track == null) 0L else player.currentPosition

    /** How long the track is, ms. */
    val duration: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: ((state.track?.duration ?: 0) * 1000L)

    /**
     * Play [track], or pause and resume it if it's the current one. A track of the playlist plays
     * from there, any other one starts the playlist of its chat.
     */
    fun play(track: Track) {
        val current = state.track
        if (current != null && current.isOf(track.chatId, track.messageId)) {
            toggle()
            return
        }

        isHeldPaused = false
        if (state.isFast) {
            player.setPlaybackSpeed(state.speed)
        }
        update(state.copy(isPlaying = true, isFast = false))
        connectSession()

        val index = (0 until player.mediaItemCount).firstOrNull {
            player.getMediaItemAt(it).track?.isOf(track.chatId, track.messageId) == true
        }
        if (index != null) {
            player.seekTo(index, 0)
            player.playWhenReady = true
            player.prepare()
            return
        }

        playlistJob?.cancel()
        player.setMediaItems(listOf(track.toMediaItem()))
        player.setPlaybackSpeed(state.speed)
        player.playWhenReady = true
        player.prepare()
        playlistJob = scope.launch { loadPlaylist(track) }
    }

    private fun connectSession() {
        if (sessionController == null) {
            sessionController = MediaController.Builder(
                application,
                SessionToken(application, ComponentName(application, PlaybackService::class.java)),
            ).buildAsync()
        }
    }

    /**
     * The rest of the chat's music, or voice and video messages, around [track]: older ones go
     * before what's there, newer ones after it.
     */
    private suspend fun loadPlaylist(track: Track) {
        val client = client()
        // ponytail: the whole chat, not the forum topic the message is in
        val filter = when (track.isMusic) {
            true -> TdApi.SearchMessagesFilterAudio()
            false -> TdApi.SearchMessagesFilterVoiceAndVideoNote()
        }

        for (older in listOf(true, false)) {
            var from = track.messageId
            while (true) {
                val (next, items) = withContext(Dispatchers.Default) {
                    val offset = if (older) 0 else -PAGE_SIZE + 1
                    val page = search(client, track.chatId, from, offset, filter)
                        ?.filter { if (older) it.id < from else it.id > from }
                        ?.sortedBy { it.id }
                        .orEmpty()
                    val edge = (if (older) page.firstOrNull() else page.lastOrNull())?.id
                        ?: return@withContext null
                    edge to page.mapNotNull { trackOf(client, it)?.toMediaItem() }
                } ?: break
                if (older) player.addMediaItems(0, items) else player.addMediaItems(items)
                from = next
            }
        }
    }

    private suspend fun search(
        client: TelegramClient,
        chatId: Long,
        fromMessageId: Long,
        offset: Int,
        filter: TdApi.SearchMessagesFilter,
    ) = (client.send(
        TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, offset, PAGE_SIZE, filter)
    ) as? Result.Success)?.data?.messages

    /** What to play for a voice, video or music message, named like the chat's bubbles do. */
    fun trackOf(client: TelegramClient, message: TdApi.Message): Track? {
        val sender = when {
            message.isOutgoing -> application.getString(R.string.playback_you)
            else -> client.chats.sender(message.senderId).name.orEmpty()
        }
        val chat = client.chats.sender(TdApi.MessageSenderChat(message.chatId)).name.orEmpty()
        return when (val content = message.content) {
            is TdApi.MessageVoiceNote -> Track(
                message.chatId, message.id, content.voiceNote.voice.id, false, sender, chat,
                content.voiceNote.duration,
            )

            is TdApi.MessageVideoNote -> Track(
                message.chatId, message.id, content.videoNote.video.id, true, sender, chat,
                content.videoNote.duration,
            )

            is TdApi.MessageAudio -> content.audio.let {
                Track(
                    message.chatId, message.id, it.audio.id, false,
                    it.title.ifBlank { it.fileName }, it.performer, it.duration,
                    isMusic = true,
                    artworkPath = it.albumCoverThumbnail?.file?.local
                        ?.takeIf { local -> local.isDownloadingCompleted }?.path,
                )
            }

            else -> null
        }
    }

    private fun Track.toMediaItem() = MediaItem.Builder()
        .setMediaId("$chatId/$messageId")
        .setUri(TdFileDataSource.uriOf(fileId))
        .setTag(this)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(artworkPath?.let { Uri.fromFile(File(it)) })
                .setDurationMs(duration * 1000L)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    fun toggle() {
        if (state.track == null) {
            return
        }
        isHeldPaused = false
        when (state.isPlaying) {
            true -> pause()
            false -> {
                player.playWhenReady = true
                update(state.copy(isPlaying = true))
            }
        }
    }

    fun pause() {
        player.playWhenReady = false
        update(state.copy(isPlaying = false))
    }

    fun stop() {
        playlistJob?.cancel()
        player.stop()
        player.clearMediaItems()
        isHeldPaused = false
        update(state.copy(track = null, isPlaying = false, isLoading = false, isFast = false))
    }

    /** Seek to [fraction] of the track, 0 to 1. */
    fun seekTo(fraction: Float) {
        if (state.track == null) {
            return
        }
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    /** Go to the next speed: 1x, 1.5x, 2x, then 1x again. */
    fun nextSpeed() {
        val speed = SPEEDS[(SPEEDS.indexOfFirst { it == state.speed } + 1) % SPEEDS.size]
        if (!state.isFast) {
            player.setPlaybackSpeed(speed)
        }
        update(state.copy(speed = speed))
    }

    /** Play at 2x while held, then at the picked speed again. */
    fun holdFast(isHeld: Boolean) {
        if (state.track == null || state.isFast == isHeld) {
            return
        }
        player.setPlaybackSpeed(if (isHeld) FAST_SPEED else state.speed)
        update(state.copy(isFast = isHeld))
    }

    /** Pause while held, then go on. Does nothing if it's paused already. */
    fun holdPause(isHeld: Boolean) {
        when {
            isHeld && state.isPlaying && !isHeldPaused -> {
                isHeldPaused = true
                player.playWhenReady = false
            }

            !isHeld && isHeldPaused -> {
                isHeldPaused = false
                player.playWhenReady = state.isPlaying
            }
        }
    }

    /**
     * Offer [surface] for the video; the one with the highest [priority], the latest among
     * equals, shows it.
     */
    fun setSurface(owner: Any, surface: Surface, priority: Int) {
        surfaces.remove(owner)
        surfaces[owner] = surface to priority
        applySurface()
    }

    fun clearSurface(owner: Any) {
        if (surfaces.remove(owner) != null) {
            applySurface()
        }
    }

    private fun applySurface() {
        val best = surfaces.values.lastOrNull { it.second == PRIORITY_INLINE }
            ?: surfaces.values.lastOrNull()
        val surface = best?.first
        if (surface !== shownSurface) {
            shownSurface = surface
            player.setVideoSurface(surface)
            // A paused video has no new frame to show on the new surface: draw the current one
            if (surface != null && !player.playWhenReady && player.playbackState == Player.STATE_READY) {
                player.seekTo(player.currentPosition)
            }
        }
        update(state.copy(hasInlineSurface = best?.second == PRIORITY_INLINE))
    }

    private fun update(state: State) {
        if (state == this.state) {
            return
        }
        this.state = state
        listeners.forEach { it.onStateChanged(state) }
    }
}

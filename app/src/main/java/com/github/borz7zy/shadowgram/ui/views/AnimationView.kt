/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.github.borz7zy.shadowgram.playback.TdFileDataSource
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.io.File

/**
 * Loops a GIF, which Telegram sends as a silent MP4, with the platform's hardware decoder; or a
 * video, muted, streamed while it downloads (see [TdFileDataSource]), as its preview.
 *
 * A player only plays while the view is on screen and its window visible, so a chat keeps as
 * many decoders busy as GIFs it shows. The still under it stays visible until the first frame.
 *
 * ExoPlayer runs its listeners and state updates on the thread it's used from, and a looping GIF
 * updates on every loop; building and releasing one costs tens of milliseconds too. On the main
 * thread a scrolling grid of GIFs dropped frames, so players live on their own thread, render
 * into a Surface this view makes from its SurfaceTexture, and are pooled rather than rebuilt.
 */
class AnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs) {
    /** One play of [uri] into [surface]; [player] is only touched on the player thread. */
    private class Session(val uri: Uri, val surface: Surface) {
        var player: ExoPlayer? = null

        @Volatile
        var isStopped = false
    }

    private var uri: Uri? = null
    private var surface: Surface? = null
    private var session: Session? = null

    /**
     * Don't start a player meanwhile, one already playing goes on. Set while a grid scrolls:
     * cells scrolling in start once it settles.
     */
    var isPlaybackDeferred = false
        set(value) {
            if (field != value) {
                field = value
                updatePlayback()
            }
        }

    init {
        // Transparent until a frame is rendered, the still shows through
        alpha = 0f

        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                updatePlayback()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val surface = surface
                this@AnimationView.surface = null
                stop()
                // The player lets go of the surface first, on its thread, then it's released
                playerHandler.post {
                    surface?.release()
                    texture.release()
                }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                alpha = 1f
            }
        }
    }

    /**
     * Play [file], null to stop.
     */
    fun setVideo(file: File?) = setVideo(file?.let(Uri::fromFile))

    /** Play [uri], a file or a [TdFileDataSource] one, null to stop. */
    fun setVideo(uri: Uri?) {
        if (uri == this.uri) {
            return
        }
        this.uri = uri
        stop()
        updatePlayback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePlayback()
    }

    private fun updatePlayback() {
        val uri = uri
        val surface = surface
        val shouldPlay = uri != null && surface != null && isAttachedToWindow
                && windowVisibility == VISIBLE
        if (!shouldPlay) {
            stop()
            return
        }
        if (session != null || isPlaybackDeferred) {
            return
        }

        val session = Session(uri, surface)
        this.session = session
        playerHandler.post {
            if (session.isStopped) {
                return@post
            }
            session.player = (pool.removeFirstOrNull() ?: build(context)).apply {
                setVideoSurface(session.surface)
                setMediaItem(MediaItem.fromUri(session.uri))
                playWhenReady = true
                prepare()
            }
        }
    }

    private fun stop() {
        val session = session ?: return
        this.session = null
        alpha = 0f

        // Marked here, read there: a start still queued won't happen
        session.isStopped = true
        playerHandler.post {
            val player = session.player ?: return@post
            session.player = null
            player.stop()
            player.clearMediaItems()
            player.clearVideoSurface(session.surface)
            when (pool.size < POOL_SIZE) {
                true -> pool.addLast(player)
                false -> player.release()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private companion object {
        /** Players kept for reuse; a screen rarely shows more GIFs at once. */
        const val POOL_SIZE = 8

        /** Where players are built, used and pooled. */
        val playerHandler by lazy {
            Handler(HandlerThread("AnimationPlayers").apply { start() }.looper)
        }

        /** Where they play, a thread for all of them. */
        val playbackLooper: Looper by lazy {
            HandlerThread("AnimationPlayback").apply { start() }.looper
        }

        // Player thread only
        val pool = ArrayDeque<ExoPlayer>()

        // GIFs are silent: video renderer only
        fun build(context: Context) = ExoPlayer.Builder(context.applicationContext) { handler, videoListener, _, _, _ ->
            arrayOf<Renderer>(
                MediaCodecVideoRenderer.Builder(context.applicationContext)
                    .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                    .setEventHandler(handler)
                    .setEventListener(videoListener)
                    .build()
            )
        }
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdFileDataSource.Factory(context)))
            .setLooper(playerHandler.looper)
            .setPlaybackLooper(playbackLooper)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ALL
            }
    }
}

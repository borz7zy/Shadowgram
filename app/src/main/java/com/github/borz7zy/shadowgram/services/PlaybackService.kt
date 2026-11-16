/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.services

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.await
import com.github.borz7zy.shadowgram.ext.Bundle
import com.github.borz7zy.shadowgram.playback.MessagePlayer

/**
 * Twelve's PlaybackService reduced to a [MediaSession] over [MessagePlayer]'s [ExoPlayer]: the
 * messages and music played in chats get the media notification, play in the background and show
 * in the view models' [MediaController] (now playing, queue). No media library, resumption,
 * offload, audio effects or custom notification layout.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    enum class CustomCommand {
        /**
         * Toggles audio offload mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable offload
         */
        TOGGLE_OFFLOAD,

        /**
         * Toggles skip silence.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable skip silence
         */
        TOGGLE_SKIP_SILENCE,

        /**
         * Get the audio session ID.
         *
         * Response:
         * - [CustomCommand.RSP_VALUE] ([Int]): The audio session ID
         */
        GET_AUDIO_SESSION_ID,

        /**
         * Toggle shuffle mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable shuffle mode
         */
        TOGGLE_SHUFFLE,

        /**
         * Toggle repeat mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([String]): The repeat mode
         */
        TOGGLE_REPEAT;

        val sessionCommand = SessionCommand(name, Bundle.EMPTY)

        companion object {
            const val ARG_VALUE = "value"
            const val RSP_VALUE = "value"

            fun fromCustomAction(
                customAction: String
            ) = entries.firstOrNull { it.name == customAction }

            suspend fun MediaController.sendCustomCommand(
                customCommand: CustomCommand,
                extras: Bundle
            ) = sendCustomCommand(customCommand.sessionCommand, extras).await()
        }
    }

    private val player: ExoPlayer get() = MessagePlayer.player
    private lateinit var mediaSession: MediaSession

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .apply {
                        for (command in CustomCommand.entries) {
                            add(command.sessionCommand)
                        }
                    }
                    .build()
            )
            .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> = Futures.immediateFuture(
            when (CustomCommand.fromCustomAction(customCommand.customAction)) {
                CustomCommand.GET_AUDIO_SESSION_ID -> SessionResult(
                    SessionResult.RESULT_SUCCESS,
                    Bundle {
                        putInt(CustomCommand.RSP_VALUE, player.audioSessionId)
                    }
                )

                // Stub: the other commands are accepted and ignored
                else -> SessionResult(SessionResult.RESULT_SUCCESS)
            }
        )
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(mediaSessionCallback)
            .build()
    }

    override fun onDestroy() {
        // The player is MessagePlayer's, it outlives the service
        mediaSession.release()

        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE =
            "com.github.borz7zy.shadowgram.ACTION_TOGGLE_PLAY_PAUSE"
    }
}

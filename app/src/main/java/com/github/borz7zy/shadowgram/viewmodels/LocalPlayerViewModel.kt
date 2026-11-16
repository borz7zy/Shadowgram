/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import com.github.borz7zy.shadowgram.ext.applicationContext
import com.github.borz7zy.shadowgram.ext.availableCommandsFlow
import com.github.borz7zy.shadowgram.ext.eventsFlow
import com.github.borz7zy.shadowgram.ext.isPlayingFlow
import com.github.borz7zy.shadowgram.ext.mediaMetadataFlow
import com.github.borz7zy.shadowgram.ext.next
import com.github.borz7zy.shadowgram.ext.playbackParametersFlow
import com.github.borz7zy.shadowgram.ext.playbackProgressFlow
import com.github.borz7zy.shadowgram.ext.playbackStateFlow
import com.github.borz7zy.shadowgram.ext.repeatModeFlow
import com.github.borz7zy.shadowgram.ext.shuffleModeEnabled
import com.github.borz7zy.shadowgram.ext.shuffleModeFlow
import com.github.borz7zy.shadowgram.ext.toThumbnail
import com.github.borz7zy.shadowgram.ext.typedRepeatMode
import com.github.borz7zy.shadowgram.models.Error
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.PlaybackState
import com.github.borz7zy.shadowgram.models.RepeatMode
import com.github.borz7zy.shadowgram.services.TwelveRenderersFactory

/**
 * A view model useful to playback stuff locally (not in the playback service).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LocalPlayerViewModel(application: Application) : AndroidViewModel(application) {
    enum class PlaybackSpeed(val value: Float) {
        ONE(1f),
        ONE_POINT_FIVE(1.5f),
        TWO(2f),
        ZERO_POINT_FIVE(0.5f);

        companion object {
            fun fromValue(value: Float) = entries.firstOrNull {
                it.value == value
            }
        }
    }

    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(application)
    }

    // ExoPlayer
    private val exoPlayer = ExoPlayer.Builder(applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setRenderersFactory(
            TwelveRenderersFactory(
                applicationContext,
                false,
                onAudioDeviceInfoChanged = {},
                onAudioTrackConfigChanged = {}
            )
        )
        .build()

    private val eventsFlow = exoPlayer.eventsFlow()
        .flowOn(Dispatchers.Main)
        .shareIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            replay = 1
        )

    private val playbackState = exoPlayer.playbackStateFlow(eventsFlow)
        .flowOn(Dispatchers.Main)

    val mediaMetadata = exoPlayer.mediaMetadataFlow(eventsFlow)
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = MediaMetadata.EMPTY
        )

    val isPlaying = exoPlayer.isPlayingFlow(eventsFlow)
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    val shuffleMode = exoPlayer.shuffleModeFlow(eventsFlow)
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    val repeatMode = exoPlayer.repeatModeFlow(eventsFlow)
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = RepeatMode.NONE
        )

    val mediaArtwork = combine(
        mediaMetadata,
        playbackState,
    ) { mediaMetadata, playbackState ->
        when (playbackState) {
            PlaybackState.BUFFERING -> FlowResult.Loading
            else -> mediaMetadata.toThumbnail(applicationContext)?.let {
                FlowResult.Success(it)
            } ?: FlowResult.Failure(Error.NOT_FOUND)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = FlowResult.Loading
        )

    val playbackParameters = exoPlayer.playbackParametersFlow(eventsFlow)
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = PlaybackParameters.DEFAULT
        )

    val availableCommands = exoPlayer.availableCommandsFlow(eventsFlow)
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = Player.Commands.EMPTY
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val durationCurrentPositionMs = exoPlayer.playbackProgressFlow(eventsFlow)
        .flatMapLatest {
            flow {
                while (true) {
                    val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET }
                    val currentPosition =
                        exoPlayer.currentPosition.takeIf { duration != null }
                    emit(duration to currentPosition)
                    delay(200)
                }
            }
        }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null to null
        )

    override fun onCleared() {
        exoPlayer.release()

        super.onCleared()
    }

    fun setMediaUris(uris: Iterable<Uri>) {
        exoPlayer.apply {
            // Initialize shuffle and repeat modes
            typedRepeatMode = sharedPreferences.typedRepeatMode
            shuffleModeEnabled = sharedPreferences.shuffleModeEnabled

            setMediaItems(
                uris.map {
                    MediaItem.fromUri(it)
                }
            )
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        exoPlayer.apply {
            if (playbackState == ExoPlayer.STATE_ENDED) {
                seekTo(0)
            } else if (playWhenReady) {
                pause()
            } else {
                play()
            }
        }
    }

    fun shufflePlaybackSpeed() {
        val playbackSpeed = PlaybackSpeed.fromValue(
            exoPlayer.playbackParameters.speed
        ) ?: PlaybackSpeed.ONE

        exoPlayer.setPlaybackSpeed(playbackSpeed.next().value)
    }

    fun seekToPosition(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun toggleShuffleMode() {
        exoPlayer.shuffleModeEnabled = exoPlayer.shuffleModeEnabled.not()
        sharedPreferences.shuffleModeEnabled = exoPlayer.shuffleModeEnabled
    }

    fun toggleRepeatMode() {
        exoPlayer.typedRepeatMode = exoPlayer.typedRepeatMode.next()
        sharedPreferences.typedRepeatMode = exoPlayer.typedRepeatMode
    }

    fun seekToPrevious() {
        exoPlayer.apply {
            val currentMediaItemIndex = currentMediaItemIndex
            seekToPrevious()
            if (this.currentMediaItemIndex < currentMediaItemIndex) {
                play()
            }
        }
    }

    fun seekToNext() {
        exoPlayer.apply {
            seekToNext()
            play()
        }
    }
}

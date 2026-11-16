/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import me.bogerchan.niervisualizer.renderer.IRenderer
import me.bogerchan.niervisualizer.renderer.circle.CircleBarRenderer
import me.bogerchan.niervisualizer.renderer.circle.CircleRenderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType1Renderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType2Renderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType3Renderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType4Renderer
import me.bogerchan.niervisualizer.renderer.line.LineRenderer
import com.github.borz7zy.shadowgram.ext.applicationContext
import com.github.borz7zy.shadowgram.ext.availableCommandsFlow
import com.github.borz7zy.shadowgram.ext.isPlayingFlow
import com.github.borz7zy.shadowgram.ext.mediaItemFlow
import com.github.borz7zy.shadowgram.ext.mediaMetadataFlow
import com.github.borz7zy.shadowgram.ext.next
import com.github.borz7zy.shadowgram.ext.playbackParametersFlow
import com.github.borz7zy.shadowgram.ext.playbackStateFlow
import com.github.borz7zy.shadowgram.ext.repeatModeFlow
import com.github.borz7zy.shadowgram.ext.shuffleModeFlow
import com.github.borz7zy.shadowgram.ext.toThumbnail
import com.github.borz7zy.shadowgram.ext.tracksFlow
import com.github.borz7zy.shadowgram.models.Error
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.FlowResult.Companion.asFlowResult
import com.github.borz7zy.shadowgram.models.FlowResult.Companion.flatMapLatestData
import com.github.borz7zy.shadowgram.models.FlowResult.Companion.getOrNull
import com.github.borz7zy.shadowgram.models.PlaybackState
import com.github.borz7zy.shadowgram.models.RepeatMode
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.services.PlaybackService
import com.github.borz7zy.shadowgram.services.PlaybackService.CustomCommand.Companion.sendCustomCommand
import com.github.borz7zy.shadowgram.utils.MimeUtils
import com.github.borz7zy.shadowgram.utils.OutputConfigurationUtils
import com.github.borz7zy.shadowgram.utils.OutputConfigurationUtils.toModel

open class NowPlayingViewModel(application: Application) : TwelveViewModel(application) {
    enum class VisualizerType(val factory: () -> Array<IRenderer>?) {
        NONE({ null }),
        TYPE_1({ arrayOf(ColumnarType1Renderer()) }),
        TYPE_2({ arrayOf(ColumnarType2Renderer()) }),
        TYPE_3({ arrayOf(ColumnarType3Renderer()) }),
        TYPE_4({ arrayOf(ColumnarType4Renderer()) }),
        LINE({ arrayOf(LineRenderer(true)) }),
        CIRCLE_BAR({ arrayOf(CircleBarRenderer()) }),
        CIRCLE({ arrayOf(CircleRenderer(true)) }),
    }

    enum class LyricsLineState {
        /**
         * Line is still to be reached.
         */
        PENDING,

        /**
         * Line is relevant with current timestamp.
         */
        ACTIVE,

        /**
         * Line is past the current timestamp.
         */
        PAST,

        /**
         * Lyrics' position is unknown.
         */
        UNKNOWN,
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaMetadata = mediaControllerFlow
        .flatMapLatest { it.mediaMetadataFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = MediaMetadata.EMPTY
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaItem = mediaControllerFlow
        .flatMapLatest { it.mediaItemFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mediaItemUri = mediaItem
        .mapLatest { mediaItem ->
            mediaItem?.let {
                runCatching { it.mediaId.toUri() }.getOrNull()
            }
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val audio = mediaItemUri
        .flatMapLatest { mediaItemUri ->
            mediaItemUri?.let {
                mediaRepository.audio(it)
            } ?: flowOf(Result.Failure(Error.NOT_FOUND))
        }
        .asFlowResult()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = FlowResult.Loading
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val playbackState = mediaControllerFlow
        .flatMapLatest { it.playbackStateFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = PlaybackState.IDLE
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val isPlaying = mediaControllerFlow
        .flatMapLatest { it.isPlayingFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val shuffleMode = mediaControllerFlow
        .flatMapLatest { it.shuffleModeFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val repeatMode = mediaControllerFlow
        .flatMapLatest { it.repeatModeFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = RepeatMode.NONE
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val playbackParameters = mediaControllerFlow
        .flatMapLatest { it.playbackParametersFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = PlaybackParameters.DEFAULT
        )

    val mediaArtwork = combine(
        mediaMetadata,
        playbackState,
    ) { mediaMetadata, playbackState ->
        when (playbackState) {
            PlaybackState.BUFFERING -> null
            else -> mediaMetadata.toThumbnail(applicationContext)?.let {
                Result.Success(it)
            } ?: Result.Failure(Error.NOT_FOUND)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentTrackFormat = mediaControllerFlow
        .flatMapLatest { it.tracksFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .mapLatest { tracks ->
            val groups = tracks.groups.filter { group ->
                group.type == C.TRACK_TYPE_AUDIO && group.isSelected
            }

            require(groups.size <= 1) { "More than one audio track selected" }

            groups.firstOrNull()?.let { group ->
                (0..group.length).firstNotNullOfOrNull { i ->
                    when (group.isTrackSelected(i)) {
                        true -> group.getTrackFormat(i)
                        false -> null
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    val mimeType = combine(currentTrackFormat, mediaItem) { format, mediaItem ->
        format?.sampleMimeType
            ?: format?.containerMimeType
            ?: mediaItem?.localConfiguration?.mimeType
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(ExperimentalCoroutinesApi::class)
    val displayFileType = mimeType
        .mapLatest { mimeType ->
            mimeType?.let {
                MimeUtils.mimeTypeToDisplayName(it)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val availableCommands = mediaControllerFlow
        .flatMapLatest { it.availableCommandsFlow(eventsFlow) }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = Player.Commands.EMPTY
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val durationCurrentPositionMs = mediaControllerFlow
        .flatMapLatest { mediaController ->
            flow {
                while (true) {
                    val duration = mediaController.duration.takeIf { it != C.TIME_UNSET }
                    val currentPosition =
                        mediaController.currentPosition.takeIf { duration != null }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val audioSessionId = mediaControllerFlow
        .mapLatest { mediaController ->
            mediaController.sendCustomCommand(
                PlaybackService.CustomCommand.GET_AUDIO_SESSION_ID,
                Bundle.EMPTY
            ).extras.getInt(PlaybackService.CustomCommand.RSP_VALUE)
        }
        .flowOn(Dispatchers.Main)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    private val _currentVisualizerType = MutableStateFlow(VisualizerType.entries.first())
    val currentVisualizerType = combine(
        _currentVisualizerType,
        isPlaying,
    ) { currentVisualizerType, isPlaying ->
        currentVisualizerType.takeIf { isPlaying } ?: VisualizerType.NONE
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = VisualizerType.NONE
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val isVisualizerEnabled = currentVisualizerType
        .mapLatest { it != VisualizerType.NONE }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lyrics = mediaItemUri
        .flatMapLatest { mediaItemUri ->
            mediaItemUri?.let {
                mediaRepository.lyrics(it)
            } ?: flowOf(Result.Failure(Error.NOT_FOUND))
        }
        .asFlowResult()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = FlowResult.Loading
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val lyricsLines = lyrics
        .flatMapLatestData { lyrics ->
            durationCurrentPositionMs.mapLatest { durationCurrentPositionMs ->
                var currentIndex: Int? = null

                val linesWithState = lyrics.lines.withIndex().map { (i, line) ->
                    val lyricsLineState = line.durationMs?.let { durationMs ->
                        durationCurrentPositionMs.second?.let { currentPositionMs ->
                            when {
                                currentPositionMs < durationMs.first -> LyricsLineState.PENDING
                                currentPositionMs in durationMs -> LyricsLineState.ACTIVE
                                currentPositionMs > durationMs.last -> LyricsLineState.PAST
                                else -> LyricsLineState.UNKNOWN
                            }
                        }
                    } ?: LyricsLineState.UNKNOWN

                    if (lyricsLineState == LyricsLineState.ACTIVE
                        || lyricsLineState == LyricsLineState.PAST
                    ) {
                        currentIndex = i
                    }

                    line to lyricsLineState
                }

                FlowResult.Success(linesWithState to currentIndex)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = FlowResult.Loading
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val outputSource = currentTrackFormat
        .mapLatest { it?.toModel() }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    private val outputTranscoding = combine(
        outputConfigurationRepository.audioTrackConfig,
        outputConfigurationRepository.format,
    ) { audioTrackConfig, format ->
        OutputConfigurationUtils.buildOutputTranscoding(
            audioTrackConfig = audioTrackConfig,
            format = format,
        )
    }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    @androidx.annotation.OptIn(UnstableApi::class)
    val outputConfiguration = combine(
        outputSource,
        outputTranscoding,
        outputConfigurationRepository.device,
    ) { outputSource, outputTranscoding, outputDevice ->
        OutputConfigurationUtils.classify(
            source = outputSource,
            transcoding = outputTranscoding,
            device = outputDevice,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    fun togglePlayPause() {
        mediaController.value?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun seekToPosition(positionMs: Long) {
        mediaController.value?.seekTo(positionMs)
    }

    fun seekToPrevious() {
        mediaController.value?.let {
            val currentMediaItemIndex = it.currentMediaItemIndex
            it.seekToPrevious()
            if (it.currentMediaItemIndex < currentMediaItemIndex) {
                it.play()
            }
        }
    }

    fun seekToNext() {
        mediaController.value?.let {
            it.seekToNext()
            it.play()
        }
    }

    fun toggleShuffleMode() {
        shuffleModeEnabled = shuffleModeEnabled.not()
    }

    fun toggleRepeatMode() {
        typedRepeatMode = typedRepeatMode.next()
    }

    fun nextVisualizerType() {
        _currentVisualizerType.value = _currentVisualizerType.value.next()
    }

    suspend fun toggleFavorites() {
        audio.value.getOrNull()?.let {
            withContext(Dispatchers.IO) {
                mediaRepository.setFavorite(it.uri, !it.isFavorite)
            }
        }
    }
}

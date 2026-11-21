/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.ext.applicationContext
import com.github.borz7zy.shadowgram.ext.eventsFlow
import com.github.borz7zy.shadowgram.ext.resources
import com.github.borz7zy.shadowgram.ext.shuffleModeEnabled
import com.github.borz7zy.shadowgram.ext.typedRepeatMode
import com.github.borz7zy.shadowgram.models.Audio
import com.github.borz7zy.shadowgram.models.RepeatMode
import com.github.borz7zy.shadowgram.services.PlaybackService
import com.github.borz7zy.shadowgram.ui.UiPreferences

/**
 * Base view model for all app view models.
 * Here we keep the shared stuff every fragment could use, like access to the repository and
 * the media controller to interact with the playback service.
 */
abstract class TwelveViewModel(application: Application) : AndroidViewModel(application) {
    protected val mediaRepository = getApplication<ShadowgramApplication>().mediaRepository
    protected val providersRepository = getApplication<ShadowgramApplication>().providersRepository
    protected val outputConfigurationRepository =
        getApplication<ShadowgramApplication>().outputConfigurationRepository

    @Suppress("EmptyMethod")
    final override fun <T : Application> getApplication() = super.getApplication<T>()

    protected val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(application)!!
    }
    private val playbackCommands = Mutex()

    private val sessionToken by lazy {
        SessionToken(
            applicationContext,
            ComponentName(applicationContext, PlaybackService::class.java)
        )
    }

    protected val mediaControllerFlow = channelFlow {
        val mediaController = MediaController.Builder(applicationContext, sessionToken)
            .buildAsync()
            .await()

        trySend(mediaController)

        awaitClose {
            mediaController.release()
        }
    }
        .flowOn(Dispatchers.Main)
        .shareIn(
            viewModelScope,
            SharingStarted.Eagerly,
            replay = 1
        )

    protected val mediaController = mediaControllerFlow
        .stateIn(
            viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    protected val eventsFlow = mediaControllerFlow
        .flatMapLatest { it.eventsFlow() }
        .shareIn(
            viewModelScope,
            SharingStarted.Eagerly,
            replay = 1
        )

    protected var shuffleModeEnabled: Boolean
        get() = mediaController.value?.shuffleModeEnabled ?: false
        set(value) {
            mediaController.value?.apply {
                shuffleModeEnabled = value
                UiPreferences.write(applicationContext) { it.shuffleModeEnabled = value }
            }
        }

    protected var typedRepeatMode: RepeatMode
        get() = mediaController.value?.typedRepeatMode ?: RepeatMode.NONE
        set(value) {
            mediaController.value?.apply {
                typedRepeatMode = value
                UiPreferences.write(applicationContext) { it.typedRepeatMode = value }
            }
        }

    fun playAudio(audios: List<Audio>, position: Int) {
        withMediaItems(audios) { items ->
            val (shuffle, repeat) = UiPreferences.read(applicationContext) {
                it.shuffleModeEnabled to it.typedRepeatMode
            }
            shuffleModeEnabled = shuffle
            typedRepeatMode = repeat
            setMediaItems(items, true)
            prepare()
            seekToDefaultPosition(position)
            play()
        }
    }

    fun addToQueue(audios: List<Audio>) {
        withMediaItems(audios) { items ->
            addMediaItems(items)
            if (mediaItemCount == items.size) play()
        }
    }

    fun playNext(audios: List<Audio>) {
        withMediaItems(audios) { items ->
            addMediaItems(currentMediaItemIndex + 1, items)
            if (mediaItemCount == items.size) play()
        }
    }

    private fun withMediaItems(
        audios: List<Audio>,
        apply: suspend MediaController.(List<MediaItem>) -> Unit,
    ) {
        val controller = mediaController.value ?: return
        viewModelScope.launch {
            playbackCommands.withLock {
                val items = withContext(Dispatchers.Default) {
                    audios.map { it.toMedia3MediaItem(resources) }
                }
                if (mediaController.value === controller) controller.apply(items)
            }
        }
    }
}

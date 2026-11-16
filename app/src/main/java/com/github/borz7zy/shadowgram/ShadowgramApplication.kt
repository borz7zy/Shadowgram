/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram

import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.MainScope
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.repositories.AccountsRepository
import com.github.borz7zy.shadowgram.repositories.MediaRepository
import com.github.borz7zy.shadowgram.repositories.OutputConfigurationRepository
import com.github.borz7zy.shadowgram.repositories.ProvidersRepository
import com.github.borz7zy.shadowgram.ui.coil.RustDecoder
import com.github.borz7zy.shadowgram.ui.coil.ThumbnailMapper
import com.github.borz7zy.rustcore.RustCache
import org.lineageos.aperture.ApertureApplication

// Based on TwelveApplication, without the database and the resumption playlist repository.
// Extends ApertureApplication: the embedded camera (:aperture) reads its repositories from it.
@androidx.annotation.OptIn(UnstableApi::class)
class ShadowgramApplication : ApertureApplication(), SingletonImageLoader.Factory {
    private val coroutineScope = MainScope()
    val accountsRepository by lazy { AccountsRepository(applicationContext, coroutineScope) }
    val providersRepository by lazy { ProvidersRepository() }
    val mediaRepository by lazy {
        MediaRepository(applicationContext, coroutineScope, providersRepository)
    }
    val outputConfigurationRepository by lazy { OutputConfigurationRepository() }

    override fun onCreate() {
        super.onCreate()

        // Before anything draws an emoji: its sizes and sprites come from the app's resources
        Emoji.init(this)

        // Voice and video messages, one at a time for the whole app
        MessagePlayer.init(this)
    }

    /** RustCache drops its RAM tier (decoded sticker frames etc.); disk stays. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        RustCache.trimMemory()
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(this)
        .components {
            add(ThumbnailMapper)
            add(RustDecoder.Factory)
        }
        .build()
}

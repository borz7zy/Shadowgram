/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.annotation.SuppressLint
import android.app.Application
import androidx.camera.camera2.internal.CameraCompatibilityFilter
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import org.lineageos.aperture.repositories.CameraRepository
import org.lineageos.aperture.repositories.MediaRepository
import org.lineageos.aperture.repositories.OverlaysRepository
import org.lineageos.aperture.repositories.PreferencesRepository

open class ApertureApplication : Application() {
    private val coroutineScope = MainScope()

    val cameraRepository by lazy { CameraRepository(this, coroutineScope, overlaysRepository) }
    // Named apart from ShadowgramApplication.mediaRepository (the host app extends this class)
    val apertureMediaRepository by lazy { MediaRepository(this) }
    val overlaysRepository by lazy { OverlaysRepository(this) }
    val preferencesRepository by lazy { PreferencesRepository(this, coroutineScope) }
    private val preferencesReady by lazy {
        coroutineScope.async(Dispatchers.IO, CoroutineStart.LAZY) { preferencesRepository.preload() }
    }

    internal suspend fun awaitPreferences() = preferencesReady.await()

    @SuppressLint("RestrictedApi")
    override fun onCreate() {
        super.onCreate()
        preferencesReady.start()

        // Observe dynamic colors changes
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Set backward compatible camera ids
        CameraCompatibilityFilter.setBackwardCompatibleCameraIds(
            overlaysRepository.backwardCompatibleCameraIds.asList()
        )
    }
}

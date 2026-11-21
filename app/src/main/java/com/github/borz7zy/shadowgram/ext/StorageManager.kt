/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executors

fun StorageManager.storageVolumesFlow() = callbackFlow {
    val update = {
        trySend(storageVolumes)
    }

    val storageVolumeCallback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) {
            update()
        }
    }

    val executor = Executors.newSingleThreadExecutor()
    registerStorageVolumeCallback(executor, storageVolumeCallback)
    update()

    awaitClose {
        unregisterStorageVolumeCallback(storageVolumeCallback)
        executor.shutdown()
    }
}.flowOn(Dispatchers.IO)

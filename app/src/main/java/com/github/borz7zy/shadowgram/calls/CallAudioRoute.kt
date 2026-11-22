/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.calls

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
internal class CallAudioRoute(
    private val context: Context,
    private val manager: AudioManager,
    private val dispatch: (() -> Unit) -> Unit,
    private val onDevicesChanged: () -> Unit,
) : AutoCloseable {
    private val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val oldSpeaker = !modern && manager.isSpeakerphoneOn
    private var listening = false
    private var receiverRegistered = false
    private var requestedSco = false
    private var scoConnected = false
    private var speaker = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
    }

    private val bluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val scoState = if (intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            } else null
            dispatch {
                if (listening) {
                    if (scoState != null) scoConnected = scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED
                    onDevicesChanged()
                }
            }
        }
    }

    fun start() {
        listening = true
        manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        if (!modern) {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED).apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            val initial = ContextCompat.registerReceiver(context, bluetooth, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            scoConnected = initial?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR) ==
                AudioManager.SCO_AUDIO_STATE_CONNECTED
        }
    }

    fun speaker(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enabled && !selectModern(true)) return false
        speaker = enabled
        return true
    }

    /** Returns whether streams should use the sample rates required by legacy Bluetooth SCO. */
    fun apply(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            selectModern(speaker)
            return false
        }
        val wired = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in wiredTypes }
        val useSco = !speaker && !wired && hasLegacyHeadset()
        if (manager.isSpeakerphoneOn != speaker) manager.isSpeakerphoneOn = speaker
        if (useSco && !requestedSco) {
            manager.startBluetoothSco()
            requestedSco = true
        } else if (!useSco) {
            stopSco()
        }
        val sco = useSco && scoConnected
        if (sco && !manager.isBluetoothScoOn) manager.isBluetoothScoOn = true
        return sco
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun selectModern(speaker: Boolean): Boolean {
        val devices = manager.availableCommunicationDevices
        val currentId = manager.communicationDevice?.id
        if (speaker) {
            val device = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return false
            return device.id == currentId || manager.setCommunicationDevice(device)
        }
        for (type in headsetTypes) {
            if (devices.any { it.type == type && (it.id == currentId || manager.setCommunicationDevice(it)) }) {
                return true
            }
        }
        manager.clearCommunicationDevice()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun hasLegacyHeadset(): Boolean {
        if (!manager.isBluetoothScoAvailableOffCall) return false
        return runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    private fun changed() = dispatch { if (listening) onDevicesChanged() }

    private fun stopSco() {
        if (!requestedSco) return
        requestedSco = false
        scoConnected = false
        manager.isBluetoothScoOn = false
        manager.stopBluetoothSco()
    }

    override fun close() {
        if (!listening) return
        listening = false
        manager.unregisterAudioDeviceCallback(devices)
        if (receiverRegistered) {
            context.unregisterReceiver(bluetooth)
            receiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()
        } else {
            stopSco()
            manager.isSpeakerphoneOn = oldSpeaker
        }
    }

    private companion object {
        val wiredTypes = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        val headsetTypes = wiredTypes + listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}

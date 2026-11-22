/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.calls

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VoiceCallService : Service() {
    private val calls get() = (application as ShadowgramApplication).voiceCalls
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy { CallNotifications(this) }
    private var token = 0L
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: return START_NOT_STICKY
        val requestedToken = request.getLongExtra(CallActivity.TOKEN, 0L)
        val userId = request.getLongExtra(CallActivity.USER, 0L)
        if (requestedToken == 0L) return START_NOT_STICKY
        val states = calls.screens.value
        val previous = states[requestedToken]
        if (token != 0L) {
            if (token != requestedToken && userId == 0L && previous?.phase == TelegramVoiceCalls.Phase.INCOMING) {
                calls.answer(requestedToken)
            }
            return START_NOT_STICKY
        }
        if ((userId == 0L && previous?.phase != TelegramVoiceCalls.Phase.INCOMING) ||
            (userId != 0L && states.values.any { it.phase != TelegramVoiceCalls.Phase.ENDED })) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            calls.end(requestedToken, R.string.call_microphone_required)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val name = request.getStringExtra(CallActivity.NAME)?.takeIf(String::isNotBlank)
            ?: previous?.name ?: getString(R.string.call_voice)
        try {
            ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID,
                notifications.ongoing(requestedToken, name, R.string.call_connecting),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            calls.end(requestedToken, R.string.call_microphone_required)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        token = requestedToken
        if (userId == 0L) {
            calls.answer(token)
        } else {
            calls.dial(token, userId, name) {
                scope.launch {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        watching = scope.launch {
            calls.screens.collect { states ->
                val screen = states[token] ?: return@collect
                if (screen.phase == TelegramVoiceCalls.Phase.ENDED) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(CallNotifications.ONGOING_ID, notifications.ongoing(token, screen.name, screen.phase.title))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (token != 0L) calls.end(token)
        watching?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

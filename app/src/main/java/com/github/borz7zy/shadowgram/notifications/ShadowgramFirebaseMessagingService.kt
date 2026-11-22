/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ShadowgramFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION") // TDLib registers an FCM token, not a Firebase installation ID.
    override fun onNewToken(token: String) = scheduleRegistration(this, refresh = true)

    override fun onMessageReceived(message: RemoteMessage) {
        logNotificationDelivery {
            val age = message.sentTime.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
            "FCM received ageMs=$age highPriority=${message.priority == RemoteMessage.PRIORITY_HIGH}"
        }
        if (message.data.isEmpty()) {
            if (message.notification == null) processPush(payload = null, highPriority = false)
            return
        }
        val payload = JSONObject()
        for ((key, value) in message.data) {
            val decoded = try {
                when (key) {
                    "custom", "data" -> JSONObject(value)
                    "loc_args" -> JSONArray(value)
                    else -> value
                }
            } catch (_: JSONException) {
                value
            }
            payload.put(key, decoded)
        }
        payload.put("google.sent_time", message.sentTime)
        message.notification?.sound?.let { payload.put("google.notification.sound", it) }
        processPush(payload.toString(), message.priority == RemoteMessage.PRIORITY_HIGH)
    }

    override fun onDeletedMessages() = processPush(payload = null, highPriority = false)

    private fun processPush(payload: String?, highPriority: Boolean) {
        // Firebase calls this on a worker thread. Start TDLib immediately and keep the callback short.
        val app = applicationContext as ShadowgramApplication
        val processor = app.telegramPushProcessor
        val delivery = processor.start(payload)
        val delivered = runBlocking { withTimeoutOrNull(DIRECT_WAIT_MS) { delivery.await() } }
        if (delivered == true) return

        val savedPayload = payload?.takeIf { it.toByteArray().size <= MAX_WORK_PAYLOAD_BYTES }
        val request = OneTimeWorkRequestBuilder<TelegramPushWorker>()
        if (savedPayload == null) request.setConstraints(connectedNetwork)
        else request.setInputData(workDataOf(TelegramPushWorker.PAYLOAD to savedPayload))
        if (highPriority && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val work = request.build()
        if (delivered == null) processor.handoff(work.id, delivery)
        logNotificationDelivery { "FCM delivery handed to WorkManager" }
        WorkManager.getInstance(app).enqueue(work)
    }

    companion object {
        private const val MAX_WORK_PAYLOAD_BYTES = 8_000
        private const val DIRECT_WAIT_MS = 1_500L
        private const val REGISTER_WORK = "telegram_fcm_registration"
        private val connectedNetwork = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        internal fun scheduleRegistration(context: Context, refresh: Boolean = false) {
            if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) return
            val policy = if (refresh) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            val request = OneTimeWorkRequestBuilder<TelegramPushRegistrationWorker>()
                .setConstraints(connectedNetwork)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(REGISTER_WORK, policy, request)
        }
    }
}

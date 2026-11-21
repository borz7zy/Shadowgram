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
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ShadowgramFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION") // TDLib registers an FCM token, not a Firebase installation ID.
    override fun onNewToken(token: String) = scheduleRegistration(this, refresh = true)

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data.isEmpty()) {
            if (message.notification == null) schedulePush(payload = null, highPriority = false)
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
        val encoded = payload.toString().takeIf { it.toByteArray().size <= MAX_WORK_PAYLOAD_BYTES }
        schedulePush(encoded, message.priority == RemoteMessage.PRIORITY_HIGH)
    }

    override fun onDeletedMessages() = schedulePush(payload = null, highPriority = false)

    private fun schedulePush(payload: String?, highPriority: Boolean) {
        val request = OneTimeWorkRequestBuilder<TelegramPushWorker>()
        if (payload == null) request.setConstraints(connectedNetwork)
        else request.setInputData(workDataOf(TelegramPushWorker.PAYLOAD to payload))
        if (highPriority && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(applicationContext).enqueue(request.build())
    }

    companion object {
        private const val MAX_WORK_PAYLOAD_BYTES = 8_000
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

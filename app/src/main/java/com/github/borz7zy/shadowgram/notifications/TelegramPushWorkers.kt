/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result as TelegramResult
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

class TelegramPushRegistrationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withTimeoutOrNull(WORK_TIMEOUT_MS) {
        if (FirebaseApp.getApps(applicationContext).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            return@withTimeoutOrNull Result.success()
        }
        val app = applicationContext as ShadowgramApplication
        val repository = app.accountsRepository
        val clients = repository.signedInClients().filterValues { it.isAuthorized() }
        if (clients.isEmpty()) return@withTimeoutOrNull Result.success()

        val token = fetchFcmToken() ?: return@withTimeoutOrNull retryOrFail()
        val userIds = clients.mapValues { (_, client) ->
            (client.send(TdApi.GetOption("my_id")).getOrNull() as? TdApi.OptionValueInteger)
                ?.value?.takeIf { it > 0 }
        }
        var retry = false
        for ((accountId, client) in clients) {
            val userId = userIds[accountId]
            if (userId == null) {
                retry = true
                continue
            }
            val otherUserIds = userIds.values.filterNotNull().filter { it != userId }.distinct().sorted()
                .toLongArray()
            when (val result = client.send(TdApi.RegisterDevice(
                TdApi.DeviceTokenFirebaseCloudMessaging(token, true), otherUserIds
            ))) {
                is TelegramResult.Success -> {
                    if (result.data.id != 0L) {
                        repository.savePushReceiver(accountId, client, result.data.id)
                        Log.i(LOG_TAG, "FCM registered for account slot $accountId")
                    } else retry = true
                }
                is TelegramResult.Failure -> {
                    val error = result.error
                    if (error.message == "APP_PUSH_APIKEY_MISSING") {
                        Log.w(LOG_TAG, "APP_PUSH_APIKEY_MISSING: configure FCM for this Telegram API ID")
                    } else {
                        Log.w(LOG_TAG, "FCM registration failed (TDLib ${error.code})")
                        retry = retry || error.code == 429 || error.code >= 500
                    }
                }
            }
        }
        if (retry) retryOrFail() else Result.success()
    } ?: retryOrFail()

    private fun retryOrFail() = if (runAttemptCount < 3) Result.retry() else Result.failure()

    @Suppress("DEPRECATION") // Telegram's FCM integration needs the registration token.
    private suspend fun fetchFcmToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) Log.w(LOG_TAG, "Check Google Play services and Firebase configuration")
            continuation.resume(if (task.isSuccessful) task.result?.takeIf(String::isNotBlank) else null)
        }
    }
}

class TelegramPushWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withTimeoutOrNull(WORK_TIMEOUT_MS) {
        val app = applicationContext as ShadowgramApplication
        val payload = inputData.getString(PAYLOAD)
        val receiver = if (payload == null) 0L else try {
            Client.execute(TdApi.GetPushReceiverId(payload)).id
        } catch (error: Client.ExecutionException) {
            Log.w(LOG_TAG, "Invalid FCM payload (TDLib ${error.error.code})")
            return@withTimeoutOrNull Result.failure()
        }

        val clients = app.accountsRepository.signedInClients(receiver).values
        coroutineScope {
            clients.map { client ->
                async {
                    if (!client.isAuthorized() || !client.awaitNotificationSetup()) return@async
                    if (payload == null) {
                        client.awaitNotificationSync()
                        return@async
                    }
                    when (val result = client.send(TdApi.ProcessPushNotification(payload))) {
                        is TelegramResult.Success -> Unit
                        is TelegramResult.Failure -> {
                            if (result.error.code == 406) client.awaitNotificationSync()
                            else Log.w(LOG_TAG, "FCM push processing failed (TDLib ${result.error.code})")
                        }
                    }
                }
            }.awaitAll()
        }
        app.telegramNotifications.flush()
        Result.success()
    } ?: if (runAttemptCount < 3) Result.retry() else Result.failure()

    companion object {
        const val PAYLOAD = "telegram_push_payload"
    }
}

private const val WORK_TIMEOUT_MS = 30_000L
private const val LOG_TAG = "TelegramPush"

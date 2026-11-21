/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.borz7zy.shadowgram.ShadowgramApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountId = intent.getIntExtra(TelegramNotifications.EXTRA_ACCOUNT_ID, -1)
        val groupId = intent.getIntExtra(TelegramNotifications.EXTRA_GROUP_ID, 0)
        val notificationId = intent.getIntExtra(TelegramNotifications.EXTRA_NOTIFICATION_ID, 0)
        if (accountId < 0 || groupId <= 0 || notificationId <= 0) return

        val pending = goAsync()
        val app = context.applicationContext as ShadowgramApplication
        app.backgroundScope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    val client = app.accountsRepository.signedInClient(accountId) ?: return@withTimeoutOrNull
                    if (client.isAuthorized()) client.send(TdApi.RemoveNotificationGroup(groupId, notificationId))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

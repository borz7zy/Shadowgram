/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.borz7zy.shadowgram.ChatActivity
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.telegram.ChatsStore
import com.github.borz7zy.shadowgram.telegram.toPreviewContent
import com.github.borz7zy.shadowgram.ui.text.stringResId
import com.github.borz7zy.shadowgram.viewmodels.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Mirrors TDLib's notification groups into the Android notification shade. */
internal class TelegramNotifications(private val context: Context, scope: CoroutineScope) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val updates = Channel<() -> Unit>(Channel.UNLIMITED)
    private val groupsByAccount = mutableMapOf<Int, MutableMap<Int, Group>>()
    private data class Group(
        val chatId: Long,
        val notifications: MutableMap<Int, TdApi.Notification> = mutableMapOf(),
    )

    init {
        scope.launch(Dispatchers.IO) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH)
            )
            for (update in updates) update()
        }
    }

    fun onUpdate(accountId: Int, update: TdApi.Object, chats: ChatsStore) {
        updates.trySend { applyUpdate(accountId, update, chats) }
    }

    /** Called after TDLib completes push processing to wait for Android notification updates. */
    suspend fun flush() {
        val processed = CompletableDeferred<Unit>()
        updates.send { processed.complete(Unit) }
        processed.await()
    }

    private fun applyUpdate(accountId: Int, update: TdApi.Object, chats: ChatsStore) {
        val tag = notificationTag(accountId)
        when (update) {
            is TdApi.UpdateActiveNotifications -> {
                val groups = update.groups
                    .associateTo(mutableMapOf()) { group ->
                        group.id to Group(group.chatId, group.notifications.associateByTo(mutableMapOf()) { it.id })
                    }
                groupsByAccount[accountId] = groups
                manager.activeNotifications.filter { it.tag == tag && it.id !in groups }
                    .forEach { manager.cancel(tag, it.id) }
                groups.forEach { (id, group) -> show(accountId, id, group, chats, alert = false) }
            }
            is TdApi.UpdateNotificationGroup -> {
                val groups = groupsByAccount.getOrPut(accountId) { mutableMapOf() }
                val id = update.notificationGroupId
                val group = groups[id]?.takeIf { it.chatId == update.chatId } ?: Group(update.chatId)
                val previousId = group.latest()?.id ?: 0
                update.removedNotificationIds.forEach(group.notifications::remove)
                update.addedNotifications.forEach { group.notifications[it.id] = it }
                if (group.notifications.isEmpty()) {
                    groups.remove(id)
                    manager.cancel(tag, id)
                } else {
                    groups[id] = group
                    val latest = group.latest()
                    val alert = latest != null && latest.id > previousId &&
                        !latest.isSilent && update.notificationSoundId != 0L
                    show(accountId, id, group, chats, alert)
                }
            }
            is TdApi.UpdateNotification -> {
                val id = update.notificationGroupId
                val group = groupsByAccount[accountId]?.get(id) ?: return
                if (update.notification.id in group.notifications) {
                    group.notifications[update.notification.id] = update.notification
                    show(accountId, id, group, chats, alert = false)
                }
            }
            is TdApi.UpdateAuthorizationState -> when (update.authorizationState) {
                is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    groupsByAccount.remove(accountId)
                    manager.activeNotifications.filter { it.tag == tag }
                        .forEach { manager.cancel(tag, it.id) }
                }
                else -> Unit
            }
        }
    }

    private fun Group.latest(): TdApi.Notification? = notifications.values
        .filter {
            when (val type = it.type) {
                is TdApi.NotificationTypeNewMessage -> !type.message.isOutgoing
                is TdApi.NotificationTypeNewPushMessage -> !type.isOutgoing
                else -> false
            }
        }
        .maxByOrNull { it.id }

    @SuppressLint("MissingPermission") // Checked here and caught if Android revokes it before notify().
    private fun show(accountId: Int, groupId: Int, group: Group, chats: ChatsStore, alert: Boolean) {
        val tag = notificationTag(accountId)
        val latest = group.latest()
        if (latest == null) {
            manager.cancel(tag, groupId)
            return
        }
        if (!manager.areNotificationsEnabled() ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val type = latest.type as? TdApi.NotificationTypeNewMessage
        val preview = type?.takeIf { it.showPreview }?.message?.content?.toPreviewContent()
        val text = preview?.let { (kind, body) ->
            body?.takeIf { it.isNotBlank() }?.take(500) ?: context.getString(kind.stringResId)
        }
            ?: context.getString(R.string.notification_new_message)
        val topicId = (type?.message?.topicId as? TdApi.MessageTopicForum)?.forumTopicId ?: 0
        val identity = Uri.parse("shadowgram://notification/$accountId/$groupId")
        val openChat = Intent(context, ChatActivity::class.java).apply {
            data = identity
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_ACCOUNT_ID, accountId)
            putExtra(ChatViewModel.ARG_CHAT_ID, group.chatId)
            putExtra(ChatViewModel.ARG_TOPIC_ID, topicId)
        }
        val dismiss = Intent(context, NotificationDismissReceiver::class.java).apply {
            data = identity
            putExtra(EXTRA_ACCOUNT_ID, accountId)
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_NOTIFICATION_ID, latest.id)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small_icon)
            .setContentTitle(chats.notificationTitle(group.chatId) ?: context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(latest.date * 1000L)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(!alert)
            .setContentIntent(PendingIntent.getActivity(context, 0, openChat, pendingFlags))
            .setDeleteIntent(PendingIntent.getBroadcast(context, 0, dismiss, pendingFlags))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(tag, groupId, notification)
        } catch (_: SecurityException) {
            // The user revoked permission while this notification was being built.
        }
    }

    private fun notificationTag(accountId: Int) = "telegram_messages_$accountId"

    companion object {
        const val EXTRA_ACCOUNT_ID = "telegram_notification_account_id"
        const val EXTRA_GROUP_ID = "telegram_notification_group_id"
        const val EXTRA_NOTIFICATION_ID = "telegram_notification_id"
        private const val CHANNEL_ID = "telegram_messages"
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.borz7zy.shadowgram.ChatActivity
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import com.github.borz7zy.shadowgram.telegram.toPreviewContent
import com.github.borz7zy.shadowgram.ui.text.stringResId
import com.github.borz7zy.shadowgram.viewmodels.ChatViewModel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Mirrors TDLib's notification groups into the Android notification shade. */
internal class TelegramNotifications(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val updates = Channel<() -> Unit>(Channel.UNLIMITED)
    internal val sounds = NotificationSoundChannels(context, manager)
    private val preparingSounds = ConcurrentHashMap.newKeySet<Pair<Int, Long>>()
    private val haptics get() = (context.applicationContext as ShadowgramApplication).audioHaptics
    private val groupsByAccount = mutableMapOf<Int, MutableMap<Int, Group>>()
    private data class Group(
        val chatId: Long,
        val notifications: MutableMap<Int, TdApi.Notification> = mutableMapOf(),
        var channel: String = NotificationSoundChannels.DEFAULT,
    )

    init {
        scope.launch(Dispatchers.IO) {
            NotificationSoundChannels.ensureDefaultChannel(context)
            for (update in updates) update()
        }
    }

    fun onUpdate(accountId: Int, update: TdApi.Object, client: TelegramClient) {
        val queuedAt = SystemClock.elapsedRealtime()
        if (update is TdApi.UpdateNotificationGroup) {
            logNotificationDelivery {
                "TDLib notification group added=${update.addedNotifications.size} removed=${update.removedNotificationIds.size}"
            }
        }
        updates.trySend {
            logNotificationDelivery { "Android notification processing queueMs=${SystemClock.elapsedRealtime() - queuedAt}" }
            applyUpdate(accountId, update, client)
        }
    }

    fun prepareSound(accountId: Int, soundId: Long, client: TelegramClient) {
        val key = accountId to soundId
        if (!preparingSounds.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                sounds.prepareChannel(accountId, soundId, client)?.let(haptics::scheduleNotificationSound)
            } finally {
                preparingSounds.remove(key)
            }
        }
    }

    /** Called after TDLib completes push processing to wait for Android notification updates. */
    suspend fun flush() {
        val processed = CompletableDeferred<Unit>()
        updates.send { processed.complete(Unit) }
        processed.await()
    }

    private fun applyUpdate(accountId: Int, update: TdApi.Object, client: TelegramClient) {
        val tag = notificationTag(accountId)
        when (update) {
            is TdApi.UpdateActiveNotifications -> {
                val active = manager.activeNotifications.filter { it.tag == tag }.associateBy { it.id }
                val groups = update.groups.associateTo(mutableMapOf()) { group ->
                    group.id to Group(
                        group.chatId,
                        group.notifications.filter { it.isMessage() }.associateByTo(mutableMapOf()) { it.id },
                        active[group.id]?.notification?.channelId ?: NotificationSoundChannels.DEFAULT,
                    )
                }
                groupsByAccount[accountId] = groups
                active.keys.filter { it !in groups }.forEach { manager.cancel(tag, it) }
                groups.forEach { (id, group) -> show(accountId, id, group, client) }
            }
            is TdApi.UpdateNotificationGroup -> {
                val groups = groupsByAccount.getOrPut(accountId) { mutableMapOf() }
                val id = update.notificationGroupId
                val group = groups[id]?.takeIf { it.chatId == update.chatId } ?: Group(update.chatId)
                val previousId = group.latest()?.id ?: 0
                update.removedNotificationIds.forEach(group.notifications::remove)
                update.addedNotifications.filter { it.isMessage() }.forEach { group.notifications[it.id] = it }
                if (group.notifications.isEmpty()) {
                    groups.remove(id)
                    manager.cancel(tag, id)
                } else {
                    groups[id] = group
                    val latest = group.latest()
                    val sound = update.notificationSoundId.takeIf {
                        latest != null && latest.id > previousId && !latest.isSilent
                    } ?: 0L
                    show(accountId, id, group, client, sound)
                }
            }
            is TdApi.UpdateNotification -> {
                val id = update.notificationGroupId
                val group = groupsByAccount[accountId]?.get(id) ?: return
                if (update.notification.id in group.notifications) {
                    group.notifications[update.notification.id] = update.notification
                    show(accountId, id, group, client)
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

    // TDLib also reports outgoing messages here when they were sent by the scheduler.
    private fun TdApi.Notification.isMessage() =
        type is TdApi.NotificationTypeNewMessage || type is TdApi.NotificationTypeNewPushMessage

    private fun Group.latest(): TdApi.Notification? = notifications.values.maxByOrNull { it.id }

    private fun notificationText(notification: TdApi.Notification): String {
        val generic = R.string.notification_new_message
        return when (val type = notification.type) {
            is TdApi.NotificationTypeNewMessage -> {
                val preview = type.takeIf { it.showPreview }?.message?.content?.toPreviewContent()
                preview?.let { (kind, body) ->
                    body?.takeIf { it.isNotBlank() }?.take(500) ?: context.getString(kind.stringResId)
                } ?: context.getString(generic)
            }
            else -> context.getString(generic)
        }
    }

    @SuppressLint("MissingPermission") // Checked here and caught if Android revokes it before notify().
    private fun show(
        accountId: Int, groupId: Int, group: Group, client: TelegramClient, soundId: Long = 0,
    ) {
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
        val text = notificationText(latest)
        val alert = soundId != 0L
        val readyChannel = if (alert) sounds.readyChannel(accountId, soundId) else null
        val baseChannel = readyChannel ?: NotificationSoundChannels.DEFAULT.takeIf { alert }
        if (baseChannel != null) group.channel = haptics.notificationChannel(baseChannel)
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
        val style = if (group.notifications.size > 1) {
            val inbox = NotificationCompat.InboxStyle()
            val messages = group.notifications.values.sortedByDescending { it.id }
            messages.take(5).forEach { inbox.addLine(notificationText(it)) }
            if (messages.size > 5) {
                inbox.setSummaryText(context.getString(R.string.notifications_group_total, messages.size))
            }
            inbox
        } else NotificationCompat.BigTextStyle().bigText(text)
        val notification = NotificationCompat.Builder(context, group.channel)
            .setSmallIcon(R.drawable.ic_notification_small_icon)
            .setContentTitle(client.chats.notificationTitle(group.chatId) ?: context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(style)
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
            logNotificationDelivery {
                "Android notify returned ageMs=${System.currentTimeMillis() - latest.date * 1_000L} " +
                    "alert=$alert fallback=${alert && readyChannel == null}"
            }
        } catch (_: SecurityException) {
            // The user revoked permission while this notification was being built.
            return
        }
        if (alert && readyChannel == null) prepareSound(accountId, soundId, client)
    }

    private fun notificationTag(accountId: Int) = "telegram_messages_$accountId"

    companion object {
        const val EXTRA_ACCOUNT_ID = "telegram_notification_account_id"
        const val EXTRA_GROUP_ID = "telegram_notification_group_id"
        const val EXTRA_NOTIFICATION_ID = "telegram_notification_id"
    }
}

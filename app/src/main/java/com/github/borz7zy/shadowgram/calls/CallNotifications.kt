/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.calls

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication

internal class CallNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        manager.createNotificationChannels(listOf(
            NotificationChannel(INCOMING_CHANNEL, context.getString(R.string.call_incoming_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(ringtone, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                enableVibration(true)
            },
            NotificationChannel(ACTIVE_CHANNEL, context.getString(R.string.call_active_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            },
        ))
    }

    @SuppressLint("MissingPermission")
    fun ring(screen: TelegramVoiceCalls.Screen) {
        if (!manager.areNotificationsEnabled()) return
        val person = Person.Builder().setName(screen.name).setImportant(true).build()
        val notification = builder(INCOMING_CHANNEL, screen.token, screen.name, R.string.call_incoming)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(
                person, end(screen.token), screenIntent(screen.token, answer = true)
            ))
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(INCOMING_ID, notification)
    }

    fun stopRinging() = manager.cancel(INCOMING_ID)

    fun ongoing(token: Long, name: String, @androidx.annotation.StringRes phase: Int): Notification =
        builder(ACTIVE_CHANNEL, token, name, phase)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_call, context.getString(R.string.call_hang_up), end(token))
            .build()

    private fun builder(channel: String, token: Long, name: String, @androidx.annotation.StringRes phase: Int) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(context.getString(phase))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(screenIntent(token, answer = false))

    private fun screenIntent(token: Long, answer: Boolean) = PendingIntent.getActivity(
        context, token.hashCode() + if (answer) 1 else 0, CallActivity.open(context, token, answer),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun end(token: Long) = PendingIntent.getBroadcast(
        context, token.hashCode(), Intent(context, CallActionReceiver::class.java).putExtra(CallActivity.TOKEN, token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val INCOMING_CHANNEL = "incoming_voice_calls"
        private const val ACTIVE_CHANNEL = "active_voice_calls"
        private const val INCOMING_ID = 1_207_410_025
        const val ONGOING_ID = 1_207_410_026
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getLongExtra(CallActivity.TOKEN, 0L)
        if (token != 0L) (context.applicationContext as ShadowgramApplication).voiceCalls.end(token)
    }
}

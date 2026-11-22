/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationHapticChannelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("audio_haptics_channels", Context.MODE_PRIVATE)
    private val channels = NotificationHapticChannels(context, manager)
    private val profile = "1:75:0"
    private val baseId = "haptics-test-${UUID.randomUUID()}"

    @After
    fun cleanUp() {
        manager.notificationChannels.filter { it.id.startsWith("ah_${baseId}_") }
            .forEach { manager.deleteNotificationChannel(it.id) }
        preferences.edit().remove(baseId).remove("$baseId:vibrates").remove("$baseId:importance")
            .remove("$baseId:prepared_id").remove("$baseId:prepared_config").remove("$baseId:prepared_revision").commit()
    }

    @Test
    fun coldAlertHasSoundAndVibrationBeforePatternIsReady() {
        val base = baseChannel()
        val alert = manager.getNotificationChannel(channels.select(base, profile))
        assertEquals(base.sound, alert.sound)
        assertTrue(alert.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 65), alert.vibrationPattern)
    }

    @Test
    fun emptyWaveformUsesFallbackAndReadyWaveformReplacesIt() {
        val base = baseChannel()
        val fallback = channels.select(base, profile)
        channels.publish(base, profile, "silence", null, longArrayOf(350))
        assertEquals(fallback, channels.select(base, profile))

        val timings = longArrayOf(15, 38, 28, 19)
        channels.publish(base, profile, "prepared", null, timings)
        val alert = manager.getNotificationChannel(channels.select(base, profile))
        assertEquals(base.sound, alert.sound)
        assertArrayEquals(timings, alert.vibrationPattern)
    }

    @Test
    fun freshProcessUsesPreparedWaveformForTheSameSettings() {
        val base = baseChannel()
        val timings = longArrayOf(30, 80, 20, 90)
        channels.publish(base, profile, "prepared", null, timings, sourceRevision = "first")

        val fresh = NotificationHapticChannels(context, manager)
        val readyId = fresh.select(base, profile) { error("A prepared channel must not schedule work") }
        assertTrue(fresh.hasPrepared(base, profile, sourceRevision = "first"))
        assertFalse(fresh.hasPrepared(base, profile, sourceRevision = "changed"))
        assertArrayEquals(timings, manager.getNotificationChannel(readyId).vibrationPattern)
        assertArrayEquals(longArrayOf(0, 65), manager.getNotificationChannel(fresh.select(base, "2:75:0")).vibrationPattern)
        assertEquals(readyId, fresh.select(base, profile))
    }

    @Test
    fun missingPreparedChannelIsEligibleForRefresh() {
        val base = baseChannel()
        channels.publish(base, profile, "prepared", null, longArrayOf(0, 45, 30, 45))
        assertTrue(channels.hasPrepared(base, profile))

        manager.deleteNotificationChannel(channels.select(base, profile))
        assertFalse(channels.hasPrepared(base, profile))
    }

    @Test
    fun blockedAndSilentChannelsAreNotReplaced() {
        val silent = baseChannel().apply { setSound(null, null) }
        assertEquals(baseId, channels.select(silent, profile))
        val blocked = NotificationChannel(baseId, "Blocked", NotificationManager.IMPORTANCE_NONE)
        assertEquals(baseId, channels.select(blocked, profile))
    }

    private fun baseChannel() =
        NotificationChannel(baseId, "Test alert", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null)
            enableVibration(false)
        }
}

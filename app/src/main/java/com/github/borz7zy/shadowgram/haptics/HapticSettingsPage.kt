/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.haptics

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HapticSettingsPage(
    private val activity: FragmentActivity,
    private val screen: PreferenceScreen,
    private val picker: () -> Unit,
) {
    private val app = activity.application as ShadowgramApplication
    private val haptics = app.audioHaptics
    private lateinit var owner: LifecycleOwner
    private var selected: HapticAudioSource? = null
    private var pendingUri: Uri? = null
    private var message = R.string.haptics_pick_summary

    fun bind(owner: LifecycleOwner) {
        this.owner = owner
        row("haptics_import").setOnPreferenceClickListener { picker(); true }
        row("haptics_current").setOnPreferenceClickListener {
            val current = haptics.currentMedia
            launch {
                val source = withContext(Dispatchers.IO) { current?.invoke() }
                if (source == null) {
                    message = R.string.haptics_track_unavailable
                } else {
                    prepare(source)
                }
            }
            true
        }
        row("haptics_cancel").setOnPreferenceClickListener {
            selected?.let { source -> launch { haptics.stopPreparing(source) } }
            true
        }
        row("haptics_sound_settings").setOnPreferenceClickListener {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            activity.startActivity(intent)
            true
        }
        row("haptics_clean").setOnPreferenceClickListener {
            confirm(R.string.haptics_clean, R.string.haptics_clean_summary) {
                withContext(Dispatchers.IO) { haptics.storage.clean(haptics.limit(), true) }
            }
            true
        }
        row("haptics_rebuild").setOnPreferenceClickListener {
            confirm(R.string.haptics_rebuild, R.string.haptics_rebuild_summary) { haptics.rebuild() }
            true
        }
        for (key in listOf(AudioHaptics.PROFILE, AudioHaptics.LIMIT)) {
            screen.findPreference<ListPreference>(key)?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }
        screen.findPreference<SeekBarPreference>(AudioHaptics.OFFSET)?.let { offset ->
            offset.summary = activity.getString(R.string.haptics_offset_ms, offset.value - 200)
            offset.setOnPreferenceChangeListener { preference, value ->
                preference.summary = activity.getString(R.string.haptics_offset_ms, (value as Int) - 200)
                true
            }
        }
        pendingUri?.let { uri -> pendingUri = null; import(uri) }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                haptics.index()
                while (true) {
                    try {
                        refresh()
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        row("haptics_stats").setSummary(R.string.haptics_error)
                    }
                    delay(1_500)
                }
            }
        }
    }

    fun import(uri: Uri) {
        if (!::owner.isInitialized) {
            pendingUri = uri
            return
        }
        launch {
            val source = withContext(Dispatchers.IO) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }
                HapticAudioSource.media(app, uri)
            }
            prepare(source)
        }
    }

    private suspend fun prepare(source: HapticAudioSource) {
        selected = source
        message = if (haptics.request(source)) R.string.haptics_queued else R.string.haptics_track_unavailable
        refresh()
    }

    private suspend fun refresh() {
        val source = selected
        val (database, status, notification) = withContext(Dispatchers.IO) {
            val stats = haptics.storage.stats()
            val file = haptics.databaseFile
            val bytes = file.length() + java.io.File(file.path + "-wal").length() + java.io.File(file.path + "-shm").length()
            val status = source?.let { it.revision(app)?.let { revision -> haptics.storage.status(it.key, revision) } } ?: 0
            Triple(stats to bytes, status, haptics.notificationStatus())
        }
        val (stats, bytes) = database
        if (stats.size == 4) {
            row("haptics_stats").summary = activity.getString(
                R.string.haptics_stats_summary, Formatter.formatShortFileSize(activity, bytes),
                stats[0], stats[1], stats[2], stats[3],
            )
        }
        row("haptics_status").setSummary(statusText(status, message))
        row("haptics_notification_status").setSummary(statusText(notification, R.string.haptics_notification_fallback))
        row("haptics_cancel").isEnabled = status in 1..5
        row("haptics_current").isEnabled = haptics.currentMedia != null
    }

    private fun statusText(status: Int, default: Int): Int = when (status) {
        1 -> R.string.haptics_queued
        in 2..5 -> R.string.haptics_preparing
        6 -> R.string.haptics_ready
        7 -> R.string.haptics_error
        8 -> R.string.haptics_cancelled
        else -> default
    }

    private fun row(key: String): Preference = requireNotNull(screen.findPreference(key))

    private fun confirm(title: Int, summary: Int, action: suspend () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title).setMessage(summary)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                launch { action(); refresh() }
            }
            .show()
    }

    private fun launch(action: suspend () -> Unit) = owner.lifecycleScope.launch {
        try {
            action()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            row("haptics_status").setSummary(R.string.haptics_error)
        }
    }
}

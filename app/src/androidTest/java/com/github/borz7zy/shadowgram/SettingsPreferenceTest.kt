/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.borz7zy.shadowgram.ui.SettingsActionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPreferenceTest {
    @Test fun removedLoadingRowCanStillBeBound() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = PreferenceManager(activity).createPreferenceScreen(activity)
                val loading = SettingsActionPreference(activity).apply {
                    setTitle(R.string.settings_accounts_loading)
                    isPersistent = false
                }
                screen.addPreference(loading)
                val row = LayoutInflater.from(activity)
                    .inflate(R.layout.settings_preference, FrameLayout(activity), false)
                val holder = PreferenceViewHolder.createInstanceForTests(row)

                // The hierarchy changes before the adapter drops its cached row.
                screen.removeAll()
                assertNull(loading.parent)
                loading.onBindViewHolder(holder)

                assertEquals(loading.title, row.findViewById<TextView>(android.R.id.title).text)
            }
        }
    }
}

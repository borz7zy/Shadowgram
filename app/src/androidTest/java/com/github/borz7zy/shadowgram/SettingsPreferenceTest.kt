/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.commitNow
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.borz7zy.shadowgram.testing.MainSectionTestActivity
import com.github.borz7zy.shadowgram.ui.SettingsActionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPreferenceTest {
    @Test fun embeddedSettingsUseTheSettingsLayoutUnderTheMainTheme() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val overview = SettingsActivity.SettingsPaneFragment()
                val detail = SettingsActivity.SettingsPaneFragment().apply {
                    arguments = Bundle().apply {
                        putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, "settings_interface")
                    }
                }
                activity.supportFragmentManager.commitNow {
                    replace(R.id.mainSectionContainer, overview)
                    replace(R.id.chatPane, detail)
                }
                activity.resize(673)

                val overviewPage = overview.childFragmentManager.findFragmentById(R.id.settings)!!
                val detailPage = detail.childFragmentManager.findFragmentById(R.id.settings)!!
                assertNotSame(activity, overviewPage.requireContext())
                assertNotSame(activity, detailPage.requireContext())
                val overviewSearch = overviewPage.requireView().requireViewById<View>(R.id.settings_search_box)
                val detailSearch = detailPage.requireView().requireViewById<View>(R.id.settings_search_box)
                assertEquals(View.VISIBLE, overviewSearch.visibility)
                assertEquals(View.GONE, detailSearch.visibility)
            }
        }
    }

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

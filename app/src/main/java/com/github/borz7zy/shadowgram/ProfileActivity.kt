/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.navigation.fragment.NavHostFragment

/**
 * The profile of a user, group or channel. The navigation graph arguments (chat_id,
 * is_from_chat) come in as the intent extras and are handed to the start destination as is.
 */
class ProfileActivity : AppCompatActivity(R.layout.activity_profile) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Restored with its arguments otherwise
        if (savedInstanceState == null) {
            // The shared avatar waits for the profile to show it, see ProfileFragment
            supportPostponeEnterTransition()

            val navHostFragment = NavHostFragment.create(R.navigation.activity_profile, intent.extras)
            supportFragmentManager.commit {
                replace(R.id.navHostFragment, navHostFragment)
                setPrimaryNavigationFragment(navHostFragment)
            }
        }
    }
}

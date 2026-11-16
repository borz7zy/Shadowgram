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
 * An open chat or forum topic. The navigation graph arguments (chat_id, topic_id) come in as
 * the intent extras and are handed to the start destination as is.
 */
class ChatActivity : AppCompatActivity(R.layout.activity_chat) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Restored with its arguments otherwise
        if (savedInstanceState == null) {
            val navHostFragment = NavHostFragment.create(R.navigation.activity_chat, intent.extras)
            supportFragmentManager.commit {
                replace(R.id.navHostFragment, navHostFragment)
                setPrimaryNavigationFragment(navHostFragment)
            }
        }
    }
}

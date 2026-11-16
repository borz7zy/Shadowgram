/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.fragment.app.commit
import androidx.navigation.fragment.NavHostFragment
import com.github.borz7zy.shadowgram.fragments.ChatPickerFragment

/**
 * "Share" from another app: files or text, sent to the chat picked in the chat picker, which
 * then opens that chat.
 */
class ShareActivity : AppCompatActivity(R.layout.activity_chat) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Restored with its arguments otherwise
        if (savedInstanceState == null) {
            val navHostFragment = NavHostFragment.create(
                R.navigation.fragment_chat_picker,
                ChatPickerFragment.createShareBundle(
                    uris().map(Uri::toString),
                    intent.getStringExtra(Intent.EXTRA_TEXT),
                ),
            )
            supportFragmentManager.commit {
                replace(R.id.navHostFragment, navHostFragment)
                setPrimaryNavigationFragment(navHostFragment)
            }
        }
    }

    /** One file for ACTION_SEND, several for ACTION_SEND_MULTIPLE, none for text. */
    private fun uris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
            intent, Intent.EXTRA_STREAM, Uri::class.java
        ).orEmpty()

        else -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        )
    }
}

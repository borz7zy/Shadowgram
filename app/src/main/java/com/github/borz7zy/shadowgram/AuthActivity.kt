/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Sign in to a Telegram account, either the first one or an additional one.
 */
class AuthActivity : AppCompatActivity(R.layout.activity_auth) {
    // Views
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

    // Arguments, set by the navigation graphs and the settings
    private val isAddingAccount
        get() = intent.getBooleanExtra(ARG_ADD_ACCOUNT, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        setSupportActionBar(toolbar)

        // The first sign in has nowhere to go back to
        if (isAddingAccount) {
            setTitle(R.string.add_account)

            supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                setDisplayShowHomeEnabled(true)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    companion object {
        /**
         * Add an account to the existing ones instead of signing in for the first time.
         * Type: [Boolean]
         */
        private const val ARG_ADD_ACCOUNT = "add_account"
    }
}

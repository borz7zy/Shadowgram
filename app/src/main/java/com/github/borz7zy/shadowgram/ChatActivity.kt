/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.borz7zy.shadowgram.notifications.TelegramNotifications
import kotlinx.coroutines.launch

/**
 * Compatibility entry point for chat navigation from profiles and the share picker. Keeping
 * one MainActivity lets the very same chat move between full width and split panes on a fold.
 */
class ChatActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val accountId = intent.getIntExtra(TelegramNotifications.EXTRA_ACCOUNT_ID, -1)
        if (accountId < 0) {
            openChat(restart = false)
            return
        }
        lifecycleScope.launch {
            val app = application as ShadowgramApplication
            val switched = app.accountsRepository.activateAccount(accountId)
            if (switched == null) {
                startActivity(Intent(this@ChatActivity, LauncherActivity::class.java))
                finish()
            } else {
                openChat(restart = switched)
            }
        }
    }

    private fun openChat(restart: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            intent.extras?.let(::putExtras)
            flags = if (restart) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    else Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}

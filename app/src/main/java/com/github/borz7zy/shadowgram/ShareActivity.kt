/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.navigation.fragment.NavHostFragment
import com.github.borz7zy.shadowgram.fragments.ChatPickerFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share" from another app: files or text, sent to the chat picked in the chat picker, which
 * then opens that chat.
 */
class ShareActivity : AppCompatActivity(R.layout.activity_chat) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Rotation can happen before the incoming files have been checked.
        if (supportFragmentManager.findFragmentById(R.id.navHostFragment) == null) {
            val all = uris()
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            lifecycleScope.launch {
                val uris = withContext(Dispatchers.IO) { all.filter(::isForeign).map(Uri::toString) }
                lifecycle.withResumed {
                    if (uris.size < all.size) {
                        Toast.makeText(this@ShareActivity, R.string.share_file_rejected, Toast.LENGTH_SHORT).show()
                    }
                    if (uris.isEmpty() && text.isNullOrBlank()) {
                        finish()
                        return@withResumed
                    }
                    val navHostFragment = NavHostFragment.create(
                        R.navigation.fragment_chat_picker,
                        ChatPickerFragment.createShareBundle(uris, text),
                    )
                    supportFragmentManager.commit {
                        replace(R.id.navHostFragment, navHostFragment)
                        setPrimaryNavigationFragment(navHostFragment)
                    }
                }
            }
        }
    }

    /**
     * Whether [uri] is another app's content. Ours is read with this app's rights, so a share
     * pointing at our own provider (or a file:// path) would send the account's private files.
     * A provider we can't resolve isn't ours: our own always resolve.
     */
    private fun isForeign(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        return uri.scheme == ContentResolver.SCHEME_CONTENT &&
                packageManager.resolveContentProvider(authority, 0)?.packageName != packageName
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

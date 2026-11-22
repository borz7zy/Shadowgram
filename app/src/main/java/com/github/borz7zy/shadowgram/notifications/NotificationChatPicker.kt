/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

internal class NotificationChatPicker(
    private val activity: FragmentActivity,
    private val owner: LifecycleOwner,
    private val client: TelegramClient,
    private val scope: TdApi.NotificationSettingsScope?,
) {
    fun show(select: (Long) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val input = TextInputEditText(activity).apply { isSingleLine = true }
        val list = ListView(activity)
        val status = TextView(activity).apply { setPadding((12 * density).toInt()) }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt())
            addView(TextInputLayout(activity).apply {
                hint = activity.getString(R.string.notifications_search_chats)
                addView(input)
            })
            addView(status)
            addView(list, ViewGroup.LayoutParams.MATCH_PARENT, (240 * density).toInt())
        }
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.notifications_add_exception)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        var matches = emptyList<TdApi.Chat>()
        var me: Long? = null
        var search: Job? = null
        fun load(query: String) {
            search?.cancel()
            status.setText(R.string.notifications_loading)
            adapter.clear()
            matches = emptyList()
            search = owner.lifecycleScope.launch {
                if (query.isNotEmpty()) delay(250)
                if (me == null) me = client.send(TdApi.GetMe()).getOrNull()?.id
                val ids = if (query.isEmpty()) {
                    val main = client.send(TdApi.GetChats(TdApi.ChatListMain(), 50)).getOrNull()?.chatIds.orEmpty()
                    val archive = client.send(TdApi.GetChats(TdApi.ChatListArchive(), 50)).getOrNull()?.chatIds.orEmpty()
                    main + archive
                } else {
                    val local = client.send(TdApi.SearchChats(query, null, 50)).getOrNull()?.chatIds.orEmpty()
                    val remote = withTimeoutOrNull(2_000) {
                        client.send(TdApi.SearchChatsOnServer(query, null, 50)).getOrNull()?.chatIds
                    }
                    local + remote.orEmpty()
                }
                matches = ids.distinct().mapNotNull { id ->
                    client.send(TdApi.GetChat(id)).getOrNull()?.takeIf(::matchesScope)
                        ?.takeUnless { (it.type as? TdApi.ChatTypePrivate)?.userId == me && me != null }
                }
                adapter.addAll(matches.map { it.title })
                status.setText(R.string.notifications_no_chats)
                status.isVisible = matches.isEmpty()
            }
            status.isVisible = true
        }
        list.setOnItemClickListener { _, _, position, _ ->
            matches.getOrNull(position)?.let {
                dialog.dismiss()
                select(it.id)
            }
        }
        input.doAfterTextChanged { load(it?.toString().orEmpty().trim()) }
        val closeOnDestroy = owner.lifecycleScope.launch {
            try {
                awaitCancellation()
            } finally {
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            search?.cancel()
            closeOnDestroy.cancel()
        }
        dialog.show()
        load("")
    }

    private fun matchesScope(chat: TdApi.Chat): Boolean {
        val type = chat.type
        return when (scope) {
            is TdApi.NotificationSettingsScopePrivateChats -> type is TdApi.ChatTypePrivate || type is TdApi.ChatTypeSecret
            is TdApi.NotificationSettingsScopeGroupChats ->
                type is TdApi.ChatTypeBasicGroup || type is TdApi.ChatTypeSupergroup && !type.isChannel
            is TdApi.NotificationSettingsScopeChannelChats -> type is TdApi.ChatTypeSupergroup && type.isChannel
            else -> true
        }
    }

    private fun LongArray?.orEmpty(): LongArray = this ?: LongArray(0)
}

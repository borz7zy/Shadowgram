/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serializes reads and writes of UI preferences outside the view lifecycle. */
internal object UiPreferences {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    suspend fun <T> read(
        context: Context, name: String? = null, read: (SharedPreferences) -> T,
    ): T = withContext(dispatcher) { read(preferences(context, name)) }

    fun write(context: Context, name: String? = null, write: (SharedPreferences) -> Unit) {
        val application = context.applicationContext
        scope.launch { write(preferences(application, name)) }
    }

    private fun preferences(context: Context, name: String?) = if (name == null) {
        PreferenceManager.getDefaultSharedPreferences(context)
    } else {
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}

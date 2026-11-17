/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import kotlin.reflect.cast

/**
 * Loads TDLib, then opens the home page when the active account is signed in, the sign in page
 * otherwise.
 */
class LauncherFragment : Fragment(R.layout.fragment_launcher) {
    // Views
    private val errorTextView by getViewProperty<TextView>(R.id.errorTextView)
    private val circularProgressIndicator by getViewProperty<CircularProgressIndicator>(R.id.circularProgressIndicator)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Only shown when loading takes long enough to notice
        circularProgressIndicator.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val client = ShadowgramApplication::class.cast(requireActivity().application)
                .accountsRepository
                .activeClient()
            // TDLib refusing the parameters never changes the state, show why instead of waiting
            val (authorizationState, error) =
                combine(client.authorizationState, client.initError, ::Pair).first { (state, error) ->
                    error != null || state != null && state !is TdApi.AuthorizationStateWaitTdlibParameters
                }
            if (error != null) {
                circularProgressIndicator.hide()
                errorTextView.text = getString(R.string.launcher_init_failed, error.message)
                errorTextView.isVisible = true
                return@launch
            }

            findNavController().navigateSafe(
                when (authorizationState) {
                    is TdApi.AuthorizationStateReady -> R.id.action_launcherFragment_to_mainActivity
                    else -> R.id.action_launcherFragment_to_authActivity
                }
            )
        }
    }
}

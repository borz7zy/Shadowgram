/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.google.android.material.progressindicator.CircularProgressIndicator
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
    private val circularProgressIndicator by getViewProperty<CircularProgressIndicator>(R.id.circularProgressIndicator)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Only shown when loading takes long enough to notice
        circularProgressIndicator.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val authorizationState = ShadowgramApplication::class.cast(requireActivity().application)
                .accountsRepository
                .activeClient()
                .authorizationState
                .first { it != null && it !is TdApi.AuthorizationStateWaitTdlibParameters }

            findNavController().navigateSafe(
                when (authorizationState) {
                    is TdApi.AuthorizationStateReady -> R.id.action_launcherFragment_to_mainActivity
                    else -> R.id.action_launcherFragment_to_authActivity
                }
            )
        }
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.navigation.fragment.NavHostFragment
import com.github.borz7zy.shadowgram.MainActivity
import com.github.borz7zy.shadowgram.R

/** Installs the pane's fallback before the chat's navigation, menus and keyboard callbacks. */
open class ChatPaneNavHostFragment : NavHostFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Keep the root chat alive while its pane animates out. Popping the last
                // destination first removes the content before closePane can animate it.
                if (navController.previousBackStackEntry != null) navController.popBackStack()
                else closePane()
            }
        })
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            initializeGraph()
        }
    }

    protected open fun initializeGraph() {
        navController.setGraph(R.navigation.activity_chat, arguments)
    }

    protected open fun closePane() {
        (requireActivity() as MainActivity).closeChat()
    }
}

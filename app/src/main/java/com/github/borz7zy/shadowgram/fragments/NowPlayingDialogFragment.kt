/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.NavHostFragment
import com.github.borz7zy.shadowgram.R

/** A separate navigation stack over the screen that opened the player. */
class NowPlayingDialogFragment : DialogFragment(R.layout.fragment_now_playing_dialog) {
    override fun getTheme() = R.style.Theme_Shadowgram_NowPlaying

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as ComponentDialog).apply {
            window?.let {
                val secure = WindowManager.LayoutParams.FLAG_SECURE
                if (requireActivity().window.attributes.flags and secure != 0) it.addFlags(secure)
                WindowCompat.setDecorFitsSystemWindows(it, false)
            }
            onBackPressedDispatcher.addCallback(this@NowPlayingDialogFragment) {
                val host = childFragmentManager.findFragmentById(R.id.nowPlayingNavHost) as? NavHostFragment
                val controller = host?.navController
                if (controller?.previousBackStackEntry == null || !controller.popBackStack()) {
                    dismiss()
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    companion object {
        private const val TAG = "now_playing_dialog"

        fun open(activity: FragmentActivity) {
            val manager = activity.supportFragmentManager
            if (!manager.isStateSaved && manager.findFragmentByTag(TAG) == null) {
                NowPlayingDialogFragment().showNow(manager, TAG)
            }
        }

        fun close(activity: FragmentActivity) {
            val player = activity.supportFragmentManager.findFragmentByTag(TAG) as? NowPlayingDialogFragment
            player?.dismiss()
        }
    }
}

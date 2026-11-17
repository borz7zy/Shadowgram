/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import com.github.borz7zy.shadowgram.R

/** Top-level tabs share one viewport, with no horizontal scroll offset to retain on resize. */
internal class MainSectionHost(
    private val fragmentManager: FragmentManager,
    @param:IdRes private val containerId: Int,
) {
    fun show(tag: String, animate: Boolean = true, create: () -> Fragment): Boolean {
        if (fragmentManager.isStateSaved) return false
        val selected = fragmentManager.findFragmentByTag(tag) ?: create()
        fragmentManager.commitNow {
            setReorderingAllowed(true)
            if (animate && fragmentManager.primaryNavigationFragment != null &&
                fragmentManager.primaryNavigationFragment != selected
            ) {
                setCustomAnimations(R.animator.pane_content_enter, R.animator.pane_content_exit)
            }
            fragmentManager.fragments.filter { it.id == containerId }.forEach { fragment ->
                if (fragment == selected) {
                    show(fragment)
                } else {
                    hide(fragment)
                    setMaxLifecycle(fragment, Lifecycle.State.STARTED)
                }
            }
            if (!selected.isAdded) add(containerId, selected, tag)
            setMaxLifecycle(selected, Lifecycle.State.RESUMED)
            setPrimaryNavigationFragment(selected)
        }
        return true
    }
}

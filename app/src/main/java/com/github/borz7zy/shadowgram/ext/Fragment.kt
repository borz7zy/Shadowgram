/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ext

import android.view.View
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlin.properties.ReadOnlyProperty

inline fun <reified T : View?> getViewProperty(@IdRes viewId: Int) =
    ReadOnlyProperty<Fragment, T> { thisRef, _ ->
        thisRef.requireView().findViewById<T>(viewId)
    }

/** Toolbar back is local to this pane, independently of activity callback registration order. */
fun Fragment.navigateBackInPane() {
    if (!findNavController().popBackStack()) {
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }
}

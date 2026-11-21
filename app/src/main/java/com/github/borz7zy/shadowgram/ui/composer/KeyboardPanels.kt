/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.borz7zy.shadowgram.ui.UiPreferences
import kotlinx.coroutines.launch

/**
 * Panels that take the keyboard's place under the composer, like VK's sticker keyboard: a panel
 * is as tall as the keyboard last was, so switching between them doesn't move the chat.
 *
 * While a panel is open the composer stays above the navigation bar and the keyboard, when it
 * slides in, covers the panel; the panel goes once the keyboard is fully in. Owns the bottom
 * insets of [container].
 *
 * @param container The composer and [panel] under it, padded above the keyboard
 * @param panels The views of each panel, children of [panel]
 * @param onChanged The open panel changed, null for none
 */
class KeyboardPanels(
    private val fragment: Fragment,
    private val container: View,
    private val panel: ViewGroup,
    private val panels: Map<Panel, View>,
    private val editText: EditText,
    private val onChanged: (Panel?) -> Unit,
) {
    enum class Panel { STICKERS, GALLERY, ATTACH }

    private val context = fragment.requireContext().applicationContext
    private val keyboardHeightKey = "height_${fragment.resources.configuration.orientation}"
    private var keyboardHeight: Int? = null

    private val containerPaddingBottom = container.paddingBottom
    private val minHeight = dp(MIN_HEIGHT_DP)

    /** The keyboard is sliding, the composer follows it frame by frame. */
    private var isImeAnimating = false

    var current: Panel? = null
        private set

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    init {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val saved = UiPreferences.read(context, PREFERENCES) { it.getInt(keyboardHeightKey, 0) }
            if (keyboardHeight == null) {
                keyboardHeight = saved
                if (current != null) updatePanelHeight(ViewCompat.getRootWindowInsets(container))
            }
        }
        fragment.requireActivity().onBackPressedDispatcher
            .addCallback(fragment.viewLifecycleOwner, backCallback)

        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            // While the keyboard slides the animation callback moves the composer
            if (!isImeAnimating) {
                apply(insets)
            }
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            container,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        isImeAnimating = true
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    if (isImeAnimating) {
                        apply(insets)
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        isImeAnimating = false
                        ViewCompat.getRootWindowInsets(container)?.let(::apply)
                    }
                }
            },
        )
    }

    /** Open [target], or go back to the keyboard when it's already open. */
    fun toggle(target: Panel) {
        when (current) {
            target -> showKeyboard()
            else -> show(target)
        }
    }

    fun show(target: Panel) {
        val insets = ViewCompat.getRootWindowInsets(container)
        updatePanelHeight(insets)

        // Typing goes back to the message, not a search field of another panel
        panel.findFocus()?.clearFocus()
        panels.forEach { (key, view) -> view.isVisible = key == target }
        panel.isVisible = true
        current = target
        backCallback.isEnabled = true
        onChanged(target)

        // The keyboard slides out over the panel, the composer doesn't move
        WindowInsetsControllerCompat(fragment.requireActivity().window, editText)
            .hide(WindowInsetsCompat.Type.ime())
        insets?.let(::apply)
    }

    private fun updatePanelHeight(insets: WindowInsetsCompat?) {
        val navigationBar = insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        panel.updateLayoutParams {
            height = ((keyboardHeight ?: 0) - navigationBar)
                .coerceAtLeast(minHeight)
                .coerceAtMost(container.rootView.height * 2 / 3)
        }
    }

    /** Bring the keyboard back, the panel goes once it's in. */
    fun showKeyboard() {
        editText.requestFocus()
        WindowInsetsControllerCompat(fragment.requireActivity().window, editText)
            .show(WindowInsetsCompat.Type.ime())
    }

    fun hide() {
        if (current == null) {
            return
        }
        panel.isVisible = false
        current = null
        backCallback.isEnabled = false
        onChanged(null)
        ViewCompat.getRootWindowInsets(container)?.let(::apply)
    }

    private fun apply(insets: WindowInsetsCompat) {
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        // Typing in the panel (the GIF search): the panel stays, over the keyboard
        val isTypingInPanel = panel.findFocus() != null

        if (isImeVisible && !isImeAnimating && ime.bottom > minHeight && !isTypingInPanel) {
            if (keyboardHeight != ime.bottom) {
                keyboardHeight = ime.bottom
                val height = ime.bottom
                val key = keyboardHeightKey
                UiPreferences.write(context, PREFERENCES) { preferences ->
                    preferences.edit(commit = true) { putInt(key, height) }
                }
            }
            // The keyboard is in: it took the panel's place
            if (current != null) {
                panel.isVisible = false
                current = null
                backCallback.isEnabled = false
                onChanged(null)
            }
        }

        container.updatePadding(
            bottom = containerPaddingBottom + when {
                current == null || isTypingInPanel -> Insets.max(ime, systemBars).bottom
                else -> systemBars.bottom
            }
        )
    }

    private fun dp(value: Int) = (value * fragment.resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFERENCES = "keyboard_panels"

        /** VK's minimum, also used before the keyboard was ever seen. */
        const val MIN_HEIGHT_DP = 260
    }
}

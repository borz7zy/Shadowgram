/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import android.graphics.Rect
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView

/**
 * Keep the dialog's last image until the restored source has submitted a frame of its own.
 * Returns a cancellation callback for the dialog's view destruction.
 */
fun finishOverlayReturn(source: View?, finish: () -> Unit): () -> Unit {
    if (source == null || !source.isAttachedToWindow || !source.isShown || source.windowVisibility != View.VISIBLE) {
        finish()
        return {}
    }
    var finished = false
    val observer = source.viewTreeObserver
    val hardware = source.isHardwareAccelerated
    lateinit var detached: View.OnAttachStateChangeListener
    lateinit var committed: Runnable
    fun cleanup() {
        source.removeOnAttachStateChangeListener(detached)
        if (observer.isAlive && hardware) {
            observer.unregisterFrameCommitCallback(committed)
        }
    }
    fun complete() {
        if (finished) return
        finished = true
        cleanup()
        finish()
    }
    committed = Runnable { complete() }
    detached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = complete()
    }
    source.addOnAttachStateChangeListener(detached)
    if (hardware) {
        observer.registerFrameCommitCallback(committed)
    } else {
        // Software rendering has no frame-commit callback. Wait until after its next draw.
        source.doOnPreDraw { source.post { complete() } }
    }
    source.invalidate()
    return {
        if (!finished) {
            finished = true
            cleanup()
        }
    }
}

/** The list's viewport, rather than the full bounds of a partly hidden thumbnail. */
fun View.mediaTransitionViewport(): Rect {
    var ancestor = parent as? View
    while (ancestor != null) {
        if (ancestor is RecyclerView) return ancestor.visibleBoundsOnScreen()
        ancestor = ancestor.parent as? View
    }
    return rootView.visibleBoundsOnScreen()
}

fun View.visibleBoundsOnScreen() = Rect().also { if (!getGlobalVisibleRect(it)) it.setEmpty() }

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextSwitcher
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.ui.views.CustomEmojiView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.drinkless.tdlib.TdApi

/** The home toolbar's title and the active account's emoji status. */
class AppTitleCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val labels = TextSwitcher(context).apply {
        val offset = toPx(4).toFloat()
        setAnimateFirstView(false)
        setFactory {
            AppCompatTextView(context).apply {
                setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleLarge)
                setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSurface))
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        inAnimation = labelAnimation(0f, 1f, offset, 0f)
        outAnimation = labelAnimation(1f, 0f, 0f, -offset)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private val emoji = CustomEmojiView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val spinner = CircularProgressIndicator(context).apply {
        trackThickness = toPx(2)
        indicatorSize = toPx(24)
        indicatorInset = 0
        isIndeterminate = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isVisible = false
    }
    private val accessory = FrameLayout(context).apply {
        val emojiSize = toPx(18)
        val spinnerSize = toPx(24)
        addView(emoji, FrameLayout.LayoutParams(emojiSize, emojiSize, Gravity.CENTER))
        addView(spinner, FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER))
        isVisible = false
    }
    private var titleRes = 0
    private var emojiId = 0L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(labels, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f))
        addView(accessory, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = toPx(8)
        })
    }

    fun bind(connection: TdApi.ConnectionState?, emojiStatusId: Long) {
        val label = when (connection) {
            is TdApi.ConnectionStateReady -> R.string.app_name
            is TdApi.ConnectionStateUpdating -> R.string.connection_updating
            is TdApi.ConnectionStateWaitingForNetwork -> R.string.connection_waiting
            else -> R.string.connection_connecting
        }
        if (titleRes != label) {
            val text = context.getText(label)
            if (titleRes != 0 && isLaidOut && isShown && ValueAnimator.areAnimatorsEnabled()) {
                labels.setText(text)
            } else {
                labels.currentView.clearAnimation()
                labels.setCurrentText(text)
            }
            titleRes = label
        }

        if (emojiId != emojiStatusId) {
            emojiId = emojiStatusId
            if (emojiId == 0L) emoji.clear() else emoji.setCustomEmoji(emojiId)
        }
        val showSpinner = label != R.string.app_name
        spinner.isVisible = showSpinner
        accessory.isVisible = showSpinner || emojiId != 0L
    }

    private fun labelAnimation(fromAlpha: Float, toAlpha: Float, fromY: Float, toY: Float) =
        AnimationSet(true).apply {
            addAnimation(AlphaAnimation(fromAlpha, toAlpha))
            addAnimation(TranslateAnimation(0f, 0f, fromY, toY))
            duration = 180L
            interpolator = DecelerateInterpolator()
        }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.telegram.EmojiStickers
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * A custom emoji or an emoji reaction, with the decoder its file needs: WebP through Coil
 * (RustCore's decoder), TGS and WebM played by [StickerView] (RustCore's rlottie and libvpx).
 * Gone while there's nothing to show.
 */
class CustomEmojiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val imageView = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val stickerView = StickerView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** What's shown or loading, to skip rebinding the same. */
    private var key: Any? = null
    private var job: Job? = null

    init {
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(stickerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isVisible = false
    }

    /**
     * Show the emoji status [id], or the Premium star for [isPremium] users without one.
     */
    fun setEmojiStatus(id: Long, isPremium: Boolean) = when {
        id != 0L -> setCustomEmoji(id)
        isPremium -> show("premium") {
            stickerView.setSticker(null, 0)
            stickerView.isVisible = false
            imageView.dispose()
            imageView.setImageResource(R.drawable.ic_premium)
            imageView.isVisible = true
        }

        else -> clear()
    }

    fun setCustomEmoji(id: Long) = load(id) { EmojiStickers.customEmoji(it, id) }

    fun setEmojiReaction(emoji: String) = load(emoji) { EmojiStickers.emojiReaction(it, emoji) }

    fun clear() {
        key = null
        job?.cancel()
        stickerView.setSticker(null, 0)
        imageView.dispose()
        imageView.setImageDrawable(null)
        isVisible = false
    }

    private inline fun show(key: Any, block: () -> Unit) {
        if (this.key == key) {
            return
        }
        job?.cancel()
        this.key = key
        isVisible = true
        block()
    }

    private fun load(key: Any, file: suspend (TelegramClient) -> EmojiStickers.File?) = show(key) {
        stickerView.setSticker(null, 0)
        imageView.dispose()
        imageView.setImageDrawable(null)

        // Bound before it's attached in lists: not the view tree's scope
        job = scope.launch {
            val client = (context.applicationContext as ShadowgramApplication)
                .accountsRepository.activeClient()
            val sticker = file(client) ?: return@launch
            stickerView.isVisible = sticker.isAnimated
            imageView.isVisible = !sticker.isAnimated
            when (sticker.isAnimated) {
                true -> stickerView.setSticker(File(sticker.path), sizePx())
                false -> imageView.load(File(sticker.path))
            }
        }
    }

    private companion object {
        /** Loads are cancelled on rebind, they're short. */
        val scope = MainScope()
    }

    /** Frames are drawn this big. */
    private fun sizePx() = maxOf(layoutParams?.height ?: 0, height).takeIf { it > 0 }
        ?: resources.getDimensionPixelSize(R.dimen.custom_emoji_size)
}

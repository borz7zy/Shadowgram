/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import com.github.borz7zy.rustcore.RustMedia
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.telegram.EmojiStickers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * A custom emoji in text, in place of the plain emoji it stands for. Sized like the emoji of
 * [Emoji.EmojiSpan], drawn by the [CustomEmojiDrawable] of its id and size, shared by every text
 * showing it. Call [attachCustomEmoji] once the text is set so it animates there.
 */
class CustomEmojiSpan(
    val customEmojiId: Long,
    fontMetrics: Paint.FontMetricsInt,
) : ReplacementSpan() {
    private val size = (abs(fontMetrics.ascent) + abs(fontMetrics.descent)).coerceAtLeast(1)

    val drawable get() = CustomEmojiDrawable.of(customEmojiId, size)

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return size
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val emojiTop = (y + paint.fontMetricsInt.descent - size)
        drawable.setBounds(x.toInt(), emojiTop, x.toInt() + size, emojiTop + size)
        drawable.draw(canvas)
    }
}

/**
 * A custom emoji, its file decoded by RustCore: WebP once, TGS and WebM frame by frame while a
 * text showing it is on screen.
 */
class CustomEmojiDrawable private constructor(
    private val customEmojiId: Long,
    private val size: Int,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** The texts showing it, invalidated on every frame. */
    private val hosts = WeakHashMap<View, Unit>()

    // Main thread
    private var current: Bitmap? = null
    private var isLoading = false
    private var isRunning = false
    private var file: EmojiStickers.File? = null

    // Decoder thread
    private var sticker: RustMedia.AnimatedSticker? = null
    private var bitmaps = arrayOf<Bitmap?>(null, null)

    fun attach(host: View, context: Context) {
        hosts[host] = Unit
        val file = file
        when {
            file == null -> load(context)
            file.isAnimated && !isRunning -> play()
        }
    }

    private fun load(context: Context) {
        if (isLoading) {
            return
        }
        isLoading = true
        scope.launch {
            val client = (context.applicationContext as ShadowgramApplication)
                .accountsRepository.activeClient()
            val file = EmojiStickers.customEmoji(client, customEmojiId)
            isLoading = false
            this@CustomEmojiDrawable.file = file ?: return@launch
            when (file.isAnimated) {
                true -> play()
                false -> decoder.post {
                    val bitmap = RustMedia.decode(File(file.path), size, size)
                    main.post {
                        current = bitmap
                        invalidateHosts()
                    }
                }
            }
        }
    }

    private fun play() {
        val file = file ?: return
        isRunning = true
        decoder.post {
            if (sticker == null) {
                val sticker = RustMedia.AnimatedSticker.open(File(file.path), size, size) ?: return@post
                this.sticker = sticker
                bitmaps = arrayOf(sticker.newBitmap(), sticker.newBitmap())
            }
            frame(0, 0)
        }
    }

    // Decoder thread
    private fun frame(index: Int, slot: Int) {
        val sticker = sticker ?: return
        val bitmap = bitmaps[slot] ?: return
        val start = SystemClock.uptimeMillis()
        if (!sticker.frame(index, bitmap)) {
            return
        }
        val frameMs = when (sticker.frameCount) {
            0 -> DEFAULT_FRAME_MS
            else -> (sticker.durationMs / sticker.frameCount).toLong().coerceAtLeast(1)
        }
        main.post {
            current = bitmap
            // Nothing shows it any more: pause until a text attaches it again
            if (!invalidateHosts()) {
                isRunning = false
                return@post
            }
            val delay = (frameMs - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0)
            main.postDelayed({
                decoder.post { frame((index + 1) % sticker.frameCount.coerceAtLeast(1), 1 - slot) }
            }, delay)
        }
    }

    /** Redraw the texts still showing it on screen, false when there are none. */
    private fun invalidateHosts(): Boolean {
        var isShown = false
        hosts.keys.toList().forEach { host ->
            val isShowing = host.isAttachedToWindow && host.windowVisibility == View.VISIBLE
                    && ((host as? TextView)?.text as? Spanned)
                ?.getSpans(0, host.text.length, CustomEmojiSpan::class.java)
                ?.any { it.drawable === this } == true
            when {
                isShowing -> {
                    isShown = true
                    host.invalidate()
                }

                !host.isAttachedToWindow || (host as? TextView)?.text !is Spanned -> hosts.remove(host)
            }
        }
        return isShown
    }

    override fun draw(canvas: Canvas) {
        current?.let { canvas.drawBitmap(it, null, bounds, paint) }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val DEFAULT_FRAME_MS = 33L

        private val scope = MainScope()
        private val main = Handler(Looper.getMainLooper())

        // ponytail: one thread for every custom emoji on screen, like StickerView's; a pool if
        // screens full of them stutter
        private val decoder by lazy {
            Handler(HandlerThread("CustomEmojiDecoder").apply { start() }.looper)
        }

        // Main thread
        private val drawables = HashMap<Pair<Long, Int>, WeakReference<CustomEmojiDrawable>>()

        fun of(customEmojiId: Long, size: Int): CustomEmojiDrawable {
            val key = customEmojiId to size
            drawables[key]?.get()?.let { return it }
            return CustomEmojiDrawable(customEmojiId, size).also {
                drawables[key] = WeakReference(it)
            }
        }
    }
}

/** A custom emoji in a text, offsets in UTF-16 code units like TDLib's. */
data class CustomEmojiRange(val offset: Int, val length: Int, val customEmojiId: Long)

/**
 * Put [ranges] of custom emoji over [text], in place of the plain emoji drawn there.
 */
fun Spannable.setCustomEmoji(ranges: List<CustomEmojiRange>, fontMetrics: Paint.FontMetricsInt, shift: Int = 0) {
    ranges.forEach { range ->
        val start = range.offset + shift
        val end = start + range.length
        if (start < 0 || end > length) {
            return@forEach
        }
        getSpans(start, end, Emoji.EmojiSpan::class.java).forEach(::removeSpan)
        setSpan(CustomEmojiSpan(range.customEmojiId, fontMetrics), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** The custom emoji spans of an edited text, as ranges. */
fun Spanned.customEmojiRanges() = getSpans(0, length, CustomEmojiSpan::class.java).map {
    val start = getSpanStart(it)
    CustomEmojiRange(start, getSpanEnd(it) - start, it.customEmojiId)
}

/** Drop the plain emoji drawn under custom ones, emoji parsing puts them back on every edit. */
fun Spannable.removeEmojiUnderCustom() {
    getSpans(0, length, CustomEmojiSpan::class.java).forEach { custom ->
        getSpans(getSpanStart(custom), getSpanEnd(custom), Emoji.EmojiSpan::class.java)
            .forEach(::removeSpan)
    }
}

/** Animate the custom emoji of this text's text here. */
fun TextView.attachCustomEmoji() {
    val text = text as? Spanned ?: return
    text.getSpans(0, text.length, CustomEmojiSpan::class.java).forEach {
        it.drawable.attach(this, context)
    }
}

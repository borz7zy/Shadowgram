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
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import com.github.borz7zy.rustcore.RustMedia
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.background.AsyncTask
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.telegram.EmojiStickers
import com.github.borz7zy.shadowgram.ui.views.AnimatedStickerPlayback
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.RejectedExecutionException
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

    // Held here: the shared ones are kept weakly, a drawable still loading could be collected
    // and the text would get a new one nobody loads
    val drawable = CustomEmojiDrawable.of(customEmojiId, size)

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
        // Drawn means on screen: start it if nothing did yet, or again after it paused
        drawable.resume()
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
    private val hosts = WeakHashMap<TextView, CustomEmojiSpan>()
    private val visibleBounds = Rect()

    // Main thread
    private var current: Bitmap? = null
    private var isLoading = false
    private var resumePosted = false

    /** TDLib had no file for it: not asked again on every draw. */
    private var isFailed = false
    private var file: EmojiStickers.File? = null
    private var playback: AnimatedStickerPlayback? = null

    fun attach(host: TextView, span: CustomEmojiSpan) {
        val context = host.context
        appContext = context.applicationContext
        hosts[host] = span
        showOrLoad(context)
    }

    /** Load it if it isn't, play it again if it paused; it's being drawn. */
    fun resume() {
        if (isLoading || isFailed || resumePosted) return
        val file = file
        if (file != null && (if (file.isAnimated) playback != null else current != null)) return
        val context = appContext ?: return
        resumePosted = true
        main.post {
            resumePosted = false
            showOrLoad(context)
        }
    }

    private fun showOrLoad(context: Context) {
        if (isLoading) return
        val file = file
        when {
            file == null -> load(context)
            file.isAnimated -> play()
            current == null && !isFailed -> decodeStatic(file.path)
        }
    }

    private fun load(context: Context) {
        isLoading = true
        scope.launch {
            val client = (context.applicationContext as ShadowgramApplication)
                .accountsRepository.activeClient()
            val file = EmojiStickers.customEmoji(client, customEmojiId)
            isLoading = false
            if (file == null) {
                isFailed = true
                return@launch
            }
            this@CustomEmojiDrawable.file = file
            isFailed = false
            if (file.isAnimated) {
                play()
            } else {
                decodeStatic(file.path)
            }
        }
    }

    private fun decodeStatic(path: String) {
        isLoading = true
        val task = object : AsyncTask<Void, Void, Bitmap?>() {
            override fun doInBackground(vararg params: Void): Bitmap? = RustMedia.decode(File(path), size, size)

            override fun onPostExecute(result: Bitmap?) {
                isLoading = false
                isFailed = result == null
                current = result
                if (result != null) invalidateHosts()
            }

            override fun onError(t: Throwable) {
                if (t is RejectedExecutionException) {
                    main.postDelayed({
                        isLoading = false
                        invalidateHosts()
                    }, DECODE_RETRY_MS)
                } else {
                    isLoading = false
                    isFailed = true
                    super.onError(t)
                }
            }
        }
        task.withPriority(AsyncTask.Priority.NORMAL).execBackgroundOn(AsyncTask.MEDIA_EXECUTOR)
    }

    private fun play() {
        val file = file ?: return
        if (playback != null) return
        playback = AnimatedStickerPlayback(File(file.path), size) { bitmap ->
            current = bitmap
            val isShown = invalidateHosts()
            if (!isShown) playback = null
            isShown
        }.also { it.start() }
    }

    /** Redraw the texts still showing it on screen, false when there are none. */
    private fun invalidateHosts(): Boolean {
        var isShown = false
        val iterator = hosts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val host = entry.key
            val text = host.text as? Spanned
            if (text == null || text.getSpanStart(entry.value) < 0) {
                val replacement = text?.getSpans(0, text.length, CustomEmojiSpan::class.java)
                    ?.firstOrNull { it.drawable === this }
                if (replacement == null) {
                    iterator.remove()
                    continue
                }
                entry.setValue(replacement)
            }
            if (host.isAttachedToWindow && host.windowVisibility == View.VISIBLE &&
                host.isShown && host.getGlobalVisibleRect(visibleBounds)
            ) {
                host.invalidate()
                isShown = true
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
        private const val DECODE_RETRY_MS = 100L
        private val scope = MainScope()

        /** For loading from [resume], set by the first [attach]. */
        private var appContext: Context? = null
        private val main = Handler(Looper.getMainLooper())

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
        it.drawable.attach(this, it)
    }
}

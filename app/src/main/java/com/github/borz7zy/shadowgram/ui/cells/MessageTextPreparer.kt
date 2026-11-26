/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */
package com.github.borz7zy.shadowgram.ui.cells

import android.util.Log
import com.github.borz7zy.shadowgram.background.AsyncTask
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.models.MessageRow
import java.util.LinkedHashMap

/** Only immutable strings and ranges cross threads. No View, Paint, Drawable or Spannable. */
internal class MessageTextPreparer {
    data class Range(val start: Int, val end: Int, val code: String)

    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, List<Range>>(32, 0.75f, true)
    private var cacheWeight = 0L
    private var cacheGeneration = 0L
    // The scheduling fields below are confined to the main thread.
    private var attached = false
    private var running: AsyncTask<Void, Void, Unit>? = null
    private var pending = emptyList<String>()

    fun attach() { attached = true }

    fun detach() {
        attached = false
        pending = emptyList()
        val task = running
        running = null
        task?.cancel()
        synchronized(cacheLock) { cacheGeneration++; cache.clear(); cacheWeight = 0 }
    }

    private fun weight(text: String, ranges: List<Range>): Long =
        text.length * 2L + 64L + ranges.sumOf { 40L + it.code.length * 2L }

    private fun cached(text: String): List<Range>? = synchronized(cacheLock) { cache[text] }

    private fun remember(text: String, ranges: List<Range>, generation: Long? = null) = synchronized(cacheLock) {
        if (generation != null && generation != cacheGeneration) return@synchronized
        val cost = weight(text, ranges)
        if (cost > MAX_CACHE_WEIGHT) return@synchronized
        cache.put(text, ranges)?.let { cacheWeight -= weight(text, it) }
        cacheWeight += cost
        val iterator = cache.entries.iterator()
        while ((cache.size > MAX_ENTRIES || cacheWeight > MAX_CACHE_WEIGHT) && iterator.hasNext()) {
            val entry = iterator.next()
            cacheWeight -= weight(entry.key, entry.value)
            iterator.remove()
        }
    }

    private fun parse(text: String): List<Range> {
        // Match the characters that can start a sequence in Emoji.parseEmojis, including keycaps.
        if (text.none {
                it in '\uD83C'..'\uD83E' || it in '\u203C'..'\u3299' ||
                    it in '\uFE00'..'\uFE0F' || it == '©' || it == '®'
            }) {
            return emptyList()
        }
        val parsed = Emoji.parseEmojis(text)
        if (parsed.isEmpty()) return emptyList()
        return buildList {
            for (index in 0 until minOf(parsed.size, MAX_RANGES)) {
                val range = parsed[index]
                val code = range.code?.toString() ?: continue
                if (range.start >= 0 && range.end > range.start && range.end <= text.length) {
                    add(Range(range.start, range.end, code))
                }
            }
        }
    }

    /** A visible cache miss is synchronous on purpose: no placeholder text or second bind. */
    fun ranges(text: String): List<Range> = cached(text) ?: parse(text).also { remember(text, it) }

    /** Main thread; bounded work around the viewport and newest messages. */
    fun prefetch(rows: List<MessageRow>, firstVisible: Int) {
        if (!attached || rows.isEmpty()) return
        val center = firstVisible.coerceIn(0, rows.lastIndex)
        val candidates = LinkedHashSet<String>()
        fun add(index: Int) {
            val row = rows.getOrNull(index) as? MessageRow.Bubble ?: return
            val text = row.message.text.orEmpty()
            if (text.isNotEmpty() && cached(text) == null) candidates.add(text)
        }
        for (offset in 0..48) {
            add(center + offset)
            if (offset != 0) add(center - offset)
        }
        for (index in 0 until minOf(24, rows.size)) add(index)
        pending = candidates.take(MAX_BATCH)
        startNext()
    }

    private fun startNext() {
        if (!attached || running != null || pending.isEmpty()) return
        val batch = pending
        pending = emptyList()
        val generation = synchronized(cacheLock) { cacheGeneration }
        val task = object : AsyncTask<Void, Void, Unit>() {
            override fun doInBackground(vararg params: Void) {
                for (text in batch) {
                    if (isCanceled || Thread.currentThread().isInterrupted) break
                    if (cached(text) != null) continue
                    val ranges = parse(text)
                    if (isCanceled) break
                    remember(text, ranges, generation)
                }
            }

            override fun onPostExecute(result: Unit) = complete(this)
            override fun onCanceled(result: Unit?) = complete(this)
            override fun onError(t: Throwable) {
                Log.w(TAG, "Text prefetch failed; visible rows use synchronous fallback", t)
                complete(this)
            }
        }
        running = task
        task.withPriority(AsyncTask.Priority.NORMAL).execBackgroundOn(AsyncTask.CPU_EXECUTOR)
    }

    private fun complete(task: AsyncTask<Void, Void, Unit>) {
        if (running !== task) return
        running = null
        startNext()
    }

    private companion object {
        const val TAG = "MessageTextPreparer"
        const val MAX_ENTRIES = 512
        const val MAX_CACHE_WEIGHT = 2L * 1024 * 1024 // estimated Java object/string storage
        const val MAX_BATCH = 96
        const val MAX_RANGES = 4096
    }
}

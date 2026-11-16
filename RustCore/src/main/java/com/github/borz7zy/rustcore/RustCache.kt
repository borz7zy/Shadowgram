/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.rustcore

import android.os.Parcel
import android.os.Parcelable
import java.util.function.Consumer
import java.util.function.Function

/**
 * Kotlin facade over the cache part of `librustcore.so`: named two-tier buckets (RAM LRU over a
 * disk store in `<root>/<name>`).
 *
 * - [bucket] with `durable = false` — a cache: disk is an LRU capped at `diskBytes`.
 * - [bucket] with `durable = true` — the app's persistent store (replaces SQLite):
 *   disk never evicts, only the RAM tier does.
 *
 * Call [init] once with a writable directory before the first read to get disk
 * persistence; buckets opened earlier pick their disk up on [init]. Values are
 * opaque byte blobs. Safe from any thread.
 */
object RustCache {

    init {
        System.loadLibrary("rustcore")
    }

    fun init(rootDir: String) = nativeInit(rootDir)

    fun bucket(name: String, memBytes: Long, diskBytes: Long = 0, durable: Boolean = false) =
        Bucket(nativeOpen(name, memBytes, diskBytes, durable))

    class Bucket internal constructor(private val h: Int) {
        fun put(key: String, data: ByteArray) = nativePut(h, key.toByteArray(), data)
        fun get(key: String): ByteArray? = nativeGet(h, key.toByteArray())
        fun remove(key: String) = nativeRemove(h, key.toByteArray())
        fun clear() = nativeClear(h)

        /** Serialise via [Parcel]. The bytes are a cache format: [FORMAT] guards Android-version changes. */
        fun write(key: String, block: Consumer<Parcel>) {
            val p = Parcel.obtain()
            try {
                block.accept(p)
                put(key, p.marshall())
            } catch (e: Exception) {
                android.util.Log.w("RustCache", "write $key failed", e)
                remove(key)
            } finally {
                p.recycle()
            }
        }

        /** null if absent or undecodable (a corrupt entry is dropped). */
        fun <R> read(key: String, block: Function<Parcel, R>): R? {
            val b = get(key) ?: return null
            val p = Parcel.obtain()
            return try {
                p.unmarshall(b, 0, b.size)
                p.setDataPosition(0)
                block.apply(p)
            } catch (e: Exception) {
                android.util.Log.w("RustCache", "read $key failed", e)
                remove(key)
                null
            } finally {
                p.recycle()
            }
        }

        fun <T : Parcelable> putList(key: String, list: List<T>) = write(key) { it.writeTypedList(list) }

        fun <T> getList(key: String, creator: Parcelable.Creator<T>): ArrayList<T>? =
            read(key) { p -> ArrayList<T>().also { p.readTypedList(it, creator) } }
    }

    /**
     * Wipe everything when the stored format can't be trusted: first run, or a new
     * [FORMAT] / Android build changed how Parcel marshals. Returns true if it wiped.
     */
    fun checkFormat(): Boolean {
        val meta = bucket("meta", 4096, durable = true)
        val want = "$FORMAT:${android.os.Build.FINGERPRINT}".toByteArray()
        if (meta.get("format")?.contentEquals(want) == true) return false
        clearAll()
        meta.put("format", want)
        return true
    }

    private const val FORMAT = 1

    /** Drop everything in every bucket — RAM and disk. Logout / format change. */
    fun clearAll() = nativeClearAll()

    /** Drop only the RAM tier; disk stays. For `Application.onTrimMemory`. */
    fun trimMemory() = nativeTrimMemory()

    /** `[mem, disk]` bytes per bucket, in open order. */
    fun stats(): LongArray = nativeStats() ?: LongArray(0)

    @JvmStatic private external fun nativeInit(rootDir: String)
    @JvmStatic private external fun nativeOpen(name: String, memBytes: Long, diskBytes: Long, durable: Boolean): Int
    @JvmStatic private external fun nativePut(h: Int, key: ByteArray, data: ByteArray)
    @JvmStatic private external fun nativeGet(h: Int, key: ByteArray): ByteArray?
    @JvmStatic private external fun nativeRemove(h: Int, key: ByteArray)
    @JvmStatic private external fun nativeClear(h: Int)
    @JvmStatic private external fun nativeClearAll()
    @JvmStatic private external fun nativeTrimMemory()
    @JvmStatic private external fun nativeStats(): LongArray?
}

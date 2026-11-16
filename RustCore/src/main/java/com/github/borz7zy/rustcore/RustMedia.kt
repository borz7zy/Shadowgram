/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.rustcore

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File

/**
 * Media decoding in `librustcore.so`: the file type is sniffed natively and
 * routed to a dedicated decoder (JPEG — libjpeg-turbo-rs, PNG — zune-png,
 * WebP — libwebp, GIF — gif, WebM VP9+alpha — libvpx, TGS — rlottie). MP4/audio
 * are [Kind.PLATFORM]: leave them to MediaCodec/ExoPlayer.
 *
 * Decoding is blocking — call off the main thread. Bitmaps are ARGB_8888,
 * premultiplied, scaled to fit `maxW×maxH` (0 = no limit), never upscaled.
 */
object RustMedia {

    init {
        System.loadLibrary("rustcore")
    }

    /** Mirrors `media::MediaKind` in Rust — same order. */
    enum class Kind {
        UNKNOWN, JPEG, PNG, WEBP, GIF, WEBM, PLATFORM, TGS;

        /** Decoded by RustCore rather than the platform. */
        val isOurs get() = this in JPEG..WEBM || this == TGS
    }

    fun detect(head: ByteArray): Kind = Kind.entries.getOrElse(nativeDetect(head)) { Kind.UNKNOWN }

    fun detect(file: File): Kind = runCatching {
        file.inputStream().use { detect(it.readNBytes(16)) }
    }.getOrDefault(Kind.UNKNOWN)

    fun decode(file: File, maxW: Int = 0, maxH: Int = 0): Bitmap? =
        nativeDecodeFile(file.absolutePath, maxW, maxH)

    fun decode(bytes: ByteArray, maxW: Int = 0, maxH: Int = 0): Bitmap? =
        nativeDecodeBytes(bytes, maxW, maxH)

    /**
     * Frame player for a WebM or TGS sticker at a fixed display size. WebM
     * frames go to the shared RAM LRU (`frames` bucket), so every copy of the
     * same sticker at the same size reuses them; TGS frames are rendered by
     * rlottie on every call. One instance per view; not thread-safe. [close]
     * when the view goes away.
     */
    class AnimatedSticker private constructor(private var handle: Long) : Closeable {
        val width: Int
        val height: Int
        val frameCount: Int
        val durationMs: Int

        init {
            val info = nativeStickerInfo(handle) ?: IntArray(4)
            width = info[0]; height = info[1]; frameCount = info[2]; durationMs = info[3]
        }

        /** A bitmap of the right size to pass to [frame]. */
        fun newBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        /** Draws frame [index] (0 until [frameCount]) into [into]. */
        fun frame(index: Int, into: Bitmap): Boolean {
            check(handle != 0L) { "closed" }
            return nativeStickerFrame(handle, index, into)
        }

        override fun close() {
            nativeStickerClose(handle)
            handle = 0
        }

        companion object {
            /** null if the file is missing or not a playable WebM/TGS. */
            fun open(file: File, maxW: Int, maxH: Int): AnimatedSticker? =
                nativeStickerOpen(file.absolutePath, maxW, maxH).takeIf { it != 0L }?.let(::AnimatedSticker)
        }
    }

    @JvmStatic private external fun nativeDetect(head: ByteArray): Int
    @JvmStatic private external fun nativeDecodeFile(path: String, maxW: Int, maxH: Int): Bitmap?
    @JvmStatic private external fun nativeDecodeBytes(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap?
    @JvmStatic private external fun nativeStickerOpen(path: String, maxW: Int, maxH: Int): Long
    @JvmStatic private external fun nativeStickerInfo(handle: Long): IntArray?
    @JvmStatic private external fun nativeStickerFrame(handle: Long, index: Int, into: Bitmap): Boolean
    @JvmStatic private external fun nativeStickerClose(handle: Long)
}

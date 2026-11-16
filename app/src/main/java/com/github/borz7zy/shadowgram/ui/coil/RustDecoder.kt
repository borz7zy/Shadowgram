/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import com.github.borz7zy.rustcore.RustMedia
import kotlinx.coroutines.runInterruptible

/**
 * Coil [Decoder] backed by RustCore: JPEG/PNG/WebP/GIF (and the first frame of WebM/TGS
 * stickers) are decoded natively, scaled to the request size during decode. Anything
 * else, or a failed native decode, falls through to Coil's own decoders.
 */
class RustDecoder(private val source: ImageSource, private val options: Options) : Decoder {

    override suspend fun decode(): DecodeResult? = runInterruptible {
        val maxW = options.size.width.pxOrElse { 0 }
        val maxH = options.size.height.pxOrElse { 0 }
        val file = source.fileOrNull()
        val bitmap = when (file) {
            null -> RustMedia.decode(source.source().peek().readByteArray(), maxW, maxH)
            else -> RustMedia.decode(file.toFile(), maxW, maxH)
        } ?: return@runInterruptible null
        DecodeResult(bitmap.asImage(), isSampled = maxW > 0 || maxH > 0)
    }

    object Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader) =
            result.source.takeIf { src ->
                val head = src.source().peek().let { it.request(16); it.buffer.copy().readByteArray() }
                RustMedia.detect(head).isOurs
            }?.let { RustDecoder(it, options) }
    }
}

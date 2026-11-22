/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Message contents for files the user picked.
 *
 * TDLib uploads from a path, so every file is first copied into the cache under its own name
 * (documents keep it). ponytail: the copies stay in the cache dir, delete them once sent if
 * cache size starts to matter.
 */
object InputContents {
    /** TDLib limits for a photo, bigger or odder ones go as documents. */
    private const val MAX_PHOTO_BYTES = 10L * 1024 * 1024
    private const val MAX_PHOTO_SIDES = 10_000
    private const val MAX_PHOTO_RATIO = 20

    private const val THUMBNAIL_SIDE = 320
    private const val PROFILE_PHOTO_SIDE = 960

    /** Telegram's limit of an album. */
    private const val MAX_ALBUM = 10

    /** A file picked for the next message, not read until it's sent. */
    data class Picked(val uri: Uri, val asFile: Boolean)

    /**
     * Split [contents] into albums, in order: up to [MAX_ALBUM] in a row of the kinds Telegram
     * groups together (photos with videos, documents, audio), anything else alone.
     */
    fun albums(contents: List<TdApi.InputMessageContent>): List<List<TdApi.InputMessageContent>> {
        val albums = ArrayList<MutableList<TdApi.InputMessageContent>>()
        var lastKind: Int? = null
        contents.forEach { content ->
            val kind = albumKind(content)
            val last = albums.lastOrNull()
            when {
                last != null && kind != null && kind == lastKind && last.size < MAX_ALBUM -> last.add(content)
                else -> albums.add(mutableListOf(content))
            }
            lastKind = kind
        }
        return albums
    }

    private fun albumKind(content: TdApi.InputMessageContent) = when (content) {
        is TdApi.InputMessagePhoto, is TdApi.InputMessageVideo -> 0
        is TdApi.InputMessageDocument -> 1
        is TdApi.InputMessageAudio -> 2
        else -> null
    }

    /**
     * @param asFile Send as a document whatever the type, as picked from "Files"
     * @return null when the file can't be read
     */
    suspend fun fromUri(
        context: Context,
        uri: Uri,
        asFile: Boolean,
        caption: String?,
        preferAudio: Boolean = false,
    ): TdApi.InputMessageContent? = withContext(Dispatchers.IO) {
        val file = copy(context, uri) ?: return@withContext null
        // Files of our own (a photo shot in the gallery panel) have no type from the resolver
        val mimeType = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: ""
        val local = TdApi.InputFileLocal(file.absolutePath)
        val text = caption?.takeIf { it.isNotBlank() }?.let { TdApi.FormattedText(it, arrayOf()) }

        when {
            preferAudio && mimeType.startsWith("audio/") -> audio(file, local)

            asFile -> document(local, text)

            mimeType == "image/gif" -> {
                val (width, height) = imageSize(file)
                TdApi.InputMessageAnimation(
                    TdApi.InputAnimation(local, null, null, 0, width, height), text, false, false
                )
            }

            mimeType.startsWith("image/") -> {
                val (width, height) = imageSize(file)
                val fitsPhoto = file.length() <= MAX_PHOTO_BYTES && width > 0 && height > 0
                        && width + height <= MAX_PHOTO_SIDES
                        && maxOf(width, height) <= MAX_PHOTO_RATIO * minOf(width, height)
                when (fitsPhoto) {
                    true -> TdApi.InputMessagePhoto(
                        TdApi.InputPhoto(local, null, null, null, width, height),
                        text, false, null, false,
                    )

                    false -> document(local, text)
                }
            }

            mimeType.startsWith("video/") -> video(file, local, text)

            else -> document(local, text)
        }
    }

    /** TDLib expects a JPEG for a static profile photo. Decode to a bounded, square image. */
    suspend fun profilePhoto(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) {
                    decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (side > PROFILE_PHOTO_SIDE) {
                    decoder.setTargetSize(
                        (info.size.width.toLong() * PROFILE_PHOTO_SIDE / side).toInt().coerceAtLeast(1),
                        (info.size.height.toLong() * PROFILE_PHOTO_SIDE / side).toInt().coerceAtLeast(1),
                    )
                }
            }
            val side = minOf(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            val file = File.createTempFile("profile-", ".jpg", context.cacheDir)
            try {
                check(file.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 92, it) })
                file
            } catch (error: Exception) {
                file.delete()
                throw error
            } finally {
                if (square !== decoded) square.recycle()
                decoded.recycle()
            }
        }.getOrNull()
    }

    private fun document(local: TdApi.InputFile, text: TdApi.FormattedText?) =
        TdApi.InputMessageDocument(TdApi.InputDocument(local, null, false), text)

    private fun audio(file: File, local: TdApi.InputFile) = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.let { ((it + 500) / 1000).toInt() } ?: 0
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().take(64)
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().take(64)
        TdApi.InputMessageAudio(TdApi.InputAudio(local, null, duration, title, artist), null)
    }

    private fun video(
        file: File,
        local: TdApi.InputFile,
        text: TdApi.FormattedText?,
    ) = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0

        val rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        var width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        var height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        if (rotation == 90 || rotation == 270) {
            width = height.also { height = width }
        }
        val duration = (int(MediaMetadataRetriever.METADATA_KEY_DURATION) + 500) / 1000

        // The frame comes rotated already
        val thumbnail = retriever.getFrameAtTime(0)?.let { frame ->
            val scale = THUMBNAIL_SIDE.toFloat() / maxOf(frame.width, frame.height)
            val scaled = Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            val out = File(file.parentFile, "thumbnail.jpg")
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            TdApi.InputThumbnail(TdApi.InputFileLocal(out.absolutePath), scaled.width, scaled.height)
        }

        TdApi.InputMessageVideo(
            TdApi.InputVideo(local, thumbnail, null, 0, null, duration, width, height, true),
            text, false, null, false,
        )
    }

    /** The size as shown, EXIF rotation applied. */
    private fun imageSize(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val rotated = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) in
                    setOf(
                        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
                    )
        }.getOrDefault(false)
        return when (rotated) {
            true -> options.outHeight to options.outWidth
            false -> options.outWidth to options.outHeight
        }
    }

    private fun copy(context: Context, uri: Uri): File? = runCatching {
        val name = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.replace('/', '_')
            ?: uri.takeIf { it.scheme == "file" }?.lastPathSegment
            ?: "file"
        val directory = File(context.cacheDir, "outgoing/${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        file
    }.getOrNull()
}

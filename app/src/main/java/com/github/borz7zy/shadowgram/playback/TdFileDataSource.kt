/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Plays a Telegram file while it downloads: [uriOf] gives it a `tdfile://` uri, reads ask TDLib
 * for the next [CHUNK] from where the player is, seeks included, and wait for it on disk. Other
 * uris are read as plain files.
 *
 * Runs on the player's loading thread, which may block.
 */
@OptIn(UnstableApi::class)
class TdFileDataSource private constructor(private val context: Context) : BaseDataSource(true) {
    class Factory(context: Context) : DataSource.Factory {
        private val context = context.applicationContext

        override fun createDataSource(): DataSource = TdFileDataSource(context)
    }

    private var fallback: FileDataSource? = null
    private var client: TelegramClient? = null
    private var uri: Uri? = null
    private var fileId = 0
    private var size = 0L
    private var position = 0L
    private var remaining = 0L

    /** Downloaded from [position] up to here, known without asking TDLib again. */
    private var readyEnd = 0L

    /** Asked TDLib to download up to here. */
    private var requestedEnd = 0L
    private var raf: RandomAccessFile? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        if (dataSpec.uri.scheme != SCHEME) {
            return FileDataSource().also { fallback = it }.open(dataSpec)
        }

        transferInitializing(dataSpec)
        fileId = dataSpec.uri.host?.toIntOrNull() ?: throw IOException("No file id: ${dataSpec.uri}")
        client = runBlocking {
            (context as ShadowgramApplication).accountsRepository.activeClient()
        }
        val file = td(TdApi.GetFile(fileId))
        size = file.size.takeIf { it > 0 } ?: file.expectedSize
        position = dataSpec.position
        remaining = when (dataSpec.length) {
            C.LENGTH_UNSET.toLong() -> size - position
            else -> dataSpec.length
        }
        readyEnd = if (file.local.isDownloadingCompleted) size else position
        requestedEnd = position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let { return it.read(buffer, offset, length) }
        if (length == 0) {
            return 0
        }
        if (remaining <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        awaitData()
        val count = minOf(length.toLong(), readyEnd - position, remaining).toInt()
        val raf = raf ?: RandomAccessFile(path(), "r").also { raf = it }
        raf.seek(position)
        raf.readFully(buffer, offset, count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    /** Until something past [position] is on disk, keeping the download a chunk ahead. */
    private fun awaitData() {
        while (true) {
            // Half a chunk left: ask for the next, from here
            if (requestedEnd - position < CHUNK / 2 && requestedEnd < size && readyEnd < size) {
                td(TdApi.DownloadFile(fileId, PRIORITY, position, CHUNK, false))
                requestedEnd = position + CHUNK
            }
            if (readyEnd > position) {
                return
            }
            readyEnd = position + td(TdApi.GetFileDownloadedPrefixSize(fileId, position)).size
            if (readyEnd > position) {
                return
            }
            // ponytail: polls TDLib, listen to UpdateFile if this shows up in profiles
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
        }
    }

    /** Where TDLib writes the file, known once its download started. */
    private fun path(): String {
        while (true) {
            td(TdApi.GetFile(fileId)).local.path.takeIf { it.isNotEmpty() }?.let { return it }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
        }
    }

    private fun <T : TdApi.Object> td(function: TdApi.Function<T>): T =
        when (val result = runBlocking { client!!.send(function) }) {
            is Result.Success -> result.data
            is Result.Failure -> throw IOException("TDLib: ${result.error.message}")
        }

    override fun getUri() = uri

    override fun close() {
        fallback?.let {
            fallback = null
            it.close()
            return
        }
        raf?.close()
        raf = null
        if (client != null) {
            client = null
            transferEnded()
        }
    }

    companion object {
        const val SCHEME = "tdfile"

        /** Played, so before the chat's pictures. */
        private const val PRIORITY = 24
        private const val CHUNK = 1L shl 20
        private const val POLL_MS = 30L

        fun uriOf(fileId: Int): Uri = Uri.parse("$SCHEME://$fileId")
    }
}

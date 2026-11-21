/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.videonote

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Transcodes recorded video notes for one account, as TDLib asks for them.
 *
 * A note is sent at once with [inputFile]: its message shows up while the raw recording is
 * transcoded here. TDLib owns the message, so the work goes on when the chat is closed, and is
 * asked for again after the process died. Deleting the message stops it.
 */
class VideoNoteGenerator(
    private val context: Context,
    private val client: TelegramClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Running generations by id. */
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Percent transcoded, by the path TDLib generates into: the local path of the note file
     * of the message while it's generated.
     */
    val progress = _progress.asStateFlow()

    // Called on the TDLib thread
    fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateFileGenerationStart -> {
                if (update.conversion.startsWith(CONVERSION)) {
                    val id = update.generationId
                    val job = scope.launch(start = CoroutineStart.LAZY) { generate(update) }
                    if (jobs.putIfAbsent(id, job) == null) {
                        job.invokeOnCompletion { cause ->
                            jobs.remove(id, job)
                            if (cause is CancellationException) {
                                scope.launch { File(update.originalPath).delete() }
                            }
                        }
                        job.start()
                    } else {
                        job.cancel()
                    }
                }
            }

            // The message was deleted: the recording isn't needed anymore
            is TdApi.UpdateFileGenerationStop -> jobs.remove(update.generationId)?.cancel()
        }
    }

    private suspend fun generate(update: TdApi.UpdateFileGenerationStart) {
        val raw = File(update.originalPath)
        val destination = File(update.destinationPath)
        val id = update.generationId

        // Gives the file its destination path, nothing is uploaded until the note is finished
        client.send(TdApi.SetFileGenerationProgress(id, 0, 0)) {}
        _progress.update { it + (update.destinationPath to 0) }

        try {
            VideoNoteCamera.transcode(context, raw, destination) { percent ->
                _progress.update { it + (update.destinationPath to percent) }
            }
            client.send(TdApi.FinishFileGeneration(id, null)) {}
            raw.delete()
        } catch (e: CancellationException) {
            // Stopped by TDLib, the message is gone
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Video note transcode failed", e)
            client.send(TdApi.FinishFileGeneration(id, TdApi.Error(400, e.message.orEmpty()))) {}
            raw.delete()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.video_note_failed, Toast.LENGTH_LONG).show()
            }
        } finally {
            _progress.update { it - update.destinationPath }
        }
    }

    companion object {
        private const val LOG_TAG = "VideoNoteGenerator"

        private const val CONVERSION = "shadowgram_video_note"

        /**
         * The note file to send for a [raw] recording, transcoded once TDLib asks for it.
         * The conversion names the recording: TDLib must not take two notes for one file.
         */
        fun inputFile(raw: File) =
            TdApi.InputFileGenerated(raw.absolutePath, "$CONVERSION:${raw.name}", 0)
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.voicenote

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Chronometer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.borz7zy.shadowgram.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Records a mono Telegram voice note, handing the finished file to its chat on confirmation. */
class VoiceNoteDialogFragment : DialogFragment() {
    private val finishRecording = CompletableDeferred<Boolean>()
    private var recording: Job? = null
    private var timer: Chronometer? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else dismissAllowingStateLoss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val timer = Chronometer(requireContext()).apply {
            gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
            val padding = (20 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, padding, padding, padding)
        }
        this.timer = timer
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_voice_note)
            .setView(timer)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chat_send, null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = false
                        setOnClickListener {
                            if (finishRecording.complete(true)) {
                                isEnabled = false
                                timer.stop()
                            }
                        }
                    }
                }
            }
    }

    override fun onStart() {
        super.onStart()
        if (recording != null) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) startRecording()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroyView() {
        recording?.cancel()
        timer = null
        super.onDestroyView()
    }

    override fun onStop() {
        if (recording?.isActive == true && !finishRecording.isCompleted) {
            finishRecording.complete(false)
            dismissAllowingStateLoss()
        }
        super.onStop()
    }

    private fun startRecording() {
        if (recording != null || !isAdded) return
        val dialog = dialog as? AlertDialog ?: return
        val audioContext = requireContext().applicationContext
        recording = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { record(audioContext, dialog) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(audioContext, R.string.voice_note_failed, Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
        }
    }

    private suspend fun record(context: Context, dialog: AlertDialog) {
        val name = "voice_note_${System.nanoTime()}"
        val opus = File(context.cacheDir, "$name.ogg")
        val recording = runCatching { openRecorder(context, opus, opus = true) }.getOrElse {
            opus.delete()
            val aac = File(context.cacheDir, "$name.m4a")
            try {
                openRecorder(context, aac, opus = false)
            } catch (failure: Exception) {
                aac.delete()
                throw failure
            }
        }
        val (recorder, file) = recording
        var delivered = false
        try {
            val started = SystemClock.elapsedRealtime()
            val samples = ArrayList<Int>()
            withContext(Dispatchers.Main) {
                timer?.base = started
                timer?.start()
            }
            while (!finishRecording.isCompleted && SystemClock.elapsedRealtime() - started < MAX_DURATION_MS) {
                delay(SAMPLE_INTERVAL_MS)
                samples.add(recorder.maxAmplitude)
                if (samples.size == 10) withContext(Dispatchers.Main) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !finishRecording.isCompleted
                }
            }
            if (finishRecording.isCompleted && !finishRecording.await()) return
            val duration = ((SystemClock.elapsedRealtime() - started) / 1000.0).roundToInt().coerceAtLeast(1)
            recorder.stop()
            val waveform = waveform(samples)
            withContext(Dispatchers.Main) {
                if (!isAdded || !dialog.isShowing) return@withContext
                setFragmentResult(REQUEST_KEY, Bundle().apply {
                    putString(KEY_PATH, file.absolutePath)
                    putInt(KEY_DURATION, duration)
                    putByteArray(KEY_WAVEFORM, waveform)
                })
                delivered = true
                dismissAllowingStateLoss()
            }
        } finally {
            recorder.release()
            if (!delivered) file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun openRecorder(context: Context, file: File, opus: Boolean): Pair<MediaRecorder, File> {
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(if (opus) MediaRecorder.OutputFormat.OGG else MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(if (opus) MediaRecorder.AudioEncoder.OPUS else MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioEncodingBitRate(32_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            return recorder to file
        } catch (failure: Exception) {
            recorder.release()
            throw failure
        }
    }

    private fun waveform(samples: List<Int>): ByteArray {
        if (samples.isEmpty()) return byteArrayOf()
        val count = samples.size.coerceAtMost(100)
        val peak = samples.max().coerceAtLeast(1)
        val packed = ByteArray((count * 5 + 7) / 8)
        repeat(count) { index ->
            val start = index * samples.size / count
            val end = (index + 1) * samples.size / count
            val level = samples.subList(start, end).max() * 31 / peak
            val bit = index * 5
            val offset = bit % 8
            val byte = bit / 8
            packed[byte] = (packed[byte].toInt() or (level shl offset)).toByte()
            if (offset > 3) packed[byte + 1] = (level shr (8 - offset)).toByte()
        }
        return packed
    }

    companion object {
        const val REQUEST_KEY = "voice_note"
        const val KEY_PATH = "path"
        const val KEY_DURATION = "duration"
        const val KEY_WAVEFORM = "waveform"
        private const val SAMPLE_INTERVAL_MS = 50L
        private const val MAX_DURATION_MS = 60 * 60 * 1_000L
    }
}

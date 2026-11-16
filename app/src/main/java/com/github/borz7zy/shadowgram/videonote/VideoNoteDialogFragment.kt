/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.videonote

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.viewmodels.ChatViewModel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Records a video note as soon as it opens. Send hands the file to the chat through
 * [REQUEST_KEY] (path in [KEY_PATH]); cancel, back or leaving the screen drops it.
 */
class VideoNoteDialogFragment : DialogFragment(R.layout.fragment_video_note) {
    // Views
    private val backPreviewView by getViewProperty<PreviewView>(R.id.backPreviewView)
    private val cancelMaterialButton by getViewProperty<MaterialButton>(R.id.cancelMaterialButton)
    private val chronometer by getViewProperty<Chronometer>(R.id.chronometer)
    private val controlsLinearLayout by getViewProperty<LinearLayout>(R.id.controlsLinearLayout)
    private val flipCameraMaterialButton by getViewProperty<MaterialButton>(R.id.flipCameraMaterialButton)
    private val frontPreviewView by getViewProperty<PreviewView>(R.id.frontPreviewView)
    private val previewFrameLayout by getViewProperty<FrameLayout>(R.id.previewFrameLayout)
    private val sendMaterialButton by getViewProperty<MaterialButton>(R.id.sendMaterialButton)

    // Arguments
    private val chatId by lazy { requireArguments().getLong(ChatViewModel.ARG_CHAT_ID) }
    private val topicId by lazy { requireArguments().getInt(ChatViewModel.ARG_TOPIC_ID) }

    private var session: VideoNoteCamera.Session? = null
    private var file: File? = null

    /** Send was pressed (or the duration limit hit): the finalized file goes to the chat. */
    private var isSending = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        when (results.values.all { it }) {
            true -> startCamera()
            false -> fail(R.string.app_permissions_toast)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStyle(STYLE_NO_FRAME, R.style.Theme_Shadowgram_VideoNote)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val controlsPaddingBottom = controlsLinearLayout.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(controlsLinearLayout) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            controlsLinearLayout.updatePadding(bottom = controlsPaddingBottom + insets.bottom)
            windowInsets
        }

        // Oval background: the previews are cut to a circle
        previewFrameLayout.clipToOutline = true

        cancelMaterialButton.setOnClickListener { dismiss() }

        flipCameraMaterialButton.setOnClickListener {
            val session = session ?: return@setOnClickListener
            session.flip()
            // The other camera already streams, show it now
            if (session.isConcurrent == true) {
                showPreview(session.isFront)
            }
        }

        sendMaterialButton.setOnClickListener { send() }

        // Show a camera once its frames arrive, not its last frame from before
        backPreviewView.previewStreamState.observe(viewLifecycleOwner) {
            if (it == PreviewView.StreamState.STREAMING && session?.isFront == false) {
                showPreview(false)
            }
        }
        frontPreviewView.previewStreamState.observe(viewLifecycleOwner) {
            if (it == PreviewView.StreamState.STREAMING && session?.isFront == true) {
                showPreview(true)
            }
        }

        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        when (permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) ==
                    PackageManager.PERMISSION_GRANTED
        }) {
            true -> startCamera()
            false -> permissionsLauncher.launch(permissions)
        }
    }

    override fun onStart() {
        super.onStart()

        // Rotating would recreate the screen and lose the recording
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    override fun onStop() {
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        super.onStop()
    }

    override fun onDestroyView() {
        // Not sent: the finalize callback deletes the file
        session?.close()
        session = null

        super.onDestroyView()
    }

    private fun startCamera() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val (provider, config) = try {
                VideoNoteCamera.awaitConfig(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Camera unavailable", e)
                fail(R.string.video_note_failed)
                return@launch
            }
            if (config == null) {
                fail(R.string.video_note_unsupported)
                return@launch
            }

            val session = VideoNoteCamera.Session(
                context,
                viewLifecycleOwner,
                provider,
                config,
                backPreviewView.surfaceProvider,
                frontPreviewView.surfaceProvider,
            )
            this@VideoNoteDialogFragment.session = session
            session.bind()

            val file = File(context.cacheDir, "videonote_${System.currentTimeMillis()}_raw.mp4")
            this@VideoNoteDialogFragment.file = file
            session.start(file, ::onFinalized)
            chronometer.base = SystemClock.elapsedRealtime()
            chronometer.start()

            // Tell the chat, TDLib drops the action after 5 seconds
            val client = (context.applicationContext as ShadowgramApplication)
                .accountsRepository.activeClient()
            while (isActive) {
                client.send(
                    TdApi.SendChatAction(
                        chatId,
                        topicId.takeIf { it != 0 }?.let { TdApi.MessageTopicForum(it) },
                        null,
                        TdApi.ChatActionRecordingVideoNote(),
                    )
                ) {}
                delay(CHAT_ACTION_INTERVAL_MS)
            }
        }
    }

    private fun showPreview(isFront: Boolean) {
        frontPreviewView.alpha = if (isFront) 1f else 0f
        backPreviewView.alpha = if (isFront) 0f else 1f
    }

    private fun send() {
        val session = session ?: return
        isSending = true
        sendMaterialButton.isEnabled = false
        chronometer.stop()
        session.stop()
    }

    private fun onFinalized(error: Int) {
        val file = file ?: return
        val isUsable = error == VideoRecordEvent.Finalize.ERROR_NONE
                || error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED

        when {
            // Reached 60 seconds: send, like Telegram does
            isUsable && (isSending || error != VideoRecordEvent.Finalize.ERROR_NONE) -> {
                setFragmentResult(
                    REQUEST_KEY, Bundle().apply { putString(KEY_PATH, file.absolutePath) }
                )
                if (isAdded) {
                    dismiss()
                }
            }

            isSending -> {
                file.delete()
                fail(R.string.video_note_failed)
            }

            else -> file.delete()
        }
    }

    private fun fail(message: Int) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
        if (isAdded) {
            dismiss()
        }
    }

    companion object {
        private const val LOG_TAG = "VideoNoteDialog"

        const val REQUEST_KEY = "video_note"
        const val KEY_PATH = "path"

        private const val CHAT_ACTION_INTERVAL_MS = 4_000L
    }
}

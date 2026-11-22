/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {
    private val calls get() = (application as ShadowgramApplication).voiceCalls
    private var token = 0L
    private var started = false
    private var requestingPermission = false
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var fingerprint: TextView
    private lateinit var timer: Chronometer
    private lateinit var answer: MaterialButton
    private lateinit var hangUp: MaterialButton
    private lateinit var mute: MaterialButton
    private lateinit var speaker: MaterialButton

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingPermission = false
        if (granted) startCallService()
        else {
            Toast.makeText(this, R.string.call_microphone_required, Toast.LENGTH_LONG).show()
            if (intent.getLongExtra(USER, 0L) != 0L) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getLongExtra(TOKEN, 0L)
        started = savedInstanceState?.getBoolean("started") ?: false
        requestingPermission = savedInstanceState?.getBoolean("requestingPermission") ?: false
        volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
        if (intent.getLongExtra(USER, 0L) == 0L) setShowWhenLocked(true)
        buildUi()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                calls.screens.collect { render(it[token]) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val notificationAnswer = intent.getBooleanExtra(ANSWER, false)
        if (notificationAnswer) intent.removeExtra(ANSWER)
        val shouldStart = intent.getLongExtra(USER, 0L) != 0L || notificationAnswer
        if (shouldStart && !started && !requestingPermission) startWithPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started)
        outState.putBoolean("requestingPermission", requestingPermission)
        super.onSaveInstanceState(outState)
    }

    private fun startWithPermission() {
        if (started || requestingPermission) return
        val states = calls.screens.value
        val incoming = intent.getLongExtra(USER, 0L) == 0L
        if (incoming && states[token]?.phase != TelegramVoiceCalls.Phase.INCOMING) return
        val current = if (incoming) states[token] else states.values.firstOrNull { it.phase != TelegramVoiceCalls.Phase.ENDED }
        if (current != null && current.token != token) {
            startActivity(open(this, current.token))
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCallService()
        } else {
            requestingPermission = true
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCallService() {
        if (started || token == 0L) return
        started = true
        val request = Intent(this, VoiceCallService::class.java)
            .putExtra(TOKEN, token)
            .putExtra(USER, intent.getLongExtra(USER, 0L))
            .putExtra(NAME, intent.getStringExtra(NAME))
        try {
            ContextCompat.startForegroundService(this, request)
        } catch (_: RuntimeException) {
            calls.end(token, R.string.call_failed)
            Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun render(screen: TelegramVoiceCalls.Screen?) {
        val phase = screen?.phase
        val incoming = phase == TelegramVoiceCalls.Phase.INCOMING
        val ended = phase == TelegramVoiceCalls.Phase.ENDED ||
            phase == null && intent.getLongExtra(USER, 0L) == 0L
        titleView.text = screen?.name ?: intent.getStringExtra(NAME) ?: getString(R.string.call_voice)
        subtitle.setText(screen?.error ?: if (ended) R.string.call_ended else phase?.title ?: R.string.call_connecting)
        fingerprint.text = screen?.fingerprint.orEmpty()
        fingerprint.isVisible = !ended && fingerprint.text.isNotEmpty()
        answer.isVisible = incoming
        answer.isEnabled = !started
        hangUp.setText(when {
            ended -> R.string.call_close
            incoming -> R.string.call_decline
            else -> R.string.call_hang_up
        })
        hangUp.setOnClickListener {
            calls.end(token)
            if (ended || screen == null || incoming) finish()
        }
        val inCall = phase == TelegramVoiceCalls.Phase.CONNECTED || phase == TelegramVoiceCalls.Phase.RECONNECTING
        mute.isVisible = inCall
        speaker.isVisible = inCall
        mute.setText(if (screen?.muted == true) R.string.call_unmute else R.string.call_mute)
        speaker.setText(if (screen?.speaker == true) R.string.call_auto_audio_route else R.string.call_speaker)
        if (phase == TelegramVoiceCalls.Phase.CONNECTED && screen.remoteMuted) subtitle.setText(R.string.call_remote_muted)
        timer.isVisible = inCall && screen.connectedAt != 0L
        if (timer.isVisible && screen != null) {
            timer.base = screen.connectedAt
            timer.start()
        } else timer.stop()
    }

    private fun buildUi() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val surface = MaterialColors.getColor(this@CallActivity, com.google.android.material.R.attr.colorSurface, "CallActivity")
            setBackgroundColor(surface)
            setPadding(padding, padding, padding, padding)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom + padding)
            insets
        }
        titleView = TextView(this).apply { textSize = 30f; gravity = Gravity.CENTER }
        subtitle = TextView(this).apply { textSize = 17f; gravity = Gravity.CENTER }
        fingerprint = TextView(this).apply { textSize = 24f; gravity = Gravity.CENTER }
        timer = Chronometer(this).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE }
        listOf(titleView, subtitle, fingerprint, timer).forEach { root.addView(it) }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, 0)
        }
        mute = MaterialButton(this).apply { setOnClickListener { calls.toggleMute(token) } }
        speaker = MaterialButton(this).apply { setOnClickListener { calls.toggleSpeaker(token) } }
        row.addView(mute, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(speaker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        answer = MaterialButton(this).apply {
            setText(R.string.call_answer)
            setOnClickListener { startWithPermission() }
        }
        hangUp = MaterialButton(this)
        root.addView(answer)
        root.addView(hangUp)
        setContentView(root)
        render(calls.screens.value[token])
    }

    companion object {
        internal const val TOKEN = "voice_call_token"
        internal const val USER = "voice_call_user_id"
        internal const val NAME = "voice_call_name"
        private const val ANSWER = "voice_call_answer"

        fun open(context: Context, token: Long, answer: Boolean = false): Intent =
            Intent(context, CallActivity::class.java).putExtra(TOKEN, token).putExtra(ANSWER, answer)

        fun outgoing(context: Context, userId: Long, name: String): Intent =
            open(context, TelegramVoiceCalls.newToken()).putExtra(USER, userId).putExtra(NAME, name)
    }
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.calls

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.github.borz7zy.rustcore.RustCall
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicInteger

internal class TelegramVoiceCalls(
    private val app: ShadowgramApplication,
    private val foreground: () -> Boolean,
) {
    enum class Phase(@StringRes val title: Int) {
        INCOMING(R.string.call_incoming), DIALING(R.string.call_dialing), RINGING(R.string.call_ringing),
        CONNECTING(R.string.call_connecting), CONNECTED(R.string.call_connected),
        RECONNECTING(R.string.call_reconnecting), ENDED(R.string.call_ended)
    }

    data class Screen(
        val token: Long,
        val name: String,
        val phase: Phase,
        val muted: Boolean = false,
        val remoteMuted: Boolean = false,
        val speaker: Boolean = false,
        val connectedAt: Long = 0,
        val fingerprint: String = "",
        @StringRes val error: Int? = null,
    )

    private class Session(
        val client: TelegramClient,
        val userId: Long,
        val outgoing: Boolean,
        var screen: Screen,
        var id: Int = 0,
        var uniqueId: Long = 0,
        var accepted: Boolean = outgoing,
    ) {
        var media: RustCall? = null
        var audio: CallAudio? = null
        var pump: Job? = null
        var timeout: Job? = null
        val earlySignaling = ArrayDeque<ByteArray>()
    }

    private val scope = app.backgroundScope
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _screens = MutableStateFlow<Map<Long, Screen>>(emptyMap())
    val screens = _screens.asStateFlow()
    private val notifications = CallNotifications(app)
    private val queuedSignals = AtomicInteger()
    private var active: Session? = null
    private var localIncoming: Session? = null
    private val pendingIncoming = LinkedHashMap<Pair<TelegramClient, Int>, TdApi.Call>()
    private val closed = LinkedHashSet<Pair<TelegramClient, Int>>()

    init {
        scope.launch(Dispatchers.IO) {
            for (action in queue) {
                try {
                    action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logFailure(error)
                    active?.let { finish(it, R.string.call_failed) }
                } catch (error: LinkageError) {
                    logFailure(error)
                    active?.let { finish(it, R.string.call_failed) }
                }
            }
        }
    }

    private fun post(action: suspend () -> Unit) {
        queue.trySend(action)
    }

    fun onUpdate(update: TdApi.Object, client: TelegramClient) {
        if (update is TdApi.UpdateNewCallSignalingData) {
            if (update.data.size !in 16..128 * 1024) return
            if (queuedSignals.incrementAndGet() > 128) {
                queuedSignals.decrementAndGet()
                return
            }
            post {
                try {
                    receiveSignal(update, client)
                } finally {
                    queuedSignals.decrementAndGet()
                }
            }
            return
        }
        if (update is TdApi.UpdateAuthorizationState &&
            update.authorizationState !is TdApi.AuthorizationStateClosed &&
            update.authorizationState !is TdApi.AuthorizationStateLoggingOut) return
        post {
            when (update) {
                is TdApi.UpdateCall -> updateCall(client, update.call)
                is TdApi.UpdateAuthorizationState -> {
                    pendingIncoming.keys.removeAll { it.first === client }
                    localIncoming?.takeIf { it.client === client }?.let { finish(it, discard = false) }
                    active?.takeIf { it.client === client }?.let { finish(it, discard = false) }
                }
            }
        }
    }

    private fun receiveSignal(update: TdApi.UpdateNewCallSignalingData, client: TelegramClient) {
        val call = session(client, update.callId) ?: return
        val media = call.media
        if (media != null) {
            media.receive(update.data)
        } else if (call.earlySignaling.size < 48) {
            call.earlySignaling.addLast(update.data)
        }
    }

    fun dial(token: Long, userId: Long, name: String, rejected: () -> Unit) = post {
        if (active != null) {
            rejected()
            return@post
        }
        val client = app.accountsRepository.activeClient()
        val call = Session(client, userId, true, Screen(token, name, Phase.DIALING))
        active = call
        publish(call)
        expire(call, 65_000, R.string.call_no_answer)
        client.send(TdApi.CreateCall(userId, protocol(), false)) { result ->
            post {
                val id = (result as? TdApi.CallId)?.id
                if (active === call && id != null) {
                    call.id = id
                } else if (active === call) {
                    finish(call, R.string.call_unavailable, discard = false)
                } else if (id != null && call.id == 0) {
                    remember(client, id)
                    discard(client, id)
                }
            }
        }
    }

    fun answer(token: Long) = post {
        val call = session(token)?.takeUnless { it.accepted } ?: return@post
        call.accepted = true
        publish(call, call.screen.copy(phase = Phase.CONNECTING))
        expire(call, 35_000, R.string.call_failed)
        call.client.send(TdApi.AcceptCall(call.id, protocol())) { result ->
            if (result is TdApi.Error) post {
                if (isLive(call)) finish(call, R.string.call_unavailable)
            }
        }
    }

    fun end(token: Long, @StringRes error: Int? = null) = post {
        session(token)?.let { finish(it, error) }
    }

    fun toggleMute(token: Long) = post {
        val call = audioOwner(token)?.takeIf { it.media != null } ?: return@post
        val muted = !call.screen.muted
        call.media?.mute(muted)
        publishControls(call, call.screen.copy(muted = muted))
    }

    fun toggleSpeaker(token: Long) = post {
        val call = audioOwner(token) ?: return@post
        val speaker = !call.screen.speaker
        if (call.audio?.speaker(speaker) == true) publishControls(call, call.screen.copy(speaker = speaker))
    }

    private fun isLive(call: Session) = active === call || localIncoming === call

    private fun session(token: Long) = active?.takeIf { it.screen.token == token }
        ?: localIncoming?.takeIf { it.screen.token == token }

    private fun session(client: TelegramClient, id: Int) = active?.takeIf { it.client === client && it.id == id }
        ?: localIncoming?.takeIf { it.client === client && it.id == id }

    private fun audioOwner(token: Long): Session? {
        val call = session(token) ?: return null
        return if (call === localIncoming) active else call
    }

    private fun publishControls(call: Session, screen: Screen) {
        publish(call, screen)
        localIncoming?.takeIf { call === active && it.accepted }?.let {
            publish(it, it.screen.copy(speaker = screen.speaker, muted = screen.muted))
        }
    }

    private fun updateCall(client: TelegramClient, info: TdApi.Call) {
        val updateKey = client to info.id
        if (updateKey in closed) return
        if (info.state !is TdApi.CallStatePending) pendingIncoming.remove(updateKey)
        val current = active
        val call = session(client, info.id) ?: current?.takeIf {
            it.client === client && it.id == 0 && info.isOutgoing && it.userId == info.userId
        }
        if (call == null) {
            if (info.isOutgoing || info.state !is TdApi.CallStatePending) return
            // The second account's update can arrive before CreateCall reaches the caller.
            if (!info.isVideo && current?.outgoing == true && current.client !== client &&
                current.uniqueId == 0L && info.uniqueId != 0L && pendingIncoming.size < 8) {
                pendingIncoming[updateKey] = info
                return
            }
            val paired = current?.outgoing == true && current.client !== client &&
                current.uniqueId != 0L && current.uniqueId == info.uniqueId && localIncoming == null
            if ((current != null && !paired) || info.isVideo) {
                remember(client, info.id)
                discard(client, info.id, video = info.isVideo)
                return
            }
            val incoming = Session(client, info.userId, false,
                Screen(newToken(), app.getString(R.string.call_incoming), Phase.INCOMING), info.id, info.uniqueId)
            if (paired) localIncoming = incoming else active = incoming
            publish(incoming)
            expire(incoming, 65_000, R.string.call_no_answer)
            scope.launch {
                val user = client.send(TdApi.GetUser(info.userId)).getOrNull()
                val name = user?.let {
                    listOf(it.firstName.orEmpty(), it.lastName.orEmpty()).filter(String::isNotBlank).joinToString(" ")
                }
                if (!name.isNullOrEmpty()) post {
                    if (isLive(incoming)) publish(incoming, incoming.screen.copy(name = name))
                }
            }
            if (foreground()) runCatching {
                app.startActivity(CallActivity.open(app, incoming.screen.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        call.id = info.id
        if (info.uniqueId != 0L) call.uniqueId = info.uniqueId
        if (call === active && call.uniqueId != 0L) processPendingIncoming()
        when (val state = info.state) {
            is TdApi.CallStatePending -> if (call.outgoing && state.isReceived && call.screen.phase == Phase.DIALING) {
                publish(call, call.screen.copy(phase = Phase.RINGING))
            }
            is TdApi.CallStateExchangingKeys -> if (call.accepted && call.media == null) {
                publish(call, call.screen.copy(phase = Phase.CONNECTING))
                expire(call, 35_000, R.string.call_failed)
            }
            is TdApi.CallStateReady -> if (call.accepted && call.media == null) {
                if (call === active) {
                    localIncoming?.takeUnless { it.accepted }?.let { finish(it, discard = false) }
                }
                startMedia(call, state)
            }
            is TdApi.CallStateError -> finish(call, R.string.call_failed, discard = false)
            is TdApi.CallStateDiscarded -> finish(call, when (state.reason) {
                is TdApi.CallDiscardReasonDeclined -> R.string.call_declined
                is TdApi.CallDiscardReasonMissed -> R.string.call_no_answer
                is TdApi.CallDiscardReasonDisconnected -> R.string.call_failed
                else -> null
            }, discard = false)
            is TdApi.CallStateHangingUp -> finish(call, discard = false)
        }
    }

    private fun startMedia(call: Session, ready: TdApi.CallStateReady) {
        val selectedProtocol = ready.protocol.libraryVersions.firstOrNull { it in RustCall.protocolVersions }
        if (selectedProtocol == null) {
            finish(call, R.string.call_incompatible)
            return
        }
        val custom = runCatching { org.json.JSONObject(ready.customParameters.orEmpty()) }.getOrNull()
        if (custom?.optBoolean("network_use_mtproto") == true) {
            finish(call, R.string.call_incompatible)
            return
        }
        val servers = ready.servers.flatMap { server ->
            if (server.port !in 1..65535) return@flatMap emptyList()
            val type = server.type as? TdApi.CallServerTypeWebrtc ?: return@flatMap emptyList()
            listOf(server.ipAddress, server.ipv6Address).filter(String::isNotBlank).distinct().map { host ->
                RustCall.Server(host, server.port, type.supportsTurn, type.supportsStun,
                    type.username.orEmpty(), type.password.orEmpty())
            }
        }
        publish(call, call.screen.copy(phase = Phase.CONNECTING, fingerprint = ready.emojis.joinToString(" ")))
        expire(call, 35_000, R.string.call_failed)
        val media = RustCall(ready.encryptionKey, call.outgoing, ready.allowP2p, selectedProtocol, servers)
        call.media = media
        while (call.earlySignaling.isNotEmpty()) media.receive(call.earlySignaling.removeFirst())
        call.pump = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val event = media.poll() ?: continue
                post { if (isLive(call)) onMedia(call, event) }
                if (event == RustCall.Event.Failed) break
            }
        }
    }

    private fun onMedia(call: Session, event: RustCall.Event) {
        when (event) {
            is RustCall.Event.Signal -> call.client.send(TdApi.SendCallSignalingData(call.id, event.packet)) {}
            RustCall.Event.Connecting -> if (call.screen.phase == Phase.CONNECTED) {
                publish(call, call.screen.copy(phase = Phase.RECONNECTING))
                expire(call, 25_000, R.string.call_failed)
            }
            RustCall.Event.Connected -> {
                call.timeout?.cancel()
                val start = call.screen.connectedAt.takeIf { it != 0L } ?: SystemClock.elapsedRealtime()
                publish(call, call.screen.copy(phase = Phase.CONNECTED, connectedAt = start))
                startAudioIfReady()
            }
            RustCall.Event.Failed -> finish(call, R.string.call_failed)
            is RustCall.Event.RemoteMute -> if (localIncoming?.accepted != true) {
                publish(call, call.screen.copy(remoteMuted = event.muted))
            }
        }
    }

    private fun startAudioIfReady() {
        val owner = active ?: return
        val playback = localIncoming?.takeIf { it.accepted } ?: owner
        if (owner.audio != null || owner.screen.phase != Phase.CONNECTED ||
            playback.screen.phase != Phase.CONNECTED) return
        val captureMedia = owner.media ?: return
        val playbackMedia = playback.media ?: return
        // A local call needs one recorder and one player, across opposite ends of the call.
        val audio = CallAudio(app, captureMedia, playbackMedia, { apply -> post { apply() } }) {
            end(owner.screen.token, R.string.call_failed)
        }
        owner.audio = audio
        audio.start()
    }

    private fun publish(call: Session, screen: Screen = call.screen) {
        val screens = _screens.value
        val previous = screens[screen.token]
        call.screen = screen
        val retained = if (previous == null) screens.filterValues { it.phase != Phase.ENDED } else screens
        _screens.value = retained + (screen.token to screen)
        if (screen.phase == Phase.INCOMING && previous != screen) notifications.ring(screen)
        if (previous?.phase == Phase.INCOMING && screen.phase != Phase.INCOMING) notifications.stopRinging()
    }

    private fun expire(call: Session, millis: Long, @StringRes reason: Int) {
        call.timeout?.cancel()
        call.timeout = scope.launch {
            delay(millis)
            post { if (isLive(call)) finish(call, reason) }
        }
    }

    private fun finish(call: Session, @StringRes error: Int? = null, discard: Boolean = true) {
        val other = when {
            call === active -> {
                val paired = localIncoming
                active = null
                localIncoming = null
                paired
            }
            call === localIncoming -> {
                localIncoming = null
                if (call.accepted || discard) active.also { active = null } else null
            }
            else -> return
        }
        close(call, error, discard)
        if (other != null) {
            val otherError = if (!call.outgoing && !call.accepted && discard && error == null) {
                R.string.call_declined
            } else error
            close(other, otherError, discard = false)
        }
        if (active == null) processPendingIncoming()
    }

    private fun processPendingIncoming() {
        val updates = pendingIncoming.toMap()
        pendingIncoming.clear()
        updates.forEach { (key, info) -> updateCall(key.first, info) }
    }

    private fun close(call: Session, @StringRes error: Int?, discard: Boolean) {
        call.timeout?.cancel()
        call.pump?.cancel()
        if (call.id != 0) {
            remember(call.client, call.id)
            val start = call.screen.connectedAt
            val seconds = if (start == 0L) 0 else ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
            if (discard) discard(call.client, call.id, seconds, disconnected = error == R.string.call_failed)
        }
        runCatching { call.audio?.close() }
        runCatching { call.media?.close() }
        call.earlySignaling.clear()
        publish(call, call.screen.copy(phase = Phase.ENDED, error = error))
    }

    private fun remember(client: TelegramClient, id: Int) {
        closed.add(client to id)
        if (closed.size > 64) closed.remove(closed.first())
    }

    private fun discard(client: TelegramClient, id: Int, seconds: Int = 0,
                        disconnected: Boolean = false, video: Boolean = false) {
        client.send(TdApi.DiscardCall(id, disconnected, "", seconds, video, 0)) {}
    }

    private fun protocol() = TdApi.CallProtocol(true, true, 65, 92, RustCall.protocolVersions.toTypedArray())

    private fun logFailure(error: Throwable) {
        Log.w("TelegramVoiceCalls", "Voice call operation failed (${error.javaClass.simpleName})")
    }

    companion object {
        fun newToken(): Long = SystemClock.elapsedRealtimeNanos()
    }
}

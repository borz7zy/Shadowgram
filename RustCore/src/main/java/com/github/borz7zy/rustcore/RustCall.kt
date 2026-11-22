/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.rustcore

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class RustCall(
    key: ByteArray,
    outgoing: Boolean,
    allowP2p: Boolean,
    protocol: String,
    servers: List<Server>,
) : AutoCloseable {
    data class Server(
        val host: String,
        val port: Int,
        val turn: Boolean,
        val stun: Boolean,
        val username: String,
        val password: String,
    )

    sealed interface Event {
        data class Signal(val packet: ByteArray) : Event
        data object Connecting : Event
        data object Connected : Event
        data object Failed : Event
        data class RemoteMute(val muted: Boolean) : Event
    }

    private val handle: AtomicLong

    init {
        val json = JSONObject()
            .put("outgoing", outgoing)
            .put("allowP2p", allowP2p)
            .put("protocol", protocol)
            .put("servers", JSONArray().apply {
                servers.forEach {
                    put(JSONObject()
                        .put("host", it.host)
                        .put("port", it.port)
                        .put("turn", it.turn)
                        .put("stun", it.stun)
                        .put("username", it.username)
                        .put("password", it.password))
                }
            })
        val copy = key.copyOf()
        val id = try {
            NativeCall.nativeOpen(copy, json.toString())
        } finally {
            copy.fill(0)
        }
        check(id != 0L) { "Call engine failed to start" }
        handle = AtomicLong(id)
    }

    fun receive(packet: ByteArray): Boolean {
        val id = handle.get()
        return id != 0L && NativeCall.nativeSignal(id, packet)
    }

    /** Blocks for at most 120 ms. Use from a background dispatcher. */
    fun poll(): Event? {
        val id = handle.get()
        if (id == 0L) return null
        val bytes = NativeCall.nativePoll(id) ?: return null
        return when (bytes.firstOrNull()?.toInt()) {
            1 -> Event.Signal(bytes.copyOfRange(1, bytes.size))
            2 -> Event.Connecting
            3 -> Event.Connected
            4 -> Event.Failed
            5 -> Event.RemoteMute(bytes.getOrNull(1)?.toInt() == 1)
            else -> null
        }
    }

    fun mute(muted: Boolean) {
        val id = handle.get()
        if (id != 0L) NativeCall.nativeMute(id, muted)
    }

    fun capture(frame: ShortArray) {
        val id = handle.get()
        if (id != 0L) NativeCall.nativeCapture(id, frame)
    }

    fun playback(frame: ShortArray): Boolean {
        val id = handle.get()
        return id != 0L && NativeCall.nativePlayback(id, frame)
    }

    override fun close() {
        val id = handle.getAndSet(0L)
        if (id != 0L) NativeCall.nativeClose(id)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 50
        val protocolVersions = listOf("11.0.0", "10.0.0")
    }
}

private object NativeCall {
    init { System.loadLibrary("rustcore") }

    external fun nativeOpen(key: ByteArray, config: String): Long
    external fun nativeSignal(handle: Long, packet: ByteArray): Boolean
    external fun nativePoll(handle: Long): ByteArray?
    external fun nativeMute(handle: Long, muted: Boolean)
    external fun nativeCapture(handle: Long, frame: ShortArray)
    external fun nativePlayback(handle: Long, frame: ShortArray): Boolean
    external fun nativeClose(handle: Long)
}

/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.drinkless.tdlib.TdApi

/** TDLib must also learn about offline periods, so restoring the default network reconnects it. */
internal class TelegramNetworkMonitor(context: Context, private val send: (TdApi.SetNetworkType) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val state = NetworkReconnectState<Network>()
    private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network, capabilities)
        override fun onLost(network: Network) {
            synchronized(this@TelegramNetworkMonitor) {
                if (!closed) state.lost(network).forEach { send(TdApi.SetNetworkType(it)) }
            }
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback)
        connectivity.activeNetwork.let { update(it, connectivity.getNetworkCapabilities(it)) }
    }

    @Synchronized private fun update(network: Network?, capabilities: NetworkCapabilities?) {
        if (closed) return
        val next = when {
            // Android's validation probe can fail on a route that still reaches Telegram.
            network == null || capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> TdApi.NetworkTypeNone()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
            else -> TdApi.NetworkTypeOther()
        }
        state.update(network, next).forEach { send(TdApi.SetNetworkType(it)) }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        connectivity.unregisterNetworkCallback(callback)
    }
}

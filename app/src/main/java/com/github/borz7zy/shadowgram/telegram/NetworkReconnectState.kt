package com.github.borz7zy.shadowgram.telegram

import org.drinkless.tdlib.TdApi

/** Kept independent of Android callbacks so handovers and late loss events have one policy. */
internal class NetworkReconnectState<N> {
    private var network: N? = null
    private var type: Int? = null

    fun update(network: N?, next: TdApi.NetworkType): List<TdApi.NetworkType> {
        if (type == next.constructor && this.network == network) return emptyList()
        val reconnect = type == next.constructor && this.network != network && next !is TdApi.NetworkTypeNone
        this.network = network
        type = next.constructor
        return if (reconnect) listOf(TdApi.NetworkTypeNone(), next) else listOf(next)
    }

    fun lost(network: N): List<TdApi.NetworkType> =
        if (this.network == network) update(null, TdApi.NetworkTypeNone()) else emptyList()
}

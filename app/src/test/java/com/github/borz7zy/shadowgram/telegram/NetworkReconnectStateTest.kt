package com.github.borz7zy.shadowgram.telegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test

class NetworkReconnectStateTest {
    @Test fun restoredInternetReconnectsEvenWhenTransportHasNotChanged() {
        val state = NetworkReconnectState<String>()
        assertTrue(state.update(null, TdApi.NetworkTypeNone()).single() is TdApi.NetworkTypeNone)
        assertTrue(state.update("wifi", TdApi.NetworkTypeWiFi()).single() is TdApi.NetworkTypeWiFi)
        assertTrue(state.update("wifi", TdApi.NetworkTypeNone()).single() is TdApi.NetworkTypeNone)
        assertTrue(state.update("wifi", TdApi.NetworkTypeWiFi()).single() is TdApi.NetworkTypeWiFi)
    }

    @Test fun wifiHandoverResetsPendingConnections() {
        val state = NetworkReconnectState<String>()
        state.update("router A", TdApi.NetworkTypeWiFi())
        val events = state.update("router B", TdApi.NetworkTypeWiFi())
        assertEquals(2, events.size)
        assertTrue(events[0] is TdApi.NetworkTypeNone)
        assertTrue(events[1] is TdApi.NetworkTypeWiFi)
    }

    @Test fun lossOfOldNetworkDoesNotDisconnectTheReplacement() {
        val state = NetworkReconnectState<String>()
        state.update("wifi", TdApi.NetworkTypeWiFi())
        state.update("mobile", TdApi.NetworkTypeMobile())
        assertTrue(state.lost("wifi").isEmpty())
        assertTrue(state.lost("mobile").single() is TdApi.NetworkTypeNone)
    }

    @Test fun repeatedCapabilitiesDoNotCauseReconnectLoops() {
        val state = NetworkReconnectState<String>()
        state.update("vpn", TdApi.NetworkTypeOther())
        repeat(10) { assertTrue(state.update("vpn", TdApi.NetworkTypeOther()).isEmpty()) }
    }

    @Test fun genuineLossIsReportedOnlyOnce() {
        val state = NetworkReconnectState<String>()
        state.update("wifi", TdApi.NetworkTypeWiFi())
        assertTrue(state.lost("wifi").single() is TdApi.NetworkTypeNone)
        assertTrue(state.lost("wifi").isEmpty())
    }
}

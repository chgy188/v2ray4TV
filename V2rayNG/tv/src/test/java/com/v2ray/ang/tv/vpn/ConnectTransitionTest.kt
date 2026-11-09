package com.v2ray.ang.tv.vpn

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectTransitionTest {
    @Test
    fun movesToBackWhenConnectFinishes() {
        assertTrue(
            ConnectTransition.shouldMoveToBack(
                VpnContract.STATE_CONNECTING, VpnContract.STATE_CONNECTED
            )
        )
        assertTrue(
            ConnectTransition.shouldMoveToBack(
                VpnContract.STATE_DISCONNECTED, VpnContract.STATE_CONNECTED
            )
        )
    }

    @Test
    fun staysVisibleWhileConnectingAndOnFailure() {
        assertFalse(
            ConnectTransition.shouldMoveToBack(
                VpnContract.STATE_DISCONNECTED, VpnContract.STATE_CONNECTING
            )
        )
        assertFalse(
            ConnectTransition.shouldMoveToBack(
                VpnContract.STATE_CONNECTING, VpnContract.STATE_ERROR
            )
        )
        assertFalse(
            ConnectTransition.shouldMoveToBack(
                VpnContract.STATE_CONNECTED, VpnContract.STATE_DISCONNECTED
            )
        )
    }

    @Test
    fun doesNotRepeatForAlreadyConnectedState() {
        assertFalse(
            ConnectTransition.shouldMoveToBack(
                VpnContract.STATE_CONNECTED, VpnContract.STATE_CONNECTED
            )
        )
    }
}

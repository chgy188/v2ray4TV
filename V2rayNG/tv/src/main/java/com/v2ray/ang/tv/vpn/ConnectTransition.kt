package com.v2ray.ang.tv.vpn

/**
 * Pure JVM rule for leaving the TV home screen once a connect finishes. It owns no lifecycle,
 * holds no process state, and references no Android types: the distinction from MainActivity
 * is that this decision can be asserted in a JVM unit test while the Activity keeps the
 * framework call.
 */
internal object ConnectTransition {
    fun shouldMoveToBack(previousState: String, state: String): Boolean =
        previousState != VpnContract.STATE_CONNECTED && state == VpnContract.STATE_CONNECTED
}

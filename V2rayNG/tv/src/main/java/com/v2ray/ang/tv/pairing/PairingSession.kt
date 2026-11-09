package com.v2ray.ang.tv.pairing

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * One short-lived pairing attempt: a random token, the TV's LAN address, and the URL encoded
 * into the QR code. The token is never displayed on screen and is checked on every request.
 */
data class PairingSession(val token: String, val ip: String) {

    fun url(port: Int): String = "http://$ip:$port/?t=$token"

    companion object {
        const val TTL_MS = 5 * 60 * 1000L

        fun create(): PairingSession? {
            val ip = findLanAddress() ?: return null
            return PairingSession(UUID.randomUUID().toString().replace("-", ""), ip)
        }

        /** Prefer ordinary home-router ranges over VPN/docker virtual interfaces. */
        fun findLanAddress(): String? {
            val enumeration = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
                ?: return null
            val candidates = ArrayList<String>()
            while (enumeration.hasMoreElements()) {
                val nic = enumeration.nextElement()
                if (!nic.isUp || nic.isLoopback) continue
                for (binding in nic.interfaceAddresses) {
                    val address = binding.address
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    if (!address.isSiteLocalAddress) continue
                    val host = address.hostAddress ?: continue
                    candidates += host
                }
            }
            return candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("10.") }
                ?: candidates.firstOrNull { it.startsWith("172.") }
                ?: candidates.firstOrNull()
        }
    }
}

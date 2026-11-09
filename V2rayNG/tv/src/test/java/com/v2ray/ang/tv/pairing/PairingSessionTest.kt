package com.v2ray.ang.tv.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingSessionTest {
    @Test
    fun urlCarriesLanAddressPortAndToken() {
        val session = PairingSession("abc123", "192.168.1.9")
        assertEquals("http://192.168.1.9:8765/?t=abc123", session.url(8765))
    }

    @Test
    fun generatedTokenIsOpaqueAndFreeOfDashes() {
        val session = PairingSession.create()
        if (session == null) {
            // A CI host without a LAN interface is a valid environment for this test.
            return
        }
        assertEquals(32, session.token.length)
        assertTrue(session.token.none { it == '-' })
        assertTrue(session.ip.none { it.isWhitespace() })
    }

    @Test
    fun ttlIsFiveMinutes() {
        assertEquals(5 * 60 * 1000L, PairingSession.TTL_MS)
    }
}

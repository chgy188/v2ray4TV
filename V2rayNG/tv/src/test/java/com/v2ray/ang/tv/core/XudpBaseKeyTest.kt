package com.v2ray.ang.tv.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class XudpBaseKeyTest {
    private fun encoded(byteLength: Int): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(byteLength) { it.toByte() })

    @Test
    fun acceptsUrlSafeUnpaddedBase64Of32Bytes() {
        val value = encoded(32)
        assertEquals(43, value.length)
        assertTrue(XudpBaseKey.isValid(value))
    }

    @Test
    fun rejectsLegacyUuidStoredByEarlierBuilds() {
        assertFalse(XudpBaseKey.isValid("f88eb6f2-58fe-42a5-9300-2385c7dea52c"))
    }

    @Test
    fun rejectsWrongDecodedLengthAndIllegalCharacters() {
        assertFalse(XudpBaseKey.isValid(encoded(31)))
        assertFalse(XudpBaseKey.isValid(encoded(33)))
        assertFalse(XudpBaseKey.isValid(""))
        assertFalse(XudpBaseKey.isValid("=".repeat(43)))
        assertFalse(XudpBaseKey.isValid("a".repeat(42) + "+"))
    }
}

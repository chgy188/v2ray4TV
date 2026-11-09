package com.v2ray.ang.tv.core

/**
 * Format of the value passed as the second argument of Libv2ray.initCoreEnv.
 *
 * Xray reads it as the XUDP base key: the string is decoded as URL-safe, unpadded Base64
 * and must decode to exactly 32 bytes, so a valid encoding is 43 characters. The :app
 * module encodes 32 bytes the same way in Utils.getDeviceIdForXUDPBaseKey().
 *
 * Ownership distinction: TvApp owns generating and persisting the value for the whole
 * process; this object owns only the encoding format, which is pure JVM and unit-testable.
 */
object XudpBaseKey {
    const val BYTE_LENGTH = 32
    const val ENCODED_LENGTH = 43

    fun isValid(value: String): Boolean =
        value.length == ENCODED_LENGTH && value.all { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '_'
        }
}

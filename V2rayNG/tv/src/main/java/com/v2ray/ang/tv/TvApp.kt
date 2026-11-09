package com.v2ray.ang.tv

import android.app.Application
import android.content.Context
import android.util.Base64
import com.v2ray.ang.tv.core.XudpBaseKey
import java.io.File
import java.security.SecureRandom

class TvApp : Application() {

    lateinit var deviceId: String
        private set

    override fun onCreate() {
        super.onCreate()
        deviceId = readOrCreateDeviceId(this)
    }

    companion object {
        private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun from(context: Context): TvApp = context.applicationContext as TvApp

        /**
         * Stable, non-identifying ID handed to Libv2ray.initCoreEnv as its XUDP base key.
         * The TV module is a separate APK and does not read :app's MMKV storage, so it owns
         * this single value itself.
         *
         * Xray decodes this parameter as URL-safe, unpadded Base64 and requires exactly 32
         * bytes. Earlier builds persisted a raw UUID string, which decodes to 27 bytes and
         * made startLoop fail with "xray.xudp.basekey: invalid value (BaseKey must be 32
         * bytes)". A stored value that fails XudpBaseKey.isValid is regenerated, so an
         * in-place upgrade migrates itself without clearing app data.
         */
        private fun readOrCreateDeviceId(context: Context): String {
            val file = File(context.filesDir, "device-id")
            val existing = runCatching { file.readText().trim() }.getOrNull()
            if (existing != null && XudpBaseKey.isValid(existing)) return existing
            val bytes = ByteArray(XudpBaseKey.BYTE_LENGTH)
            SecureRandom().nextBytes(bytes)
            val created = Base64.encodeToString(bytes, BASE64_FLAGS)
            val tmp = File(context.filesDir, "device-id.tmp")
            if (runCatching {
                    tmp.writeText(created)
                    tmp.renameTo(file)
                }.getOrDefault(false).not()) {
                tmp.delete()
            }
            return created
        }
    }
}

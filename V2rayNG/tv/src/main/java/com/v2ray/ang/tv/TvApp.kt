package com.v2ray.ang.tv

import android.app.Application
import android.content.Context
import java.io.File
import java.util.UUID

class TvApp : Application() {

    lateinit var deviceId: String
        private set

    override fun onCreate() {
        super.onCreate()
        deviceId = readOrCreateDeviceId(this)
    }

    companion object {
        fun from(context: Context): TvApp = context.applicationContext as TvApp

        /**
         * Stable, non-identifying ID handed to Libv2ray.initCoreEnv as its XUDP base key.
         * The TV module is a separate APK and does not read :app's MMKV storage, so it owns
         * this single value itself.
         */
        private fun readOrCreateDeviceId(context: Context): String {
            val file = File(context.filesDir, "device-id")
            val existing = runCatching { file.readText().trim() }.getOrNull()
            if (!existing.isNullOrEmpty()) return existing
            val created = UUID.randomUUID().toString()
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

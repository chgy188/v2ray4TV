package com.v2ray.ang.tv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.v2ray.ang.tv.R
import com.v2ray.ang.tv.TvApp
import com.v2ray.ang.tv.config.ProfileStore
import com.v2ray.ang.tv.config.XrayConfigBuilder
import com.v2ray.ang.tv.core.XrayCore

class TvVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter foreground before reading configuration, opening the tun, or touching native
        // code; on Android 8+ startForegroundService allows only a few seconds for this.
        ensureForeground()
        when (intent?.action) {
            VpnContract.ACTION_CONNECT -> connect()
            VpnContract.ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        disconnect()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun connect() {
        if (XrayCore.isRunning()) {
            VpnContract.publish(this, VpnContract.STATE_CONNECTED)
            return
        }
        VpnContract.publish(this, VpnContract.STATE_CONNECTING)
        try {
            val profile = ProfileStore(filesDir).load().selectedProfile()
                ?: throw IllegalStateException(getString(R.string.error_no_selection))
            // MainActivity obtains the VPN grant. A non-null prepare() here means the grant
            // was revoked; a Service cannot show the system consent dialog.
            if (prepare(this) != null) {
                throw IllegalStateException(getString(R.string.error_vpn_permission))
            }
            val config = XrayConfigBuilder.build(profile)
            XrayCore.initialize(this, TvApp.from(this).deviceId)
            val descriptor = establishTun()
                ?: throw IllegalStateException(getString(R.string.error_core_generic))
            tun = descriptor
            XrayCore.start(config, descriptor.fd)
            VpnContract.publish(this, VpnContract.STATE_CONNECTED)
        } catch (error: Throwable) {
            TvLog.error("TvVpnService", "connect", null, error)
            cleanup()
            VpnContract.publish(this, VpnContract.STATE_ERROR, safeMessage(error))
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun disconnect() {
        cleanup()
        VpnContract.publish(this, VpnContract.STATE_DISCONNECTED)
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanup() {
        // stopLoop can block; give it a bounded thread so a hung core cannot wedge teardown.
        val stopper = Thread({ XrayCore.stop() }, "tv-core-stop")
        stopper.start()
        stopper.join(STOP_TIMEOUT_MS)
        runCatching { tun?.close() }
        tun = null
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setMtu(XrayConfigBuilder.VPN_MTU)
            .addAddress("10.10.14.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(XrayConfigBuilder.DNS_PRIMARY)
            .addDnsServer(XrayConfigBuilder.DNS_SECONDARY)
            // API 21+. Keeping our own process off the tunnel prevents a routing loop with
            // the local socks inbound.
            .addDisallowedApplication(packageName)
        return builder.establish()
    }

    private fun ensureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            legacyNotificationBuilder()
        }
        startForeground(
            NOTIFICATION_ID,
            builder
                .setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .build()
        )
    }

    /**
     * Notification.Builder(Context) was deprecated in API 26 in favour of the channel-aware
     * overload, which does not exist on API 21-25. Isolated compatibility function; removal
     * condition is raising minSdk to 26.
     */
    @Suppress("DEPRECATION")
    private fun legacyNotificationBuilder(): Notification.Builder = Notification.Builder(this)

    /**
     * API 21-23 only have stopForeground(boolean); STOP_FOREGROUND_REMOVE arrived in API 24.
     * Isolated compatibility function; removal condition is raising minSdk to 24.
     */
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForegroundLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundLegacy() {
        stopForeground(true)
    }

    /** Never surface a raw AAR error: it may quote the full Xray config containing secrets. */
    private fun safeMessage(error: Throwable): String {
        if (error is ClassNotFoundException) return getString(R.string.error_core_missing)
        val raw = error.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            "://" in lower || "password" in lower || "outbounds" in lower || '{' in raw ->
                getString(R.string.error_core_generic)
            raw.isBlank() -> error.javaClass.simpleName
            else -> raw.take(120)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 1701
        const val CHANNEL_ID = "tv-vpn"
        const val STOP_TIMEOUT_MS = 3000L
    }
}

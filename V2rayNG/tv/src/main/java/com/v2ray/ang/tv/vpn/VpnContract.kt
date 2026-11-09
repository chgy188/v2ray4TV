package com.v2ray.ang.tv.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.v2ray.ang.tv.core.XrayCore

object VpnContract {
    const val ACTION_CONNECT = "com.v2ray.ang.tv.action.CONNECT"
    const val ACTION_DISCONNECT = "com.v2ray.ang.tv.action.DISCONNECT"
    const val ACTION_STATE = "com.v2ray.ang.tv.action.STATE"
    const val EXTRA_STATE = "state"
    const val EXTRA_MESSAGE = "message"

    const val STATE_DISCONNECTED = "disconnected"
    const val STATE_CONNECTING = "connecting"
    const val STATE_CONNECTED = "connected"
    const val STATE_ERROR = "error"

    /** Written only by TvVpnService. The UI reads it, but XrayCore.isRunning is the authority. */
    @Volatile
    var publishedState: String = STATE_DISCONNECTED
        private set

    @Volatile
    var publishedMessage: String? = null
        private set

    fun command(context: Context, action: String) {
        val intent = Intent(context, TvVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun publish(context: Context, state: String, message: String? = null) {
        publishedState = state
        publishedMessage = message
        context.sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    fun currentState(): String =
        if (XrayCore.isRunning()) STATE_CONNECTED else publishedState

    fun receiver(onState: (String, String?) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STATE) return
            onState(
                intent.getStringExtra(EXTRA_STATE) ?: STATE_DISCONNECTED,
                intent.getStringExtra(EXTRA_MESSAGE)
            )
        }
    }
}

/**
 * The TV APK does not depend on the :app module and therefore has no access to its LogUtil.
 * This wrapper keeps the same contract: operation, component, a non-secret stable ID, and
 * the exception; exception messages are never logged because AAR errors may quote config.
 */
object TvLog {
    private const val TAG = "v2rayNG-TV"

    fun error(component: String, operation: String, stableId: String?, error: Throwable) {
        val id = stableId?.let { " id=$it" }.orEmpty()
        Log.e(TAG, "$component/$operation$id: ${error.javaClass.simpleName}", error)
    }
}

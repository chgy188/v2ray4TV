package com.v2ray.ang.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.v2ray.ang.tv.config.ProfileSnapshot
import com.v2ray.ang.tv.config.ProfileStore
import com.v2ray.ang.tv.util.UiScope
import com.v2ray.ang.tv.vpn.ConnectTransition
import com.v2ray.ang.tv.vpn.VpnContract

/**
 * Lean home screen. While disconnected there is exactly one primary control: pick a profile
 * from the drop-down list and the connection starts immediately. While connected that same
 * control becomes a one-press disconnect. All configuration lives on ConfigActivity.
 *
 * A button + single-choice dialog is used instead of a Spinner: on a D-pad a Spinner can
 * change its selection with left/right and fire onItemSelected during layout, which would
 * start a VPN unexpectedly. The dialog keeps "choose" and "connect" as one explicit action.
 */
class MainActivity : Activity() {
    private val scope = UiScope()
    private lateinit var store: ProfileStore
    private lateinit var statusText: TextView
    private lateinit var primaryButton: Button
    private lateinit var configButton: Button

    private val stateReceiver = VpnContract.receiver { state, message -> onState(state, message) }
    private var snapshot = ProfileSnapshot()
    private var currentState = VpnContract.STATE_DISCONNECTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ProfileStore(filesDir)
        statusText = findViewById(R.id.statusText)
        primaryButton = findViewById(R.id.primaryButton)
        configButton = findViewById(R.id.configButton)
        primaryButton.setOnClickListener { onPrimary() }
        configButton.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        primaryButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        registerStateReceiver()
        refresh()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        scope.close()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            VpnContract.command(this, VpnContract.ACTION_CONNECT)
        }
    }

    /**
     * Android 13 requires an explicit receiver flag for a non-system broadcast.
     * RECEIVER_NOT_EXPORTED is a compile-time inlined int, so API 21-32 never loads it.
     */
    private fun registerStateReceiver() {
        val filter = IntentFilter(VpnContract.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerLegacy(stateReceiver, filter)
        }
    }

    /**
     * API 21-32 have no registerReceiver(receiver, filter, flags) overload. The Android 13+
     * branch passes RECEIVER_NOT_EXPORTED, so this receiver is never exported on any release.
     * Isolated compatibility function; removal condition is raising minSdk to 33.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLegacy(receiver: BroadcastReceiver, filter: IntentFilter) {
        registerReceiver(receiver, filter)
    }

    private fun refresh() {
        currentState = VpnContract.currentState()
        scope.launch({ store.load() }) { result ->
            snapshot = result.getOrDefault(ProfileSnapshot())
            render()
        }
    }

    private fun onState(state: String, message: String?) {
        val previousState = currentState
        currentState = state
        render(message)
        // A TV remote has no reason to stay on this screen once the tunnel is up: return to
        // the launcher so the user can go straight back to watching. Only a fresh entry into
        // STATE_CONNECTED triggers this; re-opening an already-connected app stays visible.
        if (ConnectTransition.shouldMoveToBack(previousState, state)) {
            moveTaskToBack(true)
        }
    }

    private fun render(message: String? = VpnContract.publishedMessage) {
        val connected = currentState == VpnContract.STATE_CONNECTED
        val name = snapshot.selectedProfile()?.remarks?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_unnamed)
        statusText.text = when (currentState) {
            VpnContract.STATE_CONNECTED -> getString(R.string.status_connected, name)
            VpnContract.STATE_CONNECTING -> getString(R.string.status_connecting)
            VpnContract.STATE_ERROR -> getString(
                R.string.status_error,
                message ?: getString(R.string.error_core_generic)
            )
            else -> getString(R.string.status_disconnected)
        }
        primaryButton.text = getString(
            if (connected) R.string.action_disconnect else R.string.action_select_and_connect
        )
        primaryButton.contentDescription = primaryButton.text
    }

    private fun onPrimary() {
        if (currentState == VpnContract.STATE_CONNECTED) {
            VpnContract.command(this, VpnContract.ACTION_DISCONNECT)
            currentState = VpnContract.STATE_DISCONNECTED
            render()
            return
        }
        if (snapshot.profiles.isEmpty()) {
            Toast.makeText(this, R.string.no_profiles, Toast.LENGTH_LONG).show()
            return
        }
        val names = Array<CharSequence>(snapshot.profiles.size) { index ->
            snapshot.profiles[index].remarks.takeIf { value -> value.isNotBlank() }
                ?: getString(R.string.profile_unnamed)
        }
        val checked = snapshot.profiles.indexOfFirst { it.guid == snapshot.selected }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_select_profile)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                val guid = snapshot.profiles[which].guid
                scope.launch({ store.select(guid) }) { result ->
                    if (result.getOrDefault(false)) {
                        snapshot = snapshot.copy(selected = guid)
                        render()
                        startVpn()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * VpnService.prepare needs an Activity result. The AndroidX Activity Result API would
     * raise this module's minSdk, so the platform callback is the only supported fallback.
     * Removal condition: raising minSdk to a level where AndroidX is acceptable.
     */
    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            VpnContract.command(this, VpnContract.ACTION_CONNECT)
        }
    }

    private companion object {
        const val REQUEST_VPN = 1001
    }
}

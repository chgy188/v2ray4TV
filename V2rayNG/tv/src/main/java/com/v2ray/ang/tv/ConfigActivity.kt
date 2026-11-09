package com.v2ray.ang.tv

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonPrimitive
import com.v2ray.ang.tv.config.ProfileSnapshot
import com.v2ray.ang.tv.config.ProfileStore
import com.v2ray.ang.tv.config.ShareLinkParser
import com.v2ray.ang.tv.pairing.PairingServer
import com.v2ray.ang.tv.pairing.PairingSession
import com.v2ray.ang.tv.pairing.QrCodeGenerator
import com.v2ray.ang.tv.util.UiScope
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only screen that holds configuration content: the saved profile list, deletion, and
 * the pairing endpoint that opens with the screen. The TV shows a short-lived pairing URL;
 * the phone opens it in a browser and POSTs share links back over the LAN. The button under
 * the QR code is a second import entry point for boxes that already hold the configuration
 * in the system clipboard.
 */
class ConfigActivity : Activity() {
    private val scope = UiScope()
    private lateinit var store: ProfileStore
    private lateinit var profileList: ListView
    private lateinit var emptyText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var pairingText: TextView
    private lateinit var clipboardButton: Button

    private var pairing: PairingServer? = null
    private var snapshot = ProfileSnapshot()
    private var checkedGuid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        store = ProfileStore(filesDir)
        profileList = findViewById(R.id.profileList)
        emptyText = findViewById(R.id.emptyText)
        qrImage = findViewById(R.id.qrImage)
        pairingText = findViewById(R.id.pairingText)
        clipboardButton = findViewById(R.id.clipboardButton)

        clipboardButton.setOnClickListener { importFromClipboard() }
        findViewById<Button>(R.id.deleteButton).setOnClickListener { deleteChecked() }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        profileList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            checkedGuid = snapshot.profiles.getOrNull(position)?.guid
        }
        clipboardButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        refresh()
        startPairing()
    }

    override fun onStop() {
        stopPairing()
        super.onStop()
    }

    override fun onDestroy() {
        stopPairing()
        scope.close()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch({ store.load() }) { result ->
            snapshot = result.getOrDefault(ProfileSnapshot())
            checkedGuid = checkedGuid?.takeIf { id -> snapshot.profiles.any { it.guid == id } }
            renderList()
        }
    }

    private fun renderList() {
        val labels = snapshot.profiles.map {
            it.remarks.takeIf { value -> value.isNotBlank() } ?: getString(R.string.profile_unnamed)
        }
        profileList.adapter = ArrayAdapter(this, R.layout.item_profile, R.id.profileName, labels)
        profileList.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        emptyText.visibility = if (labels.isEmpty()) View.VISIBLE else View.GONE
        val index = snapshot.profiles.indexOfFirst { it.guid == checkedGuid }
        if (index >= 0) profileList.setItemChecked(index, true)
    }

    private fun deleteChecked() {
        val guid = checkedGuid
        if (guid == null) {
            Toast.makeText(this, R.string.action_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch({ store.remove(guid) }) {
            checkedGuid = null
            refresh()
        }
    }

    private fun startPairing() {
        stopPairing()
        val created = PairingSession.create()
        if (created == null) {
            pairingText.text = getString(R.string.pairing_no_network)
            qrImage.setImageDrawable(null)
            return
        }
        val page = buildPage(created.token)
        val server = PairingServer(created.token, page, ::importFromPhone)
        val port = runCatching { server.start() }.getOrElse {
            pairingText.text = getString(R.string.pairing_failed)
            return
        }
        pairing = server
        val url = created.url(port)
        pairingText.text = getString(R.string.pairing_waiting)
        scope.launch({ QrCodeGenerator.encode(url, QR_SIZE) }) { result ->
            result.getOrNull()?.let { qrImage.setImageBitmap(it) }
        }
    }

    private fun stopPairing() {
        pairing?.stop()
        pairing = null
    }

    /** Imports the system clipboard without a keyboard; parsing and disk I/O stay off the main thread. */
    private fun importFromClipboard() {
        // ClipboardManager needs no permission: the app is foreground when the button is pressed.
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = runCatching {
            manager?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch({ parseAndStore(text) }) { result ->
            showImportResult(result.getOrDefault(0))
        }
    }

    /** Runs on the PairingServer thread; disk writes and parsing stay off the main thread. */
    private fun importFromPhone(text: String): PairingServer.ImportResult {
        val count = runCatching { parseAndStore(text) }.getOrDefault(0)
        runOnUiThread { showImportResult(count) }
        return PairingServer.ImportResult(count > 0, count)
    }

    /** Shared by both import entry points; the caller owns the thread. */
    private fun parseAndStore(text: String): Int {
        val content = if (isSubscriptionUrl(text)) {
            runCatching { downloadSubscription(text.trim()) }.getOrNull().orEmpty()
        } else {
            text
        }
        return store.add(ShareLinkParser.parse(content).profiles)
    }

    private fun showImportResult(count: Int) {
        refresh()
        pairingText.text = if (count > 0) {
            getString(R.string.pairing_received, count)
        } else {
            getString(R.string.pairing_failed)
        }
    }

    private fun isSubscriptionUrl(text: String): Boolean {
        val trimmed = text.trim()
        return '\n' !in trimmed && '\r' !in trimmed &&
            (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))
    }

    private fun downloadSubscription(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val out = StringBuilder()
                while (out.length < ShareLinkParser.MAX_INPUT) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** All visible page text comes from Android resources, so every locale is covered. */
    private fun buildPage(token: String): String {
        val template = resources.openRawResource(R.raw.pairing_page)
            .bufferedReader().use { it.readText() }
        fun js(value: String) = JsonPrimitive(value).toString()
        return template
            .replace("__TITLE__", TextUtils.htmlEncode(getString(R.string.html_title)))
            .replace("__NOTE__", TextUtils.htmlEncode(getString(R.string.html_note)))
            .replace("__PLACEHOLDER__", TextUtils.htmlEncode(getString(R.string.html_placeholder)))
            .replace("__SUBMIT__", TextUtils.htmlEncode(getString(R.string.html_submit)))
            .replace("__EMPTY__", js(getString(R.string.html_empty)))
            .replace("__SENDING__", js(getString(R.string.html_sending)))
            .replace("__SUCCESS__", js(getString(R.string.html_success)))
            .replace("__FAILURE__", js(getString(R.string.html_failure)))
            .replace("__TOKEN__", token)
    }

    private companion object {
        const val QR_SIZE = 640
    }
}

package com.v2ray.ang.tv.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class ParseResult(
    val profiles: List<Profile> = emptyList(),
    val skipped: Int = 0,
    val error: String? = null,
)

/**
 * Turns share links / base64 subscriptions into Xray outbound objects.
 *
 * Scope: the lean TV client covers the common vmess / vless / trojan / shadowsocks / socks
 * share links and full Xray JSON configs. Policy groups, proxy chains and plugin chains are
 * deliberately left to the :app module, which owns those formats. This class is pure JVM so
 * every branch is exercised by a unit test without an Android runtime.
 */
object ShareLinkParser {
    const val MAX_INPUT = 256 * 1024

    fun parse(input: String): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) return ParseResult(error = "empty")
        if (text.length > MAX_INPUT) return ParseResult(error = "too-large")
        val lines = expand(text)
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParseResult(error = "empty")
        val profiles = ArrayList<Profile>()
        var skipped = 0
        for (line in lines) {
            val profile = parseLine(line)
            if (profile == null) skipped += 1 else profiles += profile
        }
        if (profiles.isEmpty()) return ParseResult(skipped = skipped, error = "unsupported")
        return ParseResult(profiles, skipped)
    }

    /** A single base64 blob with no link scheme is a subscription, not a configuration. */
    private fun expand(text: String): String {
        if (text.contains("://") || text.startsWith("{")) return text
        val decoded = Base64Codec.decodeToString(text)
        return if (decoded != null && decoded.contains("://")) decoded else text
    }

    private fun parseLine(line: String): Profile? = runCatching {
        when (line.substringBefore("://").lowercase()) {
            "vmess" -> parseVmess(line)
            "vless" -> parseVless(line)
            "trojan" -> parseTrojan(line)
            "ss" -> parseShadowsocks(line)
            "socks", "socks5" -> parseSocks(line)
            else -> if (line.startsWith("{")) parseRawJson(line) else null
        }
    }.getOrNull()

    private fun parseVmess(line: String): Profile? {
        val payload = line.substringAfter("://").substringBefore('#')
        val json = Base64Codec.decodeToString(payload) ?: return null
        val obj = JsonParser.parseString(json).asJsonObject
        val address = obj.string("add") ?: return null
        val port = obj.string("port")?.toIntOrNull() ?: return null
        val id = obj.string("id") ?: return null
        val remarks = obj.string("ps")?.takeIf { it.isNotBlank() } ?: "$address:$port"
        val user = JsonObject().apply {
            addProperty("id", id)
            addProperty("alterId", obj.string("aid")?.toIntOrNull() ?: 0)
            addProperty("security", obj.string("scy")?.takeIf { it.isNotBlank() } ?: "auto")
        }
        val vnext = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            add("users", JsonArray().apply { add(user) })
        }
        val outbound = JsonObject().apply {
            addProperty("protocol", "vmess")
            add("settings", JsonObject().apply { add("vnext", JsonArray().apply { add(vnext) }) })
            add(
                "streamSettings",
                transport(
                    network = obj.string("net")?.takeIf { it.isNotBlank() } ?: "tcp",
                    security = if (obj.string("tls") == "tls") "tls" else "none",
                    host = obj.string("host"),
                    path = obj.string("path"),
                    sni = obj.string("sni"),
                    fp = obj.string("fp"),
                    alpn = obj.string("alpn"),
                    serviceName = obj.string("serviceName") ?: obj.string("seed"),
                    insecure = null,
                    publicKey = null,
                    shortId = null,
                    spiderX = null,
                )
            )
        }
        return newProfile(remarks, "vmess", outbound, line)
    }

    private fun parseVless(line: String): Profile? {
        val uri = URI(line)
        val address = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val id = uri.userInfo?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return null
        val q = query(uri.rawQuery)
        val remarks = uri.fragment?.takeIf { it.isNotBlank() } ?: "$address:$port"
        val user = JsonObject().apply {
            addProperty("id", id)
            addProperty("encryption", "none")
            q["flow"]?.let { addProperty("flow", it) }
        }
        val vnext = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            add("users", JsonArray().apply { add(user) })
        }
        val outbound = JsonObject().apply {
            addProperty("protocol", "vless")
            add("settings", JsonObject().apply { add("vnext", JsonArray().apply { add(vnext) }) })
            add("streamSettings", transportFromQuery(q))
        }
        return newProfile(remarks, "vless", outbound, line)
    }

    private fun parseTrojan(line: String): Profile? {
        val uri = URI(line)
        val address = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val password = uri.userInfo?.substringBefore(':') ?: return null
        val q = query(uri.rawQuery)
        val remarks = uri.fragment?.takeIf { it.isNotBlank() } ?: "$address:$port"
        val server = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            addProperty("password", password)
        }
        val outbound = JsonObject().apply {
            addProperty("protocol", "trojan")
            add("settings", JsonObject().apply { add("servers", JsonArray().apply { add(server) }) })
            add("streamSettings", transportFromQuery(q, defaultSecurity = "tls"))
        }
        return newProfile(remarks, "trojan", outbound, line)
    }

    private fun parseShadowsocks(line: String): Profile? {
        val body = line.substringAfter("://")
        val fragment = body.substringAfterLast('#', "")
        val mainPart = body.substringBeforeLast('#')
        val (credentialsPart, endpointPart) = if (mainPart.contains('@')) {
            mainPart.substringBeforeLast('@') to mainPart.substringAfterLast('@')
        } else {
            // Legacy ss://base64(method:password@host:port)#remarks
            val decoded = Base64Codec.decodeToString(mainPart) ?: return null
            decoded.substringBeforeLast('@') to decoded.substringAfterLast('@')
        }
        val credentials = if (credentialsPart.contains(':')) {
            credentialsPart
        } else {
            Base64Codec.decodeToString(credentialsPart) ?: return null
        }
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        if (method.isBlank() || password.isBlank()) return null
        val endpoint = endpointPart.substringBefore('?')
        val hostUri = runCatching { URI("tcp://$endpoint") }.getOrNull() ?: return null
        val address = hostUri.host ?: return null
        val port = hostUri.port.takeIf { it > 0 } ?: return null
        val remarks = decodeFragment(fragment).takeIf { it.isNotBlank() } ?: "$address:$port"
        val server = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            addProperty("method", method)
            addProperty("password", password)
        }
        val outbound = JsonObject().apply {
            addProperty("protocol", "shadowsocks")
            add("settings", JsonObject().apply { add("servers", JsonArray().apply { add(server) }) })
        }
        return newProfile(remarks, "shadowsocks", outbound, line)
    }

    private fun parseSocks(line: String): Profile? {
        val uri = URI(line)
        val address = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 1080
        val userInfo = uri.userInfo
        val credentials = when {
            userInfo.isNullOrBlank() -> ""
            userInfo.contains(':') -> userInfo
            else -> Base64Codec.decodeToString(userInfo) ?: userInfo
        }
        val server = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            val user = credentials.substringBefore(':')
            if (user.isNotBlank()) {
                add(
                    "users",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("user", user)
                                addProperty("pass", credentials.substringAfter(':', ""))
                            }
                        )
                    }
                )
            }
        }
        val outbound = JsonObject().apply {
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply { add("servers", JsonArray().apply { add(server) }) })
        }
        val remarks = uri.fragment?.takeIf { it.isNotBlank() } ?: "$address:$port"
        return newProfile(remarks, "socks", outbound, line)
    }

    /** A full Xray JSON config is reduced to its first outbound; TV UI stays read-only. */
    private fun parseRawJson(line: String): Profile? {
        val root = JsonParser.parseString(line).asJsonObject
        val first = root.getAsJsonArray("outbounds")?.firstOrNull()?.asJsonObject ?: return null
        val outbound = JsonParser.parseString(first.toString()).asJsonObject
        outbound.remove("tag")
        return newProfile("", "custom", outbound, line)
    }

    private fun transportFromQuery(q: Map<String, String>, defaultSecurity: String = "none") = transport(
        network = q["type"] ?: "tcp",
        security = q["security"] ?: defaultSecurity,
        host = q["host"],
        path = q["path"],
        sni = q["sni"],
        fp = q["fp"],
        alpn = q["alpn"],
        serviceName = q["servicename"],
        insecure = q["allowinsecure"] ?: q["insecure"],
        publicKey = q["pbk"],
        shortId = q["sid"],
        spiderX = q["spx"],
    )

    private fun transport(
        network: String,
        security: String,
        host: String?,
        path: String?,
        sni: String?,
        fp: String?,
        alpn: String?,
        serviceName: String?,
        insecure: String?,
        publicKey: String?,
        shortId: String?,
        spiderX: String?,
    ): JsonObject {
        val net = network.lowercase()
        val tls = security.lowercase()
        val stream = JsonObject().apply {
            addProperty("network", net)
            addProperty("security", tls)
        }
        when (net) {
            "ws" -> stream.add(
                "wsSettings",
                JsonObject().apply {
                    if (!path.isNullOrBlank()) addProperty("path", normalizePath(path))
                    if (!host.isNullOrBlank()) {
                        add("headers", JsonObject().apply { addProperty("Host", host) })
                    }
                }
            )
            "grpc" -> if (!serviceName.isNullOrBlank()) {
                stream.add("grpcSettings", JsonObject().apply { addProperty("serviceName", serviceName) })
            }
            "h2", "http" -> stream.add(
                "httpSettings",
                JsonObject().apply {
                    if (!host.isNullOrBlank()) add("host", JsonArray().apply { add(host) })
                    if (!path.isNullOrBlank()) addProperty("path", normalizePath(path))
                }
            )
        }
        when (tls) {
            "tls" -> stream.add("tlsSettings", tlsSettings(sni, fp, alpn, insecure))
            "reality" -> stream.add(
                "realitySettings",
                JsonObject().apply {
                    if (!sni.isNullOrBlank()) addProperty("serverName", sni)
                    if (!publicKey.isNullOrBlank()) addProperty("publicKey", publicKey)
                    if (!shortId.isNullOrBlank()) addProperty("shortId", shortId)
                    if (!spiderX.isNullOrBlank()) addProperty("spiderX", spiderX)
                    if (!fp.isNullOrBlank()) addProperty("fingerprint", fp)
                }
            )
        }
        return stream
    }

    private fun tlsSettings(sni: String?, fp: String?, alpn: String?, insecure: String?) =
        JsonObject().apply {
            if (!sni.isNullOrBlank()) addProperty("serverName", sni)
            if (!fp.isNullOrBlank()) addProperty("fingerprint", fp)
            if (!alpn.isNullOrBlank()) {
                add("alpn", JsonArray().apply { alpn.split(',').forEach { add(it.trim()) } })
            }
            if (insecure == "1" || insecure.equals("true", ignoreCase = true)) {
                addProperty("allowInsecure", true)
            }
        }

    private fun JsonObject.string(name: String): String? {
        if (!has(name) || get(name).isJsonNull) return null
        return runCatching { get(name).asString }.getOrNull()
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            val key = pair.substringBefore('=')
            if (key.isBlank()) continue
            val value = pair.substringAfter('=', "")
            result[key.lowercase()] = decodeFragment(value)
        }
        return result
    }

    private fun decodeFragment(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun normalizePath(path: String): String = if (path.startsWith("/")) path else "/$path"

    private fun newProfile(remarks: String, protocol: String, outbound: JsonObject, raw: String) =
        Profile(
            guid = UUID.randomUUID().toString(),
            remarks = remarks.trim(),
            protocol = protocol,
            outbound = outbound.toString(),
            raw = raw,
        )
}

/**
 * java.util.Base64 is only present from Android 8 (API 26); android.util.Base64 cannot run
 * in a JVM unit test. This codec is pure Kotlin and accepts standard and URL-safe alphabets
 * with or without padding, which is what share links and subscriptions use.
 * Removal condition: this module raises minSdk to 26 and stops running JVM parser tests.
 */
private object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val lookup = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, c -> this[c.code] = index }
        this['-'.code] = 62
        this['_'.code] = 63
    }

    fun decodeToString(input: String): String? {
        val clean = input.filter { !it.isWhitespace() }.trimEnd('=')
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        var buffer = 0
        var bits = 0
        val out = ByteArray(clean.length * 3 / 4 + 3)
        var size = 0
        for (c in clean) {
            val value = if (c.code < 128) lookup[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[size++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        val text = String(out.copyOf(size), Charsets.UTF_8)
        if ('\u0000' in text || '\uFFFD' in text) return null
        return text
    }
}

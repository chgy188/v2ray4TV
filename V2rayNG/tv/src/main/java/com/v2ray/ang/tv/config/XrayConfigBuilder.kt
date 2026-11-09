package com.v2ray.ang.tv.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Builds the complete Xray config passed to CoreController.startLoop.
 *
 * The tun is owned by Xray itself (the `tun` inbound plus the fd handed to startLoop), not
 * by hev-socks5-tunnel. That keeps the TV client free of the hev JNI libraries and of the
 * android-24 native build used by :app: no additional native artifact is needed beyond
 * libv2ray.aar. The socks inbound is retained for local consumers on the TV box.
 */
object XrayConfigBuilder {
    const val SOCKS_PORT = 10808
    const val VPN_MTU = 1500
    const val DNS_PRIMARY = "1.1.1.1"
    const val DNS_SECONDARY = "223.5.5.5"

    fun build(profile: Profile): String {
        val proxy = runCatching { JsonParser.parseString(profile.outbound).asJsonObject }
            .getOrElse { throw IllegalArgumentException("Stored outbound is not valid JSON", it) }
        proxy.addProperty("tag", "proxy")

        val inboundSocks = JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("listen", "127.0.0.1")
            addProperty("port", SOCKS_PORT)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
                addProperty("userLevel", 8)
            })
            add("sniffing", sniffing())
        }
        val inboundTun = JsonObject().apply {
            addProperty("tag", "tun")
            addProperty("protocol", "tun")
            add("settings", JsonObject().apply {
                addProperty("name", "xray0")
                addProperty("MTU", VPN_MTU)
                addProperty("userLevel", 8)
            })
            add("sniffing", sniffing())
        }
        val direct = JsonObject().apply {
            addProperty("protocol", "freedom")
            addProperty("tag", "direct")
            add("streamSettings", JsonObject().apply {
                add("sockopt", JsonObject().apply { addProperty("domainStrategy", "UseIP") })
            })
        }
        val block = JsonObject().apply {
            addProperty("protocol", "blackhole")
            addProperty("tag", "block")
            add("settings", JsonObject().apply {
                add("response", JsonObject().apply { addProperty("type", "http") })
            })
        }

        return JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply { add(inboundSocks); add(inboundTun) })
            add("outbounds", JsonArray().apply { add(proxy); add(direct); add(block) })
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "AsIs")
                add("rules", JsonArray())
            })
            add("dns", JsonObject().apply {
                add("servers", JsonArray().apply { add(DNS_PRIMARY); add(DNS_SECONDARY) })
            })
        }.toString()
    }

    private fun sniffing() = JsonObject().apply {
        addProperty("enabled", true)
        add("destOverride", JsonArray().apply { add("http"); add("tls"); add("quic") })
    }
}

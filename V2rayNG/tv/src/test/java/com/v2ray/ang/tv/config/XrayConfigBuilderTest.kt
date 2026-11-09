package com.v2ray.ang.tv.config

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class XrayConfigBuilderTest {
    private fun profile(outbound: String) = Profile(
        guid = "guid-1",
        remarks = "node",
        protocol = "vmess",
        outbound = outbound,
        raw = "vmess://x",
    )

    @Test
    fun buildsSocksAndTunInbounds() {
        val json = JsonParser.parseString(
            XrayConfigBuilder.build(profile("""{"protocol":"vmess","settings":{}}"""))
        ).asJsonObject
        val inbounds = json.getAsJsonArray("inbounds")
        assertEquals(2, inbounds.size())
        assertEquals("socks", inbounds[0].asJsonObject.get("protocol").asString)
        assertEquals(XrayConfigBuilder.SOCKS_PORT, inbounds[0].asJsonObject.get("port").asInt)
        assertEquals("tun", inbounds[1].asJsonObject.get("protocol").asString)
        assertEquals(
            XrayConfigBuilder.VPN_MTU,
            inbounds[1].asJsonObject.getAsJsonObject("settings").get("MTU").asInt
        )
    }

    @Test
    fun firstOutboundIsTheSelectedProxy() {
        val json = JsonParser.parseString(
            XrayConfigBuilder.build(profile("""{"protocol":"trojan","settings":{}}"""))
        ).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds")
        assertEquals(3, outbounds.size())
        assertEquals("trojan", outbounds[0].asJsonObject.get("protocol").asString)
        assertEquals("proxy", outbounds[0].asJsonObject.get("tag").asString)
        assertEquals("direct", outbounds[1].asJsonObject.get("tag").asString)
        assertEquals("block", outbounds[2].asJsonObject.get("tag").asString)
    }

    @Test
    fun dnsServersArePresent() {
        val json = JsonParser.parseString(
            XrayConfigBuilder.build(profile("""{"protocol":"vmess"}"""))
        ).asJsonObject
        val servers = json.getAsJsonObject("dns").getAsJsonArray("servers")
        assertEquals(2, servers.size())
        assertEquals(XrayConfigBuilder.DNS_PRIMARY, servers[0].asString)
    }

    @Test
    fun rejectsInvalidStoredOutbound() {
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigBuilder.build(profile("not json"))
        }
    }
}

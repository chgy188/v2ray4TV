package com.v2ray.ang.tv.config

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class ShareLinkParserTest {
    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private val vmessJson = """{"v":"2","ps":"Test Node","add":"example.com","port":"443","id":"11111111-1111-1111-1111-111111111111","aid":"0","scy":"auto","net":"ws","tls":"tls","host":"cdn.example.com","path":"/ray","sni":"example.com"}"""

    private fun outboundOf(result: ParseResult) =
        JsonParser.parseString(result.profiles[0].outbound).asJsonObject

    @Test
    fun rejectsEmptyInput() {
        val result = ShareLinkParser.parse("   \n  ")
        assertTrue(result.profiles.isEmpty())
        assertEquals("empty", result.error)
    }

    @Test
    fun rejectsOversizedInput() {
        val result = ShareLinkParser.parse("a".repeat(ShareLinkParser.MAX_INPUT + 1))
        assertTrue(result.profiles.isEmpty())
        assertEquals("too-large", result.error)
    }

    @Test
    fun skipsUnsupportedLines() {
        val result = ShareLinkParser.parse("not-a-link\nvmess://" + b64(vmessJson))
        assertEquals(1, result.skipped)
        assertEquals(1, result.profiles.size)
        assertNull(result.error)
    }

    @Test
    fun returnsUnsupportedWhenNothingParsed() {
        val result = ShareLinkParser.parse("hello world")
        assertTrue(result.profiles.isEmpty())
        assertEquals("unsupported", result.error)
        assertEquals(1, result.skipped)
    }

    @Test
    fun parsesVmessWebsocketTlsLink() {
        val result = ShareLinkParser.parse("vmess://" + b64(vmessJson))
        assertEquals(1, result.profiles.size)
        val profile = result.profiles[0]
        assertEquals("Test Node", profile.remarks)
        assertEquals("vmess", profile.protocol)
        val outbound = outboundOf(result)
        assertEquals("vmess", outbound.get("protocol").asString)
        val vnext = outbound.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
        assertEquals("example.com", vnext.get("address").asString)
        assertEquals(443, vnext.get("port").asInt)
        assertEquals(
            "11111111-1111-1111-1111-111111111111",
            vnext.getAsJsonArray("users")[0].asJsonObject.get("id").asString
        )
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("ws", stream.get("network").asString)
        assertEquals("tls", stream.get("security").asString)
        assertEquals("/ray", stream.getAsJsonObject("wsSettings").get("path").asString)
        assertEquals("example.com", stream.getAsJsonObject("tlsSettings").get("serverName").asString)
    }

    @Test
    fun parsesVlessRealityLink() {
        val link = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=ws&security=reality&sni=www.example.com&pbk=PUBLICKEY&sid=abcd&fp=chrome&path=%2Fray#My%20Node"
        val result = ShareLinkParser.parse(link)
        assertEquals(1, result.profiles.size)
        assertEquals("My Node", result.profiles[0].remarks)
        val stream = outboundOf(result).getAsJsonObject("streamSettings")
        assertEquals("reality", stream.get("security").asString)
        val reality = stream.getAsJsonObject("realitySettings")
        assertEquals("PUBLICKEY", reality.get("publicKey").asString)
        assertEquals("abcd", reality.get("shortId").asString)
        assertEquals("chrome", reality.get("fingerprint").asString)
    }

    @Test
    fun trojanDefaultsToTls() {
        val result = ShareLinkParser.parse("trojan://secret@example.com:443?sni=t.example.com#Trojan")
        assertEquals(1, result.profiles.size)
        val outbound = outboundOf(result)
        assertEquals("trojan", outbound.get("protocol").asString)
        assertEquals(
            "secret",
            outbound.getAsJsonObject("settings").getAsJsonArray("servers")[0]
                .asJsonObject.get("password").asString
        )
        assertEquals("tls", outbound.getAsJsonObject("streamSettings").get("security").asString)
    }

    @Test
    fun parsesShadowsocksSip002Link() {
        val credentials = b64("aes-256-gcm:password")
        val link = "ss://$credentials@example.com:8388#SS%20Node"
        val result = ShareLinkParser.parse(link)
        assertEquals(1, result.profiles.size)
        assertEquals("SS Node", result.profiles[0].remarks)
        val server = outboundOf(result).getAsJsonObject("settings")
            .getAsJsonArray("servers")[0].asJsonObject
        assertEquals("example.com", server.get("address").asString)
        assertEquals(8388, server.get("port").asInt)
        assertEquals("aes-256-gcm", server.get("method").asString)
        assertEquals("password", server.get("password").asString)
    }

    @Test
    fun parsesLegacyShadowsocksLink() {
        val link = "ss://" + b64("aes-128-gcm:password@example.com:8388") + "#Legacy"
        val result = ShareLinkParser.parse(link)
        assertEquals(1, result.profiles.size)
        val server = outboundOf(result).getAsJsonObject("settings")
            .getAsJsonArray("servers")[0].asJsonObject
        assertEquals("example.com", server.get("address").asString)
        assertEquals("password", server.get("password").asString)
    }

    @Test
    fun parsesSocksLinkWithCredentials() {
        val result = ShareLinkParser.parse("socks://user:pass@example.com:1080#Socks")
        assertEquals(1, result.profiles.size)
        val server = outboundOf(result).getAsJsonObject("settings")
            .getAsJsonArray("servers")[0].asJsonObject
        assertEquals("example.com", server.get("address").asString)
        assertEquals("user", server.getAsJsonArray("users")[0].asJsonObject.get("user").asString)
    }

    @Test
    fun expandsBase64SubscriptionWithSeveralLinks() {
        val subscription = b64(
            "vmess://" + b64(vmessJson) + "\n" +
                "trojan://secret@example.com:443#T\n" +
                "garbage\n"
        )
        val result = ShareLinkParser.parse(subscription)
        assertEquals(2, result.profiles.size)
        assertEquals(1, result.skipped)
        assertEquals("vmess", result.profiles[0].protocol)
        assertEquals("trojan", result.profiles[1].protocol)
    }

    @Test
    fun fullXrayJsonKeepsFirstOutboundWithoutTag() {
        val json = """{"inbounds":[],"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}]}"""
        val result = ShareLinkParser.parse(json)
        assertEquals(1, result.profiles.size)
        val outbound = outboundOf(result)
        assertEquals("freedom", outbound.get("protocol").asString)
        assertTrue(!outbound.has("tag"))
        assertNotNull(result.profiles[0].guid)
    }

    @Test
    fun urlSafeBase64VmessIsAccepted() {
        val standard = b64(vmessJson)
        val urlSafe = standard.replace('+', '-').replace('/', '_').trimEnd('=')
        val result = ShareLinkParser.parse("vmess://$urlSafe")
        assertEquals(1, result.profiles.size)
    }
}

package com.v2ray.ang.tv.pairing

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket

class PairingServerTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private var server: PairingServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun start(onImport: (String) -> PairingServer.ImportResult): Int {
        server = PairingServer(token, "<html>PAGE</html>", onImport)
        return server!!.start()
    }

    private fun request(port: Int, raw: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun headerLines(extra: String): String =
        if (extra.isBlank()) "" else extra.trimEnd('\r', '\n') + "\r\n"

    private fun get(port: Int, target: String, extra: String = ""): String =
        request(
            port,
            "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" + headerLines(extra) + "\r\n"
        )

    private fun post(port: Int, target: String, body: String, extra: String = ""): String {
        val length = body.toByteArray(Charsets.UTF_8).size
        val headers = "POST $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "Content-Length: $length\r\n" + headerLines(extra) + "\r\n"
        return request(port, headers + body)
    }

    @Test
    fun servesPageWithValidQueryToken() {
        val port = start { PairingServer.ImportResult(true, 0) }
        val response = get(port, "/?t=$token")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("<html>PAGE</html>"))
        assertTrue(response.contains("Cache-Control: no-store"))
    }

    @Test
    fun servesPageWithValidHeaderToken() {
        val port = start { PairingServer.ImportResult(true, 0) }
        val response = get(port, "/", "X-Pairing-Token: $token\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
    }

    @Test
    fun rejectsMissingOrWrongToken() {
        val port = start { PairingServer.ImportResult(true, 1) }
        assertTrue(get(port, "/").startsWith("HTTP/1.1 403"))
        assertTrue(get(port, "/?t=wrong").startsWith("HTTP/1.1 403"))
        assertTrue(post(port, "/import?t=wrong", "vmess://x").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun importForwardsBodyAndReportsCount() {
        val received = ArrayList<String>()
        val port = start { text ->
            received += text
            PairingServer.ImportResult(true, 2)
        }
        val response = post(port, "/import?t=$token", "vmess://abc\nvless://def")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"), response)
        assertTrue(response.contains("\"ok\":true"), response)
        assertTrue(response.contains("\"count\":2"), response)
        assertEquals(1, received.size)
        assertEquals("vmess://abc\nvless://def", received[0])
        // The submitted configuration must never be echoed back.
        assertTrue(!response.contains("vmess://"), response)
    }

    @Test
    fun importFailureIsReportedWithoutThrowing() {
        val port = start { throw IllegalArgumentException("bad input") }
        val response = post(port, "/import?t=$token", "bad")
        assertTrue(response.contains("\"ok\":false"), response)
    }

    @Test
    fun rejectsOversizedBody() {
        val port = start { PairingServer.ImportResult(true, 1) }
        val response = post(port, "/import?t=$token", "x".repeat(256 * 1024 + 1))
        assertTrue(response.startsWith("HTTP/1.1 413"), response)
    }

    @Test
    fun unknownPathReturns404() {
        val port = start { PairingServer.ImportResult(true, 0) }
        assertTrue(get(port, "/other?t=$token").startsWith("HTTP/1.1 404"))
    }
}

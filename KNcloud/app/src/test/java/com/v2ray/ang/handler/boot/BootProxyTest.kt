package com.v2ray.ang.handler.boot

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BootProxyTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @After
    fun tearDown() {
        BootProxy.stop()
        BootProxy.configProvider = { BootNodeConfig.config() }
        BootProxy.allowProvider = BootProxy::defaultAllow
        BootProxy.maxLifetimeMs = 3 * 60 * 1000L
    }

    @Test
    fun chacha20Poly1305Rfc8439Vector() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val expected = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691"
        )
        val c = ChaCha20Poly1305(key)
        assertArrayEquals(expected, c.seal(nonce, pt, aad))
        assertArrayEquals(pt, c.open(nonce, expected, aad))
        val bad = expected.copyOf().also { it[3] = (it[3] + 1).toByte() }
        try {
            c.open(nonce, bad, aad); fail("tampered ciphertext accepted")
        } catch (_: java.security.GeneralSecurityException) {}
    }

    @Test
    fun evpBytesToKeyMatchesShadowsocks() {
        assertArrayEquals(hex("3858f62230ac3c915f300c664312c63f568378529614d22ddb49237d2f60bfdf"), ShadowsocksAead.evpBytesToKey("foobar"))
    }

    /** Ciphertext produced by golang.org/x/crypto (KNcloud-WIN bootproxy) with a fixed salt. */
    @Test
    fun decryptsGoProducedStream() {
        val wire = hex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f65b6768e91f51a39f66b7e2ec11d5f4e2f7b8595dad3fcb851ddf0b8004eaeecb2a35c9bfee4dbe32d31b285979a90"
        )
        val input = ShadowsocksInputStream(ByteArrayInputStream(wire), "boot-test-pw")
        assertEquals("hello kncloud", String(input.readBytes()))
    }

    @Test
    fun streamRoundTripLargePayload() {
        val buf = ByteArrayOutputStream()
        val out = ShadowsocksOutputStream(buf, "pw")
        val big = ByteArray(100_000) { (it % 251).toByte() }
        out.write(big)
        out.write("tail".toByteArray())
        val back = ShadowsocksInputStream(ByteArrayInputStream(buf.toByteArray()), "pw").readBytes()
        assertArrayEquals(big + "tail".toByteArray(), back)
        try {
            ShadowsocksInputStream(ByteArrayInputStream(buf.toByteArray()), "wrong").readBytes()
            fail("wrong password decrypted")
        } catch (_: IOException) {}
    }

    @Test
    fun socksAddress() {
        val d = ShadowsocksAead.socksAddress("www.kncloud.top", 443)
        assertEquals(3, d[0].toInt())
        assertEquals(15, d[1].toInt())
        assertEquals(443, ((d[d.size - 2].toInt() and 0xff) shl 8) or (d[d.size - 1].toInt() and 0xff))
        assertEquals(19, ShadowsocksAead.socksAddress("[2001:db8::1]", 80).size)
        assertEquals(7, ShadowsocksAead.socksAddress("1.2.3.4", 80).size)
    }

    @Test
    fun defaultAllow() {
        assertTrue(BootProxy.defaultAllow("kncloud.top"))
        assertTrue(BootProxy.defaultAllow("WWW.KNcloud.top"))
        assertTrue(BootProxy.defaultAllow("aws.kncloud.top."))
        assertFalse(BootProxy.defaultAllow("evilkncloud.top"))
        assertFalse(BootProxy.defaultAllow("kncloud.top.evil.com"))
        assertFalse(BootProxy.defaultAllow("www.google.com"))
    }

    /** Fake Shadowsocks server: records the requested target and echoes "OK:" + what it receives. */
    private fun fakeServer(password: String, seen: AtomicReference<String>): ServerSocket {
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val c = try { ss.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) {
                    try {
                        val input = ShadowsocksInputStream(c.getInputStream(), password)
                        val out = ShadowsocksOutputStream(c.getOutputStream(), password)
                        val atyp = input.read()
                        if (atyp != 3) { c.close(); return@thread }
                        val len = input.read()
                        val host = ByteArray(len).also { var g = 0; while (g < len) g += input.read(it, g, len - g) }
                        val port = (input.read() shl 8) or input.read()
                        seen.set(String(host) + ":" + port)
                        val req = ByteArray(5).also { var g = 0; while (g < 5) g += input.read(it, g, 5 - g) }
                        out.write("OK:".toByteArray() + req)
                        c.close()
                    } catch (_: Exception) {
                        c.close()
                    }
                }
            }
        }
        return ss
    }

    private fun connectThrough(port: Int, target: String, payload: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n$payload".toByteArray())
            return String(s.getInputStream().readBytes())
        }
    }

    @Test
    fun proxyTunnelsThroughShadowsocksWithFallback() {
        val seen = AtomicReference<String>()
        val server = fakeServer("boot-pw", seen)
        val dead = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        BootProxy.configProvider = {
            BootProxy.Config(upstreams = listOf("127.0.0.1" to dead, "127.0.0.1" to server.localPort), password = "boot-pw", connectTimeoutMs = 2000)
        }
        val port = BootProxy.session {
            val p = BootProxy.ensureStarted()
            assertTrue(p > 0)
            assertEquals(p, BootProxy.ensureStarted()) // reused
            val resp = connectThrough(p, "api.kncloud.top:443", "hello")
            assertEquals("HTTP/1.1 200 Connection Established\r\n\r\nOK:hello", resp)
            assertEquals("api.kncloud.top:443", seen.get())
            // Targets outside the whitelist are refused locally
            assertTrue(connectThrough(p, "www.google.com:443", "").startsWith("HTTP/1.1 403"))
            p
        }
        // session ended -> stopped, port closed
        assertEquals(0, BootProxy.runningPort())
        try {
            Socket("127.0.0.1", port).close(); fail("still listening after session")
        } catch (_: IOException) {}
        server.close()
    }

    @Test
    fun notConfiguredMeansDisabled() {
        BootProxy.configProvider = { null }
        assertEquals(0, BootProxy.ensureStarted())
    }

    @Test
    fun maxLifetimeStopsProxy() {
        BootProxy.maxLifetimeMs = 100
        BootProxy.configProvider = { BootProxy.Config(listOf("127.0.0.1" to 1), "x") }
        assertTrue(BootProxy.ensureStarted() > 0)
        Thread.sleep(400)
        assertEquals(0, BootProxy.runningPort())
    }
}

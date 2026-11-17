package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DevicePingTest {

    private val dev = mapOf(
        DeviceIdentity.HDR_DEVICE_ID to "0f8fad5b-d9cb-469f-a165-70867728950e",
        DeviceIdentity.HDR_PLATFORM to "android"
    )

    /** One-shot plain HTTP server on 127.0.0.1: returns the request head it got. */
    private fun server(status: String, got: AtomicReference<List<String>>): ServerSocket {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            ss.accept().use { c ->
                val r = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.US_ASCII))
                val lines = mutableListOf<String>()
                while (true) {
                    val l = r.readLine() ?: break
                    if (l.isEmpty()) break
                    lines.add(l)
                }
                got.set(lines)
                c.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                c.getOutputStream().flush()
            }
        }
        return ss
    }

    @Test
    fun pingSendsAuthAndDeviceHeadersDirectly() {
        val got = AtomicReference<List<String>>()
        val protected = AtomicReference<Socket?>()
        server("200 OK", got).use { ss ->
            val code = DevicePing.pingOnce("http://127.0.0.1:${ss.localPort}/", "tok123", false, dev) { protected.set(it) }
            assertEquals(200, code)
        }
        val head = got.get()
        assertEquals("POST ${DevicePing.PATH} HTTP/1.1", head[0])
        assertTrue(head.contains("Authorization: tok123"))
        assertTrue(head.contains("X-KN-Device-Id: 0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertTrue(head.contains("X-KN-Platform: android"))
        assertTrue("socket was not protected", protected.get() != null)
    }

    @Test
    fun pingReportsStatusAndFailsSilently() {
        val got = AtomicReference<List<String>>()
        server("429 Too Many Requests", got).use { ss ->
            assertEquals(429, DevicePing.pingOnce("http://127.0.0.1:${ss.localPort}", "t", false, dev) {})
        }
        // no IPv6 address for 127.0.0.1: skipped, no fallback to IPv4
        assertEquals(-1, DevicePing.pingOnce("http://127.0.0.1:1", "t", true, dev) {})
        // nothing listening
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(-1, DevicePing.pingOnce("http://127.0.0.1:$port", "t", false, dev) {})
    }

    @Test
    fun schedule() {
        val st = DevicePing.State()
        val t0 = 1_700_000_000_000L
        assertFalse("logged out", DevicePing.decide(st, t0, null, "n1"))
        assertTrue("first", DevicePing.decide(st, t0, "acc", "n1"))
        assertFalse("too soon", DevicePing.decide(st, t0 + 5 * 60_000, "acc", "n1"))
        assertFalse("network change inside min gap", DevicePing.decide(st, t0 + 30_000, "acc", "n2"))
        assertTrue("network change", DevicePing.decide(st, t0 + 6 * 60_000, "acc", "n2"))
        assertTrue("periodic", DevicePing.decide(st, t0 + 17 * 60_000, "acc", "n2"))
        assertFalse(DevicePing.decide(st, t0 + 18 * 60_000, null, "n2"))
        assertTrue("re-login after min gap", DevicePing.decide(st, t0 + 19 * 60_000, "acc", "n2"))
    }

    @Test
    fun globalV6() {
        assertTrue(DevicePing.isGlobalV6(InetAddress.getByName("2409:8a00::1")))
        assertFalse(DevicePing.isGlobalV6(InetAddress.getByName("fdfe:dcba:9876::1")))
        assertFalse(DevicePing.isGlobalV6(InetAddress.getByName("fe80::1")))
        assertFalse(DevicePing.isGlobalV6(InetAddress.getByName("1.2.3.4")))
    }
}

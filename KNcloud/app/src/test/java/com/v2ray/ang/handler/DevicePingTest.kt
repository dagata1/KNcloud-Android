package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DevicePingTest {

    private val dev = "0f8fad5b-d9cb-469f-a165-70867728950e"

    /** One-shot stand-in for the core's local HTTP inbound: returns the request head it got. */
    private fun proxy(status: String, got: AtomicReference<List<String>>): ServerSocket {
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
                c.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                c.getOutputStream().flush()
            }
        }
        return ss
    }

    @Test
    fun reportGoesThroughLocalProxyToReservedHost() {
        val got = AtomicReference<List<String>>()
        proxy("204 No Content", got).use { ss ->
            assertEquals(204, DevicePing.report(ss.localPort, dev))
        }
        val head = got.get()
        assertEquals("GET http://${DevicePing.HOST}${DevicePing.PATH}?id=$dev HTTP/1.1", head[0])
        assertTrue(head.contains("Host: ${DevicePing.HOST}"))
        assertTrue(head.contains("${DeviceIdentity.HDR_DEVICE_ID}: $dev"))
        assertFalse("no panel session token in the report", head.any { it.startsWith("Authorization:") })
    }

    @Test
    fun reportFailsSilently() {
        val got = AtomicReference<List<String>>()
        proxy("503 Service Unavailable", got).use { ss ->
            assertEquals(503, DevicePing.report(ss.localPort, dev))
        }
        // core not listening
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(-1, DevicePing.report(port, dev))
    }

    @Test
    fun routingRuleSendsReservedHostToProxy() {
        val r = DevicePing.routingRule()
        assertEquals(listOf("full:${DevicePing.HOST}"), r.domain)
        assertEquals(AppConfig.TAG_PROXY, r.outboundTag)
        assertEquals("field", r.type)
    }

    @Test
    fun schedule() {
        val st = DevicePing.State()
        val t0 = 1_700_000_000_000L
        assertFalse("logged out / no node", DevicePing.decide(st, t0, null))
        assertTrue("first", DevicePing.decide(st, t0, "acc|sg|n1"))
        assertFalse("too soon", DevicePing.decide(st, t0 + 5 * 60_000, "acc|sg|n1"))
        assertFalse("node switch inside min gap", DevicePing.decide(st, t0 + 5_000, "acc|jp|n1"))
        assertTrue("node switch", DevicePing.decide(st, t0 + 5 * 60_000 + 1_000, "acc|jp|n1"))
        assertTrue("network change", DevicePing.decide(st, t0 + 5 * 60_000 + 12_000, "acc|jp|n2"))
        assertTrue("periodic", DevicePing.decide(st, t0 + 16 * 60_000, "acc|jp|n2"))
        assertFalse(DevicePing.decide(st, t0 + 17 * 60_000, null))
        assertTrue("reconnect after min gap", DevicePing.decide(st, t0 + 17 * 60_000 + 11_000, "acc|jp|n2"))
    }

    @Test
    fun globalV6() {
        assertTrue(DevicePing.isGlobalV6(InetAddress.getByName("2409:8a00::1")))
        assertFalse(DevicePing.isGlobalV6(InetAddress.getByName("fdfe:dcba:9876::1")))
        assertFalse(DevicePing.isGlobalV6(InetAddress.getByName("fe80::1")))
        assertFalse(DevicePing.isGlobalV6(InetAddress.getByName("1.2.3.4")))
    }
}

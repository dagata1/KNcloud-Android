package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.V2rayConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Device report for the panel's per-device traffic (我的设备) — THROUGH the current node, never
 * directly to the panel.
 *
 * Nodes report traffic per user and source IP; the panel attributes it to the devices seen at
 * that IP. Panel requests of this app go through the local proxy / bootstrap node, so the panel
 * never saw the IP the nodes see and the traffic ended up as 「未识别设备」.
 *
 * While the core runs with a node and the user is logged in, this sends a tiny plain-HTTP
 * request for the reserved host [HOST] through the core's local HTTP inbound — i.e. the normal
 * proxy route (a routing rule added first in every config sends [HOST] to the proxy outbound,
 * see [routingRule]). The node (knode >= 0.9.17) answers it itself (nothing leaves the node, the
 * name is never resolved), takes the user from the proxy credential and the source IP it sees,
 * and forwards both to the panel over its control channel. The panel domain is never contacted
 * directly (the former direct ping every 10 minutes from China exposed it).
 *
 * The request is written on a raw socket to 127.0.0.1 (the app's cleartext policy only allows
 * loopback; the bytes to the node are encrypted by the proxy protocol anyway).
 * Timing: ~15 s after the core starts, after a node switch / network change / account change
 * (within ~10 s) and every 10 minutes. Every failure is silent.
 */
object DevicePing {
    const val HOST = "device.kncloud.internal"
    const val PATH = "/v1/ping"
    const val EVERY_MS = 10 * 60_000L
    const val MIN_GAP_MS = 10_000L
    private const val CHECK_MS = 10_000L
    private const val FIRST_MS = 15_000L
    private const val TIMEOUT_MS = 8_000

    /** What the last report was sent for (only touched on the ping thread). */
    class State {
        var key: String? = null
        var last: Long = 0L
    }

    private var executor: ScheduledExecutorService? = null
    private val state = State()

    @Synchronized
    fun start() {
        if (executor != null) return
        state.key = null
        state.last = 0L
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "kn-device-ping").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({
                try {
                    tick(System.currentTimeMillis())
                } catch (e: Throwable) {
                    Log.d(AppConfig.TAG, "device report skipped: ${e.message}")
                }
            }, FIRST_MS, CHECK_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /** The first routing rule of every config: [HOST] always goes to the node. */
    fun routingRule(): V2rayConfig.RoutingBean.RulesBean =
        V2rayConfig.RoutingBean.RulesBean(domain = arrayListOf("full:$HOST"), outboundTag = AppConfig.TAG_PROXY)

    /**
     * Whether to report now (and if so records it in [st]): first time, [key] (account + node +
     * network) changed at least [MIN_GAP_MS] after the last report, or [EVERY_MS] passed.
     * key == null (logged out / no node) never reports and makes the next one go right away.
     */
    fun decide(st: State, now: Long, key: String?): Boolean {
        if (key == null) {
            st.key = null
            return false
        }
        val due = st.last == 0L || now - st.last >= EVERY_MS || (key != st.key && now - st.last >= MIN_GAP_MS)
        if (!due) return false
        st.key = key
        st.last = now
        return true
    }

    private fun tick(now: Long) {
        if (!V2RayServiceManager.isRunning()) {
            state.key = null
            return
        }
        val token = MmkvManager.getUserToken()?.takeIf { it.isNotBlank() }
        val node = MmkvManager.getSelectServer()?.takeIf { it.isNotBlank() }
        val key = if (token == null || node == null) null
        else listOf(MmkvManager.getApiDomain(), token, node, localAddrs().joinToString(",")).joinToString("\u0000").sha8()
        if (!decide(state, now, key)) return
        report(SettingsManager.getHttpPort(), DeviceIdentity.deviceId())
    }

    /**
     * One report through the local HTTP proxy on 127.0.0.1:[port]. Returns the HTTP status
     * (204 = the node took it), or -1 when it failed (never throws).
     */
    fun report(port: Int, deviceId: String): Int {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName(AppConfig.LOOPBACK), port), TIMEOUT_MS)
                s.soTimeout = TIMEOUT_MS
                val id = DeviceIdentity.asciiHeaderValue(deviceId)
                val q = URLEncoder.encode(id, "UTF-8")
                val req = "GET http://$HOST$PATH?id=$q HTTP/1.1\r\n" +
                    "Host: $HOST\r\n" +
                    "User-Agent: KNcloud/${DeviceIdentity.asciiHeaderValue(BuildConfig.VERSION_NAME)}\r\n" +
                    "${DeviceIdentity.HDR_DEVICE_ID}: $id\r\n" +
                    "Connection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()
                val status = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII)).readLine() ?: return -1
                status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            }
        } catch (e: Exception) {
            Log.d(AppConfig.TAG, "device report failed: ${e.message}")
            -1
        }
    }

    /** Global IPv6 (2000::/3); ULA / link-local do not count. */
    fun isGlobalV6(a: InetAddress): Boolean = a is Inet6Address && (a.address[0].toInt() and 0xe0) == 0x20

    /** IPv4 and global IPv6 addresses of the up, non-loopback interfaces (sorted): network fingerprint. */
    private fun localAddrs(): List<String> {
        val out = mutableListOf<String>()
        val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
        for (ni in ifs) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && !a.isLinkLocalAddress) out.add(a.hostAddress.orEmpty())
                else if (isGlobalV6(a)) out.add(a.hostAddress.orEmpty().substringBefore('%'))
            }
        }
        return out.sorted()
    }

    private fun String.sha8(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

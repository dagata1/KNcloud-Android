package com.v2ray.ang.handler

import android.net.VpnService
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Device ping for the panel's per-device traffic (我的设备).
 *
 * Nodes report traffic per user and source IP; the panel attributes it to the devices seen at
 * that IP. Panel requests of this app go through the local proxy / bootstrap node, and phones
 * often reach the nodes over (Chinese) IPv6, so the panel never saw the IP the nodes see and the
 * traffic ended up as 「未识别设备」.
 *
 * While the core runs and the user is logged in, this sends a tiny authenticated
 * POST /api/v1/user/device/ping (session token + X-KN-Device-* headers) DIRECTLY to the panel:
 *  - this app's own package is always excluded from the VPN (see V2RayVpnService
 *    configurePerAppProxy) and the socket is additionally protect()ed, so it never enters the
 *    tunnel; no proxy is used;
 *  - once over IPv4 and once over IPv6 (when the device has a global IPv6 address), on separate
 *    sockets to an address of that family, so the panel records both.
 * Timing: ~15 s after the core starts, on network changes (local addresses / account, at least
 * 70 s apart) and every 10 minutes. Every failure is silent (no UI, no retry until next round).
 */
object DevicePing {
    const val PATH = "/api/v1/user/device/ping"
    const val EVERY_MS = 10 * 60_000L
    const val MIN_GAP_MS = 70_000L
    private const val CHECK_MS = 30_000L
    private const val FIRST_MS = 15_000L
    private const val TIMEOUT_MS = 8_000

    /** What the last ping was sent for (only touched on the ping thread). */
    class State {
        var account: String? = null
        var addrs: String? = null
        var last: Long = 0L
    }

    private var executor: ScheduledExecutorService? = null
    private val state = State()

    @Synchronized
    fun start() {
        if (executor != null) return
        state.account = null
        state.last = 0L
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "kn-device-ping").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({
                try {
                    tick(System.currentTimeMillis())
                } catch (e: Throwable) {
                    Log.d(AppConfig.TAG, "device ping skipped: ${e.message}")
                }
            }, FIRST_MS, CHECK_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /**
     * Whether to ping now (and if so records it in [st]): first time, account or network changed
     * (at least [MIN_GAP_MS] after the last ping), or [EVERY_MS] passed. account == null (logged
     * out) never pings and makes the next login ping right away.
     */
    fun decide(st: State, now: Long, account: String?, addrs: String): Boolean {
        if (account == null) {
            st.account = null
            return false
        }
        val changed = account != st.account || addrs != st.addrs
        val due = st.last == 0L || now - st.last >= EVERY_MS || (changed && now - st.last >= MIN_GAP_MS)
        if (!due) return false
        st.account = account
        st.addrs = addrs
        st.last = now
        return true
    }

    private fun tick(now: Long) {
        if (!V2RayServiceManager.isRunning()) return
        val token = MmkvManager.getUserToken()?.takeIf { it.isNotBlank() }
        val domain = MmkvManager.getApiDomain()
        val addrs = localAddrs()
        val account = token?.let { domain + "\u0000" + it }
        if (!decide(state, now, account, addrs.joinToString(",").sha8())) return
        val headers = DeviceIdentity.headers()
        pingOnce(domain, token!!, false, headers, ::protectSocket)
        if (addrs.any { it.contains(':') }) pingOnce(domain, token, true, headers, ::protectSocket)
    }

    /** Keeps the socket out of the VPN (the package is excluded anyway; belt and braces). */
    private fun protectSocket(s: Socket) {
        val svc = V2RayServiceManager.serviceControl?.get()?.getService()
        if (svc is VpnService) svc.protect(s)
    }

    /**
     * One ping over IPv4 (v6 = false) or IPv6. Returns the HTTP status, or -1 when the panel host
     * has no address of that family or the request failed (never throws).
     */
    fun pingOnce(domain: String, token: String, v6: Boolean, headers: Map<String, String>, protect: (Socket) -> Unit): Int {
        return try {
            val url = URL(domain.trimEnd('/') + PATH)
            val https = url.protocol.equals("https", ignoreCase = true)
            val port = if (url.port > 0) url.port else url.defaultPort
            val addr = InetAddress.getAllByName(url.host).firstOrNull { if (v6) it is Inet6Address else it is Inet4Address }
                ?: return -1
            val raw = Socket()
            try {
                protect(raw)
                raw.connect(InetSocketAddress(addr, port), TIMEOUT_MS)
                raw.soTimeout = TIMEOUT_MS
                val sock: Socket = if (https) {
                    val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, url.host, port, true) as SSLSocket
                    s.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, s.session)) {
                        s.close()
                        throw SSLException("hostname mismatch")
                    }
                    s
                } else raw
                sock.use { request(it, url, port, token, headers) }
            } finally {
                runCatching { raw.close() }
            }
        } catch (e: Exception) {
            Log.d(AppConfig.TAG, "device ping (${if (v6) "IPv6" else "IPv4"}) failed: ${e.message}")
            -1
        }
    }

    private fun request(s: Socket, url: URL, port: Int, token: String, headers: Map<String, String>): Int {
        val host = if (port == url.defaultPort) url.host else "${url.host}:$port"
        val body = "{}"
        val sb = StringBuilder()
        sb.append("POST ").append(PATH).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(host).append("\r\n")
        sb.append("User-Agent: KNcloud/").append(DeviceIdentity.asciiHeaderValue(BuildConfig.VERSION_NAME)).append("\r\n")
        sb.append("Authorization: ").append(DeviceIdentity.asciiHeaderValue(token)).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(DeviceIdentity.asciiHeaderValue(v)).append("\r\n")
        sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ").append(body.length).append("\r\n")
        sb.append("Connection: close\r\n\r\n").append(body)
        val out = s.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
        val status = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII)).readLine() ?: return -1
        // "HTTP/1.1 200 OK"
        return status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
    }

    /** Global IPv6 (2000::/3); ULA / link-local do not count. */
    fun isGlobalV6(a: InetAddress): Boolean = a is Inet6Address && (a.address[0].toInt() and 0xe0) == 0x20

    /** IPv4 and global IPv6 addresses of the up, non-loopback interfaces (sorted). */
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

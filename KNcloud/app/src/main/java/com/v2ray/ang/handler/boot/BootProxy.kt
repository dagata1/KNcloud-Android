package com.v2ray.ang.handler.boot

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

/**
 * Login bootstrap: when the panel API cannot be reached directly (e.g. the official site is
 * blocked on the user's network) and the VPN is not running, the app's own panel HTTP calls
 * (login, getSubscribe, user info, subscription download) are retried through this local HTTP
 * CONNECT proxy on 127.0.0.1:<random>, which tunnels to the built-in "登录引导" Shadowsocks node.
 *
 * No VpnService, no TUN, no system proxy, no permission prompt: it is only a loopback socket that
 * only the app's own HttpURLConnection is pointed at. The node only allows *.kncloud.top:443, and
 * the proxy itself refuses other CONNECT targets. It is started on demand and stopped when the
 * last [session] ends (or after [maxLifetimeMs] at the latest).
 */
class BootProxy private constructor(
    private val config: Config,
    private val allow: (String) -> Boolean,
) {
    data class Config(
        /** Upstream candidates tried in order ("host" + port), e.g. the AAAA-only domain then the hardcoded IPv6. */
        val upstreams: List<Pair<String, Int>>,
        val password: String,
        val connectTimeoutMs: Int = 8000,
    )

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    @Volatile private var closed = false
    @Volatile private var lastGood = 0

    val port: Int get() = server.localPort
    val isClosed: Boolean get() = closed

    private fun serve() {
        thread(name = "boot-proxy", isDaemon = true) {
            while (!closed) {
                val c = try { server.accept() } catch (e: IOException) { break }
                sockets.add(c)
                thread(name = "boot-proxy-conn", isDaemon = true) { handle(c) }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { server.close() } catch (_: IOException) {}
        synchronized(sockets) {
            sockets.forEach { try { it.close() } catch (_: IOException) {} }
            sockets.clear()
        }
        Log.i(TAG, "login bootstrap proxy stopped")
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun reply(out: OutputStream, status: String) {
        try {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: IOException) {}
    }

    private fun dialUpstream(): Socket {
        var last: IOException? = null
        val n = config.upstreams.size
        for (k in 0 until n) {
            val i = (lastGood + k) % n
            val (host, port) = config.upstreams[i]
            val s = Socket()
            try {
                // InetSocketAddress(host) resolves the AAAA record; a literal IPv6 needs no DNS
                s.connect(InetSocketAddress(host.removePrefix("[").removeSuffix("]"), port), config.connectTimeoutMs)
                lastGood = i
                return s
            } catch (e: IOException) {
                try { s.close() } catch (_: IOException) {}
                Log.w(TAG, "boot node $host:$port unreachable: ${e.message}")
                last = e
            }
        }
        throw last ?: IOException("no boot node")
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            c.soTimeout = 15000
            val input = c.getInputStream()
            val out = c.getOutputStream()
            val requestLine = readLine(input) ?: return
            while (true) {
                val h = readLine(input) ?: return
                if (h.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("CONNECT", true)) {
                reply(out, "405 Method Not Allowed"); return
            }
            val target = parts[1]
            val idx = target.lastIndexOf(':')
            val host = if (idx > 0) target.substring(0, idx).removePrefix("[").removeSuffix("]") else ""
            val port = if (idx > 0) target.substring(idx + 1).toIntOrNull() ?: 0 else 0
            if (host.isEmpty() || port !in 1..65535 || !allow(host)) {
                reply(out, "403 Forbidden"); return
            }
            val u = try { dialUpstream() } catch (e: IOException) { reply(out, "502 Bad Gateway"); return }
            up = u
            sockets.add(u)
            if (closed) return
            val ssOut = ShadowsocksOutputStream(u.getOutputStream(), config.password)
            val ssIn = ShadowsocksInputStream(u.getInputStream(), config.password)
            ssOut.write(ShadowsocksAead.socksAddress(host, port))
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            c.soTimeout = 0
            val t = thread(name = "boot-proxy-up", isDaemon = true) { pipe(input, ssOut, u) }
            pipe(ssIn, out, c)
            t.join(1000)
        } catch (e: Exception) {
            Log.w(TAG, "boot proxy connection: ${e.message}")
        } finally {
            up?.let { sockets.remove(it); try { it.close() } catch (_: IOException) {} }
            sockets.remove(c)
            try { c.close() } catch (_: IOException) {}
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, closeOnEnd: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        } finally {
            try { closeOnEnd.shutdownOutput() } catch (_: Exception) {}
            try { closeOnEnd.close() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "KNcloudBoot"

        @Volatile var maxLifetimeMs: Long = 3 * 60 * 1000L

        /** Only the official domains are tunnelled (the node enforces the same whitelist). */
        fun defaultAllow(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            return h == "kncloud.top" || h.endsWith(".kncloud.top")
        }

        private val lock = Any()
        private var current: BootProxy? = null
        private var users = 0
        private var timer: Timer? = null

        /** Overridable for tests; production uses [BootNodeConfig]. */
        @Volatile var configProvider: () -> Config? = { BootNodeConfig.config() }
        @Volatile var allowProvider: (String) -> Boolean = ::defaultAllow

        /** Port of the running bootstrap proxy, or 0. */
        fun runningPort(): Int = synchronized(lock) {
            val p = current
            if (p == null || p.isClosed) { current = null; 0 } else p.port
        }

        /** Starts (or reuses) the bootstrap proxy; returns its port, or 0 if not configured / failed. */
        fun ensureStarted(): Int = synchronized(lock) {
            current?.let { if (!it.isClosed) return it.port }
            val cfg = configProvider() ?: return 0
            return try {
                val p = BootProxy(cfg, allowProvider)
                p.serve()
                current = p
                timer?.cancel()
                timer = Timer("boot-proxy-ttl", true).also {
                    it.schedule(object : TimerTask() { override fun run() { stopIf(p) } }, maxLifetimeMs)
                }
                Log.i(TAG, "login bootstrap proxy started on 127.0.0.1:${p.port}")
                p.port
            } catch (e: Exception) {
                Log.e(TAG, "failed to start login bootstrap proxy", e)
                0
            }
        }

        private fun stopIf(p: BootProxy) = synchronized(lock) {
            if (current === p) { current = null; timer?.cancel(); timer = null }
            p.close()
        }

        fun stop() = synchronized(lock) {
            current?.close()
            current = null
            timer?.cancel()
            timer = null
        }

        /**
         * Runs [block] as one login / subscription flow. The bootstrap proxy (if a call inside started
         * it) is stopped when the last concurrent flow finishes.
         */
        fun <T> session(block: () -> T): T {
            synchronized(lock) { users++ }
            try {
                return block()
            } finally {
                synchronized(lock) {
                    users--
                    if (users <= 0) {
                        users = 0
                        current?.close()
                        current = null
                        timer?.cancel()
                        timer = null
                    }
                }
            }
        }
    }
}

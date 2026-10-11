package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Xray 26.x removed "allowInsecure". Nodes that relied on it (self-signed
 * certificates, an IP as SNI, ...) are pinned instead: the leaf certificate
 * the server presents now is fetched once and passed as pinnedPeerCertSha256,
 * which accepts that exact certificate without CA verification.
 */
object CertPinManager {
    private const val TIMEOUT_MS = 4000
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private data class Entry(val hash: String, val time: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the hex SHA-256 of the server's leaf certificate, or null if it
     * could not be fetched. Safe to call from any thread: the network work runs
     * on its own thread and this call waits at most a few seconds.
     */
    fun getLeafSha256(host: String?, port: Int?, sni: String?): String? {
        if (host.isNullOrEmpty() || port == null || port <= 0) return null
        val key = "$host:$port:${sni.orEmpty()}"
        cache[key]?.let { if (System.currentTimeMillis() - it.time < CACHE_TTL_MS) return it.hash }

        var result: String? = null
        val worker = Thread {
            result = fetch(host, port, sni)
        }
        worker.isDaemon = true
        worker.start()
        worker.join(TIMEOUT_MS + 1000L)
        val hash = result
        if (hash != null) {
            cache[key] = Entry(hash, System.currentTimeMillis())
        } else {
            Log.w(AppConfig.TAG, "Could not fetch certificate for $key, insecure node will be verified normally")
        }
        return hash
    }

    private fun fetch(host: String, port: Int, sni: String?): String? {
        return try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trustAll), SecureRandom())
            val socket = ctx.socketFactory.createSocket() as SSLSocket
            socket.use { s ->
                s.soTimeout = TIMEOUT_MS
                s.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                if (!sni.isNullOrEmpty() && !isIp(sni)) {
                    val params = s.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    s.sslParameters = params
                }
                s.startHandshake()
                val leaf = s.session.peerCertificates.firstOrNull() ?: return null
                MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    .joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Certificate fetch failed for $host:$port", e)
            null
        }
    }

    private fun isIp(s: String): Boolean =
        s.contains(':') || s.all { it.isDigit() || it == '.' }
}

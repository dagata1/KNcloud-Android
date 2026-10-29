package com.v2ray.ang.handler.boot

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal Shadowsocks AEAD (chacha20-ietf-poly1305) TCP stream, client side only, for the
 * login bootstrap proxy. Wire format: salt(32) then chunks of Seal(len:2 BE) + Seal(payload <= 0x3FFF);
 * subkey = HKDF-SHA1(EVP_BytesToKey(password), salt, "ss-subkey"), 12-byte little-endian counter nonce.
 */
object ShadowsocksAead {
    const val KEY_SIZE = 32
    const val SALT_SIZE = 32
    const val MAX_PAYLOAD = 0x3FFF
    const val TAG = 16

    /** OpenSSL EVP_BytesToKey(MD5, no salt, 1 round), as every shadowsocks implementation does. */
    fun evpBytesToKey(password: String, keyLen: Int = KEY_SIZE): ByteArray {
        val pw = password.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (out.size() < keyLen) {
            val md = MessageDigest.getInstance("MD5")
            md.update(prev)
            md.update(pw)
            prev = md.digest()
            out.write(prev)
        }
        return out.toByteArray().copyOf(keyLen)
    }

    /** HKDF-SHA1 (RFC 5869) with info "ss-subkey". */
    fun subkey(key: ByteArray, salt: ByteArray): ByteArray {
        val ext = Mac.getInstance("HmacSHA1")
        ext.init(SecretKeySpec(salt, "HmacSHA1"))
        val prk = ext.doFinal(key)
        val info = "ss-subkey".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < KEY_SIZE) {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(prk, "HmacSHA1"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(KEY_SIZE)
    }

    internal fun increment(nonce: ByteArray) {
        for (i in nonce.indices) {
            nonce[i] = (nonce[i] + 1).toByte()
            if (nonce[i].toInt() != 0) return
        }
    }

    /** SOCKS5-style target address: the node resolves the domain (its whitelist matches domains). */
    fun socksAddress(host: String, port: Int): ByteArray {
        require(port in 1..65535) { "bad port $port" }
        val h = host.removePrefix("[").removeSuffix("]")
        val b = java.io.ByteArrayOutputStream()
        val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        when {
            v4.matches(h) -> {
                b.write(1)
                h.split('.').forEach { b.write(it.toInt()) }
            }
            h.contains(':') -> {
                b.write(4)
                b.write(java.net.InetAddress.getByName(h).address)
            }
            else -> {
                val d = h.toByteArray(Charsets.US_ASCII)
                require(d.isNotEmpty() && d.size <= 255) { "bad host" }
                b.write(3)
                b.write(d.size)
                b.write(d)
            }
        }
        b.write(port shr 8)
        b.write(port and 0xff)
        return b.toByteArray()
    }
}

/** Encrypting side: writes the salt with the first chunk. */
class ShadowsocksOutputStream(private val out: OutputStream, password: String) : OutputStream() {
    private val key = ShadowsocksAead.evpBytesToKey(password)
    private var aead: ChaCha20Poly1305? = null
    private val nonce = ByteArray(ChaCha20Poly1305.NONCE_SIZE)

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        val buf = java.io.ByteArrayOutputStream(len + 64)
        var a = aead
        if (a == null) {
            val salt = ByteArray(ShadowsocksAead.SALT_SIZE).also { SecureRandom().nextBytes(it) }
            a = ChaCha20Poly1305(ShadowsocksAead.subkey(key, salt))
            aead = a
            buf.write(salt)
        }
        var i = 0
        while (i < len) {
            val n = minOf(ShadowsocksAead.MAX_PAYLOAD, len - i)
            buf.write(a.seal(nonce, byteArrayOf((n shr 8).toByte(), n.toByte())))
            ShadowsocksAead.increment(nonce)
            buf.write(a.seal(nonce, b.copyOfRange(off + i, off + i + n)))
            ShadowsocksAead.increment(nonce)
            i += n
        }
        out.write(buf.toByteArray())
        out.flush()
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}

/** Decrypting side: reads the server's salt first. */
class ShadowsocksInputStream(private val input: InputStream, password: String) : InputStream() {
    private val key = ShadowsocksAead.evpBytesToKey(password)
    private var aead: ChaCha20Poly1305? = null
    private val nonce = ByteArray(ChaCha20Poly1305.NONCE_SIZE)
    private var buf = ByteArray(0)
    private var pos = 0
    private var eof = false

    private fun readFully(n: Int): ByteArray? {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) {
                if (got == 0) return null
                throw EOFException("truncated shadowsocks stream")
            }
            got += r
        }
        return b
    }

    private fun fill(): Boolean {
        if (eof) return false
        var a = aead
        if (a == null) {
            val salt = readFully(ShadowsocksAead.SALT_SIZE) ?: run { eof = true; return false }
            a = ChaCha20Poly1305(ShadowsocksAead.subkey(key, salt))
            aead = a
        }
        val lenChunk = readFully(2 + ShadowsocksAead.TAG) ?: run { eof = true; return false }
        val l = try { a.open(nonce, lenChunk) } catch (e: Exception) { throw IOException("shadowsocks: bad length chunk (wrong password?)") }
        ShadowsocksAead.increment(nonce)
        val size = (((l[0].toInt() and 0xff) shl 8) or (l[1].toInt() and 0xff)) and ShadowsocksAead.MAX_PAYLOAD
        val payload = readFully(size + ShadowsocksAead.TAG) ?: throw EOFException("truncated shadowsocks stream")
        buf = try { a.open(nonce, payload) } catch (e: Exception) { throw IOException("shadowsocks: bad payload chunk") }
        ShadowsocksAead.increment(nonce)
        pos = 0
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= buf.size) {
            if (!fill()) return -1
        }
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = input.close()
}

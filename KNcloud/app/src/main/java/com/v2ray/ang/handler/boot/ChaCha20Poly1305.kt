package com.v2ray.ang.handler.boot

import java.math.BigInteger
import java.security.GeneralSecurityException

/**
 * Pure-Kotlin ChaCha20-Poly1305 AEAD (RFC 8439, 12-byte nonce, 16-byte tag).
 * Used only by the login bootstrap proxy; the JCA "ChaCha20-Poly1305" cipher is
 * missing below Android 9 (minSdk is 24), and the traffic here is a few KB.
 */
class ChaCha20Poly1305(key: ByteArray) {
    private val k = IntArray(8)

    init {
        require(key.size == KEY_SIZE) { "key must be 32 bytes" }
        for (i in 0 until 8) k[i] = le32(key, i * 4)
    }

    fun seal(nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = EMPTY): ByteArray {
        require(nonce.size == NONCE_SIZE)
        val out = ByteArray(plaintext.size + TAG_SIZE)
        xor(nonce, 1, plaintext, 0, plaintext.size, out, 0)
        val tag = poly1305(polyKey(nonce), aad, out, plaintext.size)
        System.arraycopy(tag, 0, out, plaintext.size, TAG_SIZE)
        return out
    }

    fun open(nonce: ByteArray, sealed: ByteArray, aad: ByteArray = EMPTY): ByteArray {
        require(nonce.size == NONCE_SIZE)
        if (sealed.size < TAG_SIZE) throw GeneralSecurityException("message too short")
        val n = sealed.size - TAG_SIZE
        val tag = poly1305(polyKey(nonce), aad, sealed, n)
        var diff = 0
        for (i in 0 until TAG_SIZE) diff = diff or (tag[i].toInt() xor sealed[n + i].toInt())
        if (diff != 0) throw GeneralSecurityException("authentication failed")
        val out = ByteArray(n)
        xor(nonce, 1, sealed, 0, n, out, 0)
        return out
    }

    private fun polyKey(nonce: ByteArray): ByteArray = block(nonce, 0).copyOf(32)

    /** ChaCha20 block (64 bytes) for a counter. */
    internal fun block(nonce: ByteArray, counter: Int): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = k[i]
        s[12] = counter
        s[13] = le32(nonce, 0); s[14] = le32(nonce, 4); s[15] = le32(nonce, 8)
        val x = s.copyOf()
        repeat(10) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15)
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + s[i]
            out[i * 4] = v.toByte(); out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte(); out[i * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }

    private fun xor(nonce: ByteArray, firstCounter: Int, src: ByteArray, off: Int, len: Int, dst: ByteArray, dOff: Int) {
        var counter = firstCounter
        var i = 0
        while (i < len) {
            val ks = block(nonce, counter++)
            val n = minOf(64, len - i)
            for (j in 0 until n) dst[dOff + i + j] = (src[off + i + j].toInt() xor ks[j].toInt()).toByte()
            i += n
        }
    }

    companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        private val EMPTY = ByteArray(0)
        private val P: BigInteger = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        private val CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
        private val MOD128: BigInteger = BigInteger.ONE.shiftLeft(128)

        private fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

        private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }

        /** Little-endian bytes -> non-negative BigInteger. */
        private fun leNum(b: ByteArray, off: Int, len: Int, extraBit: Boolean): BigInteger {
            val be = ByteArray(len + 1)
            for (i in 0 until len) be[len - i] = b[off + i]
            var n = BigInteger(1, be)
            if (extraBit) n = n.setBit(len * 8)
            return n
        }

        /** Poly1305 over the RFC 8439 AEAD layout: aad | pad | ct[0,ctLen) | pad | len(aad) | len(ct). */
        internal fun poly1305(key: ByteArray, aad: ByteArray, ct: ByteArray, ctLen: Int): ByteArray {
            val r = leNum(key, 0, 16, false).and(CLAMP)
            val s = leNum(key, 16, 16, false)
            var acc = BigInteger.ZERO
            fun absorb(data: ByteArray, off: Int, len: Int) {
                var i = 0
                while (i < len) {
                    val n = minOf(16, len - i)
                    val blk = ByteArray(16)
                    System.arraycopy(data, off + i, blk, 0, n)
                    // padded to 16 bytes with zeros (AEAD padding), so every block gets the 2^128 bit
                    acc = acc.add(leNum(blk, 0, 16, true)).multiply(r).mod(P)
                    i += n
                }
            }
            absorb(aad, 0, aad.size)
            absorb(ct, 0, ctLen)
            val lens = ByteArray(16)
            var a = aad.size.toLong(); var c = ctLen.toLong()
            for (i in 0 until 8) { lens[i] = a.toByte(); a = a ushr 8; lens[8 + i] = c.toByte(); c = c ushr 8 }
            absorb(lens, 0, 16)
            val tagNum = acc.add(s).mod(MOD128)
            val be = tagNum.toByteArray()
            val tag = ByteArray(16)
            // big-endian magnitude -> 16 little-endian bytes
            for (i in 0 until 16) {
                val idx = be.size - 1 - i
                tag[i] = if (idx >= 0) be[idx] else 0
            }
            return tag
        }
    }
}

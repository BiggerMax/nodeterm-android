package com.nodeterm.android.core.e2ee

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal RFC 5869 HKDF with HMAC-SHA256.
 *
 * Used for the relay session traffic key:
 * `sessionKey = HKDF-SHA256(baseShared, salt = hostNonce ‖ clientNonce,
 *                           info = "nodeterm-relay-session-v2")` → 32 bytes.
 * Matches node:crypto `hkdfSync('sha256', …)` and iOS CryptoKit `HKDF<SHA256>`.
 */
object Hkdf {
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val HASH_LEN = 32

    /** HKDF-Extract: PRK = HMAC-Hash(salt, IKM). An empty salt is replaced by HashLen zeros. */
    fun extract(ikm: ByteArray, salt: ByteArray): ByteArray {
        val s = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(s, ikm)
    }

    /** HKDF-Expand: OKM = T(1) ‖ T(2) ‖ … ‖ T(n), each T(i) = HMAC(PRK, T(i-1) ‖ info ‖ i). */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(HASH_LEN * 255)) { "HKDF length out of range: $length" }
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val data = t + info + byteArrayOf(counter.toByte())
            t = hmac(prk, data)
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** Convenience: HKDF-Extract + HKDF-Expand in one call. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(ikm, salt), info, length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(data)
    }
}

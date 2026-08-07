package com.nodeterm.android.core.e2ee

import com.iwebpp.crypto.TweetNacl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * End-to-end encryption primitives for the relay transport — pure functions over NaCl box
 * (Curve25519 + XSalsa20-Poly1305), mirroring `src/main/remote/e2ee.ts` of the nodeterm
 * reference implementation. The box wire format is `nonce(24B) ‖ ciphertext ‖ mac`.
 *
 * - `baseKey` = stable per-device-pair ECDH precompute (`nacl.box.before`) — used for the SAS
 *   and pinned identity only, NEVER to encrypt traffic.
 * - `sessionKey` = HKDF-SHA256(baseKey, salt = hostNonce ‖ clientNonce,
 *   info = "nodeterm-relay-session-v2") → fresh per-session traffic key (RFC 5869).
 */
object E2ee {
    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    /** 16-byte session nonce exchanged in the handshake (not a box nonce). */
    const val SESSION_NONCE_BYTES = 16

    val BOX_NONCE_BYTES: Int = TweetNacl.Box.nonceLength
    val BOX_OVERHEAD_BYTES: Int = TweetNacl.Box.overheadLength
    val BOX_PUBLIC_KEY_BYTES: Int = TweetNacl.Box.publicKeyLength
    val BOX_SECRET_KEY_BYTES: Int = TweetNacl.Box.secretKeyLength
    val BOX_SHARED_KEY_BYTES: Int = TweetNacl.Box.sharedKeyLength

    /** Zero-byte padding used by the low-level padded box API (tweetnacl ZEROBYTES). */
    private const val ZERO_BYTES = 32

    private val rng = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val kp = TweetNacl.Box.keyPair()
        return KeyPair(kp.getPublicKey(), kp.getSecretKey())
    }

    /** Deterministic keypair from a fixed 32-byte secret (test vectors; identity import). */
    fun keyPairFromSecretKey(secretKey: ByteArray): KeyPair {
        val kp = TweetNacl.Box.keyPair_fromSecretKey(secretKey)
        return KeyPair(kp.getPublicKey(), kp.getSecretKey())
    }

    fun randomSessionNonce(): ByteArray = ByteArray(SESSION_NONCE_BYTES).also { rng.nextBytes(it) }

    private fun randomBoxNonce(): ByteArray = ByteArray(BOX_NONCE_BYTES).also { rng.nextBytes(it) }

    /** ECDH precompute with the peer's base64 public key and our secret key (stable per pair). */
    fun deriveSharedKey(theirPubB64: String, ourSecret: ByteArray): ByteArray {
        val theirPub = publicKeyFromB64(theirPubB64)
        val k = ByteArray(BOX_SHARED_KEY_BYTES)
        check(TweetNacl.crypto_box_beforenm(k, theirPub, ourSecret) == 0) { "ECDH beforenm failed" }
        return k
    }

    /** Per-session traffic key; salt order is hostNonce ‖ clientNonce on both sides. */
    fun deriveSessionKey(baseShared: ByteArray, hostNonce: ByteArray, clientNonce: ByteArray): ByteArray {
        val salt = hostNonce + clientNonce
        val info = "nodeterm-relay-session-v2".toByteArray(Charsets.UTF_8)
        return Hkdf.derive(baseShared, salt, info, 32)
    }

    /** Encrypt with the precomputed shared key. Returns `nonce ‖ ciphertext ‖ mac`. */
    fun encrypt(plain: ByteArray, shared: ByteArray): ByteArray {
        val nonce = randomBoxNonce()
        val sealed = boxAfter(plain, nonce, shared)
        return nonce + sealed
    }

    /**
     * Decrypt a `nonce ‖ ciphertext ‖ mac` box. Returns null on malformed input or a failed MAC
     * check — never throws (mirrors e2ee.ts `decrypt`).
     */
    fun decrypt(box: ByteArray, shared: ByteArray): ByteArray? {
        if (box.size < BOX_NONCE_BYTES + BOX_OVERHEAD_BYTES) return null
        val nonce = box.copyOfRange(0, BOX_NONCE_BYTES)
        // Wire layout after the nonce: [MAC(16)][ciphertext]. The low-level open API expects the
        // full box INCLUDING the 16-byte BOXZEROBYTES zero prefix — prepend it back.
        val sealed = box.copyOfRange(BOX_NONCE_BYTES, box.size)
        val c = ByteArray(BOX_OVERHEAD_BYTES + sealed.size)
        sealed.copyInto(c, BOX_OVERHEAD_BYTES)
        val m = ByteArray(c.size)
        val r = TweetNacl.crypto_box_open_afternm(m, c, c.size, nonce, shared)
        if (r != 0) return null
        val plainLen = c.size - ZERO_BYTES
        return m.copyOfRange(ZERO_BYTES, ZERO_BYTES + plainLen)
    }

    /**
     * Short Authentication String: SHA-512(baseKey), fold the first 4 bytes into a 32-bit int,
     * `code = n % 1_000_000` padded to 6 digits, formatted "NNN NNN". Both peers derive the same
     * value and compare it out-of-band before the host approves a connection.
     */
    fun sasFromSharedKey(shared: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-512").digest(shared)
        val n = ((h[0].toInt() and 0xff) shl 24) or
            ((h[1].toInt() and 0xff) shl 16) or
            ((h[2].toInt() and 0xff) shl 8) or
            (h[3].toInt() and 0xff)
        // Replicate JS `>>> 0` (unsigned 32-bit) before the modulo.
        val code = (n.toLong() and 0xffff_ffffL) % 1_000_000L
        val s = code.toString().padStart(6, '0')
        return "${s.substring(0, 3)} ${s.substring(3)}"
    }

    fun publicKeyToB64(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

    fun publicKeyFromB64(b64: String): ByteArray {
        val key = Base64.getDecoder().decode(b64)
        require(key.size == BOX_PUBLIC_KEY_BYTES) {
            "Invalid public key: expected $BOX_PUBLIC_KEY_BYTES bytes, got ${key.size}"
        }
        return key
    }

    fun secretKeyFromB64(b64: String): ByteArray {
        val key = Base64.getDecoder().decode(b64)
        require(key.size == BOX_SECRET_KEY_BYTES) {
            "Invalid secret key: expected $BOX_SECRET_KEY_BYTES bytes, got ${key.size}"
        }
        return key
    }

    /**
     * Sealed-box operation (tweetnacl padded API). The low-level API requires the message padded
     * with 32 ZEROBYTES; the output carries a 16-byte BOXZEROBYTES prefix we strip, leaving the
     * wire layout `[MAC(16)][ciphertext]` (exactly what nacl.box.after returns in the reference).
     */
    private fun boxAfter(plain: ByteArray, nonce: ByteArray, shared: ByteArray): ByteArray {
        val m = ByteArray(ZERO_BYTES + plain.size)
        plain.copyInto(m, ZERO_BYTES)
        // The API writes exactly mlen bytes: [0..16)=zeros, [16..32)=MAC, [32..)=ciphertext.
        val c = ByteArray(m.size)
        check(TweetNacl.crypto_box_afternm(c, m, m.size, nonce, shared) == 0) { "box afternm failed" }
        return c.copyOfRange(BOX_OVERHEAD_BYTES, c.size)
    }
}

package com.nodeterm.android.core.e2ee

import com.iwebpp.crypto.TweetNacl
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec

/**
 * Ed25519 keys for the host's authorized_keys install during v0.2.37 pairing AND for the
 * LAN / SSH direct transport.
 *
 * The pair server installs our public key into `~/.ssh/authorized_keys` (free tier — no relay,
 * no Pro), so the SAME keypair must authenticate the subsequent SSH connection. We mint it here
 * with TweetNaCl (the pair wire format is NaCl-signature bytes), then expose it to sshj via the
 * EdDSA JCE provider (`net.i2p.crypto:eddsa`).
 */
object SshKeys {
    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    /** TweetNaCl ed25519 signing keypair (publicKey 32B, secretKey 64B: seed ‖ public). */
    fun generateEd25519(): KeyPair {
        val kp = TweetNacl.Signature.keyPair()
        return KeyPair(kp.getPublicKey(), kp.getSecretKey())
    }

    /**
     * Rebuild a TweetNaCl ed25519 keypair from its persisted 64-byte secret key (used to restore
     * a stored session without re-deriving). The public half is recoverable from the secret.
     */
    fun fromSecretKey(secretKey: ByteArray): KeyPair {
        val kp = TweetNacl.Signature.keyPair_fromSecretKey(secretKey)
        return KeyPair(kp.getPublicKey(), kp.getSecretKey())
    }

    /** The 32-byte ed25519 seed (the first half of the TweetNaCl 64-byte secret key). */
    fun seed(secretKey: ByteArray): ByteArray = secretKey.copyOfRange(0, 32)

    /**
     * The `java.security.KeyPair` sshj authenticates with (EdDSA provider). Derives the public
     * half from the seed so only the secret needs persisting. Returns null when the JCE provider
     * is unavailable (R8/desugaring) — the caller can surface a clean error.
     */
    fun toJavaKeyPair(secretKey: ByteArray): java.security.KeyPair? = try {
        val seed = seed(secretKey)
        val spec: EdDSANamedCurveSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
        val pub = EdDSAPublicKey(EdDSAPublicKeySpec(priv.a, spec))
        java.security.KeyPair(pub, priv)
    } catch (_: Throwable) {
        null
    }
}

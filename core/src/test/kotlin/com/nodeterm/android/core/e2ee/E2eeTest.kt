package com.nodeterm.android.core.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Interop vectors generated with node + tweetnacl from the reference implementation
 * (src/main/remote/e2ee.ts). Fixed secret keys make every value deterministic:
 *   hostSecret  = 0x42 × 32, clientSecret = 0x21 × 32.
 */
class E2eeTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    private fun b64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    // --- reference vectors (see /tmp/nd_vectors/vectors.json) ---
    private val HOST_PUB_B64 = "EyxEK+AQ+9V+cmAzKKp25x/MwVA6riGTJ9FNnJmT9HI="
    private val HOST_SEC_B64 = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
    private val CLIENT_PUB_B64 = "fTSkgV+muYJTXmCvO9m0lVaBYIDxZB/4HSt8iugmikQ="
    private val CLIENT_SEC_B64 = "ISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhISE="
    private val BASE_KEY_HEX = "dc11e1f3ebea70fb8bbf42962e3eca1292b18e09acad6b807103e96647ec9ec2"
    private val HOST_NONCE_HEX = "00112233445566778899aabbccddeeff"
    private val CLIENT_NONCE_HEX = "ffeeddccbbaa99887766554433221100"
    private val SESSION_KEY_HEX = "5b00b1f6f69c7ec40ef0ed618c2de2d1767382770459d468348104d1767fea63"
    private val BOX_HEX = "000102030405060708090a0b0c0d0e0f10111213141516176f6045e06cdb00f0862b034b461b5795f7605b102eb634880787e1e04d59"
    private val PLAINTEXT = "hello nodeterm"
    private val SAS = "093 461"

    @Test
    fun keyPairFromSecretMatchesVectors() {
        val host = E2ee.keyPairFromSecretKey(b64(HOST_SEC_B64))
        val client = E2ee.keyPairFromSecretKey(b64(CLIENT_SEC_B64))
        assertEquals(HOST_PUB_B64, E2ee.publicKeyToB64(host.publicKey))
        assertEquals(CLIENT_PUB_B64, E2ee.publicKeyToB64(client.publicKey))
    }

    @Test
    fun deriveSharedKeyMatchesVectorBothDirections() {
        val hostKeys = E2ee.keyPairFromSecretKey(b64(HOST_SEC_B64))
        val clientKeys = E2ee.keyPairFromSecretKey(b64(CLIENT_SEC_B64))
        val fromClient = E2ee.deriveSharedKey(HOST_PUB_B64, clientKeys.secretKey)
        val fromHost = E2ee.deriveSharedKey(CLIENT_PUB_B64, hostKeys.secretKey)
        assertContentEquals(hex(BASE_KEY_HEX), fromClient)
        assertContentEquals(fromClient, fromHost, "ECDH must agree on both sides")
    }

    @Test
    fun deriveSessionKeyMatchesVector() {
        val shared = hex(BASE_KEY_HEX)
        val session = E2ee.deriveSessionKey(
            shared,
            hex(HOST_NONCE_HEX),
            hex(CLIENT_NONCE_HEX)
        )
        assertContentEquals(hex(SESSION_KEY_HEX), session)
    }

    @Test
    fun decryptReferenceBoxReturnsPlaintext() {
        val shared = hex(BASE_KEY_HEX)
        val session = E2ee.deriveSessionKey(shared, hex(HOST_NONCE_HEX), hex(CLIENT_NONCE_HEX))
        val plain = E2ee.decrypt(hex(BOX_HEX), session)
        assertNotNull(plain)
        assertEquals(PLAINTEXT, String(plain, Charsets.UTF_8))
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val kp = E2ee.generateKeyPair()
        val shared = E2ee.deriveSharedKey(E2ee.publicKeyToB64(kp.publicKey), kp.secretKey)
        val session = E2ee.deriveSessionKey(shared, E2ee.randomSessionNonce(), E2ee.randomSessionNonce())
        val box = E2ee.encrypt("round trip works".toByteArray(Charsets.UTF_8), session)
        assertTrue(box.size >= E2ee.BOX_NONCE_BYTES + 16)
        val plain = E2ee.decrypt(box, session)
        assertEquals("round trip works", String(plain!!, Charsets.UTF_8))
    }

    @Test
    fun decryptWithWrongKeyReturnsNull() {
        val kp = E2ee.generateKeyPair()
        val shared = E2ee.deriveSharedKey(E2ee.publicKeyToB64(kp.publicKey), kp.secretKey)
        val session = E2ee.deriveSessionKey(shared, E2ee.randomSessionNonce(), E2ee.randomSessionNonce())
        val other = E2ee.deriveSessionKey(shared, E2ee.randomSessionNonce(), E2ee.randomSessionNonce())
        val box = E2ee.encrypt("secret".toByteArray(Charsets.UTF_8), session)
        assertNull(E2ee.decrypt(box, other), "wrong session key must fail the MAC")
    }

    @Test
    fun decryptTamperedBoxReturnsNull() {
        val kp = E2ee.generateKeyPair()
        val shared = E2ee.deriveSharedKey(E2ee.publicKeyToB64(kp.publicKey), kp.secretKey)
        val session = E2ee.deriveSessionKey(shared, E2ee.randomSessionNonce(), E2ee.randomSessionNonce())
        val box = E2ee.encrypt("secret".toByteArray(Charsets.UTF_8), session)
        box[box.size - 1] = (box[box.size - 1].toInt() xor 0x01).toByte()
        assertNull(E2ee.decrypt(box, session))
    }

    @Test
    fun decryptShortBoxReturnsNull() {
        val kp = E2ee.generateKeyPair()
        val shared = E2ee.deriveSharedKey(E2ee.publicKeyToB64(kp.publicKey), kp.secretKey)
        val session = E2ee.deriveSessionKey(shared, E2ee.randomSessionNonce(), E2ee.randomSessionNonce())
        assertNull(E2ee.decrypt(ByteArray(10), session))
    }

    @Test
    fun sasMatchesReference() {
        val shared = hex(BASE_KEY_HEX)
        assertEquals(SAS, E2ee.sasFromSharedKey(shared))
    }

    @Test
    fun publicKeyFromB64RejectsWrongLength() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            E2ee.publicKeyFromB64(java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
        }
    }
}

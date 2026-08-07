package com.nodeterm.android.core.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals

class HkdfTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    /** RFC 5869 Appendix A.1 — the canonical SHA-256 known-answer test. */
    @Test
    fun rfc5869A1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() } // 0x00..0x0c
        val info = ByteArray(10) { (0xf0 + it).toByte() } // 0xf0..0xf9
        val expectedPrk = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val expectedOkm = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

        val prk = Hkdf.extract(ikm, salt)
        assertContentEquals(expectedPrk, prk, "extract")

        val okm = Hkdf.expand(prk, info, 42)
        assertContentEquals(expectedOkm, okm, "expand")

        val okm42 = Hkdf.derive(ikm, salt, info, 42)
        assertContentEquals(expectedOkm, okm42, "derive")
    }

    /** Same input truncated to 32 bytes — the relay's session-key length. */
    @Test
    fun rfc5869A1TruncatedTo32() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val expected32 = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf")
        val okm = Hkdf.derive(ikm, salt, info, 32)
        assertContentEquals(expected32, okm)
    }

    /** Empty salt must behave as HashLen zeros (RFC 5869). */
    @Test
    fun emptySaltIsHashLenZeros() {
        val ikm = ByteArray(22) { 0x0b }
        val empty = Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 32)
        val zeros = Hkdf.derive(ikm, ByteArray(32), ByteArray(0), 32)
        assertContentEquals(zeros, empty)
    }
}

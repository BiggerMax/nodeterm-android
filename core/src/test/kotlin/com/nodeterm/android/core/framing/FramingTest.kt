package com.nodeterm.android.core.framing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FramingTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    /** encodeFrame(1 /*Output*/, 5, 0x0000000100000002, "hi there") — generated from framing.ts. */
    @Test
    fun encodeMatchesReferenceBytes() {
        val bytes = Framing.encodeFrame(Op.Output, 5, 0x0000_0001_0000_0002L, "hi there".toByteArray(Charsets.UTF_8))
        assertContentEquals(
            hex("740101000500000001000000020000006869207468657265"),
            bytes
        )
    }

    /** encodeFrame(2 /*SnapshotStart*/, 0, 0, empty) — the empty-snapshot-start vector. */
    @Test
    fun encodeEmptyMatchesReferenceBytes() {
        val bytes = Framing.encodeFrame(Op.SnapshotStart, 0, 0, ByteArray(0))
        assertContentEquals(hex("74010200000000000000000000000000"), bytes)
    }

    @Test
    fun decodeReferenceBytes() {
        val frame = Framing.decodeFrame(hex("740101000500000001000000020000006869207468657265"))
        assertEquals(Op.Output, frame?.op)
        assertEquals(5, frame?.streamId)
        assertEquals(0x0000_0001_0000_0002L, frame?.seq)
        assertContentEquals("hi there".toByteArray(Charsets.UTF_8), frame?.payload)
    }

    @Test
    fun roundTrip() {
        val payload = "line of terminal output\nwith unicode: 你好".toByteArray(Charsets.UTF_8)
        for (op in Op.KNOWN) {
            for (seq in listOf(0L, 1L, 0xffff_ffffL, 0x1_0000_0000L, Long.MAX_VALUE)) {
                val frame = Framing.decodeFrame(Framing.encodeFrame(op, 7, seq, payload))
                assertEquals(op, frame?.op)
                assertEquals(7, frame?.streamId)
                assertEquals(seq, frame?.seq)
                assertContentEquals(payload, frame?.payload)
            }
        }
    }

    @Test
    fun shortBufferReturnsNull() {
        assertNull(Framing.decodeFrame(ByteArray(15)))
        assertNull(Framing.decodeFrame(ByteArray(0)))
    }

    @Test
    fun badKindOrVersionReturnsNull() {
        val good = Framing.encodeFrame(Op.Output, 1, 0, ByteArray(0))
        val badKind = good.copyOf()
        badKind[0] = 0x55
        assertNull(Framing.decodeFrame(badKind))
        val badVersion = good.copyOf()
        badVersion[1] = 2
        assertNull(Framing.decodeFrame(badVersion))
    }

    @Test
    fun unknownOpcodeReturnsNull() {
        val good = Framing.encodeFrame(Op.Output, 1, 0, ByteArray(0))
        val bad = good.copyOf()
        bad[2] = 99
        assertNull(Framing.decodeFrame(bad))
    }

    @Test
    fun negativeSeqClampsToZero() {
        val frame = Framing.decodeFrame(Framing.encodeFrame(Op.Input, 3, -5, ByteArray(0)))
        assertEquals(0L, frame?.seq)
    }
}

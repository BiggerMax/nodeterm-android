package com.nodeterm.android.core.framing

/**
 * Terminal-stream binary framing for the relay transport — mirrors `src/main/remote/framing.ts`.
 *
 * The 16-byte header layout and opcode numbers are stable wire constants:
 * ```
 * [0]      kind    = 0x74
 * [1]      version = 1
 * [2]      opcode
 * [3]      reserved (0)
 * [4..8)   streamId  (uint32 LE)
 * [8..12)  seq high  (uint32 LE)
 * [12..16) seq low   (uint32 LE)
 * [16..)   payload
 * ```
 */

/** Opcode numbers are part of the stable wire contract — do not renumber. */
object Op {
    const val Output = 1
    const val SnapshotStart = 2
    const val SnapshotChunk = 3
    const val SnapshotEnd = 4
    const val Resized = 5
    const val Error = 6
    const val Input = 7
    const val Resize = 8
    const val Subscribe = 9
    const val Unsubscribe = 10
    const val SnapshotRequest = 11

    val KNOWN: Set<Int> = setOf(
        Output, SnapshotStart, SnapshotChunk, SnapshotEnd, Resized, Error,
        Input, Resize, Subscribe, Unsubscribe, SnapshotRequest
    )
}

/** Max bytes we let buffer on a binary channel before applying backpressure. */
const val MAX_BINARY_BUFFERED_AMOUNT = 8 * 1024 * 1024

data class Frame(
    val op: Int,
    val streamId: Int,
    val seq: Long,
    val payload: ByteArray
)

object Framing {
    private const val STREAM_KIND = 0x74
    private const val STREAM_VERSION = 1
    const val HEADER_BYTES = 16

    fun encodeFrame(op: Int, streamId: Int, seq: Long, payload: ByteArray): ByteArray {
        require(op in Op.KNOWN) { "unknown opcode: $op" }
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = STREAM_KIND.toByte()
        out[1] = STREAM_VERSION.toByte()
        out[2] = op.toByte()
        out[3] = 0
        writeU32LE(out, 4, streamId.toLong() and 0xffff_ffffL)
        val s = maxOf(0L, seq)
        writeU32LE(out, 8, (s ushr 32) and 0xffff_ffffL)
        writeU32LE(out, 12, s and 0xffff_ffffL)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /** Decode a frame. Returns null for short buffers, a bad kind/version, or an unknown opcode. */
    fun decodeFrame(buf: ByteArray): Frame? {
        if (buf.size < HEADER_BYTES) return null
        if ((buf[0].toInt() and 0xff) != STREAM_KIND || (buf[1].toInt() and 0xff) != STREAM_VERSION) return null
        val op = buf[2].toInt() and 0xff
        if (op !in Op.KNOWN) return null
        val streamId = readU32LE(buf, 4).toInt()
        val high = readU32LE(buf, 8)
        val low = readU32LE(buf, 12)
        return Frame(op, streamId, (high shl 32) or low, buf.copyOfRange(HEADER_BYTES, buf.size))
    }

    private fun writeU32LE(out: ByteArray, offset: Int, value: Long) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value ushr 8) and 0xff).toByte()
        out[offset + 2] = ((value ushr 16) and 0xff).toByte()
        out[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun readU32LE(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xff) or
            ((buf[offset + 1].toLong() and 0xff) shl 8) or
            ((buf[offset + 2].toLong() and 0xff) shl 16) or
            ((buf[offset + 3].toLong() and 0xff) shl 24)
}

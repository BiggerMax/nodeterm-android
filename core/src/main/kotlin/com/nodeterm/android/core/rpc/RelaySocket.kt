package com.nodeterm.android.core.rpc

import com.nodeterm.android.core.e2ee.E2ee
import com.nodeterm.android.core.framing.Frame
import com.nodeterm.android.core.framing.Framing
import com.nodeterm.android.core.framing.MAX_BINARY_BUFFERED_AMOUNT
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Relay socket + E2EE handshake + RPC/frame state machine — mirrors `relay-socket.ts`.
 *
 * A single connection to the dumb relay (which forwards opaque bytes host↔client matched by a
 * pairing token; it never decrypts). The token gates entry at the relay as a `?token=` QUERY
 * PARAM on the wss URL — it is never sent as a data frame.
 *
 * Handshake: `e2ee_hello → e2ee_ready → e2ee_auth → e2ee_authenticated`:
 *   - client (knows the host pubkey from the pairing offer) sends a PLAINTEXT text frame
 *     `e2ee_hello {publicKeyB64, nonceB64}` (its 16B session nonce) and derives the base key;
 *   - host replies a plaintext text frame `e2ee_ready {nonceB64}` (its 16B nonce);
 *   - client derives `sessionKey = HKDF(baseKey, hostNonce ‖ clientNonce)` and sends an
 *     ENCRYPTED `e2ee_auth` marker (tag 0x01);
 *   - host replies an ENCRYPTED `e2ee_authenticated`. Both sides fire onReady once complete.
 *
 * After authentication every peer message is an E2EE box (sent as a BINARY WebSocket frame).
 * The decrypted plaintext is `[role:1][seq:8 LE][tag:1][payload…]`:
 *   - role (host=1, client=2): only the peer's role tag is accepted — a reflected box back at its
 *     sender is rejected (defeats relay reflection under the same bidirectional session key);
 *   - seq: strictly increasing per direction (LE u64), receiver drops `seq <= recvSeq` — defeats
 *     replay/reorder; resets per (re)connection;
 *   - tag: 0x01 JSON RPC envelope, 0x02 terminal frame (framing.ts), 0x03 tunnel text (rpc.ts
 *     RpcMessage), 0x04 tunnel binary (encodePtyData).
 *
 * Handshake control frames are TEXT; E2EE boxes are BINARY — the transport preserves that.
 *
 * The WebSocket itself is INJECTED as a [Transport] (tests pass an in-process fake; production
 * passes an OkHttp wrapper from the app layer). Reconnect is the CALLER's job: onClose fires once
 * and the caller re-dials with a FRESH pairing token (the relay rejects reused tokens).
 */
class RelaySocket(
    private val opts: Options,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    /** The transport adapter. Text = handshake control (plaintext JSON); bytes = E2EE boxes. */
    interface Transport {
        /** Bytes handed to the socket but not yet flushed (drives frame backpressure). */
        val bufferedAmount: Long
        fun sendText(text: String)
        fun sendBinary(bytes: ByteArray)
        fun close()
        fun onMessage(cb: (Message) -> Unit)
        fun onClose(cb: () -> Unit)
    }

    sealed interface Message {
        data class Text(val text: String) : Message
        data class Binary(val bytes: ByteArray) : Message
    }

    enum class Role { HOST, CLIENT }

    enum class TunnelKind { TEXT, BINARY }

    /** An RPC request envelope as seen by the peer's onRpc handler. `id` correlates a later respond. */
    data class RpcRequest(val id: String, val method: String, val params: JsonElement?)

    data class Options(
        val url: String,
        val token: String,
        val role: Role,
        val ourKeys: E2ee.KeyPair,
        /** REQUIRED for the client (the pinned host pubkey); learned from e2ee_hello for a host. */
        val theirPubB64: String?,
        val transport: Transport,
        val rpcTimeoutMs: Long = 30_000,
        val keepaliveMs: Long = 25_000,
        val onReady: () -> Unit,
        val onRpc: (RpcRequest) -> Unit,
        val onFrame: (Frame) -> Unit,
        val onTunnel: ((TunnelKind, ByteArray) -> Unit)? = null,
        val onClose: () -> Unit
    )

    // ---- wire constants (stable, mirror relay-socket.ts) ----
    private val TAG_RPC = 0x01
    private val TAG_FRAME = 0x02
    private val TAG_TUNNEL_TEXT = 0x03
    private val TAG_TUNNEL_BIN = 0x04
    private val SEQ_BYTES = 8
    private val HEADER_BYTES = 1 + SEQ_BYTES

    private enum class State { CONNECTING, HANDSHAKING, READY, CLOSED }

    private val OUR_ROLE: Byte = if (opts.role == Role.HOST) 1 else 2
    private val PEER_ROLE: Byte = if (opts.role == Role.HOST) 2 else 1

    // baseKey = the stable ECDH precompute (drives the SAS / pinned identity).
    // sessionKey = HKDF(baseKey, hostNonce ‖ clientNonce) = the fresh per-session traffic key.
    private var baseKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private val ourNonce: ByteArray = E2ee.randomSessionNonce()
    private var peerPubB64: String? = if (opts.role == Role.CLIENT) opts.theirPubB64 else null

    private var state: State = State.CONNECTING
    private var intentionallyClosed = false
    private var readyFired = false
    private var sendSeq = 0L
    private var recvSeq = -1L
    private var requestCounter = 0

    private class PendingRpc(val continuation: CancellableContinuation<JsonElement?>, val timer: Job)
    private val pending = LinkedHashMap<String, PendingRpc>()
    private var keepaliveJob: Job? = null

    init {
        if (opts.role == Role.CLIENT) {
            require(opts.theirPubB64 != null) { "client role requires theirPubB64 (the host public key)" }
        }
        // Set HANDSHAKING BEFORE registering the transport callbacks: an injected transport may
        // already hold a buffered `e2ee_hello`, and registering onMessage drains it synchronously
        // — the whole handshake can complete reentrantly during construction, so `state` must be
        // correct before any inbound frame is processed.
        state = State.HANDSHAKING
        opts.transport.onMessage { handleMessage(it) }
        opts.transport.onClose { handleClose() }

        if (opts.role == Role.CLIENT) {
            // Client knows the host pubkey: derive the base (identity) key now and greet.
            baseKey = E2ee.deriveSharedKey(opts.theirPubB64!!, opts.ourKeys.secretKey)
            sendControl(buildJsonObject {
                put("type", "e2ee_hello")
                put("publicKeyB64", E2ee.publicKeyToB64(opts.ourKeys.publicKey))
                put("nonceB64", b64(ourNonce))
            }.toString())
        }
        // Host waits passively for `e2ee_hello` to learn the client pubkey.
    }

    // ---- public API (mirrors RelaySocket in relay-socket.ts) --------------------------------

    /** Send an RPC request to the peer; suspends until the `res` arrives (or timeout/close). */
    suspend fun rpc(method: String, params: JsonElement? = null): JsonElement? {
        val id = nextId()
        val timer = scope.launch {
            delay(opts.rpcTimeoutMs)
            val p = pending.remove(id) ?: return@launch
            p.timer.cancel()
            p.continuation.resumeWithException(RpcException("RPC timed out: $method"))
        }
        return suspendCancellableCoroutine { cont ->
            pending[id] = PendingRpc(cont, timer)
            cont.invokeOnCancellation {
                pending.remove(id)
                timer.cancel()
            }
            val sent = sendEncrypted(
                tagged(TAG_RPC, RpcCodec.encode(RpcMessage.Request(id, method, params)).toByteArray(Charsets.UTF_8))
            )
            if (!sent) {
                pending.remove(id)
                timer.cancel()
                cont.resumeWithException(RpcException("Relay socket is not connected."))
            }
        }
    }

    /** Fire a one-way notification (no response expected). Returns false if not ready. */
    fun notify(method: String, params: JsonElement? = null): Boolean =
        sendEncrypted(tagged(TAG_RPC, RpcCodec.encode(RpcMessage.Notify(method, params)).toByteArray(Charsets.UTF_8)))

    /** Answer a received RPC request by its id. */
    fun respond(id: String, ok: Boolean, body: JsonElement?) {
        sendEncrypted(tagged(TAG_RPC, RpcCodec.encode(RpcMessage.Response(id, ok, body)).toByteArray(Charsets.UTF_8)))
    }

    /**
     * Send a terminal frame to the peer. Returns false when the transport is over its
     * buffered-amount threshold (backpressure) or not ready.
     */
    fun sendFrame(op: Int, streamId: Int, seq: Long, payload: ByteArray): Boolean {
        if (state != State.READY) return false
        if (opts.transport.bufferedAmount > MAX_BINARY_BUFFERED_AMOUNT) return false
        return sendEncrypted(tagged(TAG_FRAME, Framing.encodeFrame(op, streamId, seq, payload)))
    }

    /** 4c tunnel: send one rpc.ts TEXT frame (a JSON RpcMessage). False if not ready — never sent in the clear. */
    fun sendTunnelText(json: String): Boolean =
        sendEncrypted(tagged(TAG_TUNNEL_TEXT, json.toByteArray(Charsets.UTF_8)))

    /** 4c tunnel: send one rpc.ts BINARY frame (encodePtyData bytes). False if not ready. */
    fun sendTunnelBinary(bytes: ByteArray): Boolean =
        sendEncrypted(tagged(TAG_TUNNEL_BIN, bytes))

    /** The Short Authentication String for this channel, or null before the handshake derives a key. */
    fun sas(): String? = baseKey?.let { E2ee.sasFromSharedKey(it) }

    /** The peer's NaCl box public key (base64), or null before `e2ee_hello` (client: pinned host key). */
    fun peerPublicKeyB64(): String? = peerPubB64

    /** Tear down: stops timers and closes the transport. Idempotent. */
    fun close() {
        if (intentionallyClosed) return
        intentionallyClosed = true
        keepaliveJob?.cancel()
        keepaliveJob = null
        rejectAllPending(RpcException("Relay socket closed."))
        state = State.CLOSED
        opts.transport.close()
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun sendControl(control: String) {
        opts.transport.sendText(control)
    }

    private fun sendEncrypted(plaintext: ByteArray): Boolean {
        val key = sessionKey ?: return false
        if (state != State.READY) return false
        opts.transport.sendBinary(E2ee.encrypt(withHeader(plaintext), key))
        return true
    }

    /** Auth control frames are sent before state flips to READY — this variant does not gate on state. */
    private fun sendEncryptedHandshake(plaintext: ByteArray) {
        val key = sessionKey ?: return
        opts.transport.sendBinary(E2ee.encrypt(withHeader(plaintext), key))
    }

    private fun withHeader(plaintext: ByteArray): ByteArray {
        val seq = sendSeq++
        val out = ByteArray(HEADER_BYTES + plaintext.size)
        out[0] = OUR_ROLE
        writeU64LE(out, 1, seq)
        plaintext.copyInto(out, HEADER_BYTES)
        return out
    }

    private fun tagged(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 1)
        out[0] = tag.toByte()
        body.copyInto(out, 1)
        return out
    }

    private fun fireReadyOnce() {
        if (readyFired) return
        readyFired = true
        startKeepalive()
        opts.onReady()
    }

    private fun handleMessage(message: Message) {
        when (message) {
            is Message.Text -> handleControl(message.text)
            is Message.Binary -> handleBinary(message.bytes)
        }
    }

    private fun handleBinary(bytes: ByteArray) {
        if (state == State.CLOSED) return
        val key = sessionKey ?: return
        val sealed = E2ee.decrypt(bytes, key) ?: return
        if (sealed.size < HEADER_BYTES) return
        // Reject a box tagged with our OWN role (a reflected message) — only the peer's role is valid.
        if (sealed[0] != PEER_ROLE) return
        // Enforce the strictly-increasing per-direction counter inside the authenticated plaintext.
        val seq = readU64LE(sealed, 1)
        if (seq <= recvSeq) return
        recvSeq = seq
        val plain = sealed.copyOfRange(HEADER_BYTES, sealed.size)
        when (state) {
            State.HANDSHAKING -> handleHandshakeEncrypted(plain)
            State.READY -> handlePeerPlaintext(plain)
            else -> {}
        }
    }

    private fun handleControl(raw: String) {
        // SECURITY: a handshake control frame is legitimate ONLY during the one handshake. Once
        // ready, re-processing one would let a relay MITM RE-KEY a live session under its own
        // keypair — drop it without re-keying (the real peer never re-sends a hello).
        if (readyFired) return
        val ctrl = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }
        when (ctrl["type"]?.jsonPrimitive?.contentOrNull) {
            "e2ee_hello" -> if (opts.role == Role.HOST) {
                val pub = ctrl["publicKeyB64"]?.jsonPrimitive?.contentOrNull ?: return
                val clientNonce = nonceFromB64(ctrl["nonceB64"]) ?: return
                peerPubB64 = pub
                baseKey = E2ee.deriveSharedKey(pub, opts.ourKeys.secretKey)
                // salt = hostNonce ‖ clientNonce (host's nonce first; both roles agree on the order).
                sessionKey = E2ee.deriveSessionKey(baseKey!!, ourNonce, clientNonce)
                sendControl(buildJsonObject {
                    put("type", "e2ee_ready")
                    put("nonceB64", b64(ourNonce))
                }.toString())
            }
            "e2ee_ready" -> if (opts.role == Role.CLIENT) {
                val hostNonce = nonceFromB64(ctrl["nonceB64"]) ?: return
                val bk = baseKey ?: return
                sessionKey = E2ee.deriveSessionKey(bk, hostNonce, ourNonce)
                sendEncryptedHandshake(tagged(TAG_RPC, "{\"type\":\"e2ee_auth\"}".toByteArray(Charsets.UTF_8)))
            }
        }
    }

    private fun handleHandshakeEncrypted(plain: ByteArray) {
        // During the handshake we only expect control JSON (auth / authenticated), tag 0x01.
        if (plain.size < 1 || plain[0] != TAG_RPC.toByte()) return
        val msg = try {
            Json.parseToJsonElement(String(plain, 1, plain.size - 1, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            return
        }
        when (msg["type"]?.jsonPrimitive?.contentOrNull) {
            "e2ee_auth" -> if (opts.role == Role.HOST) {
                sendEncryptedHandshake(tagged(TAG_RPC, "{\"type\":\"e2ee_authenticated\"}".toByteArray(Charsets.UTF_8)))
                state = State.READY
                fireReadyOnce()
            }
            "e2ee_authenticated" -> if (opts.role == Role.CLIENT) {
                state = State.READY
                fireReadyOnce()
            }
        }
    }

    private fun handlePeerPlaintext(plain: ByteArray) {
        if (plain.isEmpty()) return
        val tag = plain[0].toInt() and 0xff
        val body = plain.copyOfRange(1, plain.size)
        when (tag) {
            TAG_TUNNEL_TEXT -> opts.onTunnel?.invoke(TunnelKind.TEXT, body)
            TAG_TUNNEL_BIN -> opts.onTunnel?.invoke(TunnelKind.BINARY, body)
            TAG_FRAME -> Framing.decodeFrame(body)?.let { opts.onFrame(it) }
            TAG_RPC -> handleRpcBody(body)
            else -> {}
        }
    }

    private fun handleRpcBody(body: ByteArray) {
        val text = String(body, Charsets.UTF_8)
        val msg = RpcCodec.decode(text) ?: return
        when (msg) {
            RpcMessage.Keepalive -> {}
            is RpcMessage.Request -> opts.onRpc(RpcRequest(msg.id, msg.method, msg.params))
            is RpcMessage.Notify -> opts.onRpc(RpcRequest("", msg.method, msg.params))
            is RpcMessage.Response -> {
                val waiter = pending.remove(msg.id) ?: return
                waiter.timer.cancel()
                if (msg.ok) {
                    waiter.continuation.resume(msg.body)
                } else {
                    waiter.continuation.resumeWithException(RpcException(rpcErrorMessage(msg.body)))
                }
            }
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                delay(opts.keepaliveMs)
                sendEncrypted(tagged(TAG_RPC, "{\"kind\":\"keepalive\"}".toByteArray(Charsets.UTF_8)))
            }
        }
    }

    private fun handleClose() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        rejectAllPending(RpcException("Relay connection closed."))
        if (intentionallyClosed) {
            state = State.CLOSED
            return
        }
        state = State.CLOSED
        // Reconnect is the CALLER's responsibility (via onClose) with a fresh token.
        opts.onClose()
    }

    private fun rejectAllPending(err: RpcException) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (_, p) = it.next()
            it.remove()
            p.timer.cancel()
            p.continuation.resumeWithException(err)
        }
    }

    private fun nextId(): String {
        requestCounter += 1
        return "${opts.role.name.lowercase()}-rpc-$requestCounter-${System.currentTimeMillis()}"
    }

    private fun writeU64LE(out: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until SEQ_BYTES) {
            out[offset + i] = (v and 0xff).toByte()
            v = v ushr 8
        }
    }

    private fun readU64LE(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until SEQ_BYTES) {
            v = v or ((buf[offset + i].toLong() and 0xff) shl (8 * i))
        }
        return v
    }

    private companion object {
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        fun nonceFromB64(v: JsonElement?): ByteArray? {
            val s = v?.jsonPrimitive?.contentOrNull ?: return null
            val n = try {
                Base64.getDecoder().decode(s)
            } catch (_: Exception) {
                return null
            }
            return if (n.size == E2ee.SESSION_NONCE_BYTES) n else null
        }

        fun rpcErrorMessage(body: JsonElement?): String {
            if (body is JsonObject) {
                body["message"]?.jsonPrimitive?.contentOrNull?.let { return it }
                body["error"]?.jsonPrimitive?.contentOrNull?.let { return it }
            }
            return "RPC failed."
        }
    }
}

/** An RPC failure (timeout, peer error body, or a closed connection). */
class RpcException(message: String) : Exception(message)

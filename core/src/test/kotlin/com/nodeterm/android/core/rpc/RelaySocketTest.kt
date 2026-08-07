package com.nodeterm.android.core.rpc

import com.nodeterm.android.core.e2ee.E2ee
import com.nodeterm.android.core.framing.Frame
import com.nodeterm.android.core.framing.Framing
import com.nodeterm.android.core.framing.MAX_BINARY_BUFFERED_AMOUNT
import com.nodeterm.android.core.framing.Op
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-process fake transport: two linked pipes that deliver text/binary messages synchronously
 * (like the injected `RelayTransport` the reference tests use). A message is buffered until the
 * receiving side's `onMessage` is registered, so either construction order works.
 */
class InMemoryTransport : RelaySocket.Transport {
    var peer: InMemoryTransport? = null
    override var bufferedAmount: Long = 0
    private var onMsg: ((RelaySocket.Message) -> Unit)? = null
    private var onCloseCb: (() -> Unit)? = null
    private val buffer = ArrayDeque<RelaySocket.Message>()
    /** Total messages the relay handed this side (for keepalive counting). */
    var receiveCount = 0
    /** Binary messages the relay handed this side, in order (for replay/reflection tests). */
    val receivedBinaries = mutableListOf<ByteArray>()

    override fun sendText(text: String) {
        peer?.deliver(RelaySocket.Message.Text(text))
    }

    override fun sendBinary(bytes: ByteArray) {
        peer?.deliver(RelaySocket.Message.Binary(bytes))
    }
    override fun close() { onCloseCb?.invoke() }
    override fun onMessage(cb: (RelaySocket.Message) -> Unit) {
        onMsg = cb
        while (buffer.isNotEmpty()) cb(buffer.removeFirst())
    }
    override fun onClose(cb: () -> Unit) { onCloseCb = cb }

    fun deliver(m: RelaySocket.Message) {
        receiveCount++
        if (m is RelaySocket.Message.Binary) receivedBinaries.add(m.bytes)
        val cb = onMsg
        if (cb != null) cb(m) else buffer.addLast(m)
    }

    /** Simulate the relay injecting a captured frame (replay / reflection tests). */
    fun inject(m: RelaySocket.Message) {
        receiveCount++
        if (m is RelaySocket.Message.Binary) receivedBinaries.add(m.bytes)
        onMsg?.invoke(m)
    }

    /** The remote peer closed the socket — fires this side's onClose. */
    fun remoteClosed() { onCloseCb?.invoke() }
}

class RelaySocketTest {

    private val hostKeys = E2ee.keyPairFromSecretKey(ByteArray(32) { 0x42 })
    private val clientKeys = E2ee.keyPairFromSecretKey(ByteArray(32) { 0x21 })
    private val hostPubB64 = E2ee.publicKeyToB64(hostKeys.publicKey)

    private class Harness(
        val host: RelaySocket,
        val client: RelaySocket,
        val hostTransport: InMemoryTransport,
        val clientTransport: InMemoryTransport,
        val hostRequests: MutableList<RelaySocket.RpcRequest>,
        val clientRequests: MutableList<RelaySocket.RpcRequest>
    )

    /** Build a host+client pair over linked fake transports and run the full handshake to ready. */
    private fun pair(
        scope: CoroutineScope,
        hostOnRpc: (host: RelaySocket, req: RelaySocket.RpcRequest) -> Unit = { h, req ->
            req.id.takeIf { it.isNotEmpty() }?.let { h.respond(it, true, null) }
        },
        clientOnRpc: (RelaySocket.RpcRequest) -> Unit = {},
        hostOnFrame: (Frame) -> Unit = {},
        clientOnFrame: (Frame) -> Unit = {},
        clientOnTunnel: ((RelaySocket.TunnelKind, ByteArray) -> Unit)? = null,
        rpcTimeoutMs: Long = 30_000,
        keepaliveMs: Long = 25_000
    ): Harness {
        val hostTransport = InMemoryTransport()
        val clientTransport = InMemoryTransport()
        hostTransport.peer = clientTransport
        clientTransport.peer = hostTransport

        var hostReady = false
        var clientReady = false
        val hostRequests = mutableListOf<RelaySocket.RpcRequest>()
        val clientRequests = mutableListOf<RelaySocket.RpcRequest>()

        lateinit var host: RelaySocket
        lateinit var client: RelaySocket
        host = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.HOST,
                ourKeys = hostKeys, theirPubB64 = null, transport = hostTransport,
                rpcTimeoutMs = rpcTimeoutMs, keepaliveMs = keepaliveMs,
                onReady = { hostReady = true },
                onRpc = { req ->
                    hostRequests.add(req)
                    hostOnRpc(host, req)
                },
                onFrame = hostOnFrame, onClose = {}
            ), scope
        )
        client = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.CLIENT,
                ourKeys = clientKeys, theirPubB64 = hostPubB64, transport = clientTransport,
                rpcTimeoutMs = rpcTimeoutMs, keepaliveMs = keepaliveMs,
                onReady = { clientReady = true },
                onRpc = {
                    clientRequests.add(it)
                    clientOnRpc(it)
                },
                onFrame = clientOnFrame, onTunnel = clientOnTunnel, onClose = {}
            ), scope
        )
        assertTrue(hostReady, "host must reach ready after the handshake")
        assertTrue(clientReady, "client must reach ready after the handshake")
        assertEquals(host.sas(), client.sas(), "both sides must compute the same SAS")
        assertEquals(hostPubB64, client.peerPublicKeyB64(), "client pins the host pubkey")
        return Harness(host, client, hostTransport, clientTransport, hostRequests, clientRequests)
    }

    // ---- handshake ---------------------------------------------------------------------------

    @Test
    fun handshakeReachesReadyAndSasAgrees() = runTest {
        pair(backgroundScope)
    }

    @Test
    fun handshakeCompletesWhenClientCreatedFirst() = runTest {
        val hostTransport = InMemoryTransport()
        val clientTransport = InMemoryTransport()
        hostTransport.peer = clientTransport
        clientTransport.peer = hostTransport
        var hostReady = false
        var clientReady = false
        // Client created FIRST (before the host is listening) — the hello must be buffered.
        val client = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.CLIENT,
                ourKeys = clientKeys, theirPubB64 = hostPubB64, transport = clientTransport,
                onReady = { clientReady = true }, onRpc = {}, onFrame = {}, onClose = {}
            ), backgroundScope
        )
        val host = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.HOST,
                ourKeys = hostKeys, theirPubB64 = null, transport = hostTransport,
                onReady = { hostReady = true }, onRpc = {}, onFrame = {}, onClose = {}
            ), backgroundScope
        )
        assertTrue(hostReady)
        assertTrue(clientReady)
        assertEquals(host.sas(), client.sas())
    }

    // ---- RPC ---------------------------------------------------------------------------------

    @Test
    fun rpcRequestResponseRoundTrip() = runTest {
        val harness = pair(backgroundScope, hostOnRpc = { h, req ->
            h.respond(req.id, true, buildJsonObject { put("output", "blob-here") })
        })
        val result = harness.client.rpc("projects.list")
        assertEquals("projects.list", harness.hostRequests.first().method)
        val output = result?.jsonObject?.get("output")?.jsonPrimitive?.contentOrNull
        assertEquals("blob-here", output)
    }

    @Test
    fun rpcErrorBodyRejects() = runTest {
        val harness = pair(backgroundScope, hostOnRpc = { h, req ->
            h.respond(req.id, false, buildJsonObject { put("message", "Awaiting host approval.") })
        })
        val err = runCatching { harness.client.rpc("pty.attach") }.exceptionOrNull()
        assertTrue(err is RpcException)
        assertEquals("Awaiting host approval.", err!!.message)
    }

    @Test
    fun notifyDeliveredAsEmptyIdRequest() = runTest {
        val harness = pair(backgroundScope)
        val ok = harness.client.notify("canvas:mutate", buildJsonObject { put("op", "upsert") })
        assertTrue(ok)
        assertEquals(1, harness.hostRequests.size)
        assertEquals("canvas:mutate", harness.hostRequests[0].method)
        assertEquals("", harness.hostRequests[0].id, "notifications carry no correlation id")
        assertEquals("upsert", harness.hostRequests[0].params?.jsonObject?.get("op")?.jsonPrimitive?.content)
    }

    @Test
    fun rpcTimesOutWhenPeerNeverAnswers() = runTest {
        val harness = pair(backgroundScope, rpcTimeoutMs = 100, hostOnRpc = { _, _ -> /* never respond */ })
        val deferred = async { runCatching { harness.client.rpc("slow.method") }.exceptionOrNull() }
        advanceTimeBy(1000)
        val err = deferred.await()
        assertTrue(err is RpcException)
        assertTrue(err!!.message!!.contains("timed out"))
    }

    @Test
    fun rpcRejectedWhenNotReady() = runTest {
        val hostTransport = InMemoryTransport()
        val clientTransport = InMemoryTransport()
        hostTransport.peer = clientTransport
        clientTransport.peer = hostTransport
        val client = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.CLIENT,
                ourKeys = clientKeys, theirPubB64 = hostPubB64, transport = clientTransport,
                rpcTimeoutMs = 100, onReady = {}, onRpc = {}, onFrame = {}, onClose = {}
            ), backgroundScope
        )
        // No host socket → nothing ever answers; the send must fail fast (not ready).
        val err = runCatching { client.rpc("projects.list") }.exceptionOrNull()
        assertTrue(err is RpcException)
        assertEquals("Relay socket is not connected.", err!!.message)
    }

    // ---- keepalive ---------------------------------------------------------------------------

    @Test
    fun keepaliveSentOnInterval() = runTest {
        val harness = pair(backgroundScope, keepaliveMs = 100)
        val base = harness.hostTransport.receivedBinaries.size
        advanceTimeBy(250)
        // After ready the only traffic is keepalives: at t=100 and t=200.
        assertEquals(base + 2, harness.hostTransport.receivedBinaries.size, "expected 2 keepalives in 250ms")
    }

    // ---- terminal frames ---------------------------------------------------------------------

    @Test
    fun frameRoundTrip() = runTest {
        val frames = mutableListOf<Frame>()
        val harness = pair(backgroundScope, clientOnFrame = { frames.add(it) })
        val ok = harness.host.sendFrame(Op.Output, 7, 3, "line one\nline two".toByteArray(Charsets.UTF_8))
        assertTrue(ok)
        assertEquals(1, frames.size)
        assertEquals(Op.Output, frames[0].op)
        assertEquals(7, frames[0].streamId)
        assertEquals(3L, frames[0].seq)
        assertContentEquals("line one\nline two".toByteArray(Charsets.UTF_8), frames[0].payload)
    }

    @Test
    fun sendFrameBeforeReadyReturnsFalse() = runTest {
        val hostTransport = InMemoryTransport()
        val clientTransport = InMemoryTransport()
        hostTransport.peer = clientTransport
        clientTransport.peer = hostTransport
        val host = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.HOST,
                ourKeys = hostKeys, theirPubB64 = null, transport = hostTransport,
                onReady = {}, onRpc = {}, onFrame = {}, onClose = {}
            ), backgroundScope
        )
        assertFalse(host.sendFrame(Op.Output, 1, 0, ByteArray(0)))
    }

    @Test
    fun sendFrameRespectsBackpressureThreshold() = runTest {
        val harness = pair(backgroundScope)
        // The HOST's transport is the one whose buffered-amount gates host→client frames.
        harness.hostTransport.bufferedAmount = MAX_BINARY_BUFFERED_AMOUNT.toLong() + 1
        assertFalse(harness.host.sendFrame(Op.Output, 1, 0, ByteArray(0)))
        harness.hostTransport.bufferedAmount = 0
        assertTrue(harness.host.sendFrame(Op.Output, 1, 0, ByteArray(0)))
    }

    // ---- tunnel ------------------------------------------------------------------------------

    @Test
    fun tunnelTextDelivered() = runTest {
        var kind: RelaySocket.TunnelKind? = null
        var payload: String? = null
        val harness = pair(backgroundScope, clientOnTunnel = { k, p ->
            kind = k
            payload = String(p, Charsets.UTF_8)
        })
        assertTrue(harness.host.sendTunnelText("""{"t":"ev","channel":"agent:status","args":[]}"""))
        assertEquals(RelaySocket.TunnelKind.TEXT, kind)
        assertEquals("""{"t":"ev","channel":"agent:status","args":[]}""", payload)
    }

    @Test
    fun tunnelBinaryDelivered() = runTest {
        var kind: RelaySocket.TunnelKind? = null
        var payload: ByteArray? = null
        val harness = pair(backgroundScope, clientOnTunnel = { k, p ->
            kind = k
            payload = p
        })
        val bytes = byteArrayOf(0x01, 0x00, 0x04, 0x73, 0x69, 0x64)
        assertTrue(harness.host.sendTunnelBinary(bytes))
        assertEquals(RelaySocket.TunnelKind.BINARY, kind)
        assertContentEquals(bytes, payload)
    }

    // ---- security defenses -------------------------------------------------------------------

    @Test
    fun replayedBoxIsRejected() = runTest {
        val harness = pair(backgroundScope)
        harness.client.rpc("projects.list")
        assertEquals(1, harness.hostRequests.size)
        // The relay replays the LAST client→host binary (the request box). seq <= recvSeq → dropped.
        val replay = harness.hostTransport.receivedBinaries.last().copyOf()
        harness.hostTransport.inject(RelaySocket.Message.Binary(replay))
        assertEquals(1, harness.hostRequests.size, "replayed request must be dropped by the seq guard")
    }

    @Test
    fun reflectedBoxBackToSenderIsRejected() = runTest {
        val harness = pair(backgroundScope)
        harness.client.rpc("projects.list") // create client→host traffic (role byte = client)
        val reflected = harness.hostTransport.receivedBinaries.last().copyOf()
        harness.clientTransport.inject(RelaySocket.Message.Binary(reflected))
        assertTrue(harness.clientRequests.isEmpty(), "a reflected box (own role tag) must be dropped")
    }

    @Test
    fun corruptBoxIsIgnored() = runTest {
        val harness = pair(backgroundScope)
        harness.hostTransport.inject(RelaySocket.Message.Binary(ByteArray(64) { 0x55 }))
        assertTrue(harness.hostRequests.isEmpty())
    }

    @Test
    fun handshakeControlAfterReadyIsIgnored() = runTest {
        val harness = pair(backgroundScope)
        // A plaintext e2ee_hello re-injected after ready must NOT re-key the session.
        harness.clientTransport.inject(
            RelaySocket.Message.Text("""{"type":"e2ee_hello","publicKeyB64":"AAAA","nonceB64":"BBBB"}""")
        )
        // Session still works: an rpc round-trip succeeds (default responder answers ok).
        val result = harness.client.rpc("projects.list")
        assertNull(result)
        assertEquals(1, harness.hostRequests.size)
    }

    // ---- close / teardown --------------------------------------------------------------------

    @Test
    fun closeFiresPeerOnCloseAndRejectsPending() = runTest {
        var clientClosed = false
        val hostTransport = InMemoryTransport()
        val clientTransport = InMemoryTransport()
        hostTransport.peer = clientTransport
        clientTransport.peer = hostTransport
        var hostReady = false
        val host = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.HOST,
                ourKeys = hostKeys, theirPubB64 = null, transport = hostTransport,
                onReady = { hostReady = true }, onRpc = {}, onFrame = {}, onClose = {}
            ), backgroundScope
        )
        val client = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.CLIENT,
                ourKeys = clientKeys, theirPubB64 = hostPubB64, transport = clientTransport,
                onReady = {}, onRpc = {}, onFrame = {}, onClose = { clientClosed = true }
            ), backgroundScope
        )
        assertTrue(hostReady)
        // In-flight rpc must be rejected when the peer closes (the host never responds).
        val deferred = async { runCatching { client.rpc("projects.list") }.exceptionOrNull() }
        runCurrent() // run the launch body → the request is sent and suspends
        clientTransport.remoteClosed() // host side dropped the socket
        val err = deferred.await()
        assertTrue(err is RpcException)
        assertTrue(err!!.message!!.contains("closed"))
        assertTrue(clientClosed)
    }

    @Test
    fun intentionalCloseDoesNotFireOnClose() = runTest {
        var clientClosed = false
        var clientReady = false
        val hostTransport = InMemoryTransport()
        val clientTransport = InMemoryTransport()
        hostTransport.peer = clientTransport
        clientTransport.peer = hostTransport
        RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.HOST,
                ourKeys = hostKeys, theirPubB64 = null, transport = hostTransport,
                onReady = {}, onRpc = {}, onFrame = {}, onClose = {}
            ), backgroundScope
        )
        val client = RelaySocket(
            RelaySocket.Options(
                url = "wss://relay.test", token = "tok", role = RelaySocket.Role.CLIENT,
                ourKeys = clientKeys, theirPubB64 = hostPubB64, transport = clientTransport,
                onReady = { clientReady = true }, onRpc = {}, onFrame = {},
                onClose = { clientClosed = true }
            ), backgroundScope
        )
        assertTrue(clientReady)
        client.close()
        assertFalse(clientClosed, "an intentional close must NOT fire onClose")
    }

}

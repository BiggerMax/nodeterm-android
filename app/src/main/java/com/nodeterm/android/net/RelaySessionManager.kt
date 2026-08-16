package com.nodeterm.android.net

import android.util.Log
import com.nodeterm.android.core.e2ee.E2ee
import com.nodeterm.android.core.framing.Frame
import com.nodeterm.android.core.framing.Op
import com.nodeterm.android.core.model.CanvasState
import com.nodeterm.android.core.model.GitModels
import com.nodeterm.android.core.model.GitStatus
import com.nodeterm.android.core.model.JsonModels
import com.nodeterm.android.core.model.MirrorFile
import com.nodeterm.android.core.model.PairingOffer
import com.nodeterm.android.core.model.ProjectsBlob
import com.nodeterm.android.core.model.Workspace
import com.nodeterm.android.core.rpc.RelaySocket
import com.nodeterm.android.core.rpc.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Drives one host<->client relay session from the app layer:
 *
 *  1. opens the relay socket (client role) against the pairing offer's token, runs the E2EE
 *     handshake to READY;
 *  2. once the human confirms the SAS, syncs `projects.list` (workspace + tmux sessions +
 *     agent-status mirror), requests the canvas mirror, and polls `projects.list` to keep the
 *     node statuses / NEEDS-YOU inbox fresh (the relay phone dialect has no status push);
 *  3. attaches terminals (`pty.attach`) and streams output as RAW BYTES (Snapshot/Output/Error
 *     frames — P2 feeds them into the :core VT renderer), forwards typed input as OP.Input frames
 *     and `pty.scroll` for tmux-backed scrollback;
 *  4. answers held approvals via the host's `agent:answer-permission` RPC (or falls back to
 *     send-keys — the reference's documented v1 fallback);
 *  5. serves read-only remote file browsing (`fs.list` / `fs.read` / `fs.readBinary`, jailed to
 *     the host's shared project roots).
 *
 * Reconnect is NOT automatic: the pairing token is single-use, so onClose surfaces
 * [RelayEvent.Closed] and the user re-pairs with a fresh offer (spec: reconnects are driven by
 * the takeover party minting a fresh token).
 */
class RelaySessionManager(
    private val scope: CoroutineScope,
    private val onEvent: (HostSession.SessionEvent) -> Unit,
    /**
     * How long to wait for the host's E2EE handshake before surfacing a timeout. The caller may
     * shorten this when a direct LAN/SSH fallback is armed — free-tier hosts grant a relay token
     * but never join the relay room, so the handshake is doomed and stalling is pure UX cost.
     */
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS
) : HostSession {

    /** The host RPC method that answers a held Claude permission hook (deterministic approvals). */
    private val ANSWER_PERMISSION = "agent:answer-permission"

    private var socket: RelaySocket? = null
    private var transport: RelaySocket.Transport? = null
    private var syncing = false
    private var pollJob: Job? = null
    /** Fires if the E2EE handshake never completes (host never joins the token room). */
    private var handshakeTimeoutJob: Job? = null
    private var readyFired = false
    private val streamSeq = HashMap<Int, Long>()
    /** Snapshot reassembly per stream: SnapshotStart clears, Chunk appends, End emits once. */
    private val snapshotBuffers = HashMap<Int, ByteArrayOutputStream2>()

    fun connect(offer: PairingOffer, keys: E2ee.KeyPair) =
        connect(offer.relayEndpoint, offer.pairingToken, offer.hostPublicKeyB64, keys)

    /**
     * Connect to the relay as a client — the flat-offer path OR the v0.2.37 device path (the
     * pair response's `relayDeviceToken` is used verbatim as the connection token).
     */
    fun connect(relayEndpoint: String, token: String, hostPubB64: String, keys: E2ee.KeyPair) {
        if (socket != null) return
        val url = buildRelayUrl(relayEndpoint, token)
        Log.i(TAG, "connect ${relayEndpoint.substringBefore('?')} token.len=${token.length}")
        val relayTransport = OkHttpRelayTransport(url)
        this.transport = relayTransport
        // A nullable holder so the onReady lambda (which can fire only after the socket is
        // constructed and the transport opens) can safely read the socket back.
        var socketRef: RelaySocket? = null
        socketRef = RelaySocket(
            RelaySocket.Options(
                url = url,
                token = token,
                role = RelaySocket.Role.CLIENT,
                ourKeys = keys,
                theirPubB64 = hostPubB64,
                transport = relayTransport,
                onReady = {
                    Log.i(TAG, "relay E2EE READY")
                    readyFired = true
                    handshakeTimeoutJob?.cancel()
                    handshakeTimeoutJob = null
                    socketRef?.sas()?.let { onEvent(HostSession.SessionEvent.Ready(it)) }
                },
                onRpc = { req ->
                    if (req.method == "canvas:state") {
                        val nodesJson = req.params?.toString()
                        nodesJson?.let { JsonModels.canvasState(it) }?.let { onEvent(HostSession.SessionEvent.Canvas(it)) }
                    }
                },
                onFrame = { frame -> handleFrame(frame) },
                onClose = {
                    // Read the transport's reason BEFORE nulling it (the close detail is captured
                    // by OkHttp on its own thread; the callback re-delivers a pre-registration
                    // close so the reason is never lost).
                    val reason = (transport as? OkHttpRelayTransport)?.closeReason
                    Log.w(TAG, "relay socket closed reason=$reason")
                    handshakeTimeoutJob?.cancel()
                    handshakeTimeoutJob = null
                    readyFired = false
                    pollJob?.cancel()
                    pollJob = null
                    syncing = false
                    snapshotBuffers.clear()
                    // Drop the dead socket so a re-pair / re-connect is not blocked by the
                    // `if (socket != null) return` guard in connect().
                    socket = null
                    transport = null
                    onEvent(HostSession.SessionEvent.Closed(reason))
                }
            ),
            scope
        )
        this.socket = socketRef

        // Backstop: if the host never completes the E2EE handshake (e.g. its standing-host
        // listener is down / not entitled), surface a reason instead of hanging on
        // "Connecting to host…" forever.
        readyFired = false
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(handshakeTimeoutMs)
            if (!readyFired && socket != null) {
                Log.w(TAG, "relay handshake timed out after ${handshakeTimeoutMs}ms")
                (transport as? OkHttpRelayTransport)?.forceCloseWithReason(
                    "Timed out waiting for the host handshake — is the host connected to the relay?"
                )
            }
        }
    }

    /** After the human confirms the SAS: pull projects + canvas, then poll for fresh status. */
    override fun beginSync() {
        if (syncing) return
        syncing = true
        refreshProjects()
        requestCanvas()
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                if (syncing) refreshProjects()
            }
        }
    }

    /** Pull-to-refresh: fetch projects + the canvas mirror right now. */
    override fun refreshNow() {
        refreshProjects()
        requestCanvas()
    }

    /** Lifecycle-aware battery saver: pause polling while backgrounded, resume (with an
     *  immediate refresh) when the user returns. */
    override fun setPollingEnabled(enabled: Boolean) {
        if (!syncing) return
        if (enabled) {
            refreshNow()
            startPolling()
        } else {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /** `pty.attach {nodeId, cols, rows}` → host replies {streamId}, then streams frames. */
    override fun attach(nodeId: String, cols: Int, rows: Int, onResult: (Int?, String?) -> Unit) {
        scope.launch {
            try {
                val body = socket?.rpc(
                    "pty.attach",
                    buildJsonObject {
                        put("nodeId", nodeId)
                        put("cols", cols)
                        put("rows", rows)
                    }
                )
                val streamId = body?.jsonObject?.get("streamId")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                onResult(streamId, null)
            } catch (e: RpcException) {
                onResult(null, e.message)
            }
        }
    }

    /** Typed input → OP.Input frame (UTF-8 payload). */
    override fun sendInput(streamId: Int, text: String) {
        val seq = streamSeq.getOrDefault(streamId, 0L)
        streamSeq[streamId] = seq + 1
        socket?.sendFrame(Op.Input, streamId, seq, text.toByteArray(Charsets.UTF_8))
    }

    /** Client size report → OP.Resize frame (payload = 2x uint16 LE: cols, rows). */
    override fun resize(streamId: Int, cols: Int, rows: Int) {
        val payload = ByteArray(4)
        payload[0] = (cols and 0xff).toByte()
        payload[1] = ((cols ushr 8) and 0xff).toByte()
        payload[2] = (rows and 0xff).toByte()
        payload[3] = ((rows ushr 8) and 0xff).toByte()
        socket?.sendFrame(Op.Resize, streamId, 0, payload)
    }

    /**
     * Scroll the session's tmux history (`pty.scroll`). The host writes SGR mouse wheel events
     * into the tmux client and streams the repainted screen back as Output — the emulator's own
     * transcript is not the scrollback source of truth for tmux-backed nodes.
     */
    override fun scroll(streamId: Int, dir: String, lines: Int) {
        scope.launch {
            try {
                socket?.rpc(
                    "pty.scroll",
                    buildJsonObject {
                        put("streamId", streamId)
                        put("dir", dir)
                        put("lines", lines)
                    }
                )
            } catch (_: RpcException) {
                // Scrolling is best-effort; the next repaint still arrives.
            }
        }
    }

    /** List a directory on the host (jailed to shared project roots). */
    override fun fsList(path: String, onResult: (List<HostSession.FsEntry>?, String?) -> Unit) {
        scope.launch {
            try {
                val body = socket?.rpc("fs.list", buildJsonObject { put("path", path) })
                val entries = body?.jsonObject?.get("entries")?.jsonArray?.mapNotNull { el ->
                    runCatching {
                        val o = el.jsonObject
                        HostSession.FsEntry(
                            name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            dir = o["dir"]?.jsonPrimitive?.booleanOrNull ?: false,
                            ignored = o["ignored"]?.jsonPrimitive?.booleanOrNull ?: false
                        )
                    }.getOrNull()
                }
                if (entries == null) onResult(null, "fs.list: unexpected response") else onResult(entries, null)
            } catch (e: RpcException) {
                onResult(null, e.message)
            }
        }
    }

    /** Read a text file on the host (read-only). */
    override fun fsRead(path: String, onResult: (String?, String?) -> Unit) {
        scope.launch {
            try {
                val body = socket?.rpc("fs.read", buildJsonObject { put("path", path) })
                val content = body?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                if (content == null) onResult(null, "fs.read: no content") else onResult(content, null)
            } catch (e: RpcException) {
                onResult(null, e.message)
            }
        }
    }

    /** Detach a stream on the host (`pty.kill {streamId}`) — stops output streaming. Best-effort. */
    override fun ptyKill(streamId: Int) {
        scope.launch {
            try {
                socket?.rpc("pty.kill", buildJsonObject { put("streamId", streamId) })
            } catch (_: RpcException) {
                // The host drops its streams when this socket closes anyway.
            }
        }
    }

    /** Read-only git status for a project dir (`git.status {cwd}` → GitStatus body). */
    override fun gitStatus(cwd: String, onResult: (GitStatus?, String?) -> Unit) {
        scope.launch {
            try {
                val body = socket?.rpc("git.status", buildJsonObject { put("cwd", cwd) })
                val status = body?.let { GitModels.gitStatus(it) }
                if (status == null) onResult(null, "git.status: unexpected response") else onResult(status, null)
            } catch (e: RpcException) {
                onResult(null, e.message)
            }
        }
    }

    /**
     * Read-only unified diff for one file (`git.diff {cwd, path, staged, untracked}`). The host
     * responds with the diff text directly (JSON string body).
     */
    override fun gitDiff(cwd: String, path: String, staged: Boolean, untracked: Boolean, onResult: (String?, String?) -> Unit) {
        scope.launch {
            try {
                val body = socket?.rpc(
                    "git.diff",
                    buildJsonObject {
                        put("cwd", cwd)
                        put("path", path)
                        put("staged", staged)
                        put("untracked", untracked)
                    }
                )
                // The host responds with the diff text directly; tolerate a {content} wrap too.
                // Type-checked so a non-string body can never throw out of the coroutine.
                val text = when (body) {
                    is kotlinx.serialization.json.JsonPrimitive -> body.contentOrNull
                    is kotlinx.serialization.json.JsonObject ->
                        body["content"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
                if (text == null) onResult(null, "git.diff: unexpected response") else onResult(text, null)
            } catch (e: RpcException) {
                onResult(null, e.message)
            }
        }
    }

    /** Read a binary file on the host (read-only; delivered base64). */
    override fun fsReadBinary(path: String, onResult: (ByteArray?, String?) -> Unit) {
        scope.launch {
            try {
                val body = socket?.rpc("fs.readBinary", buildJsonObject { put("path", path) })
                val b64 = body?.jsonObject?.get("base64")?.jsonPrimitive?.contentOrNull
                if (b64 == null) onResult(null, "fs.readBinary: no data")
                else onResult(runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull(), null)
            } catch (e: RpcException) {
                onResult(null, e.message)
            }
        }
    }

    /**
     * Answer a held approval. With a `pendingId` (hook-reply ticket) the host's
     * `agent:answer-permission` RPC writes the answer file; without one we fall back to
     * send-keys (the reference's documented v1 fallback — typed into the node's terminal).
     */
    override fun answerApproval(nodeId: String, pendingId: String?, decision: String, onResult: (Boolean, String) -> Unit) {
        if (pendingId == null) {
            onResult(false, "No hook-reply ticket — answer in the terminal (send-keys fallback).")
            return
        }
        scope.launch {
            try {
                val body = socket?.rpc(
                    ANSWER_PERMISSION,
                    buildJsonObject {
                        put("nodeId", nodeId)
                        put("pendingId", pendingId)
                        put("decision", decision)
                    }
                )
                val ok = body?.jsonObject?.get("ok")?.let {
                    if (it is kotlinx.serialization.json.JsonPrimitive && it.contentOrNull != "true") false else true
                } ?: true
                onResult(ok, if (ok) "Answered." else "Host refused the answer.")
            } catch (e: RpcException) {
                onResult(false, e.message ?: "Failed to answer.")
            }
        }
    }

    override fun requestCanvas() {
        scope.launch {
            try {
                socket?.rpc("canvas:request")
            } catch (e: RpcException) {
                onEvent(HostSession.SessionEvent.Error(e.message ?: "canvas:request failed"))
            }
        }
    }

    override fun close() {
        syncing = false
        pollJob?.cancel()
        pollJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        readyFired = false
        snapshotBuffers.clear()
        socket?.close()
        socket = null
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun handleFrame(frame: Frame) {
        val streamId = frame.streamId
        when (frame.op) {
            Op.SnapshotStart -> snapshotBuffers[streamId] = ByteArrayOutputStream2()
            Op.SnapshotChunk -> snapshotBuffers[streamId]?.write(frame.payload)
            Op.SnapshotEnd -> {
                val snapshot = snapshotBuffers.remove(streamId)?.toByteArray() ?: ByteArray(0)
                onEvent(HostSession.SessionEvent.StreamData(streamId, HostSession.StreamKind.SNAPSHOT, snapshot))
            }
            Op.Output -> onEvent(
                HostSession.SessionEvent.StreamData(streamId, HostSession.StreamKind.OUTPUT, frame.payload)
            )
            Op.Error -> {
                val exitCode = String(frame.payload, Charsets.UTF_8)
                    .let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) } }
                    .getOrNull()
                    ?.let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("exitCode")?.jsonPrimitive?.contentOrNull
                    ?.toIntOrNull()
                onEvent(HostSession.SessionEvent.StreamEnded(streamId, exitCode))
            }
            else -> {}
        }
    }

    private fun refreshProjects() {
        scope.launch {
            try {
                val body = socket?.rpc("projects.list")
                val output = body?.jsonObject?.get("output")?.jsonPrimitive?.contentOrNull ?: return@launch
                val parsed = ProjectsBlob.parse(output)
                onEvent(
                    HostSession.SessionEvent.Projects(
                        workspace = JsonModels.workspace(parsed.workspaceJson),
                        mirror = JsonModels.mirror(parsed.statusJson),
                        tmuxSessions = parsed.tmuxSessions
                    )
                )
            } catch (e: RpcException) {
                onEvent(HostSession.SessionEvent.Error(e.message ?: "projects.list failed"))
            }
        }
    }

    private fun buildRelayUrl(endpoint: String, token: String): String {
        val sep = if (endpoint.contains("?")) "&" else "?"
        return "$endpoint${sep}token=${java.net.URLEncoder.encode(token, "UTF-8")}"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        /** Give the host time to complete the E2EE handshake before surfacing a failure. */
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val TAG = "NodetermRelay"
    }
}

/** Tiny growable byte buffer (avoids a dependency on java.io for the reassembly path). */
private class ByteArrayOutputStream2 {
    private var buf = ByteArray(4096)
    private var size = 0

    fun write(b: ByteArray) {
        if (size + b.size > buf.size) {
            var cap = buf.size
            while (cap < size + b.size) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(b, 0, buf, size, b.size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

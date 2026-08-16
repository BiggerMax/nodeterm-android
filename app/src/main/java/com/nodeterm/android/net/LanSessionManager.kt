package com.nodeterm.android.net

import android.util.Log
import com.nodeterm.android.core.e2ee.SshKeys
import com.nodeterm.android.core.model.GitFileChange
import com.nodeterm.android.core.model.GitStatus
import com.nodeterm.android.core.model.JsonModels
import com.nodeterm.android.core.model.MirrorFile
import com.nodeterm.android.core.model.Workspace
import com.nodeterm.android.core.remote.LanCommands
import com.nodeterm.android.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.kex.Curve25519SHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import net.schmizz.sshj.transport.kex.KeyExchange
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The LAN / SSH direct transport — the free-tier path (no relay, no Pro). Mirrors the iOS SSH
 * browse surface of the reference host:
 *
 *  1. after pairing installed our ed25519 key into `~/.ssh/authorized_keys` (free, ungated), we
 *     SSH straight to `host:port` as `user` with that key (TOFU host-key pinning);
 *  2. we probe the host's userData dir, then cat `workspace.json` + `agent-status.json` and list
 *     the live `nt-<nodeId>` tmux sessions (the same bytes the relay `projects.list` serves);
 *  3. a node's terminal is a tmux CLIENT: `tmux -L node-terminal new-session -A -s nt-<id>` under
 *     a PTY, with capture-pane as the initial snapshot; input/resize/scroll go straight into it;
 *  4. file browsing / git status+diff are read-only one-shot `ssh … 'command'` execs.
 *
 * Threading: all blocking sshj IO runs on [Dispatchers.IO]; the SHARED control connection is
 * serialized behind [controlMutex] (sshj does not document concurrent channel opening), while
 * each attached terminal gets its OWN connection so streaming never contends with the control
 * plane. Events are pushed on the same [HostSession.SessionEvent] surface as the relay.
 */
class LanSessionManager(
    private val scope: CoroutineScope,
    private val lan: SessionStore.LanSession,
    private val store: SessionStore,
    private val onEvent: (HostSession.SessionEvent) -> Unit
) : HostSession {

    private var client: SSHClient? = null
    private var userDataDir: String? = null
    private var syncing = false
    private var pollJob: Job? = null
    /** One-shot notice: the host's project files could not be read over SSH (macOS TCC etc.). */
    private var reportedUnreadableProjects = false
    /** nodeId → project cwd, filled from each projects sync; used to start new tmux sessions. */
    private val nodeCwds = ConcurrentHashMap<String, String>()
    private val streams = ConcurrentHashMap<Int, StreamShell>()
    private val streamSeq = AtomicInteger(0)
    /** Serializes every exec on the shared control connection. */
    private val controlMutex = Mutex()
    /** Serializes control-connection (re)establishment so concurrent failing polls don't race. */
    private val connectMutex = Mutex()
    /** True while a reconnect attempt is in flight (failing polls no-op meanwhile). */
    private var reconnecting = false

    /** One live terminal: its own SSH connection running a tmux client under a PTY. */
    private class StreamShell(
        val nodeId: String,
        val ssh: SSHClient,
        val session: Session,
        val shell: Session.Shell
    ) {
        fun close() {
            runCatching { shell.close() }
            runCatching { session.close() }
            runCatching { ssh.close() }
        }
    }

    /** One `ssh … 'command'` outcome: stdout, stderr (for error text), and the exit status. */
    private data class ExecResult(val output: String, val stderr: String, val exitStatus: Int)

    /** SSH connect + ed25519 auth + userData probe. On success → [HostSession.SessionEvent.Ready]. */
    fun connect() {
        scope.launch(Dispatchers.IO) {
            var c: SSHClient? = null
            try {
                c = newClient()
                c.connect(lan.host, lan.port)
                c.authPublickey(lan.user, keyProvider())
                client = c
                userDataDir = probeUserDataDir()
                if (userDataDir == null) {
                    onEvent(
                        HostSession.SessionEvent.Error(
                            "SSH connected, but no nodeterm workspace was found on the host — " +
                                "is the desktop app open? Will keep looking…"
                        )
                    )
                }
                Log.i(TAG, "lan connected ${lan.user}@${lan.host}:${lan.port} userData=$userDataDir")
                onEvent(HostSession.SessionEvent.Ready(""))
            } catch (e: Exception) {
                Log.w(TAG, "lan connect failed", e)
                runCatching { c?.close() } // never leak a half-open client
                client = null
                val msg = friendlyError(e)
                onEvent(HostSession.SessionEvent.Error(msg))
                onEvent(HostSession.SessionEvent.Closed(msg))
            }
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun beginSync() {
        if (syncing) return
        syncing = true
        refreshProjects()
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

    /** Pull-to-refresh: read the workspace + agent-status files right now. */
    override fun refreshNow() {
        refreshProjects()
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

    override fun close() {
        syncing = false
        pollJob?.cancel()
        pollJob = null
        streams.values.forEach { it.close() }
        streams.clear()
        client?.let { c -> runCatching { c.close() } }
        client = null
    }

    // ---- projects + status --------------------------------------------------------------------

    private fun refreshProjects() {
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            try {
                var dir = userDataDir
                if (dir == null) {
                    // The desktop app may have been closed at connect time — re-probe silently;
                    // connect() already surfaced the reason once.
                    dir = probeUserDataDir()
                    if (dir == null) return@launch
                    userDataDir = dir
                }
                val meta = LanCommands.parseMetadata(controlExec(LanCommands.fetchMetadataCommand(dir)).output)
                val index = LanCommands.parseWorkspaceIndex(meta.workspaceJson)
                val cwds = index.entries.mapNotNull { it.cwd }
                val files = if (cwds.isEmpty()) emptyMap()
                else LanCommands.parseProjectFiles(controlExec(LanCommands.fetchProjectFilesCommand(cwds)).output)
                if (!reportedUnreadableProjects && cwds.isNotEmpty() && files.values.none { it.isNotBlank() }) {
                    // Every project file came back empty — macOS sshd usually lacks Full Disk Access
                    // for ~/Documents etc. Surface ONCE so the empty list is not a silent mystery.
                    reportedUnreadableProjects = true
                    onEvent(
                        HostSession.SessionEvent.Error(
                            "Connected, but project files could not be read over SSH — the host's sshd " +
                                "may lack Full Disk Access for ~/Documents (macOS)."
                        )
                    )
                }
                val v2 = LanCommands.assembleV2Workspace(meta.workspaceJson, files)
                val workspace: Workspace? = JsonModels.workspace(v2)
                // Remember each node's project dir so a fresh tmux session starts in the right
                // place (attachCommand's `-c`) instead of the SSH login directory.
                workspace?.projects?.forEach { p ->
                    val pcwd = p.cwd
                    if (!pcwd.isNullOrBlank()) {
                        p.nodes.forEach { n -> if (n.id.isNotBlank()) nodeCwds[n.id] = pcwd }
                    }
                }
                val mirror: MirrorFile? = JsonModels.mirror(meta.statusJson)
                onEvent(
                    HostSession.SessionEvent.Projects(
                        workspace = workspace,
                        mirror = mirror,
                        tmuxSessions = meta.sessions
                    )
                )
            } catch (e: Exception) {
                if (!isConnectionFailure(e)) {
                    // Command/parse-level failure — the connection is fine, surface it once.
                    Log.w(TAG, "lan projects sync failed", e)
                    onEvent(HostSession.SessionEvent.Error("projects sync failed: ${e.message}"))
                    return@launch
                }
                // The SSH control connection died mid-session (Wi-Fi blip, host sleep, desktop app
                // quit). Reconnect with bounded retries instead of spamming "projects sync failed"
                // every poll while the UI sits in a stale READY state with no recovery path.
                Log.w(TAG, "lan control connection lost during sync — reconnecting", e)
                reconnect()
            }
        }
    }

    /**
     * The SSH control connection died mid-session. Re-establish it with bounded retries so a
     * transient blip (Wi-Fi change, host sleep) self-heals; give up with a proper
     * [HostSession.SessionEvent.Closed] so the UI leaves the stale READY state instead of
     * erroring forever. No-ops when the session was already torn down or another poll is
     * already reconnecting.
     */
    private suspend fun reconnect() {
        connectMutex.withLock {
            if (!syncing || reconnecting) return
            reconnecting = true
            try {
                var attempt = 0
                while (attempt < MAX_RECONNECT_ATTEMPTS && syncing) {
                    attempt++
                    // Drop the dead client before retrying; each attempt opens a fresh connection.
                    client?.let { runCatching { it.close() } }
                    client = null
                    try {
                        val c = newClient()
                        c.connect(lan.host, lan.port)
                        c.authPublickey(lan.user, keyProvider())
                        client = c
                        userDataDir = probeUserDataDir()
                        Log.i(TAG, "lan control connection re-established (attempt $attempt)")
                        // Fresh connection — re-sync immediately; the standing poll keeps it fresh.
                        refreshProjects()
                        return
                    } catch (e: Exception) {
                        if (e is net.schmizz.sshj.userauth.UserAuthException) {
                            // The host stopped trusting this device's key — retrying is pointless.
                            failClosed(e)
                            return
                        }
                        Log.w(TAG, "lan reconnect attempt $attempt failed", e)
                        client?.let { runCatching { it.close() } }
                        client = null
                        kotlinx.coroutines.delay(RECONNECT_BACKOFF_MS * attempt)
                    }
                }
                failClosed(null)
            } finally {
                reconnecting = false
            }
        }
    }

    /** Tear the session down and surface [HostSession.SessionEvent.Closed] with a clear reason. */
    private fun failClosed(e: Exception?) {
        if (!syncing) return // already torn down (user action) — keep quiet
        syncing = false
        pollJob?.cancel()
        pollJob = null
        streams.values.forEach { it.close() }
        streams.clear()
        client?.let { runCatching { it.close() } }
        client = null
        val reason = if (e != null) friendlyError(e) else
            "SSH connection to ${lan.host}:${lan.port} was lost and could not be re-established — " +
                "check the host's network and Remote Login, then reconnect."
        onEvent(HostSession.SessionEvent.Closed(reason))
    }

    /**
     * True when [e] means the SSH control connection itself is dead (socket/transport closed), as
     * opposed to a command/parse failure — the former warrants a reconnect, the latter does not.
     */
    private fun isConnectionFailure(e: Exception): Boolean = when (e) {
        is java.io.IOException -> true // socket reset / EOF / timeout
        is net.schmizz.sshj.transport.TransportException -> true
        // "Not connected" guards from our controlExec and sshj's SSHClient; deliberately message-
        // scoped so other IllegalStateExceptions (e.g. the fatal "Ed25519 key provider unavailable"
        // guard) aren't misclassified as a dead connection and reconnected pointlessly.
        is IllegalStateException -> e.message?.contains("Not connected", ignoreCase = true) == true
        else -> e.cause?.let { c -> if (c is Exception) isConnectionFailure(c) else false } ?: false
    }

    // ---- terminal -----------------------------------------------------------------------------

    /** Attach the node's tmux session as a PTY client on its own SSH connection. */
    override fun attach(nodeId: String, cols: Int, rows: Int, onResult: (Int?, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            var stream: StreamShell? = null
            var c: SSHClient? = null
            var sid = 0
            try {
                // Recreate a node session stranded in the WRONG directory: older clients created
                // it without `-c`, so `new-session -A` would keep attaching the stale cwd. When we
                // know the project dir and the live session is elsewhere, kill it so the attach
                // below recreates it where the project lives.
                val wantCwd = nodeCwds[nodeId]
                if (wantCwd != null) {
                    runCatching {
                        val current = controlExec(LanCommands.sessionPathCommand(nodeId)).output.trim()
                        if (current.isNotEmpty() && current != wantCwd) {
                            Log.w(TAG, "node $nodeId session in '$current', project is '$wantCwd' — recreating")
                            controlExec(LanCommands.killSessionCommand(nodeId))
                        }
                    }.onFailure { Log.w(TAG, "lan session path check failed node=$nodeId", it) }
                }
                c = newClient()
                c.connect(lan.host, lan.port)
                c.authPublickey(lan.user, keyProvider())
                val s = c.startSession()
                val cc = cols.coerceAtLeast(2)
                val rr = rows.coerceAtLeast(2)
                s.allocatePTY("xterm-256color", cc, rr, 0, 0, emptyMap())
                val shell = s.startShell()
                stream = StreamShell(nodeId, c, s, shell)
                c = null // ownership moved to the stream
                sid = streamSeq.incrementAndGet()
                streams[sid] = stream!!
                onResult(sid, null)
                // Initial paint: capture-pane on the SAME connection (channels multiplex) — the
                // raw dump feeds the emulator's SNAPSHOT path (bare \n rows; UI normalizes to \r\n).
                val snapshot = execText(stream!!.ssh, LanCommands.captureCommand(nodeId)).output
                onEvent(HostSession.SessionEvent.StreamData(sid, HostSession.StreamKind.SNAPSHOT, snapshot.toByteArray(Charsets.UTF_8)))
                // Attach-or-create the tmux client in the PTY. Pass the node's project dir so a
                // session that doesn't exist yet starts there (`-c`); an existing session keeps
                // its own cwd (tmux `-A` wins over `-c`).
                val out = shell.outputStream
                val cmd = LanCommands.pathAware(LanCommands.attachCommand(nodeId, cc, rr, nodeCwds[nodeId])) + "\n"
                out.write(cmd.toByteArray(Charsets.UTF_8))
                out.flush()
                // Stream the client's output until the shell (or the connection) dies.
                val reader = shell.inputStream
                val buf = ByteArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        onEvent(HostSession.SessionEvent.StreamData(sid, HostSession.StreamKind.OUTPUT, buf.copyOf(n)))
                    }
                }
                Log.i(TAG, "lan stream $sid ended (EOF)")
                streams.remove(sid)
                onEvent(HostSession.SessionEvent.StreamEnded(sid, 0))
            } catch (e: Exception) {
                Log.w(TAG, "lan attach failed node=$nodeId", e)
                if (sid != 0) streams.remove(sid)
                stream?.close()
                runCatching { c?.close() } // connect/auth failed before stream ownership
                // Only surface an attach error when the stream never came up; a mid-stream break
                // is a terminal end, not a failed attach.
                if (sid == 0) onResult(null, friendlyError(e))
                else onEvent(HostSession.SessionEvent.StreamEnded(sid, null))
            }
        }
    }

    /** Typed input → the tmux client's stdin. */
    /** Typed input → the tmux client's stdin (blocking sshj IO — off the main thread). */
    override fun sendInput(streamId: Int, text: String) {
        val stream = streams[streamId] ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val out = stream.shell.outputStream
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "lan sendInput failed stream=$streamId", e)
            }
        }
    }

    /** tmux client window size → PTY dimensions (blocking sshj IO — never on the main thread). */
    override fun resize(streamId: Int, cols: Int, rows: Int) {
        scope.launch(Dispatchers.IO) {
            val stream = streams[streamId] ?: return@launch
            try {
                stream.shell.changeWindowDimensions(cols.coerceAtLeast(2), rows.coerceAtLeast(2), 0, 0)
            } catch (e: Exception) {
                Log.w(TAG, "lan resize failed stream=$streamId", e)
            }
        }
    }

    /**
     * Scroll the session's history by writing SGR mouse-wheel events into the stream's tmux
     * CLIENT stdin — the exact approach of the reference handleScroll (host-service.ts):
     * [LanCommands.scrollSeq] clamped to 1..20 notches, addressed to cell 1,1. The bytes must
     * reach the tmux client's terminal input so tmux parses them as mouse events for the pane
     * the phone is viewing — a `send-keys` exec on the control connection injects into the
     * session's key path instead, which tmux never treats as a wheel, so nothing scrolled. The
     * repainted screen then streams back as normal Output.
     *
     * Each wheel sequence is written as ONE write() call (the full sequence, never split), so it
     * cannot interleave mid-sequence with concurrent [sendInput] typing on the same PTY.
     */
    override fun scroll(streamId: Int, dir: String, lines: Int) {
        val stream = streams[streamId] ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val notches = lines.coerceIn(1, 20)
                val seq = LanCommands.scrollSeq(dir)
                val out = stream.shell.outputStream
                repeat(notches) { out.write(seq.toByteArray(Charsets.UTF_8)) }
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "lan scroll failed stream=$streamId", e)
            }
        }
    }

    /** Drop the tmux client (the session keeps running detached on the host). */
    override fun ptyKill(streamId: Int) {
        streams.remove(streamId)?.close()
    }

    // ---- read-only file browse ---------------------------------------------------------------

    override fun fsList(path: String, onResult: (List<HostSession.FsEntry>?, String?) -> Unit) {
        if (client == null) {
            onResult(null, "Not connected")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val r = controlExec(LanCommands.lsCommand(path))
                if (r.exitStatus != 0) {
                    onResult(null, r.stderr.trim().take(160).ifBlank { "ls failed (exit ${r.exitStatus})" })
                    return@launch
                }
                val entries = LanCommands.parseLs(r.output).map { HostSession.FsEntry(it.name, it.dir, false) }
                onResult(entries, null)
            } catch (e: Exception) {
                onResult(null, friendlyError(e))
            }
        }
    }

    override fun fsRead(path: String, onResult: (String?, String?) -> Unit) {
        if (client == null) {
            onResult(null, "Not connected")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val r = controlExec(LanCommands.catCommand(path))
                if (r.exitStatus != 0) {
                    onResult(null, r.stderr.trim().take(160).ifBlank { "cat failed (exit ${r.exitStatus})" })
                    return@launch
                }
                onResult(r.output, null)
            } catch (e: Exception) {
                onResult(null, friendlyError(e))
            }
        }
    }

    override fun fsReadBinary(path: String, onResult: (ByteArray?, String?) -> Unit) {
        if (client == null) {
            onResult(null, "Not connected")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val r = controlExec(LanCommands.base64Command(path))
                if (r.exitStatus != 0) {
                    onResult(null, r.stderr.trim().take(160).ifBlank { "base64 failed (exit ${r.exitStatus})" })
                    return@launch
                }
                onResult(Base64.getDecoder().decode(r.output.filterNot { it.isWhitespace() }), null)
            } catch (e: Exception) {
                onResult(null, friendlyError(e))
            }
        }
    }

    // ---- read-only git ------------------------------------------------------------------------

    override fun gitStatus(cwd: String, onResult: (GitStatus?, String?) -> Unit) {
        if (client == null) {
            onResult(null, "Not connected")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val ps = LanCommands.parseGitStatus(controlExec(LanCommands.gitStatusCommand(cwd)).output)
                if (ps.branch.isEmpty() && ps.staged.isEmpty() && ps.changed.isEmpty()) {
                    onResult(GitStatus(), null) // not a repo (or no commits yet) — clean empty status
                    return@launch
                }
                onResult(
                    GitStatus(
                        hasRepo = true,
                        branch = ps.branch,
                        ahead = ps.ahead,
                        behind = ps.behind,
                        staged = ps.staged.map { (code, path) -> GitFileChange(path = path, status = code) },
                        changes = ps.changed.map { (code, path) -> GitFileChange(path = path, status = code) }
                    ),
                    null
                )
            } catch (e: Exception) {
                onResult(null, friendlyError(e))
            }
        }
    }

    override fun gitDiff(cwd: String, path: String, staged: Boolean, untracked: Boolean, onResult: (String?, String?) -> Unit) {
        if (untracked) {
            onResult("", null) // nothing to diff for an untracked file
            return
        }
        if (client == null) {
            onResult(null, "Not connected")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val r = controlExec(LanCommands.gitDiffCommand(cwd, path, staged, untracked))
                if (r.exitStatus != 0) {
                    onResult(null, r.stderr.trim().take(160).ifBlank { "git diff failed (exit ${r.exitStatus})" })
                    return@launch
                }
                onResult(r.output, null)
            } catch (e: Exception) {
                onResult(null, friendlyError(e))
            }
        }
    }

    // ---- approvals ----------------------------------------------------------------------------

    /** No RPC on the LAN channel — type the decision into the node's terminal (v1 fallback). */
    override fun answerApproval(nodeId: String, pendingId: String?, decision: String, onResult: (Boolean, String) -> Unit) {
        if (client == null) {
            onResult(false, "Not connected")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val r = controlExec(LanCommands.sendKeysCommand(nodeId, decision, enter = true))
                if (r.exitStatus != 0) onResult(false, r.stderr.trim().take(160).ifBlank { "send-keys failed" })
                else onResult(true, "Answered.")
            } catch (e: Exception) {
                onResult(false, friendlyError(e))
            }
        }
    }

    /** LAN has no canvas RPC — re-push the workspace (nodes come from the project files). */
    override fun requestCanvas() {
        refreshProjects()
    }

    // ---- sshj plumbing ------------------------------------------------------------------------

    private fun newClient(): SSHClient {
        ensureModernBouncyCastle()
        val config = DefaultConfig()
        // KEX algorithms the client offers. sshj defaults to curve25519 first, which needs JCE
        // X25519; when that is unavailable the server must negotiate ECDH-NIST instead (both are
        // always offered by macOS sshd). List curve25519 first when supported — it is the fastest.
        val kex = mutableListOf<Factory.Named<KeyExchange>>()
        if (runCatching { KeyPairGenerator.getInstance("X25519", "BC") }.isSuccess) {
            kex += Curve25519SHA256.Factory()
        }
        kex += ECDHNistP.Factory256()
        kex += ECDHNistP.Factory384()
        kex += ECDHNistP.Factory521()
        config.setKeyExchangeFactories(kex)
        val c = SSHClient(config)
        c.addHostKeyVerifier(hostKeyVerifier())
        c.setConnectTimeout(CONNECT_TIMEOUT_MS)
        c.setTimeout(READ_TIMEOUT_MS)
        return c
    }

    /**
     * Android ships an ANCIENT BouncyCastle fork under the provider name "BC"
     * (`com.android.org.bouncycastle.*`). sshj registers its own provider under "BC" only when
     * none exists, so without this the SSH client silently runs on the platform fork — which
     * lacks X25519/Ed25519 (curve25519 KEX dies with `no such algorithm: X25519 for provider BC`)
     * and modern AEAD ciphers. Replace it with our bundled bcprov 1.78+ before any sshj crypto.
     */
    private fun ensureModernBouncyCastle() {
        val current = Security.getProvider("BC") ?: return // none yet — sshj will add our bcprov
        if (current.javaClass.name == "org.bouncycastle.jce.provider.BouncyCastleProvider") return // ours already
        Security.removeProvider("BC")
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        Log.i(TAG, "replaced platform BC provider (${current.javaClass.name}) with bundled bcprov 1.78+")
    }

    /** TOFU: accept + pin the first host key; reject any later key change (across runs AND streams). */
    private fun hostKeyVerifier(): HostKeyVerifier = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fp = fingerprint(key)
            // Read the pin fresh from the store every time: a fingerprint pinned by an earlier
            // stream (or a previous app run) must still protect this connection.
            val pinned = store.loadLan()?.hostKeyFingerprint
            return if (pinned == null) {
                Log.i(TAG, "pinning host key $fp (TOFU)")
                store.saveLanHostKeyFingerprint(fp)
                true
            } else {
                val ok = pinned == fp
                if (!ok) Log.e(TAG, "HOST KEY CHANGED: saw $fp, pinned $pinned")
                ok
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private fun keyProvider(): KeyProvider {
        val kp = SshKeys.toJavaKeyPair(lan.sshKey.secretKey)
            ?: throw IllegalStateException("Ed25519 key provider unavailable — restart the app")
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = kp.private
            override fun getPublic(): PublicKey = kp.public
            override fun getType(): KeyType = KeyType.fromKey(kp.public)
        }
    }

    /** OpenSSH-style `SHA256:<base64-no-padding>` of the raw public key. */
    private fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Serialized exec on the shared control connection (also used for re-probes). */
    private suspend fun controlExec(command: String): ExecResult {
        val c = client ?: throw IllegalStateException("Not connected")
        return controlMutex.withLock { execText(c, command) }
    }

    private suspend fun probeUserDataDir(): String? {
        val out = controlExec(LanCommands.probeUserDataCommand()).output
        return out.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.takeIf { it.startsWith("/") || it.startsWith("$") }
    }

    /** One-shot `ssh … 'command'` — drain stdout + stderr to EOF, join, capture the exit status. */
    private fun execText(c: SSHClient, command: String): ExecResult {
        // Non-interactive execs lack the login-shell PATH — augment it first.
        val session = c.startSession()
        try {
            val cmd = session.exec(LanCommands.pathAware(command))
            val errBuf = ByteArrayOutputStream()
            val errDrain = kotlin.concurrent.thread(name = "lan-stderr", isDaemon = true) {
                runCatching { cmd.errorStream.copyTo(errBuf) }
            }
            val out = cmd.inputStream.readBytes()
            cmd.join()
            errDrain.join(1000)
            return ExecResult(
                output = String(out, Charsets.UTF_8),
                stderr = String(errBuf.toByteArray(), Charsets.UTF_8),
                exitStatus = cmd.exitStatus ?: 0
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is net.schmizz.sshj.userauth.UserAuthException ->
            "SSH authentication failed — the host no longer trusts this device's key. Re-pair from the host."
        is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Cannot reach ${lan.host}:${lan.port} — is the host on this network with Remote Login enabled?"
        else -> {
            val m = e.message ?: "unknown error"
            if (m.contains("host key", ignoreCase = true)) "Host key verification failed — the host changed its SSH identity."
            else "SSH error: $m"
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 30_000
        /** How many times a dead control connection is re-established before giving up. */
        const val MAX_RECONNECT_ATTEMPTS = 3
        /** Backoff between reconnect attempts (multiplied by the attempt number). */
        const val RECONNECT_BACKOFF_MS = 1_500L
        const val TAG = "NodetermLan"
    }
}

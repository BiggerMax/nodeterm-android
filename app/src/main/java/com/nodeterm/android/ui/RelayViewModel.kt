package com.nodeterm.android.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nodeterm.android.core.e2ee.E2ee
import com.nodeterm.android.core.e2ee.SshKeys
import com.nodeterm.android.core.model.CanvasNode
import com.nodeterm.android.core.model.GitFileChange
import com.nodeterm.android.core.model.GitStatus
import com.nodeterm.android.core.model.InboxEvent
import com.nodeterm.android.core.model.Kanban
import com.nodeterm.android.core.model.KanbanCardMeta
import com.nodeterm.android.core.model.KanbanColumn
import com.nodeterm.android.core.model.KanbanLabel
import com.nodeterm.android.core.model.NodeStatus
import com.nodeterm.android.core.model.PairingCodec
import com.nodeterm.android.core.model.PairingOffer
import com.nodeterm.android.core.model.Project
import com.nodeterm.android.core.remote.HostPairingCodec
import com.nodeterm.android.core.remote.HostPairingPayload
import com.nodeterm.android.core.vt.VtParser
import com.nodeterm.android.core.vt.VtScreen
import com.nodeterm.android.data.SessionStore
import com.nodeterm.android.net.HostSession
import com.nodeterm.android.net.LanSessionManager
import com.nodeterm.android.net.PairingClient
import com.nodeterm.android.net.RelaySessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { NO_SESSION, CONNECTING, SAS_CONFIRM, READY, DISCONNECTED }

data class NodeRow(
    val nodeId: String,
    val title: String,
    val kind: String,
    val agentId: String?,
    val cwd: String?,
    val projectName: String
)

/** Read model for the Trello-style board of the active project (see `Kanban` in :core). */
data class KanbanView(
    val activeProjectName: String,
    /** Real board columns (default To Do / In Progress / Done when the host has no board yet). */
    val columns: List<KanbanColumn>,
    /** nodeId → columnId for cards with a live assignment. */
    val assignments: Map<String, String>,
    /** nodeIds with no live assignment → the virtual Ungrouped column. */
    val ungrouped: List<String>,
    /** nodeId → per-card metadata (assignees / priority / labels / due date). */
    val meta: Map<String, KanbanCardMeta>,
    /** Board-level label palette. */
    val labels: List<KanbanLabel>
)

data class TerminalState(
    val nodeId: String,
    val title: String,
    val streamId: Int?,
    val screen: VtScreen?,
    /** Bumped on every data arrival so the renderer recomposes (the screen mutates in place). */
    val generation: Int = 0,
    val cols: Int = 80,
    val rows: Int = 24,
    val status: NodeStatus,
    val ended: Boolean,
    val exitCode: Int?,
    val attaching: Boolean,
    val attachError: String?
)

/** The P2 remote file browser: one directory at a time over `fs.list`. */
data class FsBrowserState(
    val path: String,
    val entries: List<HostSession.FsEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/** The read-only file viewer: text via `fs.read`, binary via `fs.readBinary`. */
data class FileViewerState(
    val path: String,
    val name: String,
    val text: String? = null,
    val bytes: ByteArray? = null,
    val loading: Boolean = true,
    val error: String? = null
) {
    val isBinary: Boolean get() = bytes != null
    val size: Long get() = text?.length?.toLong() ?: bytes?.size?.toLong() ?: 0
}

/** P3: read-only git status for the current browser directory (`git.status`). */
data class GitState(
    val cwd: String,
    val status: GitStatus? = null,
    val loading: Boolean = false,
    val error: String? = null
)

/** P3: read-only unified diff for one changed file (`git.diff`). */
data class GitDiffState(
    val cwd: String,
    val change: GitFileChange,
    val staged: Boolean = false,
    val untracked: Boolean = false,
    val text: String? = null,
    val loading: Boolean = true,
    val error: String? = null
)

data class RelayUiState(
    val phase: Phase = Phase.NO_SESSION,
    val sas: String = "",
    val connected: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Why the last connection dropped (shown on the home screen's disconnected state). */
    val disconnectReason: String? = null,
    val projects: List<Project> = emptyList(),
    val nodes: List<NodeRow> = emptyList(),
    val status: Map<String, NodeStatus> = emptyMap(),
    val nodeNames: Map<String, String> = emptyMap(),
    val inbox: List<InboxEvent> = emptyList(),
    /** nodeId → most recent inbox snippet (detail, title as fallback) for the board's card preview. */
    val boardPreviews: Map<String, String> = emptyMap(),
    val terminal: TerminalState? = null,
    /** Canvas nodes for the mobile board view (P2). */
    val board: List<CanvasNode> = emptyList(),
    /** Active project id (host-driven; empty when unknown — fall back to the first project). */
    val activeProjectId: String = "",
    /** Resolved Trello-style kanban board for the active project (read model for the board view). */
    val kanban: KanbanView? = null,
    /** Read-only remote file browsing (P2). */
    val browser: FsBrowserState? = null,
    val viewer: FileViewerState? = null,
    /** P3: read-only git status / diff views (accessed from the file browser). */
    val git: GitState? = null,
    val gitDiff: GitDiffState? = null
)

class RelayViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SessionStore(application)
    private val scope = viewModelScope

    private val _ui = MutableStateFlow(RelayUiState())
    val ui: StateFlow<RelayUiState> = _ui.asStateFlow()

    private var manager: HostSession? = null
    private var offer: PairingOffer? = null
    private var keys: E2ee.KeyPair? = null
    /** Non-null while the session runs on the direct LAN / SSH transport (free tier — no relay). */
    private var lanSession: SessionStore.LanSession? = null
    private val streamParsers = HashMap<Int, VtParser>()
    private var canvasNodes: List<CanvasNode> = emptyList()
    /** Debounces the host-side pty resize while the IME animation resizes the viewport frame by frame. */
    private var resizeJob: kotlinx.coroutines.Job? = null
    /** True once the current session reached READY — guards the LAN fallback against mid-session drops. */
    private var sawReady = false
    /** User-initiated teardown (disconnect/unpair): never auto-fallback after this. */
    private var userInitiatedTearDown = false
    /**
     * The current attempt came from a v0.2.37 host-payload pairing, so the persisted LAN/SSH
     * credentials belong to THIS host and a failed relay handshake may fall back to them. Cleared
     * by legacy flat-offer pairings (which never touch LAN creds) so a relay failure there can
     * never silently SSH-attempt a different host's persisted credentials.
     */
    private var lanFallbackArmed = false

    init {
        // Restore a persisted session: reconnect automatically (the SAS screen shows again if the
        // human never confirmed the code). Prefer the direct LAN / SSH transport when a previous
        // session actually used it (free-tier hosts never join the relay room, so the relay path
        // would stall for its handshake timeout before falling back — skip the stall entirely).
        val lan = store.loadLan()
        if (store.prefersLan() && lan != null) {
            lanSession = lan
            _ui.update { it.copy(phase = Phase.CONNECTING) }
            connectLan()
        } else {
            store.load()?.let { stored ->
                offer = stored.offer
                keys = stored.keys
                // Best-effort: the LAN creds were persisted by the pairing that produced this
                // offer, so a failed relay restore may fall back to them (same host).
                lanFallbackArmed = store.loadLan() != null
                _ui.update { it.copy(phase = Phase.CONNECTING) }
                connect()
            } ?: lan?.let {
                lanSession = it
                _ui.update { it.copy(phase = Phase.CONNECTING) }
                connectLan()
            }
        }
    }

    // ---- user actions ------------------------------------------------------------------------

    /**
     * Route raw pairing input: a flat `nodeterm://pair?code=…` offer (relay-only) OR a v0.2.37
     * host QR payload (raw JSON → pairPort handshake → relay device connection). Returns false
     * when the text is neither, so the caller can stay on the pairing screen.
     */
    fun pairCode(text: String): Boolean {
        val trimmed = text.trim()
        PairingCodec.decodeOffer(trimmed)?.let { pair(it); return true }
        HostPairingCodec.decodePayload(trimmed)?.let { payload -> pairViaHostPayload(payload); return true }
        _ui.update { it.copy(error = "Unrecognized pairing code — scan or paste a fresh one.") }
        return false
    }

    /** A pairing offer was decoded from the QR code (or pasted text). */
    fun pair(newOffer: PairingOffer) {
        lanFallbackArmed = false // a flat offer carries no LAN host — never fall back to old creds
        offer = newOffer
        // Reuse the persisted device keypair when present so the host's pin survives reconnects
        // (v0.2.37 standing host pins the client pubkey on first approval).
        keys = stableKeys()
        store.save(newOffer, keys!!)
        _ui.update { it.copy(phase = Phase.CONNECTING, error = null, notice = null, disconnectReason = null) }
        connect()
    }

    /**
     * v0.2.37 host pairing: sealed POST to the host's one-shot pair server (installs our
     * ed25519 key + mints the relay device token), then connects through the relay as a device.
     */
    private fun pairViaHostPayload(payload: HostPairingPayload) {
        // This host's LAN creds are about to be persisted (and the key installed) — a failed
        // relay handshake may safely fall back to them.
        lanFallbackArmed = true
        _ui.update { it.copy(phase = Phase.CONNECTING, error = null, notice = null, disconnectReason = null) }
        scope.launch {
            try {
                val deviceId = store.getOrCreateDeviceId()
                // The SAME ed25519 keypair authenticates the LAN / SSH connection after the pair
                // server installs it into ~/.ssh/authorized_keys — persist it so the free-tier
                // transport can reconnect (when the host grants relay access we use the relay).
                val sshKeys = SshKeys.generateEd25519()
                val result = PairingClient().pair(payload, deviceName(), deviceId, sshKeys)
                val relay = result.relay
                val deviceToken = result.relayDeviceToken
                val lanCreds = SessionStore.LanSession(
                    host = payload.host,
                    port = payload.port,
                    user = payload.user,
                    sshKey = sshKeys
                )
                // Persist the direct-SSH credentials ALWAYS (not just on the no-relay path): even
                // when the host grants a relay token it may never join the relay room (free tier),
                // so a failed relay handshake falls back to this same LAN/SSH transport.
                store.saveLan(lanCreds)
                if (relay == null || deviceToken.isNullOrBlank()) {
                    // Free tier: no relay grant → direct LAN / SSH (the iOS SSH browse path).
                    android.util.Log.i(
                        "NodetermPair",
                        "no relay grant — falling back to LAN/SSH ${payload.user}@${payload.host}:${payload.port}"
                    )
                    store.setPreferLan(true)
                    lanSession = lanCreds
                    connectLan()
                    return@launch
                }
                val deviceOffer = PairingOffer(relay.relayEndpoint, deviceToken, relay.hostPublicKeyB64)
                offer = deviceOffer
                keys = stableKeys()
                store.setPreferLan(false)
                store.save(deviceOffer, keys!!)
                android.util.Log.i("NodetermPair", "device offer saved — connecting to ${relay.relayEndpoint}")
                connect()
            } catch (e: Exception) {
                android.util.Log.e("NodetermPair", "pair failed", e)
                _ui.update {
                    it.copy(
                        phase = Phase.NO_SESSION,
                        error = "Pairing failed: ${e.message ?: "unknown error"} — go back and retry with a fresh code."
                    )
                }
            }
        }
    }

    /** The persisted device keypair, or a fresh one on first use (kept stable for host pinning). */
    private fun stableKeys(): E2ee.KeyPair = store.load()?.keys ?: E2ee.generateKeyPair()

    private fun deviceName(): String = Build.MODEL.ifBlank { "Android" }

    fun confirmSas() {
        store.markSasConfirmed()
        manager?.beginSync()
        _ui.update { it.copy(phase = Phase.READY) }
    }

    fun openNode(node: NodeRow) {
        // Subscription hygiene: the host keeps a detached stream alive until `pty.kill`, so release
        // any previous stream before attaching a new one (no more than one live stream at a time).
        _ui.value.terminal?.streamId?.let { sid -> manager?.ptyKill(sid) }
        streamParsers.clear()
        _ui.update {
            it.copy(
                terminal = TerminalState(
                    nodeId = node.nodeId,
                    title = node.title,
                    streamId = null,
                    screen = null,
                    status = it.status[node.nodeId] ?: NodeStatus.IDLE,
                    ended = false,
                    exitCode = null,
                    attaching = true,
                    attachError = null
                )
            )
        }
        manager?.attach(node.nodeId, 80, 24) { streamId, err ->
            if (streamId != null) {
                val parser = streamParsers.getOrPut(streamId) { VtParser(VtScreen(80, 24)) }
                _ui.update { st ->
                    val t = st.terminal
                    if (t == null) st else st.copy(
                        terminal = t.copy(streamId = streamId, screen = parser.screen, attaching = false)
                    )
                }
            } else {
                _ui.update { st ->
                    val t = st.terminal
                    if (t == null) st else st.copy(
                        terminal = t.copy(attaching = false, attachError = err ?: "attach failed")
                    )
                }
            }
        }
    }

    fun closeTerminal() {
        // P3 subscription polish: tell the host to drop this stream (`pty.kill`) so it stops
        // streaming output (and releases the detached pty) once the terminal screen is gone.
        _ui.value.terminal?.streamId?.let { sid -> manager?.ptyKill(sid) }
        streamParsers.clear()
        _ui.update { it.copy(terminal = null) }
    }

    fun sendInput(text: String) {
        _ui.value.terminal?.streamId?.let { sid -> manager?.sendInput(sid, text) }
    }

    /** The renderer reports its computed cell grid; resize the screen and the remote pty. */
    fun resizeTerminal(cols: Int, rows: Int) {
        val t = _ui.value.terminal ?: return
        if (cols == t.cols && rows == t.rows) return
        t.screen?.resize(cols, rows)
        _ui.update { st ->
            val tt = st.terminal ?: return@update st
            st.copy(terminal = tt.copy(cols = cols, rows = rows, generation = tt.generation + 1))
        }
        // The pty resize is a network round-trip; the IME pop animation resizes the viewport
        // frame-by-frame, so coalesce bursts into one host call once the size settles.
        t.streamId?.let { sid ->
            resizeJob?.cancel()
            resizeJob = scope.launch {
                kotlinx.coroutines.delay(RESIZE_DEBOUNCE_MS)
                manager?.resize(sid, cols, rows)
            }
        }
    }

    /** Scroll tmux history on the host (`pty.scroll`); the repainted screen streams back. */
    fun scrollTerminal(dir: String, lines: Int = 6) {
        _ui.value.terminal?.streamId?.let { sid -> manager?.scroll(sid, dir, lines) }
    }

    // ---- P2: mobile board -------------------------------------------------------------------

    fun refreshBoard() {
        manager?.requestCanvas()
    }

    // ---- P2: remote file browsing ------------------------------------------------------------

    fun openFiles(startPath: String?) {
        val path = startPath?.ifBlank { null } ?: return
        _ui.update { it.copy(browser = FsBrowserState(path = path, loading = true)) }
        listDir(path)
    }

    fun listDir(path: String) {
        if (manager == null) {
            _ui.update { st ->
                st.copy(browser = st.browser?.copy(path = path, loading = false, error = "Not connected"))
            }
            return
        }
        _ui.update { st ->
            st.copy(browser = st.browser?.copy(path = path, loading = true, error = null))
        }
        manager?.fsList(path) { entries, err ->
            _ui.update { st ->
                val b = st.browser ?: return@update st
                if (err != null) st.copy(browser = b.copy(loading = false, error = err))
                else st.copy(browser = b.copy(loading = false, entries = entries ?: emptyList(), error = null))
            }
        }
    }

    fun browserGoUp() {
        val current = _ui.value.browser?.path ?: return
        val parent = parentPath(current) ?: return
        listDir(parent)
    }

    fun openFile(entry: HostSession.FsEntry, dirPath: String) {
        val path = if (dirPath.endsWith("/")) dirPath + entry.name else "$dirPath/${entry.name}"
        _ui.update { st ->
            st.copy(viewer = FileViewerState(path = path, name = entry.name))
        }
        manager?.fsRead(path) { text, err ->
            if (text != null) {
                _ui.update { st ->
                    val v = st.viewer
                    if (v == null) st else st.copy(viewer = v.copy(text = text, loading = false))
                }
            } else {
                // Not text — try the binary channel (base64).
                manager?.fsReadBinary(path) { bytes, binErr ->
                    _ui.update { st ->
                        val v = st.viewer ?: return@update st
                        if (bytes != null) st.copy(viewer = v.copy(bytes = bytes, loading = false))
                        else st.copy(viewer = v.copy(loading = false, error = binErr ?: err ?: "unreadable"))
                    }
                }
            }
        }
    }

    fun closeViewer() {
        _ui.update { it.copy(viewer = null) }
    }

    fun closeFiles() {
        _ui.update { it.copy(browser = null, viewer = null, git = null, gitDiff = null) }
    }

    // ---- P3: read-only git status / diff ----------------------------------------------------

    fun openGit(cwd: String) {
        if (manager == null) {
            _ui.update { it.copy(git = GitState(cwd = cwd, loading = false, error = "Not connected")) }
            return
        }
        _ui.update { it.copy(git = GitState(cwd = cwd, loading = true), gitDiff = null) }
        manager?.gitStatus(cwd) { status, err ->
            _ui.update { st ->
                val g = st.git ?: return@update st
                if (err != null) st.copy(git = g.copy(loading = false, error = err))
                else st.copy(git = g.copy(loading = false, status = status, error = null))
            }
        }
    }

    fun refreshGit() {
        val g = _ui.value.git ?: return
        _ui.update { st -> st.copy(git = g.copy(loading = true, error = null)) }
        manager?.gitStatus(g.cwd) { status, err ->
            _ui.update { st ->
                val gg = st.git ?: return@update st
                if (err != null) st.copy(git = gg.copy(loading = false, error = err))
                else st.copy(git = gg.copy(loading = false, status = status, error = null))
            }
        }
    }

    fun closeGit() {
        _ui.update { it.copy(git = null, gitDiff = null) }
    }

    fun openGitDiff(change: GitFileChange) {
        val g = _ui.value.git ?: return
        val staged = g.status?.staged?.any { it.path == change.path } == true
        if (manager == null) {
            _ui.update {
                it.copy(
                    gitDiff = GitDiffState(
                        cwd = g.cwd, change = change, staged = staged,
                        untracked = change.isUntracked, loading = false, error = "Not connected"
                    )
                )
            }
            return
        }
        _ui.update {
            it.copy(
                gitDiff = GitDiffState(
                    cwd = g.cwd,
                    change = change,
                    staged = staged,
                    untracked = change.isUntracked
                )
            )
        }
        manager?.gitDiff(g.cwd, change.path, staged, change.isUntracked) { text, err ->
            _ui.update { st ->
                val d = st.gitDiff ?: return@update st
                if (err != null) st.copy(gitDiff = d.copy(loading = false, error = err))
                else st.copy(gitDiff = d.copy(loading = false, text = text, error = null))
            }
        }
    }

    fun backFromGitDiff() {
        _ui.update { it.copy(gitDiff = null) }
    }

    /** Approve/deny a held approval; the manager tries the host RPC, else send-keys. */
    fun answerApproval(nodeId: String, pendingId: String?, decision: String) {
        manager?.answerApproval(nodeId, pendingId, decision) { ok, message ->
            _ui.update { it.copy(notice = if (ok) "Approval answered." else message) }
            if (ok) {
                // Optimistic: remove the answered card; the next poll confirms. Recompute board
                // previews so a card doesn't keep showing the just-answered event's snippet.
                _ui.update { st ->
                    val inbox = st.inbox.filterNot { ev -> ev.pendingId == pendingId && ev.nodeId == nodeId }
                    st.copy(inbox = inbox, boardPreviews = latestSnippets(inbox))
                }
            }
        }
    }

    /** Send a typed answer for a question card (send-keys fallback via the terminal). */
    fun answerQuestion(nodeId: String, text: String) {
        val node = _ui.value.nodes.firstOrNull { it.nodeId == nodeId } ?: return
        openNode(node)
        scope.launch {
            kotlinx.coroutines.delay(600) // let the attach settle
            _ui.value.terminal?.streamId?.let { manager?.sendInput(it, text) }
        }
    }

    fun disconnect() {
        userInitiatedTearDown = true
        manager?.close()
        manager = null
        lanSession = null
        _ui.update {
            it.copy(connected = false, phase = Phase.DISCONNECTED, disconnectReason = "You disconnected from the host.")
        }
    }

    fun unpair() {
        userInitiatedTearDown = true
        manager?.close()
        manager = null
        store.clear()
        streamParsers.clear()
        canvasNodes = emptyList()
        offer = null
        keys = null
        lanSession = null
        _ui.value = RelayUiState()
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    fun clearNotice() {
        _ui.update { it.copy(notice = null) }
    }

    // ---- session wiring ----------------------------------------------------------------------

    private fun connect() {
        val o = offer ?: return
        val k = keys ?: return
        sawReady = false
        userInitiatedTearDown = false
        lanSession = null // this connection uses the relay transport
        // Tear down any previous session first: its socket may still be closing (e.g. an app-start
        // reconnect against an already-consumed single-use token) and would otherwise deliver a
        // stale `Closed` event that flips THIS fresh connection to DISCONNECTED right after a scan.
        manager?.close()
        // Gate events by the manager identity so a superseded session can never write into the
        // live UI state (a late `Closed` from an old socket must not clobber the new connection).
        // (`m` is referenced from its own initializer, so it must be a lateinit var, not a val.)
        lateinit var m: RelaySessionManager
        // Free-tier hosts grant a relay token but never join the relay room, so the handshake
        // ALWAYS times out; when a LAN fallback is armed, fail it fast instead of stalling the
        // scan for the full 20s.
        val handshakeTimeout = if (lanFallbackArmed && store.loadLan() != null) {
            LAN_FALLBACK_HANDSHAKE_TIMEOUT_MS
        } else {
            DEFAULT_HANDSHAKE_TIMEOUT_MS
        }
        m = RelaySessionManager(
            scope,
            onEvent = { event -> if (manager === m) handleEvent(event) },
            handshakeTimeoutMs = handshakeTimeout
        )
        manager = m
        m.connect(o, k)
    }

    /** Connect through the direct LAN / SSH transport (free tier — host grants no relay). */
    private fun connectLan() {
        val lan = lanSession ?: return
        sawReady = false
        userInitiatedTearDown = false
        manager?.close()
        lateinit var m: LanSessionManager
        m = LanSessionManager(scope, lan, store) { event -> if (manager === m) handleEvent(event) }
        manager = m
        m.connect()
    }

    private fun handleEvent(event: HostSession.SessionEvent) {
        when (event) {
            is HostSession.SessionEvent.Ready -> {
                sawReady = true
                // LAN / SSH has no SAS (key auth — the host already trusts us), so skip the
                // confirmation screen entirely. Relay sessions still gate on the human's OK.
                val confirmed = store.load()?.sasConfirmed == true || lanSession != null
                _ui.update {
                    it.copy(
                        connected = true,
                        sas = event.sas,
                        notice = null, // clear the relay-unreachable → LAN fallback notice
                        phase = if (confirmed) Phase.READY else Phase.SAS_CONFIRM
                    )
                }
                if (confirmed) manager?.beginSync()
            }
            is HostSession.SessionEvent.Projects -> {
                val workspace = event.workspace
                val mirror = event.mirror
                val status = mirror?.nodes?.mapValues { (_, n) -> NodeStatus.fromMirrorState(n.state) } ?: emptyMap()
                val names = mirror?.nodes?.mapNotNull { (id, n) -> n.name?.let { id to it } }?.toMap() ?: emptyMap()
                _ui.update {
                    // Build the new projects list first so `nodes` reflects host-driven deletions
                    // (re-deriving from `it.projects` would re-freeze stale rows from the prior
                    // poll — closed terminals lingered in the UI as "ghost" nodes).
                    val newProjects = workspace?.projects ?: it.projects
                    val activeId = workspace?.activeProjectId ?: it.activeProjectId
                    val newBoard = if (lanSession != null)
                        workspace?.projects?.firstOrNull { p -> p.id == activeId }?.nodes
                            ?: workspace?.projects?.firstOrNull()?.nodes ?: it.board
                    else
                        it.board
                    it.copy(
                        phase = if (it.phase == Phase.CONNECTING) Phase.READY else it.phase,
                        connected = true,
                        projects = newProjects,
                        nodes = buildNodes(newProjects),
                        status = status,
                        nodeNames = names,
                        inbox = mirror?.inbox ?: emptyList(),
                        boardPreviews = latestSnippets(mirror?.inbox ?: emptyList()),
                        activeProjectId = activeId,
                        board = newBoard,
                        kanban = buildKanban(newProjects, activeId, newBoard.map { n -> n.id })
                    )
                }
            }
            is HostSession.SessionEvent.Canvas -> {
                if (event.state.nodes.isNotEmpty()) canvasNodes = event.state.nodes
                _ui.update { st ->
                    val projects = if (canvasNodes.isNotEmpty() && st.projects.isNotEmpty()) {
                        // The mirror pushes the ACTIVE project's canvas — overlay its nodes.
                        st.projects.mapIndexed { i, p -> if (i == 0) p.copy(nodes = canvasNodes) else p }
                    } else {
                        st.projects
                    }
                    st.copy(
                        projects = projects,
                        nodes = buildNodes(projects),
                        board = canvasNodes,
                        kanban = buildKanban(projects, st.activeProjectId, canvasNodes.map { n -> n.id })
                    )
                }
            }
            is HostSession.SessionEvent.StreamData -> handleStreamData(event)
            is HostSession.SessionEvent.StreamEnded -> {
                _ui.update { st ->
                    val t = st.terminal
                    if (t?.streamId == event.streamId) st.copy(
                        terminal = t.copy(ended = true, exitCode = event.exitCode, generation = t.generation + 1)
                    ) else st
                }
            }
            is HostSession.SessionEvent.ApprovalResult -> {
                _ui.update { it.copy(notice = if (event.ok) event.message else event.message) }
            }
            is HostSession.SessionEvent.Error -> {
                _ui.update { it.copy(error = event.message) }
            }
            is HostSession.SessionEvent.Closed -> {
                // Free-tier hosts grant a relay token but never join the relay room (relay is a Pro
                // entitlement), so the relay handshake times out and the transport closes BEFORE
                // READY. When that happens and we hold persisted direct-SSH credentials, fall back
                // to the LAN/SSH transport instead of surfacing a dead "Disconnected" screen.
                val lan = if (!sawReady && !userInitiatedTearDown && lanFallbackArmed && manager is RelaySessionManager) {
                    store.loadLan()
                } else {
                    null
                }
                if (lan != null) {
                    attemptLanFallback(event.reason, lan)
                } else {
                    _ui.update {
                        it.copy(connected = false, phase = Phase.DISCONNECTED, disconnectReason = event.reason)
                    }
                }
            }
        }
    }

    /**
     * The relay connection never reached READY and we have direct-SSH credentials for the same
     * host (installed at pairing) — reconnect through LAN/SSH and remember the preference so a
     * later restore skips the doomed relay attempt entirely.
     */
    private fun attemptLanFallback(relayReason: String?, lan: SessionStore.LanSession) {
        android.util.Log.w(
            "NodetermLan",
            "relay unavailable (${relayReason ?: "handshake timeout"}) — " +
                "falling back to LAN/SSH ${lan.user}@${lan.host}:${lan.port}"
        )
        store.setPreferLan(true)
        lanSession = lan
        _ui.update {
            it.copy(phase = Phase.CONNECTING, error = null, disconnectReason = null)
        }
        connectLan()
    }

    private fun handleStreamData(event: HostSession.SessionEvent.StreamData) {
        val sid = event.streamId
        val parser = streamParsers.getOrPut(sid) { VtParser(VtScreen(80, 24)) }
        try {
            when (event.kind) {
                HostSession.StreamKind.SNAPSHOT -> {
                    // The snapshot is a tmux capture-pane dump: bare \n joins rows, so feed \r\n so
                    // each row starts at column 0 in the emulator (real pty output already uses \r\n).
                    parser.screen.prepareSnapshot()
                    parser.feed(normalizeSnapshotNewlines(event.bytes))
                }
                HostSession.StreamKind.OUTPUT -> parser.feed(event.bytes)
            }
        } catch (e: Exception) {
            // A single malformed escape sequence (real-world tmux output is messy) must never
            // tear down the stream — an uncaught exception here propagates into the transport's
            // read loop and kills the terminal (seen on-device: CSI L with the cursor near the
            // top threw ArrayIndexOutOfBounds in VtScreen.copyRow). Log and skip the chunk; the
            // next repaint continues from a sane screen.
            android.util.Log.w("NodetermVt", "parser feed failed stream=$sid kind=${event.kind}", e)
        }
        _ui.update { st ->
            val t = st.terminal
            if (t?.streamId == sid) st.copy(
                terminal = t.copy(generation = t.generation + 1, attaching = false)
            ) else st
        }
    }

    /**
     * The most recent inbox snippet per node (detail preferred, title as fallback), shown on board
     * cards as a mini terminal preview. Newest wins on `ts`; equal timestamps defer to list order
     * (the mirror publishes events newest-first, so the later entry is treated as newer).
     */
    private fun latestSnippets(inbox: List<InboxEvent>): Map<String, String> {
        val latestTs = HashMap<String, Long>()
        val snippets = HashMap<String, String>()
        for (ev in inbox) {
            if (ev.nodeId.isBlank()) continue
            val snippet = ev.detail?.takeIf { it.isNotBlank() } ?: ev.title.takeIf { it.isNotBlank() } ?: continue
            val prev = latestTs[ev.nodeId]
            if (prev == null || ev.ts >= prev) {
                latestTs[ev.nodeId] = ev.ts
                snippets[ev.nodeId] = snippet.trim()
            }
        }
        return snippets
    }

    private fun buildNodes(projects: List<Project>): List<NodeRow> {
        val nodes = mutableListOf<NodeRow>()
        projects.forEach { project ->
            project.nodes.forEach { node ->
                nodes.add(
                    NodeRow(
                        nodeId = node.id,
                        title = node.title,
                        kind = node.kind,
                        agentId = node.agentId,
                        cwd = node.cwd,
                        projectName = project.name
                    )
                )
            }
        }
        return nodes.sortedWith(compareBy({ it.statusRank(_ui.value.status) }, { it.title }))
    }

    private fun NodeRow.statusRank(status: Map<String, NodeStatus>): Int = when (status[this.nodeId]) {
        NodeStatus.NEEDS_YOU -> 0
        NodeStatus.WORKING -> 1
        NodeStatus.DONE -> 2
        else -> 3
    }

    /** Resolve the active project's Trello-style kanban board for the mobile board view. */
    private fun buildKanban(
        projects: List<Project>,
        activeProjectId: String,
        sessionNodeIds: List<String>
    ): KanbanView? {
        val active = projects.firstOrNull { p -> p.id == activeProjectId } ?: projects.firstOrNull()
            ?: return null
        val k = active.kanban
        val columns = Kanban.columns(k)
        val assignments = k?.assignments
            ?.filter { a -> columns.any { c -> c.id == a.columnId } }
            ?.associateTo(LinkedHashMap()) { it.nodeId to it.columnId }
            ?: emptyMap()
        return KanbanView(
            activeProjectName = active.name.ifBlank { active.id },
            columns = columns,
            assignments = assignments,
            ungrouped = Kanban.unassigned(k, sessionNodeIds),
            meta = (k?.meta ?: emptyList()).associateBy { it.nodeId },
            labels = k?.labels ?: emptyList()
        )
    }

    private fun normalizeSnapshotNewlines(bytes: ByteArray): ByteArray {
        // capture-pane joins rows with bare \n; feed \r\n so each row starts at col 0.
        // Normalise any existing CRLF first so it is never double-converted.
        return String(bytes, Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace("\n", "\r\n")
            .toByteArray(Charsets.UTF_8)
    }

    private fun parentPath(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val idx = trimmed.lastIndexOf('/')
        if (idx < 0) return null
        val parent = trimmed.substring(0, idx)
        return parent.ifEmpty { "/" }
    }

    private companion object {
        /** Fail the relay handshake fast when a LAN fallback is armed (free-tier hosts never join). */
        const val LAN_FALLBACK_HANDSHAKE_TIMEOUT_MS = 8_000L
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 20_000L
        /** Coalesce burst pty resizes (IME animation) into one host call. */
        const val RESIZE_DEBOUNCE_MS = 180L
    }
}

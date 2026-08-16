package com.nodeterm.android.net

import com.nodeterm.android.core.model.CanvasState
import com.nodeterm.android.core.model.GitStatus
import com.nodeterm.android.core.model.MirrorFile
import com.nodeterm.android.core.model.Workspace

/**
 * The app-layer session surface shared by BOTH transports:
 *  - [RelaySessionManager] — E2EE relay (Pro/entitled hosts);
 *  - [LanSessionManager] — direct LAN / SSH (free tier, no relay needed).
 *
 * The UI layer depends only on this interface + [SessionEvent], so pairing can pick whichever
 * transport the host offers (relay block present → relay; absent → LAN SSH).
 */
interface HostSession {

    sealed interface SessionEvent {
        /** Handshake/connect done; `sas` is the out-of-band code (empty for LAN — key auth). */
        data class Ready(val sas: String) : SessionEvent
        data class Projects(
            val workspace: Workspace?,
            val mirror: MirrorFile?,
            val tmuxSessions: List<String>
        ) : SessionEvent

        data class Canvas(val state: CanvasState) : SessionEvent
        /** Terminal output as raw PTY bytes: SNAPSHOT replaces the screen; OUTPUT appends. */
        data class StreamData(val streamId: Int, val kind: StreamKind, val bytes: ByteArray) : SessionEvent
        data class StreamEnded(val streamId: Int, val exitCode: Int?) : SessionEvent
        data class ApprovalResult(val ok: Boolean, val message: String) : SessionEvent
        data class Error(val message: String) : SessionEvent
        /** The connection dropped; [reason] is the transport's close/failure detail, if any. */
        data class Closed(val reason: String?) : SessionEvent
    }

    enum class StreamKind { SNAPSHOT, OUTPUT }

    /** One entry of the host's directory listing (DirEntry in fs-ops.ts). */
    data class FsEntry(val name: String, val dir: Boolean, val ignored: Boolean)

    /** Pull projects + start polling for fresh status (relay: after SAS confirm; LAN: after connect). */
    fun beginSync()

    /** Attach the node's terminal; [onResult] receives (streamId, error). */
    fun attach(nodeId: String, cols: Int, rows: Int, onResult: (Int?, String?) -> Unit)

    /** Typed input for a stream. */
    fun sendInput(streamId: Int, text: String)

    /** Client size change for a stream. */
    fun resize(streamId: Int, cols: Int, rows: Int)

    /** Scroll the session's history (tmux-backed). */
    fun scroll(streamId: Int, dir: String, lines: Int)

    /** Detach/kill a stream (stops output). */
    fun ptyKill(streamId: Int)

    /** List a directory on the host (jailed). */
    fun fsList(path: String, onResult: (List<FsEntry>?, String?) -> Unit)

    /** Read a text file on the host. */
    fun fsRead(path: String, onResult: (String?, String?) -> Unit)

    /** Read a binary file on the host (base64). */
    fun fsReadBinary(path: String, onResult: (ByteArray?, String?) -> Unit)

    /** Read-only git status for a directory. */
    fun gitStatus(cwd: String, onResult: (GitStatus?, String?) -> Unit)

    /** Read-only unified diff for one file. */
    fun gitDiff(cwd: String, path: String, staged: Boolean, untracked: Boolean, onResult: (String?, String?) -> Unit)

    /** Answer a held approval (RPC when possible, send-keys fallback otherwise). */
    fun answerApproval(nodeId: String, pendingId: String?, decision: String, onResult: (Boolean, String) -> Unit)

    /** Re-request the current canvas mirror (board view). */
    fun requestCanvas()

    /** Pull fresh node/inbox state NOW (pull-to-refresh); safe to call at any time. */
    fun refreshNow()

    /**
     * Pause/resume the background status polling. Lifecycle-aware battery saver: the app keeps
     * polling every few seconds while backgrounded otherwise. Re-enabling refreshes immediately.
     */
    fun setPollingEnabled(enabled: Boolean)

    /** Tear the session down. Idempotent. */
    fun close()
}

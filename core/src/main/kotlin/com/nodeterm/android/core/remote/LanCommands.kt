package com.nodeterm.android.core.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Command builders + output parsers for the LAN / SSH transport.
 *
 * Mirrors the host's own SSH-facing surface so a free-tier phone (no relay, no Pro) can browse
 * the desktop exactly like the iOS "SSH browse path" does: cat the assembled workspace/status
 * files under the host's userData dir, list the live tmux sessions, attach a terminal by running
 * a tmux client, and answer approvals with `tmux send-keys`.
 *
 * Everything here is pure (strings in, strings out) so the wire shapes are unit-testable without
 * a socket. Field/marker names follow the reference implementation (`src/main/index.ts`
 * `listProjectsOutput`, `src/core/tmux-naming.ts`, `src/core/pty-manager.ts`).
 */
object LanCommands {

    /** tmux socket name — must match TMUX_SOCKET in src/core/tmux-naming.ts. */
    const val TMUX_SOCKET = "node-terminal"

    /** Per-node tmux session name — must match sessionName() in src/core/tmux-naming.ts. */
    fun tmuxSessionName(nodeId: String): String =
        "nt-" + nodeId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /**
     * POSIX single-quote a shell argument so it is treated as ONE literal word. Mirrors the
     * reference `posixQuote` (src/shared/ssh.ts) so paths with spaces/specials survive.
     */
    fun posixQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Non-interactive SSH execs run WITHOUT a login shell, so Homebrew's bin dirs (where `tmux`
     * lives on macOS) are missing from PATH. Augment PATH with the standard Homebrew prefixes
     * (ARM + Intel) so commands resolve exactly as they do in the user's terminal. Harmless as an
     * extra prefix on a login-shell PTY too.
     */
    fun pathAware(command: String): String =
        "export PATH=\"\$PATH:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin\"; $command"

    /** Candidate macOS userData dirs (Electron app.name = "node-terminal"). */
    private val USER_DATA_CANDIDATES = listOf(
        "\$HOME/Library/Application Support/node-terminal",
        "\$HOME/Library/Application Support/nodeterm"
    )

    /**
     * Detect the host's userData dir (where workspace.json / agent-status.json live). Prints the
     * first candidate that contains a workspace.json (exits 0), else prints nothing (exit 1).
     * The candidates carry `$HOME`, expanded by the remote login shell.
     */
    fun probeUserDataCommand(): String =
        USER_DATA_CANDIDATES.joinToString("\n") { d ->
            "[ -f \"$d/workspace.json\" ] && echo \"$d\" && exit 0"
        } + "\nexit 1"

    /** One-shot metadata fetch: workspace.json + agent-status.json + tmux sessions, marker-framed. */
    fun fetchMetadataCommand(userDataDir: String): String {
        val ws = posixQuote("$userDataDir/workspace.json")
        val st = posixQuote("$userDataDir/agent-status.json")
        return buildString {
            append("echo \"@WS\"; cat $ws 2>/dev/null; ")
            append("echo; echo \"@STATUS\"; cat $st 2>/dev/null; ")
            append("echo; echo \"@SESSIONS\"; ")
            append("tmux -L $TMUX_SOCKET list-sessions -F '#{session_name}' 2>/dev/null")
        }
    }

    /**
     * Fetch one local project's file (`<cwd>/.nodeterm/project.json`), framed as
     * `@FILE <cwd>\n<json>`. All cwds are read in one round trip.
     */
    fun fetchProjectFilesCommand(cwds: List<String>): String {
        if (cwds.isEmpty()) return "true"
        return cwds.joinToString("; ") { cwd ->
            val f = posixQuote("$cwd/.nodeterm/project.json")
            "echo \"@FILE ${posixQuote(cwd)}\"; cat $f 2>/dev/null; echo"
        }
    }

    /** `ls -Ap1 <path>` — the same probe ssh-fs.ts runs (folders carry a trailing slash). */
    fun lsCommand(path: String): String = "ls -Ap1 ${posixQuote(path)}"

    /** `cat <path>` for read-only text browsing. */
    fun catCommand(path: String): String = "cat ${posixQuote(path)}"

    /** `base64 <path>` for read-only binary browsing. */
    fun base64Command(path: String): String = "base64 ${posixQuote(path)}"

    /**
     * Attach-or-create the node's tmux session as a PTY client (the phone's terminal). `-A`
     * attaches to the existing session, `-x/-y` size the client window to the phone's grid.
     * When [cwd] is present the SESSION is created in that directory (`-c`), so a node whose
     * tmux session does not exist yet starts where its project actually lives instead of the
     * SSH login directory. An existing session keeps its own cwd (`-A` wins over `-c`).
     */
    fun attachCommand(nodeId: String, cols: Int, rows: Int, cwd: String? = null): String {
        val session = posixQuote(tmuxSessionName(nodeId))
        val dir = if (cwd.isNullOrBlank()) "" else " -c ${posixQuote(cwd)}"
        return "tmux -L $TMUX_SOCKET new-session -A -s $session$dir -x ${cols.coerceAtLeast(2)} -y ${rows.coerceAtLeast(2)}"
    }

    /** Capture the current pane (with colors) for the initial snapshot paint. */
    fun captureCommand(nodeId: String): String =
        "tmux -L $TMUX_SOCKET capture-pane -p -e -t ${posixQuote(tmuxSessionName(nodeId))}"

    /**
     * The tmux session's start directory (`session_path`), empty when the session doesn't exist.
     * Used pre-attach to detect sessions stranded in the wrong cwd (created by older clients
     * without `-c`) so they can be recreated in the project dir.
     */
    fun sessionPathCommand(nodeId: String): String =
        "tmux -L $TMUX_SOCKET display-message -p -t ${posixQuote(tmuxSessionName(nodeId))} '#{session_path}' 2>/dev/null"

    /** Kill a stray node session (wrong cwd / orphaned). Best-effort, only when path mismatches. */
    fun killSessionCommand(nodeId: String): String =
        "tmux -L $TMUX_SOCKET kill-session -t ${posixQuote(tmuxSessionName(nodeId))} 2>/dev/null"

    /** Type text into the node's session (NEEDS YOU answer / fallback). */
    fun sendKeysCommand(nodeId: String, text: String, enter: Boolean): String {
        val session = posixQuote(tmuxSessionName(nodeId))
        val literal = posixQuote(text)
        val base = "tmux -L $TMUX_SOCKET send-keys -t $session -l -- $literal"
        return if (enter) "$base && tmux -L $TMUX_SOCKET send-keys -t $session Enter" else base
    }

    /**
     * The raw SGR mouse-wheel sequence for ONE notch — the wire bytes the reference
     * handleScroll (host-service.ts) writes into the tmux client's stdin: `\x1b[<64;1;1M`
     * wheel-up / `\x1b[<65;1;1M` wheel-down, addressed to cell 1,1. The live LAN terminal path
     * writes these bytes straight into the stream's tmux-client PTY; [scrollCommand] re-uses it
     * to build the shell-quoted form for the LAN E2E test.
     */
    fun scrollSeq(dir: String): String {
        val up = dir != "down"
        return "\u001b[<${if (up) 64 else 65};1;1M"
    }

    /**
     * Build the `tmux send-keys` shell command that emits [scrollSeq] clamped to 1..20 notches,
     * with the ESC byte in bash ANSI-C quoting (`$'…'`) so it arrives as a REAL ESC byte. Kept
     * for the LAN E2E test, which validates the exact wire shape the reference host understands.
     * The live LAN scroll path does NOT use send-keys anymore: it writes [scrollSeq] directly
     * into the stream's tmux-client PTY (mirroring the reference handleScroll), because
     * `send-keys` injects into the session's key path, which tmux never treats as a wheel event.
     */
    fun scrollCommand(nodeId: String, dir: String, lines: Int): String {
        val notches = lines.coerceIn(1, 20)
        val seq = scrollSeq(dir).replace("\u001b", "\\x1b")
        val session = posixQuote(tmuxSessionName(nodeId))
        val one = "tmux -L $TMUX_SOCKET send-keys -t $session -l -- \$'$seq'"
        return List(notches) { one }.joinToString(" && ")
    }

    /** Git porcelain status: `-b` prints the branch line, `--porcelain=v1` prints XY rows. */
    fun gitStatusCommand(cwd: String): String =
        "git -C ${posixQuote(cwd)} status --porcelain=v1 -b 2>/dev/null; " +
            "printf '\\n###BRANCH###\\n'; git -C ${posixQuote(cwd)} rev-parse --abbrev-ref HEAD 2>/dev/null"

    /** Unified diff for one file: staged (`--cached`), unstaged, or nothing for untracked. */
    fun gitDiffCommand(cwd: String, path: String, staged: Boolean, untracked: Boolean): String {
        if (untracked) return "true"
        val flag = if (staged) " --cached" else ""
        return "git -C ${posixQuote(cwd)} diff$flag -- ${posixQuote(path)} 2>/dev/null"
    }



    // -------------------------------------------------------------------------------------------
    // Workspace assembly: the on-disk workspace.json is a v3 INDEX (entries → local/remote refs),
    // while the phone renders a v2-shaped workspace ({version:2, projects:[Project]}). LAN mode
    // reads the index + each local project file and assembles the v2 shape the UI already parses.
    // Mirrors workspaceStore.load() (src/core/workspace-store.ts) — read-only, never writes.
    // -------------------------------------------------------------------------------------------

    /** One v3 index entry the phone can serve: local refs only (remote refs need nested SSH). */
    data class IndexEntry(val id: String, val name: String, val color: String, val cwd: String?)

    data class WorkspaceIndex(val activeProjectId: String, val entries: List<IndexEntry>)

    /** Parse a v3 workspace index (or an already-v2 workspace → single-entry passthrough). */
    fun parseWorkspaceIndex(json: String): WorkspaceIndex {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        } catch (_: Exception) {
            return WorkspaceIndex("", emptyList())
        }
        val version = obj["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (version == 2) {
            // v2 file: {projects:[...]} — hand it straight back as one implicit entry? No: v2 is
            // already the assembled shape; the caller can pass it through as-is.
            return WorkspaceIndex("", emptyList())
        }
        val active = obj["activeProjectId"]?.jsonPrimitive?.contentOrNull ?: ""
        val entries = obj["entries"]?.jsonArray?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                IndexEntry(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    color = o["color"]?.jsonPrimitive?.contentOrNull ?: "",
                    cwd = o["cwd"]?.jsonPrimitive?.contentOrNull
                )
            }.getOrNull()
        } ?: emptyList()
        return WorkspaceIndex(active, entries)
    }

    /**
     * Assemble a v2-shaped workspace JSON from the v3 index + the local project files
     * (`<cwd>/.nodeterm/project.json` bodies, ProjectFileV1 shape). Returns a JSON string
     * `{version:2, activeProjectId, projects:[...]}` that `JsonModels.workspace()` can parse,
     * or the original text when it was already a v2 file (pass-through).
     */
    fun assembleV2Workspace(indexJson: String, projectFiles: Map<String, String>): String {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(indexJson).jsonObject
        } catch (_: Exception) {
            return indexJson
        }
        if (obj["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 2) return indexJson
        val index = parseWorkspaceIndex(indexJson)
        val projects = mutableListOf<String>()
        for (entry in index.entries) {
            val cwd = entry.cwd ?: continue // remote refs need nested SSH — skip
            val file = projectFiles[cwd] ?: continue
            val fileObj = try {
                kotlinx.serialization.json.Json.parseToJsonElement(file).jsonObject
            } catch (_: Exception) {
                continue
            }
            // ProjectFileV1 = {version:1, rev, savedAt, id, name, color, viewport, nodes, kanban,…}
            // Re-wrap as a v2 Project (id/name/color/viewport/nodes/cwd/kanban). Tolerant of missing
            // pieces — the UI degrades to an empty project rather than dropping the whole list.
            val project = buildJsonObject {
                put("id", entry.id)
                put("name", entry.name)
                put("color", entry.color)
                put("cwd", cwd)
                put("viewport", fileObj["viewport"] ?: kotlinx.serialization.json.buildJsonObject {})
                put("nodes", fileObj["nodes"] ?: kotlinx.serialization.json.JsonArray(emptyList()))
                // Kanban board rides in project.json like nodes — carry it through so the phone can
                // render the project's Trello-style board (absent = no board yet).
                fileObj["kanban"]?.let { put("kanban", it) }
            }
            projects += project.toString()
        }
        return buildJsonObject {
            put("version", 2)
            put("activeProjectId", index.activeProjectId)
            put("projects", kotlinx.serialization.json.JsonArray(projects.map { kotlinx.serialization.json.Json.parseToJsonElement(it) }))
        }.toString()
    }

    // -------------------------------------------------------------------------------------------
    // Parsers (pure)
    // -------------------------------------------------------------------------------------------

    /** Split the marker-framed metadata output into its three sections. Tolerant of missing parts. */
    data class Metadata(
        val workspaceJson: String,
        val statusJson: String,
        val sessions: List<String>
    )

    fun parseMetadata(output: String): Metadata {
        // Sections are framed "@WS\n…\n@STATUS\n…\n@SESSIONS\n…" — each marker may be absent,
        // so each section is cut at the NEXT marker whatever it is (never bleed into a neighbor).
        val afterWs = output.substringAfter("@WS\n", "")
        val ws = afterWs.substringBefore("\n@STATUS\n").substringBefore("\n@SESSIONS\n").trim()
        val afterStatus = afterWs.substringAfter("\n@STATUS\n", "")
        val status = afterStatus.substringBefore("\n@SESSIONS\n").trim()
        // The sessions marker may follow @STATUS OR appear alone (no status file) — search the
        // raw output so a status-less host still yields its session list.
        val sessPart = output.substringAfter("\n@SESSIONS\n", "")
        val sessions = sessPart.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return Metadata(ws, status, sessions)
    }

    /** Parse `fetchProjectFilesCommand` output → cwd → project.json text. */
    fun parseProjectFiles(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        // Blocks are "@FILE '<cwd>'\n<json>" separated by newlines. Scan for the marker rather
        // than splitting on "\n@FILE " so the FIRST file (no leading newline) is not dropped.
        var idx = 0
        while (true) {
            val mark = output.indexOf("@FILE ", idx)
            if (mark < 0) break
            val nl = output.indexOf('\n', mark)
            if (nl < 0) break
            val cwd = output.substring(mark + "@FILE ".length, nl).trim().trim('\'').replace("''", "'")
            val next = output.indexOf("\n@FILE ", nl + 1)
            val end = if (next < 0) output.length else next
            val json = output.substring(nl + 1, end).trim()
            if (cwd.isNotEmpty()) result[cwd] = json
            if (next < 0) break
            idx = next + 1
        }
        return result
    }

    /** Parse `ls -Ap1` output → entries (trailing slash = dir; .git hidden; folders first). */
    data class DirEntry(val name: String, val dir: Boolean)

    fun parseLs(output: String): List<DirEntry> {
        return output.lines()
            .map { it.removeSuffix("\r") }
            .filter { it.isNotBlank() && it != "./" && it != "../" }
            .map { line -> if (line.endsWith("/")) DirEntry(line.dropLast(1), true) else DirEntry(line, false) }
            // `ls -Ap1` prints directories with a trailing slash (.git/), so the .git filter must
            // match the NAME after the slash is stripped — mirroring the reference parseLsEntries.
            .filter { it.name != ".git" }
            .sortedWith(compareBy({ !it.dir }, { it.name }))
    }

    /** Parse `git status --porcelain=v1 -b` output → (branch, ahead, behind, staged, changed). */
    data class PorcelainStatus(
        val branch: String,
        val ahead: Int,
        val behind: Int,
        val staged: List<Pair<String, String>>, // (XY-code, path)
        val changed: List<Pair<String, String>>
    )

    fun parseGitStatus(output: String): PorcelainStatus {
        var branch = ""
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<Pair<String, String>>()
        val changed = mutableListOf<Pair<String, String>>()
        var inBranchFooter = false
        for (line in output.lines()) {
            val trimmed = line.trimEnd()
            // The trailing `###BRANCH###\n<name>` footer is a fallback source — everything after
            // the marker is not a porcelain row and must never be parsed as one.
            if (trimmed.startsWith("###BRANCH###")) {
                inBranchFooter = true
                continue
            }
            if (inBranchFooter) continue
            if (trimmed.startsWith("## ")) {
                val meta = trimmed.substring(3)
                // Local branch is the part BEFORE "...upstream"; `## No commits yet on main` has
                // no upstream marker and is used verbatim.
                branch = meta.substringBefore("...").substringBefore("[").trim()
                val match = Regex("\\[(ahead (\\d+)(, behind (\\d+))?|behind (\\d+))]").find(meta)
                if (match != null) {
                    ahead = match.groupValues[2].toIntOrNull() ?: 0
                    behind = match.groupValues[4].toIntOrNull() ?: match.groupValues[5].toIntOrNull() ?: 0
                }
                continue
            }
            if (trimmed.isEmpty()) continue
            // Porcelain v1: "XY path" or "?? path" (untracked).
            if (trimmed.length < 3) continue
            val code = trimmed.substring(0, 2)
            val path = trimmed.substring(3).trim()
            if (code == "??") {
                changed += "U" to path
            } else {
                val x = code[0]
                val y = code[1]
                if (x != ' ' && x != '?') staged += x.toString() to path
                if (y != ' ' && y != '?') changed += y.toString() to path
            }
        }
        return PorcelainStatus(branch, ahead, behind, staged, changed)
    }
}

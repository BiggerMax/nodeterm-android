package com.nodeterm.android.core.remote

import com.nodeterm.android.core.model.JsonModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure wire-shape tests for the LAN / SSH transport: command builders must produce exactly the
 * shell text the reference host understands, and the parsers must round-trip its output.
 */
class LanCommandsTest {

    // ---- naming + quoting --------------------------------------------------------------------

    @Test
    fun tmuxSessionNameSanitizesLikeTheReference() {
        // src/core/tmux-naming.ts: `nt-${persistKey.replace(/[^a-zA-Z0-9_-]/g, '_')}`
        assertEquals("nt-abc123", LanCommands.tmuxSessionName("abc123"))
        assertEquals("nt-a_b_c", LanCommands.tmuxSessionName("a b.c"))
        assertEquals("nt-my-node_42", LanCommands.tmuxSessionName("my-node:42"))
    }

    @Test
    fun posixQuoteHandlesSpacesAndApostrophes() {
        assertEquals("'plain'", LanCommands.posixQuote("plain"))
        assertEquals("'a b'", LanCommands.posixQuote("a b"))
        // ' → '\'' (close quote, literal quote, reopen quote)
        assertEquals("'it'\\''s'", LanCommands.posixQuote("it's"))
    }

    @Test
    fun pathAwarePrefixesHomebrewDirs() {
        val cmd = LanCommands.pathAware("tmux -V")
        assertTrue(cmd.startsWith("export PATH=\"\$PATH:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin\"; "), cmd)
        assertTrue(cmd.endsWith("tmux -V"))
    }

    // ---- metadata fetch ----------------------------------------------------------------------

    @Test
    fun probeUserDataCommandTestsBothCandidates() {
        val cmd = LanCommands.probeUserDataCommand()
        assertTrue(cmd.contains("Application Support/node-terminal/workspace.json"))
        assertTrue(cmd.contains("Application Support/nodeterm/workspace.json"))
        assertTrue(cmd.contains("&& echo"))
        assertTrue(cmd.endsWith("exit 1"))
    }

    @Test
    fun fetchMetadataCommandFramesSections() {
        val dir = "\$HOME/Library/Application Support/node-terminal"
        val cmd = LanCommands.fetchMetadataCommand(dir)
        assertTrue(cmd.contains("echo \"@WS\""))
        assertTrue(cmd.contains("echo; echo \"@STATUS\""))
        assertTrue(cmd.contains("echo; echo \"@SESSIONS\""))
        assertTrue(cmd.contains("tmux -L node-terminal list-sessions"))
        // The userData dir is posix-quoted so spaces survive (and $HOME stays a shell variable).
        assertTrue(cmd.contains("'\$HOME/Library/Application Support/node-terminal/workspace.json'"))
    }

    @Test
    fun parseMetadataSplitsAllThreeSections() {
        val output = buildString {
            append("@WS\n")
            append("{\"version\":3,\"activeProjectId\":\"p1\",\"entries\":[]}\n")
            append("\n@STATUS\n")
            append("{\"v\":1,\"nodes\":{}}\n")
            append("\n@SESSIONS\n")
            append("nt-sess-1\nnt-sess-2\n")
        }
        val meta = LanCommands.parseMetadata(output)
        assertEquals("{\"version\":3,\"activeProjectId\":\"p1\",\"entries\":[]}", meta.workspaceJson)
        assertEquals("{\"v\":1,\"nodes\":{}}", meta.statusJson)
        assertEquals(listOf("nt-sess-1", "nt-sess-2"), meta.sessions)
    }

    @Test
    fun parseMetadataDegradesWhenSectionsAreMissing() {
        val meta = LanCommands.parseMetadata("")
        assertEquals("", meta.workspaceJson)
        assertEquals("", meta.statusJson)
        assertEquals(emptyList(), meta.sessions)

        val partial = LanCommands.parseMetadata("@WS\n{}\n\n@SESSIONS\nnt-x\n")
        assertEquals("{}", partial.workspaceJson)
        assertEquals("", partial.statusJson)
        assertEquals(listOf("nt-x"), partial.sessions)
    }

    @Test
    fun fetchProjectFilesAndParseRoundTrip() {
        val cmd = LanCommands.fetchProjectFilesCommand(listOf("/work/app", "/work/other"))
        assertTrue(cmd.contains("echo \"@FILE '/work/app'\""))
        assertTrue(cmd.contains("cat '/work/app/.nodeterm/project.json'"))
        assertTrue(cmd.contains("cat '/work/other/.nodeterm/project.json'"))

        val output = buildString {
            append("@FILE '/work/app'\n")
            append("{\"version\":1,\"id\":\"p1\"}\n")
            append("@FILE '/work/other'\n")
            append("{\"version\":1,\"id\":\"p2\"}\n")
        }
        val files = LanCommands.parseProjectFiles(output)
        assertEquals(2, files.size)
        assertEquals("{\"version\":1,\"id\":\"p1\"}", files["/work/app"])
        assertEquals("{\"version\":1,\"id\":\"p2\"}", files["/work/other"])
    }

    // ---- workspace assembly ------------------------------------------------------------------

    private val v3Index = """{"version":3,"activeProjectId":"p1","entries":[
        {"id":"p1","name":"My App","color":"#7aa2f7","cwd":"/work/app"},
        {"id":"s1","name":"SSH Remote","color":"#fff","cwd":null}
    ]}"""

    @Test
    fun parseWorkspaceIndexReadsV3Entries() {
        val index = LanCommands.parseWorkspaceIndex(v3Index)
        assertEquals("p1", index.activeProjectId)
        assertEquals(2, index.entries.size)
        assertEquals("My App", index.entries[0].name)
        assertEquals("/work/app", index.entries[0].cwd)
        assertEquals(null, index.entries[1].cwd) // remote ref — skipped by the assembler
    }

    @Test
    fun parseWorkspaceIndexDegradesOnGarbage() {
        val index = LanCommands.parseWorkspaceIndex("not json")
        assertEquals("", index.activeProjectId)
        assertEquals(emptyList(), index.entries)
    }

    @Test
    fun assembleV2WorkspaceBuildsParseableProjectList() {
        val projectFile = """{"version":1,"rev":"abc","savedAt":123,"id":"p1","name":"My App","color":"#7aa2f7",
            "viewport":{"x":1,"y":2,"zoom":0.5},
            "nodes":[{"id":"n1","kind":"terminal","title":"Build","cwd":"/work/app"}]}"""
        val v2 = LanCommands.assembleV2Workspace(v3Index, mapOf("/work/app" to projectFile))
        val ws = JsonModels.workspace(v2)
        assertNotNull(ws)
        assertEquals(2, ws.version)
        assertEquals("p1", ws.activeProjectId)
        assertEquals(1, ws.projects.size) // the remote-ref entry is skipped
        val p = ws.projects[0]
        assertEquals("p1", p.id)
        assertEquals("My App", p.name)
        assertEquals("#7aa2f7", p.color)
        assertEquals("/work/app", p.cwd)
        assertEquals(1, p.nodes.size)
        assertEquals("n1", p.nodes[0].id)
        assertEquals(0.5, p.viewport.zoom)
    }

    @Test
    fun assembleV2WorkspacePassesV2Through() {
        val v2 = """{"version":2,"activeProjectId":"p1","projects":[{"id":"p1","name":"X"}]}"""
        assertEquals(v2, LanCommands.assembleV2Workspace(v2, emptyMap()))
    }

    @Test
    fun assembleV2WorkspacePreservesKanban() {
        val projectFile = """{"version":1,"id":"p1","name":"My App","color":"#7aa2f7","viewport":{"x":0},
            "nodes":[{"id":"n1","kind":"terminal","title":"Build","cwd":"/work/app"}],
            "kanban":{"columns":[{"id":"c1","title":"To Do","color":""}],
                      "assignments":[{"nodeId":"n1","columnId":"c1"}],
                      "meta":[{"nodeId":"n1","priority":"high"}]}}"""
        val v2 = LanCommands.assembleV2Workspace(v3Index, mapOf("/work/app" to projectFile))
        val ws = JsonModels.workspace(v2)
        assertNotNull(ws)
        val k = ws.projects[0].kanban
        assertNotNull(k)
        assertEquals("To Do", k.columns[0].title)
        assertEquals("n1", k.assignments[0].nodeId)
        assertEquals("high", k.meta[0].priority)
    }

    // ---- directory listing -------------------------------------------------------------------

    @Test
    fun lsCommandQuotesThePath() {
        assertEquals("ls -Ap1 '/work/my app'", LanCommands.lsCommand("/work/my app"))
    }

    @Test
    fun parseLsMarksDirsAndSkipsGit() {
        // Real `ls -Ap1` output: directories carry a trailing slash — INCLUDING .git/.
        val out = "app/\n.git/\nREADME.md\nsrc\n"
        val entries = LanCommands.parseLs(out)
        assertEquals(3, entries.size)
        assertTrue(entries.none { it.name == ".git" }, "entries: $entries")
        assertEquals(LanCommands.DirEntry("app", true), entries[0]) // folders first
        assertEquals(LanCommands.DirEntry("README.md", false), entries[1]) // files sorted by name
        assertEquals(LanCommands.DirEntry("src", false), entries[2])
    }

    // ---- git ----------------------------------------------------------------------------------

    @Test
    fun gitStatusCommandBuildsPorcelainProbe() {
        val cmd = LanCommands.gitStatusCommand("/work/app")
        assertTrue(cmd.contains("git -C '/work/app' status --porcelain=v1 -b"))
        assertTrue(cmd.contains("###BRANCH###"))
    }

    @Test
    fun parseGitStatusReadsBranchAndChanges() {
        val out = "## main...origin/main [ahead 2, behind 1]\n M src/a.ts\nA  src/b.ts\n?? untracked.txt\n"
        val ps = LanCommands.parseGitStatus(out)
        assertEquals("main", ps.branch)
        assertEquals(2, ps.ahead)
        assertEquals(1, ps.behind)
        assertEquals(listOf("A" to "src/b.ts"), ps.staged)
        assertEquals(listOf("M" to "src/a.ts", "U" to "untracked.txt"), ps.changed)
    }

    @Test
    fun parseGitStatusHandlesDetachedAndClean() {
        val clean = LanCommands.parseGitStatus("## main\n###BRANCH###\nmain\n")
        assertEquals("main", clean.branch)
        assertTrue(clean.staged.isEmpty())
        assertTrue(clean.changed.isEmpty())
    }

    @Test
    fun gitDiffCommandFlagsStaged() {
        assertTrue(LanCommands.gitDiffCommand("/w", "a.ts", staged = false, untracked = false).contains("git -C '/w' diff -- 'a.ts'"))
        assertTrue(LanCommands.gitDiffCommand("/w", "a.ts", staged = true, untracked = false).contains("--cached"))
        assertEquals("true", LanCommands.gitDiffCommand("/w", "a.ts", staged = false, untracked = true))
    }

    // ---- terminal -----------------------------------------------------------------------------

    @Test
    fun attachCommandBuildsTmuxClient() {
        val cmd = LanCommands.attachCommand("abc", 80, 24)
        assertTrue(cmd.contains("tmux -L node-terminal new-session -A -s 'nt-abc'"))
        assertTrue(cmd.contains("-x 80 -y 24"))
        // No cwd → no -c flag (backwards compatible).
        assertFalse(cmd.contains("-c "))
    }

    @Test
    fun attachCommandStartsSessionInProjectCwd() {
        val cmd = LanCommands.attachCommand("abc", 80, 24, "/Users/me/My Project")
        assertTrue(cmd.contains("-c '/Users/me/My Project'"))
        assertTrue(cmd.contains("-x 80 -y 24"))
    }

    @Test
    fun captureCommandTargetsTheNodeSession() {
        val cmd = LanCommands.captureCommand("abc")
        assertTrue(cmd.contains("tmux -L node-terminal capture-pane -p -e -t 'nt-abc'"))
    }

    @Test
    fun sessionPathAndKillTargetTheNodeSession() {
        val path = LanCommands.sessionPathCommand("abc")
        assertTrue(path.contains("display-message -p -t 'nt-abc' '#{session_path}'"))
        val kill = LanCommands.killSessionCommand("abc")
        assertTrue(kill.contains("kill-session -t 'nt-abc'"))
    }

    @Test
    fun sendKeysTypesLiteralTextAndEnter() {
        val typed = LanCommands.sendKeysCommand("abc", "approve", enter = false)
        assertTrue(typed.contains("send-keys -t 'nt-abc' -l -- 'approve'"))
        assertFalse(typed.contains("Enter"))
        val withEnter = LanCommands.sendKeysCommand("abc", "approve", enter = true)
        assertTrue(withEnter.contains("Enter"))
    }

    @Test
    fun scrollSeqIsTheRawSgrWheelByte() {
        // The live LAN path writes these bytes into the tmux client's PTY — the exact
        // handleScroll wire format: wheel-up <64 / wheel-down <65, addressed to cell 1,1.
        assertEquals("\u001b[<64;1;1M", LanCommands.scrollSeq("up"))
        assertEquals("\u001b[<65;1;1M", LanCommands.scrollSeq("down"))
        // Anything that is not "down" scrolls up (mirrors the reference `!== 'down'`).
        assertEquals("\u001b[<64;1;1M", LanCommands.scrollSeq(""))
    }

    @Test
    fun scrollCommandEmitsRealEscBytesClampedTo20() {
        val up = LanCommands.scrollCommand("abc", "up", 3)
        // bash ANSI-C quoting so the sequence carries a REAL ESC byte (not the literal \x1b text).
        assertTrue(up.contains("$'\\x1b[<64;1;1M'"), "up sequence missing: $up")
        assertEquals(3, up.split(" && ").size)
        val down = LanCommands.scrollCommand("abc", "down", 1)
        assertTrue(down.contains("$'\\x1b[<65;1;1M'"), "down sequence missing: $down")
        // clamp
        assertEquals(20, LanCommands.scrollCommand("abc", "up", 500).split(" && ").size)
        assertEquals(1, LanCommands.scrollCommand("abc", "up", 0).split(" && ").size)
    }

    // ---- file read ----------------------------------------------------------------------------

    @Test
    fun catAndBase64QuotePathsAndCapLargeFiles() {
        // The path stays posix-quoted (spaces survive)…
        val cat = LanCommands.catCommand("/work/a b.txt")
        assertTrue(cat.contains("'/work/a b.txt'"), cat)
        assertTrue(cat.contains("head -c ${LanCommands.MAX_FILE_BYTES}"), cat)
        val b64 = LanCommands.base64Command("/work/a b.txt")
        assertTrue(b64.contains("'/work/a b.txt'"), b64)
        assertTrue(b64.contains("head -c ${LanCommands.MAX_FILE_BYTES}"), b64)
        // …and both end with a `fi` (the size guard closes).
        assertTrue(cat.trim().endsWith("fi"), cat)
        assertTrue(b64.trim().endsWith("fi"), b64)
    }
}

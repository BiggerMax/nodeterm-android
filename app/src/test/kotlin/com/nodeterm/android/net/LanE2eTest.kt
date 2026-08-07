package com.nodeterm.android.net

import com.nodeterm.android.core.e2ee.SshKeys
import com.nodeterm.android.core.model.JsonModels
import com.nodeterm.android.core.remote.HostPairingCodec
import com.nodeterm.android.core.remote.LanCommands
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Random

/**
 * REAL end-to-end check of the LAN / SSH transport against THIS machine's own sshd + tmux +
 * the live nodeterm desktop userData dir — the exact commands [LanSessionManager] runs.
 *
 * Opt-in (it mutates `~/.ssh/authorized_keys` for the duration, and creates a throwaway project
 * + tmux session under the home dir):
 *
 *   NODETERM_E2E=1 ./gradlew :app:testDebugUnitTest --tests '*LanE2eTest'
 *
 * Flow (mirrors a free-tier pair): install a throwaway ed25519 key (the same line `/pair` would
 * append) → sshj connect to 127.0.0.1:22 → probe the REAL userData dir → fetch metadata from the
 * live desktop (workspace + agent-status + tmux sessions) → assemble a v3→v2 workspace through
 * the real SSH channel → attach a REAL tmux client PTY to a throwaway `nt-*` session and type
 * input back and forth → capture-pane → SGR scroll → fs.list/read + git status on a home-level
 * test project.
 *
 * macOS caveat: sshd (the launchd daemon) usually lacks Full Disk Access, so ~/Documents (where
 * this machine's projects live) returns "Operation not permitted" — the fs/git/projects steps
 * therefore use a home-LEVEL test project, which sshd CAN read. Everything is removed in finally.
 */
class LanE2eTest {

    private val user = System.getProperty("user.name") ?: "unknown"
    private val comment = "nodeterm-android-e2e-${System.currentTimeMillis()}"
    private val marker = "LAN_E2E_" + Random().nextInt(1_000_000_000)
    private val throwawayId = "e2e-${System.currentTimeMillis()}"
    private val throwawaySession = LanCommands.tmuxSessionName(throwawayId)
    private val proj = "/Users/$user/.nodeterm-e2e-test/proj1"

    private val authKeys: Path = Path.of(System.getProperty("user.home"), ".ssh", "authorized_keys")

    private lateinit var client: SSHClient
    private var authKeysBackup: ByteArray? = null

    @Test
    fun lanE2eAgainstRealSshd() {
        // Opt-in via NODETERM_E2E=1 env (gradle workers inherit env but not -D reliably).
        assumeTrue("opt-in: NODETERM_E2E=1", System.getenv("NODETERM_E2E") == "1" || System.getProperty("nodeterm.e2e") == "1")
        assumeTrue("sshd must be listening on 127.0.0.1:22", portOpen("127.0.0.1", 22))

        val keys = SshKeys.generateEd25519()
        try {
            installKey(keys)
            withClient(keys) {
                var dir = ""
                step("probe userData dir") {
                    val probe = exec(LanCommands.probeUserDataCommand())
                    // The probe echoes the EXPANDED path (double-quoted `echo`), which is what the
                    // transport feeds into the single-quoted fetch commands.
                    dir = probe.lines().firstOrNull { it.isNotBlank() }
                        ?: error("probe should find the desktop userData dir, got: $probe")
                    check(dir.contains("Application Support/node-terminal")) { "unexpected dir: $dir" }
                    println("    userData dir: $dir")
                }

                // A home-level test project (NOT ~/Documents — macOS TCC blocks sshd there),
                // created through the SAME ssh channel: project.json + a git repo with a dirty file.
                step("setup throwaway test project over ssh") {
                    val projJson = buildProjectJson()
                    exec(
                        "mkdir -p '$proj/.nodeterm' && printf '%s' '$projJson' > '$proj/.nodeterm/project.json' && " +
                            "cd '$proj' && git init -q && git -c user.email=e2e@test -c user.name=e2e add . && " +
                            "git -c user.email=e2e@test -c user.name=e2e commit -qm init && echo dirty > notes.txt"
                    )
                    println("    created $proj (project.json + git repo)")
                }

                // A real throwaway tmux session on the DESKTOP's socket (node-terminal).
                exec("tmux -L node-terminal new-session -d -s '$throwawaySession' -x 80 -y 24")
                Thread.sleep(500)
                try {
                    step("metadata fetch from the LIVE desktop (workspace + status + sessions)") {
                        val meta = LanCommands.parseMetadata(exec(LanCommands.fetchMetadataCommand(dir)))
                        check(meta.workspaceJson.isNotBlank()) { "workspace.json section missing" }
                        check(meta.statusJson.isNotBlank()) { "agent-status.json section missing" }
                        check(meta.sessions.contains(throwawaySession)) {
                            "throwaway session missing from tmux list: ${meta.sessions}"
                        }
                        println("    live tmux sessions: ${meta.sessions}")
                    }

                    step("real workspace project files (TCC caveat observed, not asserted)") {
                        val meta = LanCommands.parseMetadata(exec(LanCommands.fetchMetadataCommand(dir)))
                        val index = LanCommands.parseWorkspaceIndex(meta.workspaceJson)
                        val cwds = index.entries.mapNotNull { it.cwd }
                        val files = LanCommands.parseProjectFiles(exec(LanCommands.fetchProjectFilesCommand(cwds)))
                        val readable = files.count { it.value.isNotBlank() }
                        println(
                            "    workspace: ${index.entries.size} entries, ${cwds.size} local cwds, " +
                                "$readable project files readable over ssh (0 here = macOS TCC blocks sshd on ~/Documents)"
                        )
                    }

                    step("projects assembly (v3 → v2) through the real ssh channel") {
                        val indexJson = "{\"version\":3,\"activeProjectId\":\"p1\",\"entries\":[" +
                            "{\"id\":\"p1\",\"name\":\"E2E\",\"color\":\"#32d74b\",\"cwd\":\"$proj\"}]}"
                        val files = LanCommands.parseProjectFiles(exec(LanCommands.fetchProjectFilesCommand(listOf(proj))))
                        check(files[proj]?.contains("\"version\"") == true) { "project file did not read back over ssh" }
                        val ws = JsonModels.workspace(LanCommands.assembleV2Workspace(indexJson, files))
                        checkNotNull(ws) { "assembled workspace did not parse" }
                        check(ws.projects.size == 1 && ws.projects[0].id == "p1") { "expected 1 assembled project" }
                        check(ws.projects[0].nodes.isNotEmpty()) { "project nodes did not survive assembly" }
                        println("    assembled ${ws.projects.size} project with ${ws.projects[0].nodes.size} node(s) over ssh")
                    }

                    step("terminal: attach tmux client PTY + type input + read it back") {
                        val pty = openPty(LanCommands.attachCommand(throwawayId, 80, 24))
                        try {
                            Thread.sleep(1200) // let the client attach + first paint arrive
                            val echoCmd = "echo $marker"
                            pty.write((echoCmd + "\r").toByteArray(Charsets.UTF_8))
                            pty.flush()
                            val out = readUntil(pty.inputStream, marker, 10_000)
                            check(out.contains(marker)) { "echo output not seen; got: ${out.takeLast(300)}" }
                            println("    typed input round-tripped: $echoCmd")
                        } finally {
                            pty.close()
                        }
                    }

                    step("snapshot: capture-pane -e contains the marker") {
                        val snap = exec(LanCommands.captureCommand(throwawayId))
                        check(snap.contains(marker)) { "capture-pane missing the marker" }
                    }

                    step("scroll: SGR wheel events (exit 0)") {
                        exec(LanCommands.scrollCommand(throwawayId, "up", 2))
                    }
                } finally {
                    runCatching { exec("tmux -L node-terminal kill-session -t '$throwawaySession' 2>/dev/null || true") }
                }

                step("fs.list + fs.read on the test project") {
                    val listing = LanCommands.parseLs(exec(LanCommands.lsCommand(proj)))
                    check(listing.isNotEmpty()) { "project dir listing empty" }
                    val projectJson = exec(LanCommands.catCommand("$proj/.nodeterm/project.json"))
                    check(projectJson.contains("\"version\"")) { "project.json did not read back" }
                    println("    fs ok: ${listing.size} entries (${listing.map { it.name }.joinToString(", ")})")
                }

                step("git status on the test project") {
                    val ps = LanCommands.parseGitStatus(exec(LanCommands.gitStatusCommand(proj), allowFail = true))
                    println("    branch='${ps.branch}' staged=${ps.staged.size} changed=${ps.changed.size}")
                    check(ps.branch.isNotEmpty()) { "expected a branch in the test repo" }
                    check(ps.changed.any { it.second == "notes.txt" }) { "expected dirty notes.txt: ${ps.changed}" }
                }

                println("LAN E2E: ALL CHECKS PASSED against ${user}@127.0.0.1:22")
            }
        } finally {
            restoreAuthKeys()
        }
    }

    /** A minimal ProjectFileV1 (no single quotes — it is embedded in a shell printf). */
    private fun buildProjectJson(): String =
        "{\"version\":1,\"rev\":\"abc123\",\"savedAt\":1700000000000,\"id\":\"p1\",\"name\":\"E2E\"," +
            "\"color\":\"#32d74b\",\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}," +
            "\"nodes\":[{\"id\":\"n1\",\"kind\":\"terminal\",\"title\":\"E2E Node\",\"cwd\":\"$proj\"}]}"

    // ---- transport plumbing (mirrors LanSessionManager) ---------------------------------------

    private inline fun step(name: String, body: () -> Unit) {
        println("== $name")
        body()
    }

    private inline fun withClient(keys: SshKeys.KeyPair, body: () -> Unit) {
        client = SSHClient().apply {
            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = true
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            setConnectTimeout(8_000)
            setTimeout(30_000)
            connect("127.0.0.1", 22)
            authPublickey(user, ed25519Provider(keys))
        }
        try {
            body()
        } finally {
            runCatching { exec("rm -rf '$proj' 2>/dev/null || true") }
            runCatching { client.close() }
        }
    }

    private fun ed25519Provider(keys: SshKeys.KeyPair): KeyProvider {
        val kp = checkNotNull(SshKeys.toJavaKeyPair(keys.secretKey)) { "eddsa provider unavailable" }
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = kp.private
            override fun getPublic(): PublicKey = kp.public
            override fun getType(): KeyType = KeyType.fromKey(kp.public)
        }
    }

    /** One-shot exec on the control connection (stdout + stderr drained, exit status checked). */
    private fun exec(command: String, allowFail: Boolean = false): String {
        val session = client.startSession()
        try {
            val cmd = session.exec(LanCommands.pathAware(command))
            val errBuf = ByteArrayOutputStream()
            val errDrain = Thread {
                runCatching { cmd.errorStream.copyTo(errBuf) }
            }.apply { isDaemon = true; start() }
            val out = cmd.inputStream.readBytes()
            cmd.join()
            errDrain.join(1000)
            if (!allowFail) {
                check(cmd.exitStatus == null || cmd.exitStatus == 0) {
                    "command failed (exit ${cmd.exitStatus}): $command\nstderr: ${String(errBuf.toByteArray())}"
                }
            }
            return String(out, Charsets.UTF_8)
        } finally {
            runCatching { session.close() }
        }
    }

    /** A PTY shell running one command (the tmux client), like LanSessionManager.attach. */
    private class Pty(val session: Session, val shell: Session.Shell) {
        val inputStream: InputStream get() = shell.inputStream
        fun write(bytes: ByteArray) {
            shell.outputStream.write(bytes)
            shell.outputStream.flush()
        }
        fun flush() = Unit
        fun close() {
            runCatching { shell.close() }
            runCatching { session.close() }
        }
    }

    private fun openPty(command: String): Pty {
        val s = client.startSession()
        s.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
        val shell = s.startShell()
        val pty = Pty(s, shell)
        pty.write((LanCommands.pathAware(command) + "\n").toByteArray(Charsets.UTF_8))
        return pty
    }

    /** Non-blocking poll for a marker in the pty output (the tmux client never EOFs). */
    private fun readUntil(input: InputStream, marker: String, timeoutMs: Long): String {
        val buf = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            while (input.available() > 0) {
                val b = input.read()
                if (b < 0) return buf.toString(Charsets.UTF_8.name())
                buf.write(b)
            }
            if (buf.toString(Charsets.UTF_8.name()).contains(marker)) break
            Thread.sleep(100)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    // ---- authorized_keys management -----------------------------------------------------------

    private fun installKey(keys: SshKeys.KeyPair) {
        val line = HostPairingCodec.sshEd25519Line(keys.publicKey, comment)
        authKeysBackup = if (Files.exists(authKeys)) Files.readAllBytes(authKeys) else ByteArray(0)
        Files.createDirectories(authKeys.parent)
        Files.write(
            authKeys,
            (String(authKeysBackup!!, Charsets.UTF_8).trimEnd() + "\n$line\n").toByteArray(Charsets.UTF_8)
        )
        // sshd reads authorized_keys once per connection; perms must be sane.
        runCatching { Files.setPosixFilePermissions(authKeys, setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        )) }
        println("installed throwaway key (comment=$comment) — will restore on exit")
    }

    private fun restoreAuthKeys() {
        val backup = authKeysBackup ?: return
        try {
            if (backup.isEmpty()) Files.deleteIfExists(authKeys)
            else Files.write(authKeys, backup)
            println("restored authorized_keys")
        } catch (e: Exception) {
            println("WARNING: could not restore authorized_keys: ${e.message}")
        }
    }

    private fun portOpen(host: String, port: Int): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1500); true }
    } catch (_: Exception) {
        false
    }
}

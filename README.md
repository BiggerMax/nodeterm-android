# nodeterm Android companion (P1 + P2 + P3)

Native Android (Kotlin + Jetpack Compose) companion client for
[nodeterm](https://github.com/eneskirca/nodeterm) — the node-based terminal manager. Implements
P1 + P2 + P3 of `docs/ANDROID_CLIENT_SPEC.md`: **QR pairing → persistent E2EE relay session →
project/node list → agent status stream (RUNNING / NEEDS YOU) → FULL terminal rendering (VT
state machine, colours, scrollback via tmux) → answer NEEDS YOU → mobile board view → read-only
remote/SSH file browsing + git status/diff → dictation → host-push entry point**. On top of the
relay (Pro/entitled hosts), the client also speaks the **LAN / SSH direct transport** — the same
free-tier path the iOS client uses, with no relay and no Pro required.

The wire protocol is implemented byte-for-byte against the nodeterm reference source
(`src/main/remote/*`); the E2EE / framing / relay state machine unit tests pass against vectors
generated from the TypeScript implementation itself, and the VT renderer is verified with its
own ANSI-behaviour test suite.

## Modules

| Module | Kind | Contents |
|--------|------|----------|
| `:core` | pure Kotlin/JVM, **Android-free** | NaCl box E2EE + HKDF-SHA256, 16-byte terminal framing, RPC envelope + relay state machine (injectable transport), pairing offer / workspace / canvas / agent-status mirror / inbox models, **VT100/xterm terminal emulator** (screen + parser + scrollback + alternate buffer), **git status models + per-line syntax highlighter**, **LAN command builders + parsers** (tmux/ssh-fs wire shapes, v3→v2 workspace assembly) |
| `:app` | Android application | OkHttp WebSocket transport, **sshj LAN/SSH transport** (ed25519 auth, TOFU host-key pinning, tmux PTY terminal attach), Keystore-backed session persistence (relay + LAN credentials), Compose UI (pairing/SAS/home/nodes/**full terminal renderer + dictation**/inbox/**board**/**file browser + syntax-highlighted viewer + git status/diff**/settings), FCM messaging service + local notifications |

## Build

Prerequisites:

- **JDK 17+** (verified with OpenJDK 21).
- **Android SDK** with `platforms;android-35` and `build-tools` (AGP 8.10). Set `ANDROID_HOME`
  or create `local.properties` with `sdk.dir=…`.

Commands:

```bash
# Pure protocol layer unit tests — needs ONLY a JDK:
./gradlew :core:test

# Full app:
./gradlew :app:assembleDebug
```

> **Verification status (honest):** `:core:test` is **green** on this machine (**133 tests**: e2ee
> vs reference vectors, RFC 5869 HKDF, framing roundtrip + known byte samples, full relay
> handshake to ready over an in-process fake transport, RPC req/res/timeout/keepalive,
> replay/reflection defenses, tunnel, pairing-offer validation, projects-blob parsing, the P2
> VT suite: SGR 16/256/truecolor, cursor/erase/wrap/scroll, scroll regions, alternate screen,
> transcript, REP/RIS/resize, fragmented and split-UTF-8 input, and the LAN command builders +
> parsers: tmux naming/quoting, metadata + project-file framing, v3→v2 workspace assembly,
> ls/git status parsing, SGR scroll sequence). `./gradlew :app:assembleDebug` also **passes on
> this machine** (Android SDK installed via Homebrew `android-commandlinetools`; JDK 21 +
> `platforms;android-35` + `build-tools;35.0.0`), producing
> `app/build/outputs/apk/debug/app-debug.apk` (~18 MB, includes sshj + BouncyCastle).

## FCM (host push entry, optional for P1)

The app builds and works **without** a Firebase project. `NodetermMessagingService`
(`notify/NodetermMessagingService.kt`) is the host-push entry point: it renders local
notifications for host-pushed RUNNING / NEEDS YOU / done events.

To enable real delivery:

1. Create a Firebase project and add an Android app (`com.nodeterm.android`).
2. Download `google-services.json` into `app/`.
3. Add to the root `build.gradle.kts`:
   ```kotlin
   plugins { id("com.google.gms.google-services") version "4.4.2" apply false }
   ```
   and to `app/build.gradle.kts`:
   ```kotlin
   plugins { id("com.google.gms.google-services") }
   ```
4. Rebuild. `NodetermMessagingService.onNewToken` persists the registration token for the host's
   push-grant model.

## Pairing flow (P1)

1. Host: Settings → Phone → show pairing code (a `nodeterm://pair?code=…` QR).
2. Android: scan it (or paste the code). The offer is validated (wss-only, loopback-ws allowed,
   token + host pubkey required) — mirroring the reference `pairing.ts`.
3. The client mints its own NaCl box keypair (secret key encrypted at rest via the Android
   Keystore), connects `wss://<relay>/?token=…`, and runs the E2EE handshake:
   `e2ee_hello → e2ee_ready → e2ee_auth → e2ee_authenticated` (plaintext text frames for the
   control messages, E2EE binary boxes after that — role-tagged, sequence-guarded).
4. Both humans compare the 6-digit SAS; the host approves pin-once; the client syncs
   `projects.list` (workspace + tmux sessions + agent-status mirror) and the canvas mirror.
5. Terminal nodes stream output as RAW PTY bytes (Snapshot*/Output/Error frames); the :core VT
   emulator renders them (SGR colours, bold/italic/underline, cursor, alternate screen) and typed
   input goes back as OP.Input frames. NEEDS YOU approvals answer via the host's
   `agent:answer-permission` RPC (hook-reply ticket) with a send-keys fallback — the reference's
   documented v1 behavior.

## LAN / SSH direct transport (free tier)

When a v0.2.37 host QR payload carries **no relay block** (free tier — “Remote access from your
phone” off, no Pro), the app falls back to the direct LAN / SSH channel instead of erroring —
the exact path the iOS client browses through. **Even when the host does mint a relay token**
(v0.2.37 pair servers always do), a free-tier host never actually joins the relay room, so the
relay handshake times out and the app then **falls back to the same LAN / SSH channel**
automatically (a one-time notice, then READY — no dead “Disconnected” screen). Once a LAN
session works, the preference is persisted, so later restores skip the doomed relay attempt
entirely and go straight to LAN.

1. Pairing (`POST http://<host>:<pairPort>/pair`) is ungated on the host and always installs our
   ed25519 key into `~/.ssh/authorized_keys`; the client **persists that keypair** (encrypted at
   rest) as its LAN credential (even when the relay path is also offered, so a relay handshake
   failure can fall back to it).
2. It SSH-connects straight to `host:22` as `user` (sshj + EdDSA), pinning the host key
   (TOFU: first-connect accepted, later changes rejected).
3. Projects/status come from the same bytes the relay serves: probe the host userData dir, cat
   `workspace.json` + `agent-status.json`, list the live `nt-<nodeId>` tmux sessions, assemble
   the v3 index + project files into the v2 workspace the UI renders.
4. A node's terminal is a tmux **client** (`tmux -L node-terminal new-session -A -s nt-<id>`)
   under a PTY with capture-pane snapshots; input/resize/SGR-wheel scroll go straight into it.
   File browsing, git status/diff and NEEDS-YOU answers (send-keys) are read-only one-shot execs.

**Real end-to-end check** (against this machine's own sshd + tmux + live desktop userData) —
opt-in integration test that installs a throwaway ed25519 key, runs the exact LAN command chain
(probe → metadata → workspace assembly → tmux PTY attach + typed input round-trip → capture →
scroll → fs/git), then restores everything:

```bash
NODETERM_E2E=1 ./gradlew :app:testDebugUnitTest --tests '*LanE2eTest'
```

**macOS caveat (TCC):** the `sshd` launchd daemon usually lacks Full Disk Access, so project files
under `~/Documents` etc. come back `Operation not permitted` over SSH — the LAN transport then
shows an empty project list with a one-time notice (the relay path is unaffected because the
host-side app, which has access, reads the files). Grant `sshd-keygen-wrapper` Full Disk Access in
System Settings → Privacy & Security, or keep projects outside TCC-protected folders. Non-
interactive SSH execs also lack the Homebrew PATH, so commands are prefixed with a PATH
augmentation (`/opt/homebrew/bin` etc.).

**Android caveat (BouncyCastle):** Android ships an ANCIENT BouncyCastle fork under the provider
name `"BC"`, and sshj reuses whatever `"BC"` provider already exists instead of registering the
bundled bcprov — so curve25519 KEX died with `no such algorithm: X25519 for provider BC` on real
devices (JVM unit tests never hit it). `LanSessionManager` now replaces the platform provider with
the bundled bcprov 1.78+ before any sshj crypto and offers an adaptive KEX list (curve25519 when
X25519 is available, else ECDH-NIST P-256/384/521 — both supported by macOS sshd).

## P2 features

- **Full terminal rendering** — `core/…/vt/` implements the xterm state machine (UTF-8,
  CSI/OSC/DCS, SGR 16/256/truecolor, cursor addressing, erase, scroll regions, alternate
  screen, transcript). The app's `TerminalRenderer` draws the cell grid on a Compose Canvas with
  per-run colours and a block cursor; the view auto-sizes the remote pty (`OP.Resize`).
- **Scrollback via tmux** — the host's tmux sessions own history: the phone sends `pty.scroll`
  (`{streamId, dir, lines}`) and the repainted screen streams back as Output frames (SGR wheel
  events → tmux scroll mode). The emulator's local transcript is stored/tested but is not the
  scrollback source of truth for tmux nodes.
- **Snapshots** — `tmux capture-pane -e` dumps arrive as Snapshot* frames; the client resets the
  screen and replays them (`\n` → `\r\n` so capture rows start at column 0; live pty output
  already arrives as `\r\n` via ONLCR).
- **Mobile board** — `canvas:request`/`canvas:state` nodes render on a pannable/zoomable surface
  with their canvas colours, titles and live status badges; tap a node to open its terminal.
- **Read-only remote file browsing** — `fs.list` / `fs.read` / `fs.readBinary` (jailed to the
  host's shared project roots) drive a directory walker and a read-only text viewer (hex preview
  for binaries). SSH/remote projects resolve through the host's own layers.

## P3 features

- **Read-only file/editor** — the file viewer now shows **line numbers + syntax highlighting**
  (per-line tokenizer in `:core` for Kotlin/Java/TS/Python/Go/Rust/Shell/Swift/C/JSON/YAML/SQL/
  Markdown, language auto-detected from the extension) with a large-file render cap.
- **Read-only git** — from the file browser: `git.status` (branch, ahead/behind, staged +
  changed lists with status letters) and `git.diff` (unified diff with +/-/meta coloring),
  all read-only.
- **Dictation** — a mic button in the terminal input bar uses Android `SpeechRecognizer`
  (RECORD_AUDIO runtime grant; graceful when no speech service is installed, e.g. emulators):
  live partial results land in the field, then Send.
- **Subscription polish** — closing a terminal (or switching nodes) sends `pty.kill {streamId}`
  so the host drops the detached pty and stops streaming; no more leaked streams.

## Boundaries (explicitly not done)

- No auto-reconnect with fresh tokens (the pairing token is single-use; a dropped session asks
  you to re-pair — the spec leaves reconnects to the token-minting takeover party).
- Board is read-only (canvas edits / `canvas:mutate`), file **editing** (`fs.write`) and git
  **mutations** (`git.stage` / commit / push) are deliberately not exposed to the phone: P3 is
  read-only by design.

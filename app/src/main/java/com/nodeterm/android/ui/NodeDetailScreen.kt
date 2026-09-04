package com.nodeterm.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.R
import com.nodeterm.android.core.model.InboxNodeNow
import com.nodeterm.android.core.model.NodeStatus

/**
 * Node detail: status header, NEEDS-YOU actions, the P2 FULL terminal renderer (VT state machine
 * in :core, Compose Canvas rendering, tmux-backed scrollback via `pty.scroll`).
 *
 * Input model: the built-in keyboard ([BuiltInKeyboard]) replaces the system IME — no editable
 * field is focused, so the soft keyboard never pops. Read-first: the keyboard starts hidden and
 * toggles on a terminal tap, drops automatically after a send, and system Back hides it before
 * leaving. Text accumulates in an on-screen input line and is shipped to the pty with the send
 * key; [TerminalShortcutChips] fires raw control sequences (Esc / Tab / ^C / arrows …) straight
 * at the pty and stays available even when the keyboard is hidden. 中 mode composes pinyin
 * against the bundled CC-CEDICT dictionary with a tappable candidate bar.
 */
@Composable
fun NodeDetailScreen(
    state: TerminalState,
    nodeStatus: NodeStatus,
    nodeNow: InboxNodeNow?,
    onBack: () -> Unit,
    onSendInput: (String) -> Unit,
    onScroll: (dir: String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onAnswer: (pendingId: String?, decision: String) -> Unit
) {
    val view = LocalView.current
    // Never let the system IME linger when the terminal opens — the built-in keyboard owns input.
    LaunchedEffect(Unit) { FastKeyboard.hide(view) }

    // Input state for the built-in keyboard. Read-first: the keyboard starts hidden so the
    // terminal output fills the screen; it pops on tap and drops again after a send.
    var keyboardVisible by rememberSaveable { mutableStateOf(false) }
    var inputLine by rememberSaveable { mutableStateOf("") }
    var pinyinBuffer by rememberSaveable { mutableStateOf("") }
    var pinyinMode by rememberSaveable { mutableStateOf(false) }

    /** Printable key from the keyboard: pinyin letters compose, everything else appends. */
    fun handleChar(c: String) {
        if (pinyinMode && c.length == 1 && c[0] in 'a'..'z') {
            pinyinBuffer += c
        } else {
            // A space with an unselectable composition commits the raw pinyin first.
            if (pinyinMode && pinyinBuffer.isNotEmpty() && c == " ") {
                inputLine += pinyinBuffer
                pinyinBuffer = ""
            }
            inputLine += c
        }
    }

    fun backspace() {
        if (pinyinBuffer.isNotEmpty()) pinyinBuffer = pinyinBuffer.dropLast(1)
        else if (inputLine.isNotEmpty()) inputLine = inputLine.dropLast(1)
    }

    /**
     * Send the input line (plus any pending pinyin) and an Enter, then clear the line.
     *
     * Enter is sent as CR (\r, 0x0D) — the byte a real Enter key produces on a terminal — NOT
     * LF. Raw-mode TUIs like Claude Code use a multiline input box where LF just inserts a newline
     * into the pending text (a send press would look like "just a newline" and the message never
     * submits); CR is what those apps treat as submit. Canonical-mode shells map CR to their line
     * discipline the same way, so CR works for both.
     */
    fun sendLine() {
        val text = inputLine + pinyinBuffer
        inputLine = ""
        pinyinBuffer = ""
        onSendInput("$text\r")
        // The message is out — drop the keyboard so the reply fills the screen.
        keyboardVisible = false
    }

    /** A candidate was tapped: commit it and keep any leftover pinyin composing. */
    fun selectCandidate(cand: PinyinCandidate) {
        inputLine += cand.word
        if (pinyinBuffer.isNotEmpty()) {
            pinyinBuffer = pinyinBuffer.drop(cand.consumed.coerceAtMost(pinyinBuffer.length))
        }
    }

    /** EN ⇄ 中 toggle; a pending composition is committed raw so no pinyin is lost. */
    fun togglePinyinMode() {
        if (pinyinMode && pinyinBuffer.isNotEmpty()) {
            inputLine += pinyinBuffer
            pinyinBuffer = ""
        }
        pinyinMode = !pinyinMode
    }

    // System back: hide the built-in keyboard first, then leave the terminal.
    BackHandler {
        if (keyboardVisible) keyboardVisible = false
        else onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(state.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                Text(
                    when {
                        state.attachError != null -> stringResource(R.string.attach_failed, state.attachError)
                        state.ended -> stringResource(R.string.ended_exit, state.exitCode?.toString() ?: "?")
                        state.attaching -> stringResource(R.string.attaching)
                        else -> stringResource(R.string.terminal_size_hint, state.cols, state.rows)
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Voice dictation — the mobile mirror of the desktop's ⌘⇧D (speak, review, then send).
            if (!state.ended && state.streamId != null) {
                DictationButton(
                    modifier = Modifier.size(40.dp),
                    onText = { text -> onSendInput("$text\r") }
                )
            }
            StatusBadge(nodeStatus)
        }

        // NEEDS-YOU quick actions while the terminal is open.
        if (nodeStatus == NodeStatus.NEEDS_YOU) {
            NeedsYouBar(onAnswer)
        }

        // What the agent is doing right now + its context-window fill — the desktop's per-node
        // context meter, fed by the host mirror's `inbox.nodes[<nodeId>]`.
        NodeNowPanel(nodeNow)

        // Terminal viewport — a tap toggles the built-in keyboard; swiping scrolls the host's
        // tmux history.
        TerminalView(
            modifier = Modifier.weight(1f),
            state = state,
            onScroll = onScroll,
            onResize = onResize,
            onTap = { keyboardVisible = !keyboardVisible }
        )

        if (!state.ended && state.streamId != null) {
            if (keyboardVisible) {
                // Full keyboard: input line + candidates + shortcut row.
                BuiltInKeyboard(
                    inputLine = inputLine,
                    composing = pinyinBuffer,
                    pinyinMode = pinyinMode,
                    onChar = ::handleChar,
                    onBackspace = ::backspace,
                    onSendLine = ::sendLine,
                    onSendKey = { seq -> onSendInput(seq) },
                    onSelectCandidate = ::selectCandidate,
                    onToggleMode = ::togglePinyinMode,
                    onPaste = { text -> inputLine += text }
                )
            } else {
                // Keyboard hidden: keep the one-tap control chips (^C / Esc / arrows / paste) on
                // screen so they work while reading output; the keyboard shows the same row.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 2.dp)
                ) {
                    TerminalShortcutChips(
                        onSendKey = { seq -> onSendInput(seq) },
                        onPaste = { text ->
                            inputLine += text
                            keyboardVisible = true // show the line so the paste is visible
                        }
                    )
                }
            }
        }
    }
}

/** The terminal canvas. Tap anywhere to toggle the built-in keyboard; swipe scrolls tmux history. */
@Composable
private fun TerminalView(
    modifier: Modifier,
    state: TerminalState,
    onScroll: (dir: String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onTap: () -> Unit
) {
    // Swipe up/down on the terminal scrolls the host's tmux history (`pty.scroll`). Drag pixels
    // are accumulated into whole scroll steps so a long fling pages multiple lines, and taps
    // pass through untouched to the clickable below (the drag handler only consumes once it
    // exceeds the touch slop).
    val density = LocalDensity.current
    // One scroll step per 24dp of finger travel — coarse enough that taps never scroll, fine
    // enough that an ordinary swipe reliably pages through the history (48dp needed a ~half-
    // screen flick before the first scroll step fired, so normal swipes felt dead).
    val scrollStepPx = with(density) { 24.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                var scrollAccum = 0f
                detectVerticalDragGestures { change, dragAmount ->
                    scrollAccum += dragAmount
                    while (scrollAccum >= scrollStepPx) {
                        scrollAccum -= scrollStepPx
                        onScroll("down")
                    }
                    while (scrollAccum <= -scrollStepPx) {
                        scrollAccum += scrollStepPx
                        onScroll("up")
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onTap()
            }
    ) {
        val screen = state.screen
        when {
            screen != null -> TerminalRenderer(
                screen = screen,
                generation = state.generation,
                onResize = onResize
            )
            state.attaching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.attachError ?: stringResource(R.string.no_output_yet),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The agent's live status line: a "NOW" headline (what tool it's running — "Editing foo.ts",
 * "Running npm test", or the current turn's "You: …" prompt) plus a context-window meter. Only
 * rendered when the host mirror carries the data; terminal nodes without an agent show nothing.
 */
@Composable
private fun NodeNowPanel(now: InboxNodeNow?) {
    val activity = now?.activity?.takeIf { it.isNotBlank() }
    val ctx = now?.contextPercent
    val prompt = now?.prompt?.takeIf { it.isNotBlank() }
    if (activity == null && ctx == null && prompt == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val headline = activity ?: prompt?.let { stringResource(R.string.you_prompt, it) }
        if (headline != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.now_label),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = headline,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (ctx != null) {
            Spacer(Modifier.height(5.dp))
            ContextMeter(percent = ctx)
        }
    }
}

@Composable
private fun NeedsYouBar(onAnswer: (pendingId: String?, decision: String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.notification_needs_you),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { onAnswer(null, "allow") }, modifier = Modifier.height(36.dp)) {
            Text(stringResource(R.string.approve), fontSize = 12.sp)
        }
        TextButton(onClick = { onAnswer(null, "deny") }) {
            Text(stringResource(R.string.deny), fontSize = 12.sp)
        }
    }
}

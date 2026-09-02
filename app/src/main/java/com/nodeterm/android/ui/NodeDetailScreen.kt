package com.nodeterm.android.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.nodeterm.android.core.model.InboxNodeNow
import com.nodeterm.android.core.model.NodeStatus

/**
 * Node detail: status header, NEEDS-YOU actions, the P2 FULL terminal renderer (VT state machine
 * in :core, Compose Canvas rendering, tmux-backed scrollback via `pty.scroll`).
 *
 * Input model: TAP-TO-TYPE. Tapping the terminal pops the system IME (any third-party keyboard —
 * Gboard / 搜狗 / SwiftKey …), and typed text flows straight into the pty. There is no visible
 * input box — a tiny hidden BasicTextField is the IME carrier; committed text (including IME
 * composition results, i.e. Chinese/Japanese) is forwarded verbatim, and backspace deletes. The
 * quick-key bar below stays for keys an IME cannot produce (Esc / Tab / Ctrl combos).
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
    // System back: dismiss the IME instantly, then leave the terminal (same as the header Back
    // button) — no two-step "back hides keyboard first" lag.
    val view = LocalView.current
    BackHandler {
        FastKeyboard.hide(view)
        onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                FastKeyboard.hide(view)
                onBack()
            }) { Text("‹ Back") }
            Column(Modifier.weight(1f)) {
                Text(state.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                Text(
                    when {
                        state.attachError != null -> "attach failed: ${state.attachError}"
                        state.ended -> "ended (exit ${state.exitCode ?: "?"})"
                        state.attaching -> "attaching…"
                        else -> "${state.cols}×${state.rows} · tap terminal to type"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Voice dictation — the mobile mirror of the desktop's ⌘⇧D (speak, review, then send).
            if (!state.ended && state.streamId != null) {
                DictationButton(
                    modifier = Modifier.size(40.dp),
                    onText = { text -> onSendInput("$text\n") }
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

        // Terminal viewport — tapping it pops the IME and routes input to the pty.
        TapToTypeTerminal(
            modifier = Modifier.weight(1f),
            state = state,
            onSendInput = onSendInput,
            onScroll = onScroll,
            onResize = onResize
        )

        // Quick-key toolbar (P2 scrollback + keys the IME cannot type).
        if (!state.ended && state.streamId != null) {
            ShortcutBar(onSendInput, onScroll)
        }
    }
}

/**
 * One send press can reach the carrier twice — an Enter KeyEvent AND the ImeAction (Sogou and
 * Gboard deliver differently). Anything within this window after a forwarded "\n" is the same
 * press; a human cannot legitimately submit twice this fast.
 */
private const val NEWLINE_DEDUPE_MS = 150L

/** The terminal canvas + the hidden IME carrier. Tap anywhere to open the keyboard. */
@Composable
private fun TapToTypeTerminal(
    modifier: Modifier,
    state: TerminalState,
    onSendInput: (String) -> Unit,
    onScroll: (dir: String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    // The IME carrier's text. It accumulates the typed text (transparent, 1px, single-line) so
    // backspace and IME prediction keep working — each change is diffed and forwarded to the pty.
    var carrier by remember { mutableStateOf(TextFieldValue("")) }
    // Set when the user presses send/enter while the IME still holds an uncommitted composition
    // (pinyin → hanzi). The newline must NOT race the commit: it is flushed in onValueChange right
    // AFTER the committed text is forwarded, never before it.
    var pendingNewline by remember { mutableStateOf(false) }
    // Uptime of the last "\n" forwarded. One send press can be delivered twice (an Enter key
    // event AND the editor action — Sogou/Gboard differ); the second delivery within a few frames
    // is the same press and must not submit a duplicate empty line.
    var lastNewlineMs by remember { mutableLongStateOf(0L) }

    /** Forward "\n" to the pty, collapsing double deliveries of the same send press. */
    fun sendNewline() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNewlineMs < NEWLINE_DEDUPE_MS) return
        lastNewlineMs = now
        onSendInput("\n")
    }

    /**
     * A send / enter press. If the IME is mid-composition the newline must wait for the commit
     * (it arrives via onValueChange right after the committed text); otherwise it goes straight
     * to the pty.
     */
    fun submitLine() {
        if (carrier.composition != null) {
            pendingNewline = true
        } else {
            sendNewline()
        }
    }

    // FocusRequester.requestFocus() is applied on the NEXT layout pass, so calling the platform
    // InputMethodManager before focus actually lands is dropped by some IMEs (the pop then never
    // happens until a second tap). Track a pending show and fire FastKeyboard.show from
    // onFocusChanged — the instant the carrier owns focus the keyboard pops, zero lag.
    var showImeWhenFocused by remember { mutableStateOf(false) }
    // Whether the hidden carrier currently owns focus (mirrored from onFocusChanged). The keyboard
    // can be dismissed while focus stays on the carrier (system back hides the IME, the screen
    // stays); on re-tap requestFocus() is then a no-op and onFocusChanged never fires — so when we
    // already own focus we must pop the keyboard directly instead of waiting for a focus change.
    var carrierFocused by remember { mutableStateOf(false) }

    fun showKeyboard() {
        showImeWhenFocused = true
        focusRequester.requestFocus()
        if (carrierFocused) {
            showImeWhenFocused = false
            FastKeyboard.show(view)
        }
    }

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
                showKeyboard()
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
                    text = state.attachError ?: "No output yet",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Hidden 1px IME carrier. It owns focus + the soft keyboard; committed text (including
        // IME composition results) is diffed against the previous carrier and forwarded to the
        // pty as forward-typing or backspace sequences. Composition (pinyin etc.) is held in the
        // carrier and only forwarded when the IME commits it.
        BasicTextField(
            value = carrier,
            onValueChange = { new ->
                val oldStr = carrier.text
                // Where the composition started in the OLD value — the pty already holds that
                // prefix, so a commit can be forwarded relative to it instead of by raw length.
                val oldCompositionStart = carrier.composition?.start
                val newStr = new.text
                val composing = new.composition != null
                val wasComposing = carrier.composition != null
                carrier = new
                if (composing) return@BasicTextField // hold pinyin mid-state; commit sends below
                when {
                    // An IME composition just committed (pinyin → hanzi). The composing text was
                    // held in the carrier and never forwarded, so diffing oldStr against newStr
                    // would look like random edits (e.g. "nihao" → "你好" reads as 3 deletions
                    // and the hanzi is dropped — the message never reaches the pty). The pty is
                    // sitting at the pre-composition prefix: type just the committed suffix, or
                    // rewind and retype when the IME rewrote text before the composition.
                    wasComposing -> {
                        val prefix = oldCompositionStart?.let { oldStr.substring(0, it) } ?: ""
                        if (newStr.startsWith(prefix)) {
                            onSendInput(newStr.substring(prefix.length))
                        } else {
                            repeat(prefix.length) { onSendInput("\u007f") }
                            onSendInput(newStr)
                        }
                    }
                    // Plain append (typing) — forward the added suffix.
                    newStr.length > oldStr.length && newStr.startsWith(oldStr) ->
                        onSendInput(newStr.substring(oldStr.length))
                    // Deletion — forward backspaces.
                    newStr.length < oldStr.length ->
                        repeat(oldStr.length - newStr.length) { onSendInput("\u007f") }
                    // Mid-string edits are rare (IME corrections); ignore to keep the pty in sync.
                    else -> {}
                }
                // A send pressed during composition: the IME commits the composition via this
                // onValueChange (the diff above just forwarded the text) BEFORE or instead of
                // firing the editor action — deliver the queued newline only now, after the
                // text. A commit that cancelled to empty text submits nothing.
                if (pendingNewline) {
                    pendingNewline = false
                    if (wasComposing && newStr.isNotEmpty()) sendNewline()
                }
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { fs ->
                    carrierFocused = fs.isFocused
                    if (!fs.isFocused) {
                        showImeWhenFocused = false
                        pendingNewline = false // drop a queued send if focus moves away uncommitted
                    } else if (showImeWhenFocused) {
                        showImeWhenFocused = false
                        FastKeyboard.show(view)
                    }
                }
                // Enter → "\n". This catches BOTH hardware Enter and the soft-keyboard action key
                // (the IME delivers it as a KeyEvent here too, so ImeAction.Send's onSend below
                // is the fallback, not the primary path). While a composition is pending the
                // newline is queued behind the commit instead of racing it (see onValueChange).
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        submitLine()
                        true
                    } else {
                        false
                    }
                },
            textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
            singleLine = true,
            // Plain terminal input: NO autocorrect (that flag makes Sogou/Gboard show a
            // "newline" action instead of "send" — the carrier must stay a raw text field).
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(onSend = { submitLine() })
        )
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
        val headline = activity ?: prompt?.let { "You: $it" }
        if (headline != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "NOW",
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
            "Needs you",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { onAnswer(null, "allow") }, modifier = Modifier.height(36.dp)) {
            Text("Approve", fontSize = 12.sp)
        }
        TextButton(onClick = { onAnswer(null, "deny") }) {
            Text("Deny", fontSize = 12.sp)
        }
    }
}

/** Terminal quick keys — tap to send the raw control sequence (scroll arrows first). */
@Composable
private fun ShortcutBar(onSendInput: (String) -> Unit, onScroll: (dir: String) -> Unit) {
    val keys = listOf(
        "Esc" to "\u001b",
        "Tab" to "\t",
        "^C" to "\u0003",
        "^D" to "\u0004",
        "^Z" to "\u001a",
        "^L" to "\u000c"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Scroll arrows: host-side tmux scrollback (only while a stream is live).
        IconButton(onClick = { onScroll("up") }, modifier = Modifier.size(32.dp)) {
            Text("▲", fontSize = 14.sp)
        }
        IconButton(onClick = { onScroll("down") }, modifier = Modifier.size(32.dp)) {
            Text("▼", fontSize = 14.sp)
        }
        keys.forEach { (label, seq) ->
            OutlinedButton(
                onClick = { onSendInput(seq) },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

package com.nodeterm.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A selectable pinyin candidate: the word plus how many pinyin letters it consumed. */
data class PinyinCandidate(val word: String, val consumed: Int)

/**
 * The CC-CEDICT-backed pinyin dictionary for the built-in keyboard.
 *
 * Asset format (one line per pinyin key): `key<TAB>word word ...` — tones stripped, `ü` written
 * as `v`. Loaded once per process from `assets/pinyin_dict.txt` (CC BY-SA 4.0, (c) MDBG,
 * https://cc-cedict.org). Candidates are re-ranked by per-character frequency inside the
 * dictionary so common characters (你/我/好…) surface before rare variants.
 */
object PinyinDict {

    private val map = HashMap<String, List<String>>()
    private val sortedKeys = ArrayList<String>()
    private val charFreq = HashMap<Char, Int>()
    @Volatile
    private var loaded = false

    /** How many keys the partial-syllable fallback merges before re-ranking. */
    private const val MAX_FALLBACK_KEYS = 15

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            context.assets.open("pinyin_dict.txt").bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val key = line.substring(0, tab)
                    val words = line.substring(tab + 1).split(' ')
                    map[key] = words
                    // A character that appears in many dictionary entries is a common character.
                    for (w in words) for (c in w) charFreq[c] = (charFreq[c] ?: 0) + 1
                }
            }
            sortedKeys.addAll(map.keys)
            sortedKeys.sort()
            loaded = true
        }
    }

    /**
     * Candidates for the current pinyin buffer, best first. Tiers:
     *  1. exact key match (multi-char phrases in dict order, single chars by frequency)
     *  2. longest complete-syllable prefix of the buffer (e.g. "ni" for "nihao")
     *  3. partial syllable (e.g. "w") → single chars from keys that start with it, by frequency
     */
    fun candidates(buffer: String): List<PinyinCandidate> {
        if (buffer.isEmpty()) return emptyList()
        val out = ArrayList<PinyinCandidate>()
        val exact = map[buffer]
        if (exact != null) {
            for (w in exact) if (w.length > 1) out.add(PinyinCandidate(w, buffer.length))
            val singles = ArrayList<String>()
            for (w in exact) if (w.length == 1) singles.add(w)
            singles.sortByDescending { charFreq[it[0]] ?: 0 }
            for (w in singles) out.add(PinyinCandidate(w, buffer.length))
        }
        for (len in buffer.length - 1 downTo 1) {
            val prefix = buffer.substring(0, len)
            val ws = map[prefix] ?: continue
            val singles = ArrayList<String>()
            for (w in ws) if (w.length == 1) singles.add(w)
            singles.sortByDescending { charFreq[it[0]] ?: 0 }
            for (w in singles) out.add(PinyinCandidate(w, len))
            break
        }
        if (out.isEmpty()) {
            val merged = ArrayList<String>()
            for (k in prefixKeys(buffer)) {
                map[k]?.forEach { w -> if (w.length == 1 && !merged.contains(w)) merged.add(w) }
            }
            merged.sortByDescending { charFreq[it[0]] ?: 0 }
            for (w in merged.take(12)) out.add(PinyinCandidate(w, buffer.length))
        }
        return out.take(30)
    }

    /** Up to [MAX_FALLBACK_KEYS] sorted keys that start with [prefix] (binary search over the range). */
    private fun prefixKeys(prefix: String): List<String> {
        var lo = 0
        var hi = sortedKeys.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sortedKeys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<String>()
        var i = lo
        while (i < sortedKeys.size && out.size < MAX_FALLBACK_KEYS && sortedKeys[i].startsWith(prefix)) {
            out.add(sortedKeys[i])
            i++
        }
        return out
    }
}

/**
 * The built-in terminal keyboard — replaces the system IME entirely (no editable field is ever
 * focused, so the soft keyboard never pops). Text accumulates in an input line and is sent to the
 * pty with one tap of the send key; the shortcut row fires raw control sequences straight at the
 * pty (Esc / Tab / ^C / arrows…).
 *
 * Modes: EN (plain ASCII), 中 (pinyin — letters compose into a candidate bar backed by
 * [PinyinDict], tap a candidate to commit hanzi), and a ?123 symbols page.
 */
@Composable
fun BuiltInKeyboard(
    inputLine: String,
    composing: String,
    pinyinMode: Boolean,
    onChar: (String) -> Unit,
    onBackspace: () -> Unit,
    onSendLine: () -> Unit,
    onSendKey: (String) -> Unit,
    onSelectCandidate: (PinyinCandidate) -> Unit,
    onToggleMode: () -> Unit,
    onPaste: (String) -> Unit
) {
    val context = LocalContext.current
    // One-time parse of the pinyin dictionary (guarded by the `loaded` flag inside PinyinDict).
    remember { PinyinDict.load(context) }

    var symbolsPage by remember { mutableStateOf(false) }
    val candidates = remember(composing) {
        if (pinyinMode && composing.isNotEmpty()) PinyinDict.candidates(composing) else emptyList()
    }

    // Space: commit the best candidate mid-composition; otherwise a plain space. When composing
    // with no candidates the raw pinyin is committed first (handled by onChar for " ").
    val spaceAction: () -> Unit = if (composing.isNotEmpty()) {
        { val first = candidates.firstOrNull(); if (first != null) onSelectCandidate(first) else onChar(" ") }
    } else {
        { onChar(" ") }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Input line — exactly what the send key will ship to the pty; composing pinyin in color.
        // Compact single line so the terminal viewport keeps as many rows as possible.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildAnnotatedString {
                    if (inputLine.isNotEmpty()) append(inputLine)
                    if (composing.isNotEmpty()) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(composing) }
                    }
                },
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Pinyin candidates (only while composing).
        if (candidates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                candidates.forEach { cand -> CandidateChip(cand.word) { onSelectCandidate(cand) } }
            }
        }

        // Common terminal shortcuts — raw sequences straight to the pty (bypass the input line).
        TerminalShortcutChips(onSendKey, onPaste)

        if (symbolsPage) {
            SymbolKeyRow("~!@#$%^&*", onChar, onBackspace, trailingBackspace = true)
            SymbolKeyRow("()_=+[]{}\\|", onChar, onBackspace)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                "';:\",./?".forEach { ch ->
                    KeyButton(ch.toString(), { onChar(ch.toString()) }, Modifier.weight(1f).height(KeyHeight))
                }
            }
        } else {
            SymbolKeyRow("1234567890", onChar, onBackspace, trailingBackspace = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                KeyRow("qwertyuiop", onChar)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                KeyRow("asdfghjkl", onChar)
            }
        }

        // Bottom letter row, then the utility row: symbols toggle + EN/中 + space + send.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            KeyRow("zxcvbnm", onChar)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            KeyButton(
                if (symbolsPage) "ABC" else "?123",
                { symbolsPage = !symbolsPage },
                Modifier.weight(1.4f).height(KeyHeight)
            )
            KeyButton(
                stringResource(if (pinyinMode) R.string.keyboard_mode_zh else R.string.keyboard_mode_en),
                onToggleMode,
                Modifier.weight(1.4f).height(KeyHeight)
            )
            SpaceKey(spaceAction, Modifier.weight(3.4f).height(KeyHeight))
            KeyButton(
                stringResource(R.string.keyboard_send),
                onSendLine,
                Modifier.weight(2f).height(KeyHeight),
                emphasized = true
            )
        }
    }
}

/** Compact key height so the terminal viewport keeps more rows (was 46.dp). */
private val KeyHeight = 40.dp

/**
 * One-tap control keys (arrows, Esc, Tab, ^C/^D/^Z/^L, paste) — raw sequences straight to the
 * pty, bypassing the input line. Shared by the built-in keyboard and by the slim strip that stays
 * on screen when the keyboard is hidden, so ^C/Esc stay reachable while reading output.
 */
@Composable
fun TerminalShortcutChips(
    onSendKey: (String) -> Unit,
    onPaste: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        QuickKey("Esc") { onSendKey("\u001b") }
        QuickKey("Tab") { onSendKey("\t") }
        QuickKey("^C") { onSendKey("\u0003") }
        QuickKey("^D") { onSendKey("\u0004") }
        QuickKey("^Z") { onSendKey("\u001a") }
        QuickKey("^L") { onSendKey("\u000c") }
        QuickKey(stringResource(R.string.keyboard_paste)) {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) onPaste(text)
        }
        // Keep navigation together at the far right, where it is easier to reach with the thumb.
        QuickKey("↑") { onSendKey("\u001b[A") }
        QuickKey("←") { onSendKey("\u001b[D") }
        QuickKey("↓") { onSendKey("\u001b[B") }
        QuickKey("→") { onSendKey("\u001b[C") }
    }
}

/** One full-width row of letter keys (no side keys). */
@Composable
private fun RowScope.KeyRow(letters: String, onChar: (String) -> Unit) {
    letters.forEach { ch ->
        KeyButton(ch.toString(), { onChar(ch.toString()) }, Modifier.weight(1f).height(KeyHeight))
    }
}

/** One row of symbol keys, with an optional backspace on the right. */
@Composable
private fun SymbolKeyRow(keys: String, onChar: (String) -> Unit, onBackspace: () -> Unit, trailingBackspace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        keys.forEach { ch ->
            KeyButton(ch.toString(), { onChar(ch.toString()) }, Modifier.weight(1f).height(KeyHeight))
        }
        if (trailingBackspace) {
            RepeatKeyButton("⌫", onBackspace, Modifier.weight(1.3f).height(KeyHeight))
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (label.length > 1) 14.sp else 17.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun SpaceKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(" ", fontSize = 17.sp)
    }
}

/** How long a held key waits before auto-repeating, and the repeat cadence. */
private const val REPEAT_DELAY_MS = 420L
private const val REPEAT_INTERVAL_MS = 60L

/**
 * A key that fires on touch-down and keeps firing while held (after a short delay) — used for ⌫
 * so deleting a long line is one press instead of dozens of taps. A quick tap fires exactly once.
 */
@Composable
private fun RepeatKeyButton(label: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (pressed) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    var job: Job? = null
                    try {
                        // Fire immediately, then auto-repeat after the hold delay.
                        job = scope.launch {
                            onAction()
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                onAction()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                        // Stay until the pointer lifts (or the gesture is cancelled).
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break
                        }
                    } finally {
                        pressed = false
                        job?.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (label.length > 1) 14.sp else 17.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun CandidateChip(word: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(word, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun QuickKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
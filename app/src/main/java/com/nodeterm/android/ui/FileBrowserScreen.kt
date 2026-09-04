package com.nodeterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.R
import com.nodeterm.android.core.model.GitFileChange
import com.nodeterm.android.core.model.GitStatus
import com.nodeterm.android.core.text.CodeLang
import com.nodeterm.android.core.text.HighlightKind
import com.nodeterm.android.core.text.Markdown
import com.nodeterm.android.core.text.MdKind
import com.nodeterm.android.core.text.MdLine
import com.nodeterm.android.core.text.SyntaxHighlighter
import com.nodeterm.android.net.HostSession

/** Render cap for a single text file (keep LazyColumn smooth on phones). */
private const val MAX_TEXT_CHARS = 400_000
/** Render cap for a single diff (diffs can be huge). */
private const val MAX_DIFF_CHARS = 300_000

// VS Code-dark inspired palette (the companion UI is dark by default).
private val CODE_KEYWORD = Color(0xFF569CD6)
private val CODE_TYPE = Color(0xFF4EC9B0)
private val CODE_STRING = Color(0xFFCE9178)
private val CODE_NUMBER = Color(0xFFB5CEA8)
private val CODE_COMMENT = Color(0xFF6A9955)
private val DIFF_ADD = Color(0xFF3FB950)
private val DIFF_DEL = Color(0xFFF85149)
private val DIFF_META = Color(0xFF8B949E)

/**
 * P2/P3 remote file browsing: the host's `fs.list` / `fs.read` / `fs.readBinary` RPCs (jailed to
 * the shared project roots) plus P3 read-only git status/diff (`git.status` / `git.diff`).
 * Layering: git diff → git status → file viewer → directory walker.
 */
@Composable
fun FileBrowserScreen(
    browser: FsBrowserState,
    viewer: FileViewerState?,
    git: GitState?,
    gitDiff: GitDiffState?,
    onBack: () -> Unit,
    onListDir: (String) -> Unit,
    onGoUp: () -> Unit,
    onOpenFile: (HostSession.FsEntry) -> Unit,
    onCloseViewer: () -> Unit,
    onOpenGit: (String) -> Unit,
    onRefreshGit: () -> Unit,
    onCloseGit: () -> Unit,
    onOpenDiff: (GitFileChange) -> Unit,
    onBackFromDiff: () -> Unit
) {
    val diff = gitDiff
    if (diff != null) {
        DiffView(diff = diff, onBack = onBackFromDiff)
        return
    }
    val g = git
    if (g != null) {
        GitScreen(
            git = g,
            onBack = onCloseGit,
            onRefresh = onRefreshGit,
            onOpenDiff = onOpenDiff
        )
        return
    }
    val v = viewer
    if (v != null) {
        FileViewer(viewer = v, onBack = onCloseViewer)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.close)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.files), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    browser.path,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace
                )
            }
            // P3: git status for this directory (read-only source control).
            TextButton(onClick = { onOpenGit(browser.path) }) { Text(stringResource(R.string.git)) }
            if (browser.path != "/") {
                TextButton(onClick = onGoUp) { Text(stringResource(R.string.up)) }
            }
        }

        when {
            browser.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            browser.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    browser.error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
            browser.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_folder), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(browser.entries, key = { it.name }) { entry ->
                    EntryRow(entry = entry, onClick = {
                        if (entry.dir) onListDir(join(browser.path, entry.name)) else onOpenFile(entry)
                    })
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: HostSession.FsEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (entry.dir) "📁" else "📄",
            fontSize = 15.sp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (entry.ignored) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
        if (entry.dir) {
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- read-only file viewer (P2) + P3 syntax highlighting / line numbers -----------------------

@Composable
private fun FileViewer(viewer: FileViewerState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(viewer.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (viewer.isBinary)
                        stringResource(R.string.viewer_binary_size, viewer.size)
                    else
                        stringResource(R.string.viewer_text_size_readonly, viewer.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            viewer.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            viewer.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(viewer.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            viewer.text != null -> {
                val text = viewer.text
                // .md / README files get the desktop's ⌘M markdown view; everything else keeps
                // the line-numbered syntax-highlighted code view.
                if (Markdown.isMarkdown(viewer.name)) {
                    MarkdownFileView(text = text)
                } else {
                    val lang = SyntaxHighlighter.detectLanguage(viewer.name)
                    val truncated = text.length > MAX_TEXT_CHARS
                    val shown = if (truncated) text.take(MAX_TEXT_CHARS) else text
                    val lines = remember(text) { shown.split("\n") }
                    if (truncated) {
                        Text(
                            stringResource(R.string.file_truncated, shown.length, text.length),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                    ) {
                        items(lines.size) { idx ->
                            CodeLine(
                                number = idx + 1,
                                line = lines[idx].ifEmpty { " " },
                                lang = lang
                            )
                        }
                    }
                }
            }
            viewer.bytes != null -> BinaryPreview(viewer.bytes)
        }
    }
}

/** One viewer row: gutter line number + (optionally highlighted) code text. */
@Composable
private fun CodeLine(number: Int, line: String, lang: CodeLang?) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            number.toString(),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.width(40.dp)
        )
        val annotated = rememberAnnotated(line, lang)
        Text(
            annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun rememberAnnotated(
    line: String,
    lang: CodeLang?
): AnnotatedString {
    return remember(line, lang) {
        buildAnnotatedString {
            if (lang == null) {
                append(line)
            } else {
                var cursor = 0
                for (t in SyntaxHighlighter.highlight(line, lang)) {
                    if (t.start > cursor) append(line.substring(cursor, t.start))
                    val span = line.substring(t.start, t.start + t.length)
                    val style = when (t.kind) {
                        HighlightKind.KEYWORD -> SpanStyle(color = CODE_KEYWORD, fontWeight = FontWeight.SemiBold)
                        HighlightKind.TYPE -> SpanStyle(color = CODE_TYPE)
                        HighlightKind.STRING -> SpanStyle(color = CODE_STRING)
                        HighlightKind.NUMBER -> SpanStyle(color = CODE_NUMBER)
                        HighlightKind.COMMENT -> SpanStyle(color = CODE_COMMENT, fontStyle = FontStyle.Italic)
                        HighlightKind.PLAIN -> SpanStyle()
                    }
                    withStyle(style) { append(span) }
                    cursor = t.start + t.length
                }
                if (cursor < line.length) append(line.substring(cursor))
            }
        }
    }
}

// ---- markdown view (the desktop's ⌘M, read-only) ---------------------------------------------

@Composable
private fun MarkdownFileView(text: String) {
    val truncated = text.length > MAX_TEXT_CHARS
    val shown = if (truncated) text.take(MAX_TEXT_CHARS) else text
    // Consecutive fenced-code lines merge into ONE block so the background reads as a solid
    // code panel instead of disconnected per-line strips.
    val lines = remember(shown) { groupCodeBlocks(Markdown.render(shown)) }
    Column(Modifier.fillMaxSize()) {
        if (truncated) {
            Text(
                stringResource(R.string.file_truncated, shown.length, text.length),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            items(lines) { line -> MdLineRow(line) }
        }
    }
}

/** Merge runs of consecutive [MdKind.CODE] lines into a single multi-line block. */
private fun groupCodeBlocks(lines: List<MdLine>): List<MdLine> {
    val out = mutableListOf<MdLine>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.kind == MdKind.CODE) {
            val sb = StringBuilder(line.raw)
            var j = i + 1
            while (j < lines.size && lines[j].kind == MdKind.CODE) {
                sb.append("\n").append(lines[j].raw)
                j++
            }
            out += MdLine(MdKind.CODE, raw = sb.toString())
            i = j
        } else {
            out += line
            i++
        }
    }
    return out
}

@Composable
private fun MdLineRow(line: MdLine) {
    when (line.kind) {
        MdKind.HEADING -> {
            Spacer(Modifier.height(8.dp))
            Text(
                mdAnnotated(line),
                fontSize = when (line.level) { 1 -> 20.sp; 2 -> 17.sp; else -> 15.sp },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
        }
        MdKind.PARAGRAPH -> Text(
            mdAnnotated(line),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(vertical = 3.dp)
        )
        MdKind.QUOTE -> Text(
            mdAnnotated(line),
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 3.dp, bottom = 3.dp)
        )
        MdKind.LIST_ITEM -> Row(Modifier.padding(vertical = 2.dp)) {
            Text("•  ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(mdAnnotated(line), fontSize = 13.sp, lineHeight = 19.sp)
        }
        MdKind.CODE -> Text(
            line.raw,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        MdKind.RULE -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
        MdKind.EMPTY -> Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun mdAnnotated(line: MdLine): AnnotatedString = buildAnnotatedString {
    if (line.segments.isEmpty()) {
        append(line.raw)
        return@buildAnnotatedString
    }
    for (s in line.segments) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (s.code) FontFamily.Monospace else FontFamily.Default,
            color = when {
                s.code -> MaterialTheme.colorScheme.tertiary
                s.link -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (s.link) TextDecoration.Underline else TextDecoration.None
        )
        withStyle(style) { append(s.text) }
    }
}

@Composable
private fun BinaryPreview(bytes: ByteArray) {
    val preview = bytes.copyOf(minOf(bytes.size, 1024))
    val rows = preview.size / 16 + if (preview.size % 16 != 0) 1 else 0
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(rows) { r ->
            val sb = StringBuilder()
            val hex = StringBuilder()
            for (c in 0 until 16) {
                val i = r * 16 + c
                if (i >= preview.size) break
                val b = preview[i].toInt() and 0xff
                hex.append(String.format("%02x ", b))
                sb.append(if (b in 0x20..0x7e) preview[i].toInt().toChar() else '.')
            }
            Text(
                text = hex.toString().padEnd(48) + "  " + sb,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ---- P3: read-only git status / diff -----------------------------------------------------------

private fun statusColor(status: String): Color = when (status) {
    "M" -> Color(0xFFD29922)      // amber
    "A" -> DIFF_ADD               // green
    "D" -> DIFF_DEL               // red
    "R" -> Color(0xFF58A6FF)      // blue
    else -> DIFF_META             // untracked / unknown
}

@Composable
private fun GitScreen(
    git: GitState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDiff: (GitFileChange) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.git_status), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    git.cwd,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace
                )
            }
            TextButton(onClick = onRefresh, enabled = !git.loading) { Text(stringResource(R.string.refresh)) }
        }

        when {
            git.loading && git.status == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            git.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(git.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
            }
            git.status == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_response), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            else -> GitStatusList(git = git, onOpenDiff = onOpenDiff)
        }
    }
}

@Composable
private fun GitStatusList(git: GitState, onOpenDiff: (GitFileChange) -> Unit) {
    val status = git.status!!
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status.repoName.ifEmpty { stringResource(R.string.not_a_repo) },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (status.branch.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            status.branch,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CODE_TYPE,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    listOfNotNull(
                        if (status.ahead > 0) "↑${status.ahead}" else null,
                        if (status.behind > 0) "↓${status.behind}" else null
                    ).joinToString(" ").ifEmpty { stringResource(R.string.clean_tree) } +
                        " · " + stringResource(R.string.git_status_counts, status.staged.size, status.changes.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (status.staged.isNotEmpty()) {
            item {
                SectionLabel(stringResource(R.string.staged))
            }
            items(status.staged, key = { "s:${it.path}" }) { change ->
                ChangeRow(change = change, onClick = { onOpenDiff(change) })
            }
        }
        if (status.changes.isNotEmpty()) {
            item {
                SectionLabel(stringResource(R.string.changes))
            }
            items(status.changes, key = { "c:${it.path}" }) { change ->
                ChangeRow(change = change, onClick = { onOpenDiff(change) })
            }
        }
        if (status.staged.isEmpty() && status.changes.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_changes), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun ChangeRow(change: GitFileChange, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            change.status,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = statusColor(change.status),
            modifier = Modifier.width(22.dp)
        )
        Text(
            change.path,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (change.added > 0 || change.deleted > 0) {
            Text(
                "+${change.added} -${change.deleted}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiffView(diff: GitDiffState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.git)) }
            Column(Modifier.weight(1f)) {
                Text(diff.change.path, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        diff.untracked -> stringResource(R.string.diff_untracked_readonly)
                        diff.staged -> stringResource(R.string.diff_staged_readonly)
                        else -> stringResource(R.string.diff_unstaged_readonly)
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            diff.loading && diff.text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            diff.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(diff.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
            }
            diff.text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_diff), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            else -> DiffLines(text = diff.text)
        }
    }
}

@Composable
private fun DiffLines(text: String) {
    val truncated = text.length > MAX_DIFF_CHARS
    val shown = if (truncated) text.take(MAX_DIFF_CHARS) else text
    val lines = remember(text) { shown.split("\n") }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp)
    ) {
        if (truncated) {
            item {
                Text(
                    stringResource(R.string.diff_truncated, shown.length, text.length),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        items(lines.size) { idx ->
            val line = lines[idx]
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> DIFF_ADD
                line.startsWith("-") && !line.startsWith("---") -> DIFF_DEL
                line.startsWith("@@") -> CODE_KEYWORD
                line.startsWith("diff --git") || line.startsWith("index ") ||
                    line.startsWith("--- ") || line.startsWith("+++ ") ||
                    line.startsWith("new file") || line.startsWith("deleted file") -> DIFF_META
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color
            )
        }
    }
}

private fun join(dir: String, name: String): String =
    if (dir.endsWith("/")) dir + name else "$dir/$name"

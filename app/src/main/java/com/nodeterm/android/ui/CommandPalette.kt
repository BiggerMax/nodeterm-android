package com.nodeterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nodeterm.android.R
import com.nodeterm.android.core.model.Project

/**
 * The mobile ⌘K: a command palette overlay that jumps anywhere — open any node (terminal or
 * agent), browse a project's files, or run an app action. Typing filters by title / project /
 * path; Enter (hardware keyboard) or tapping a row activates it, Esc / outside-tap closes —
 * the same muscle memory as the desktop's palette.
 */
@Composable
fun CommandPalette(
    nodes: List<NodeRow>,
    projects: List<Project>,
    connected: Boolean,
    onOpenNode: (NodeRow) -> Unit,
    onBrowseProject: (Project) -> Unit,
    onOpenBoard: () -> Unit,
    onSettings: () -> Unit,
    onRePair: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    // Selection index over the flattened (nodes then actions) result list.
    var selectedIndex by remember { mutableIntStateOf(0) }
    // ⌘K parity: the field is focused the moment the palette opens, so typing just works — no
    // extra tap, and the soft keyboard pops straight away (same FastKeyboard pattern as the
    // terminal's tap-to-type carrier).
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    var popImeWhenFocused by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val openBoardLabel = stringResource(R.string.open_board)
    val openBoardSubtitle = stringResource(R.string.open_board_subtitle)
    val browseFilesLabel = stringResource(R.string.browse_files)
    val settingsLabel = stringResource(R.string.settings_title)
    val settingsSubtitle = stringResource(R.string.settings_subtitle)
    val rePairLabel = stringResource(R.string.re_pair)
    val rePairSubtitle = stringResource(R.string.re_pair_subtitle)
    val actionEntries = remember(projects, connected, openBoardLabel, openBoardSubtitle, browseFilesLabel, settingsLabel, settingsSubtitle, rePairLabel, rePairSubtitle) {
        buildList {
            add(
                PaletteAction("board", openBoardLabel, openBoardSubtitle,
                    Icons.Outlined.ViewKanban, Color(0xFF58A6FF))
            )
            projects.forEach { p ->
                p.cwd?.takeIf { it.isNotBlank() }?.let { cwd ->
                    add(
                        PaletteAction("files:${p.id}", browseFilesLabel, "${p.name.ifBlank { p.id }} — $cwd",
                            Icons.Outlined.FolderOpen, Color(0xFF39C5CF))
                    )
                }
            }
            add(
                PaletteAction("settings", settingsLabel, settingsSubtitle,
                    Icons.Outlined.Settings, Color(0xFF8B949E))
            )
            if (!connected) {
                add(
                    PaletteAction("repair", rePairLabel, rePairSubtitle,
                        Icons.Outlined.Refresh, Color(0xFFFF7B72))
                )
            }
        }
    }

    val nodeResults = remember(query, nodes) { filterNodes(query, nodes) }
    val actionResults = remember(query, actionEntries) { filterActions(query, actionEntries) }
    val results = nodeResults + actionResults
    // Derived (never written during composition): the keyboard selection clamped to the results.
    val currentIndex = selectedIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))

    fun activate(index: Int) {
        val entry = results.getOrNull(index) ?: return
        when (entry) {
            is PaletteNode -> onOpenNode(entry.row)
            is PaletteAction -> when (entry.id) {
                "board" -> onOpenBoard()
                "settings" -> onSettings()
                "repair" -> onRePair()
                else -> entry.project?.let(onBrowseProject)
            }
        }
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(top = 10.dp, bottom = 8.dp)
        ) {
            // Search field — the palette is keyboard-first (⌘K parity): ↑/↓ move, Enter jumps.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; selectedIndex = 0 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { fs ->
                        if (fs.isFocused && popImeWhenFocused) {
                            popImeWhenFocused = false
                            FastKeyboard.show(view)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                selectedIndex = (selectedIndex + 1).coerceAtMost((results.size - 1).coerceAtLeast(0))
                                true
                            }
                            Key.DirectionUp -> {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter -> {
                                activate(currentIndex)
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
                placeholder = { Text(stringResource(R.string.palette_placeholder), fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_cd), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { activate(currentIndex) }
                )
            )
            Spacer(Modifier.size(4.dp))

            if (results.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.palette_no_matches, query),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (nodeResults.isNotEmpty()) {
                        item(key = "hdr-nodes") { PaletteSectionHeader(stringResource(R.string.nodes)) }
                        items(nodeResults, key = { it.key }) { entry ->
                            PaletteRow(
                                entry = entry,
                                selected = results.indexOf(entry) == currentIndex,
                                onClick = { activate(results.indexOf(entry)) }
                            )
                        }
                    }
                    if (actionResults.isNotEmpty()) {
                        item(key = "hdr-actions") { PaletteSectionHeader(stringResource(R.string.actions)) }
                        items(actionResults, key = { it.key }) { entry ->
                            PaletteRow(
                                entry = entry,
                                selected = results.indexOf(entry) == currentIndex,
                                onClick = { activate(results.indexOf(entry)) }
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.palette_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

private sealed interface PaletteEntry {
    val key: String
    val title: String
    val subtitle: String
    val icon: ImageVector
    val tint: Color
}

private data class PaletteNode(val row: NodeRow) : PaletteEntry {
    override val key get() = "n:" + row.nodeId
    override val title get() = row.title.ifBlank { row.nodeId }
    override val subtitle
        get() = listOfNotNull(
            row.projectName.ifBlank { null },
            row.cwd?.takeIf { it.isNotBlank() },
            row.agentId?.let { "agent $it" }
        ).joinToString(" · ")
    override val icon get() = NodeKinds.meta(row.kind).icon
    override val tint get() = NodeKinds.meta(row.kind).color
}

private data class PaletteAction(
    val id: String,
    override val title: String,
    override val subtitle: String,
    override val icon: ImageVector,
    override val tint: Color,
    /** For the per-project "Browse files" actions — the project to open. */
    val project: Project? = null
) : PaletteEntry {
    override val key get() = "a:" + id
}

private fun filterNodes(query: String, nodes: List<NodeRow>): List<PaletteNode> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val scored = nodes
        .map { row ->
            val haystack = listOf(
                row.title,
                row.projectName,
                row.cwd.orEmpty(),
                row.agentId.orEmpty(),
                NodeKinds.meta(row.kind).label
            ).joinToString(" ").lowercase()
            val titleLower = row.title.lowercase()
            val q = query.trim().lowercase()
            val score = when {
                tokens.isEmpty() -> 0
                tokens.all { haystack.contains(it) } && titleLower.startsWith(q) -> 0
                tokens.all { haystack.contains(it) } && titleLower.contains(q) -> 1
                tokens.all { haystack.contains(it) } -> 2
                else -> Int.MAX_VALUE
            }
            Triple(score, titleLower, row)
        }
        .filter { it.first != Int.MAX_VALUE }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .take(25)
        .map { PaletteNode(it.third) }
    return scored
}

private fun filterActions(query: String, actions: List<PaletteAction>): List<PaletteAction> {
    if (query.isBlank()) return actions
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return actions.filter { a ->
        val haystack = "${a.title} ${a.subtitle}".lowercase()
        tokens.all { haystack.contains(it) }
    }
}

@Composable
private fun PaletteSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun PaletteRow(entry: PaletteEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = entry.tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    entry.subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = if (entry is PaletteNode) FontFamily.Monospace else null
                )
            }
        }
    }
}

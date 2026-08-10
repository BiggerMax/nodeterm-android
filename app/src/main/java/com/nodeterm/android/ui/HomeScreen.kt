package com.nodeterm.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.core.model.InboxEvent
import com.nodeterm.android.core.model.NodeStatus

@Composable
fun HomeScreen(
    state: RelayUiState,
    onOpenNode: (NodeRow) -> Unit,
    onAnswer: (nodeId: String, pendingId: String?, decision: String) -> Unit,
    onAnswerQuestion: (nodeId: String, text: String) -> Unit,
    onOpenBoard: () -> Unit,
    onBrowse: (NodeRow) -> Unit,
    onSettings: () -> Unit,
    onRepair: (() -> Unit)? = null
) {
    var tab by remember { mutableIntStateOf(0) }
    // The mobile ⌘K — search anywhere (nodes, project files, actions) from the header.
    var paletteOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Header: wordmark + live connection dot, then palette / board / settings actions.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("nodeterm", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        state.connected -> {
                            PulsingDot(Color(0xFF4ADE80))
                            Spacer(Modifier.width(6.dp))
                            Text("Connected to host", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.phase == Phase.CONNECTING -> {
                            Text("Connecting to host…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            PulsingDot(MaterialTheme.colorScheme.error, steady = true)
                            Spacer(Modifier.width(6.dp))
                            Text("Disconnected", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            // NOTE: the docked recovery banner below owns the Re-pair CTA when disconnected — a
            // header button here would be a third duplicate (banner + empty state + header).
            IconButton(onClick = { paletteOpen = true }) {
                Icon(Icons.Outlined.Search, contentDescription = "Jump to…", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onOpenBoard) {
                Icon(Icons.Outlined.ViewKanban, contentDescription = "Board", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Nodes (${state.nodes.size})") })
            val needsCount = state.inbox.count { it.kind != "done" }
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = {
                    Text(
                        "Needs you ($needsCount)",
                        color = if (needsCount > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (needsCount > 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            )
        }

        when (tab) {
            0 -> NodesTab(state, onOpenNode, onBrowse, onRepair)
            1 -> InboxTab(state, onAnswer, onAnswerQuestion)
        }

        if (state.phase == Phase.DISCONNECTED) {
            // Docked recovery banner: the connection dropped — explain why (nodes may still be
            // cached from the previous session, so show the reason regardless of node count) and
            // offer the one-tap fix. Styled like the desktop's announcement banners (icon + text
            // + action) instead of a bare red line.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.disconnectReason ?: "Connection to the host was lost.",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (onRepair != null) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onRepair) {
                        Text("Re-pair", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (paletteOpen) {
        CommandPalette(
            nodes = state.nodes,
            projects = state.projects,
            connected = state.connected,
            onOpenNode = onOpenNode,
            onBrowseProject = { project ->
                project.cwd?.takeIf { it.isNotBlank() }?.let { cwd ->
                    onBrowse(
                        NodeRow(
                            nodeId = "",
                            title = project.name.ifBlank { project.id },
                            kind = "terminal",
                            agentId = null,
                            cwd = cwd,
                            projectName = project.name
                        )
                    )
                }
            },
            onOpenBoard = onOpenBoard,
            onSettings = onSettings,
            onRePair = { onRepair?.invoke() },
            onDismiss = { paletteOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodesTab(
    state: RelayUiState,
    onOpenNode: (NodeRow) -> Unit,
    onBrowse: (NodeRow) -> Unit,
    onRepair: (() -> Unit)?
) {
    if (state.nodes.isEmpty()) {
        if (state.phase == Phase.DISCONNECTED) {
            EmptyState(
                title = "Connection dropped",
                subtitle = state.disconnectReason
                    ?: "Re-pair with your host to see its projects again.",
                icon = Icons.Outlined.CloudOff,
                actionLabel = "Re-pair",
                onAction = onRepair
            )
        } else {
            EmptyState(
                title = "No nodes yet",
                subtitle = "Start a terminal on your host — it will appear here. Sticky notes, agents and editors show up too.",
                icon = Icons.Outlined.Terminal
            )
        }
        return
    }
    // Group the flat node list by project directory. nodeterm's "project" is a directory on the
    // host, and `projectName` is its display name (fall back to the node's cwd when it is blank).
    // The flat list is already sorted (status first, then title) in the ViewModel, so `groupBy`
    // preserves that order within each group; we only reorder groups alphabetically.
    // projectName → canvas colour for the section dots. Keyed exactly like the group key below
    // (name, falling back to cwd, then id) so blank-name projects still get their colour.
    val projectColors = remember(state.projects) {
        state.projects.associate { p ->
            (p.name.ifBlank { p.cwd?.ifBlank { null } ?: p.id }) to p.color
        }
    }
    val groups = remember(state.nodes) {
        state.nodes
            .groupBy { it.projectName.ifBlank { it.cwd ?: "Other" } }
            .toList()
            .sortedBy { (name, _) -> name.lowercase() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { (dir, nodes) ->
            item(key = "dir:$dir") {
                ProjectHeader(
                    title = dir,
                    count = nodes.size,
                    colorHex = projectColors[dir]
                )
            }
            items(nodes, key = { it.nodeId }) { node ->
                val now = state.nodeNow[node.nodeId]
                NodeCard(
                    node = node,
                    status = state.status[node.nodeId] ?: NodeStatus.IDLE,
                    name = state.nodeNames[node.nodeId] ?: node.title,
                    // What the agent is doing right now ("Running npm test") — the mirror's
                    // per-node `now` block; desktop parity for the node-card meta line.
                    activity = now?.activity,
                    // Per-node context-window fill — the same meter the desktop paints on agent
                    // cards, now on the phone's list too.
                    contextPercent = now?.contextPercent,
                    onOpenNode = onOpenNode,
                    onBrowse = onBrowse
                )
            }
        }
    }
}

/** Sticky section header for one project directory: project colour dot + name + node count. */
@Composable
private fun ProjectHeader(title: String, count: Int, colorHex: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!colorHex.isNullOrBlank()) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        parseNodeColor(colorHex, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One node row: kind icon + title + kind tag, project/agent meta, live activity line, cwd, a
 * node-colour accent bar on the left edge (desktop canvas parity), a context meter for agent
 * nodes, status badge and Files button. Long-press opens a quick-actions sheet (the mobile
 * mirror of the desktop's right-click node menu).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NodeCard(
    node: NodeRow,
    status: NodeStatus,
    name: String,
    activity: String?,
    contextPercent: Int?,
    onOpenNode: (NodeRow) -> Unit,
    onBrowse: (NodeRow) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showActions by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpenNode(node) },
                onLongClick = { showActions = true }
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Left edge accent in the node's canvas colour (only when the host provides one).
            if (!node.color.isNullOrBlank()) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(parseNodeColor(node.color, Color.Transparent))
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    // Title row: kind icon + name + kind tag (non-terminal nodes get a label).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KindIcon(node.kind)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (NodeKinds.normalize(node.kind) !in NodeKinds.TERMINAL_LIKE) {
                            Spacer(Modifier.width(6.dp))
                            KindTag(node.kind)
                        }
                    }
                    // Project / agent line — the agent's live activity rides the same meta row
                    // ("My App · • Running npm test"), mirroring the desktop's node card.
                    val meta = listOfNotNull(
                        node.projectName.ifBlank { null },
                        node.agentId?.let { "agent: $it" },
                        activity?.takeIf { it.isNotBlank() }?.let { "• $it" }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = meta,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // cwd on its own monospace line — the most useful signal for picking a node.
                    if (!node.cwd.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = node.cwd,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // The desktop's per-node context meter — a thin fill bar on the card.
                    if (contextPercent != null) {
                        Spacer(Modifier.height(6.dp))
                        ContextMeter(percent = contextPercent)
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(status)
                if (node.cwd != null) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = { onBrowse(node) },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Text("Files", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // Quick actions (long-press): the desktop's right-click node menu, on a phone sheet.
    if (showActions) {
        ModalBottomSheet(
            onDismissRequest = { showActions = false },
            sheetState = sheetState
        ) {
            Column(Modifier.padding(bottom = 20.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KindIcon(node.kind)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(
                    onClick = {
                        showActions = false
                        onOpenNode(node)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open terminal") }
                if (node.cwd != null) {
                    TextButton(
                        onClick = {
                            showActions = false
                            onBrowse(node)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Browse files") }
                    TextButton(
                        onClick = {
                            showActions = false
                            clipboard.setText(AnnotatedString(node.cwd))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy path") }
                }
            }
        }
    }
}

@Composable
private fun InboxTab(
    state: RelayUiState,
    onAnswer: (nodeId: String, pendingId: String?, decision: String) -> Unit,
    onAnswerQuestion: (nodeId: String, text: String) -> Unit
) {
    val actionable = state.inbox.filter { it.kind != "done" }
    if (actionable.isEmpty()) {
        EmptyState(
            title = "Nothing needs you",
            subtitle = "When an agent needs approval or asks a question, it lands here.",
            icon = Icons.Outlined.CheckCircle
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(actionable, key = { it.id }) { event ->
            InboxCard(
                event = event,
                nodeName = state.nodeNames[event.nodeId] ?: state.nodes.firstOrNull { it.nodeId == event.nodeId }?.title ?: event.nodeId,
                onAnswer = onAnswer,
                onAnswerQuestion = onAnswerQuestion
            )
        }
    }
}

@Composable
private fun InboxCard(
    event: InboxEvent,
    nodeName: String,
    onAnswer: (nodeId: String, pendingId: String?, decision: String) -> Unit,
    onAnswerQuestion: (nodeId: String, text: String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (event.kind == "approval") MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(NodeStatus.NEEDS_YOU)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (event.kind == "approval") "Approval" else "Question",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(event.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            event.detail?.let { detail ->
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "on $nodeName",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            when (event.kind) {
                "approval" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAnswer(event.nodeId, event.pendingId, "allow") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Approve") }
                    TextButton(
                        onClick = { onAnswer(event.nodeId, event.pendingId, "deny") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Deny") }
                }
                "question" -> {
                    val options = event.options ?: emptyList()
                    if (options.isNotEmpty()) {
                        options.take(4).forEachIndexed { index, option ->
                            TextButton(
                                onClick = { onAnswerQuestion(event.nodeId, "${index + 1}\n") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${index + 1} · $option", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Text(
                            "Answer in the node's terminal.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            // A soft circular glyph gives the empty state a visual anchor (like the desktop's
            // canvas hint), instead of a bare block of text.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

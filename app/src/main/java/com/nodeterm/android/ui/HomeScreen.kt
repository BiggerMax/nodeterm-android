package com.nodeterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("nodeterm", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = when {
                        state.connected -> "Connected to host"
                        state.phase == Phase.CONNECTING -> "Connecting to host…"
                        else -> "Disconnected"
                    },
                    fontSize = 12.sp,
                    color = if (state.connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            if (state.phase == Phase.DISCONNECTED && onRepair != null) {
                TextButton(onClick = onRepair) { Text("Re-pair") }
            }
            TextButton(onClick = onOpenBoard) { Text("Board") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Nodes (${state.nodes.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Needs you (${state.inbox.count { it.kind != "done" }})") })
        }

        when (tab) {
            0 -> NodesTab(state, onOpenNode, onBrowse)
            1 -> InboxTab(state, onAnswer, onAnswerQuestion)
        }

        if (state.phase == Phase.DISCONNECTED) {
            // Docked recovery bar: the connection dropped — explain why (nodes may still be
            // cached from the previous session, so show the reason regardless of node count).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.disconnectReason ?: "Connection to the host was lost.",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodesTab(
    state: RelayUiState,
    onOpenNode: (NodeRow) -> Unit,
    onBrowse: (NodeRow) -> Unit
) {
    if (state.nodes.isEmpty()) {
        EmptyState(
            if (state.phase == Phase.DISCONNECTED) "Connection dropped" else "No nodes yet",
            if (state.phase == Phase.DISCONNECTED) "Re-pair with your host to see its projects." else "Start a terminal on your host — it will appear here."
        )
        return
    }
    // Group the flat node list by project directory. nodeterm's "project" is a directory on the
    // host, and `projectName` is its display name (fall back to the node's cwd when it is blank).
    // The flat list is already sorted (status first, then title) in the ViewModel, so `groupBy`
    // preserves that order within each group; we only reorder groups alphabetically.
    val groups = remember(state.nodes) {
        state.nodes
            .groupBy { it.projectName.ifBlank { it.cwd ?: "Other" } }
            .toList()
            .sortedBy { (name, _) -> name.lowercase() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { (dir, nodes) ->
            item(key = "dir:$dir") {
                ProjectHeader(dir, nodes.size)
            }
            items(nodes, key = { it.nodeId }) { node ->
                NodeCard(
                    node = node,
                    status = state.status[node.nodeId] ?: NodeStatus.IDLE,
                    name = state.nodeNames[node.nodeId] ?: node.title,
                    onOpenNode = onOpenNode,
                    onBrowse = onBrowse
                )
            }
        }
    }
}

/** Sticky section header for one project directory, with its node count. */
@Composable
private fun ProjectHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

/** One node row: title + kind tag, project/agent meta, cwd, status badge and Files button. */
@Composable
private fun NodeCard(
    node: NodeRow,
    status: NodeStatus,
    name: String,
    onOpenNode: (NodeRow) -> Unit,
    onBrowse: (NodeRow) -> Unit
) {
    Card(
        onClick = { onOpenNode(node) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                // Title row: name + kind tag (non-terminal nodes get a label).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (node.kind != "terminal") {
                        Spacer(Modifier.width(6.dp))
                        KindTag(node.kind)
                    }
                }
                // Project / agent line.
                val meta = listOfNotNull(
                    node.projectName.ifBlank { null },
                    node.agentId?.let { "agent: $it" }
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

@Composable
private fun InboxTab(
    state: RelayUiState,
    onAnswer: (nodeId: String, pendingId: String?, decision: String) -> Unit,
    onAnswerQuestion: (nodeId: String, text: String) -> Unit
) {
    val actionable = state.inbox.filter { it.kind != "done" }
    if (actionable.isEmpty()) {
        EmptyState("Nothing needs you", "When an agent needs approval or asks a question, it lands here.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

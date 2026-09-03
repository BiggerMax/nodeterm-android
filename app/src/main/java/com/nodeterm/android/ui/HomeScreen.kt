package com.nodeterm.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nodeterm.android.R
import com.nodeterm.android.core.model.InboxEvent
import com.nodeterm.android.core.model.NodeStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: RelayUiState,
    onOpenNode: (NodeRow) -> Unit,
    onAnswer: (nodeId: String, pendingId: String?, decision: String) -> Unit,
    onAnswerQuestion: (nodeId: String, text: String) -> Unit,
    onOpenBoard: () -> Unit,
    onBrowse: (NodeRow) -> Unit,
    onSettings: () -> Unit,
    onRepair: (() -> Unit)? = null,
    /** Returns true when the node was actually hidden (false = already hidden, e.g. double-swipe). */
    onDeleteNode: (NodeRow) -> Boolean,
    onMoveNode: (nodeId: String, targetNodeId: String) -> Unit,
    onReorderCommit: () -> Unit,
    onClearInbox: () -> Unit,
    onDismissInboxEvent: (eventId: String) -> Unit,
    onRefresh: () -> Unit,
    onRestoreNode: (NodeRow) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    // The mobile ⌘K — search anywhere (nodes, project files, actions) from the header.
    var paletteOpen by remember { mutableStateOf(false) }
    // Swipe-delete undo — owned here because the activity-level snackbar has no action button.
    val deleteSnackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Swipe-delete with one-tap Undo (the node keeps running on the host — we only hid it).
    val hiddenMessage = stringResource(R.string.node_hidden_snackbar)
    val undoLabel = stringResource(R.string.undo)
    val deleteNode: (NodeRow) -> Unit = { node ->
        // Only offer Undo when the node was actually hidden — a double-swipe on an already
        // hidden node is a no-op (hideNode returns false) and would show a dead Undo.
        if (onDeleteNode(node)) {
            scope.launch {
                val result = deleteSnackbar.showSnackbar(
                    message = hiddenMessage,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) onRestoreNode(node)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                            Text(stringResource(R.string.connected_to_host), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.phase == Phase.CONNECTING -> {
                            Text(stringResource(R.string.connecting_to_host), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            PulsingDot(MaterialTheme.colorScheme.error, steady = true)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.disconnected), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            // NOTE: the docked recovery banner below owns the Re-pair CTA when disconnected — a
            // header button here would be a third duplicate (banner + empty state + header).
            IconButton(onClick = { paletteOpen = true }) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.jump_to), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onOpenBoard) {
                Icon(Icons.Outlined.ViewKanban, contentDescription = stringResource(R.string.board_cd), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_cd), modifier = Modifier.size(20.dp))
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.nodes_count, state.nodes.size)) })
            val needsCount = state.inbox.count { it.kind != "done" }
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = {
                    Text(
                        stringResource(R.string.needs_you_tab, needsCount),
                        color = if (needsCount > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (needsCount > 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            )
        }

        when (tab) {
            0 -> NodesTab(state, onOpenNode, onBrowse, onRepair, deleteNode, onMoveNode, onReorderCommit, onRefresh)
            1 -> InboxTab(state, onAnswer, onAnswerQuestion, onClearInbox, onDismissInboxEvent, onRefresh)
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
                    text = state.disconnectReason ?: stringResource(R.string.connection_to_host_lost),
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (onRepair != null) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onRepair) {
                        Text(stringResource(R.string.re_pair), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        }
        // Floating undo snackbar for swipe-delete.
        SnackbarHost(deleteSnackbar, Modifier.align(Alignment.BottomCenter))
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
    onRepair: (() -> Unit)?,
    onDeleteNode: (NodeRow) -> Unit,
    onMoveNode: (nodeId: String, targetNodeId: String) -> Unit,
    onReorderCommit: () -> Unit,
    onRefresh: () -> Unit
) {
    // Group the flat node list by project directory. nodeterm's "project" is a directory on the
    // host, and `projectName` is its display name (fall back to the node's cwd when it is blank).
    // The flat list is already ordered (the user's drag order, else status then title) in the
    // ViewModel, so `groupBy` preserves that order within each group; groups are alphabetical.
    // projectName → canvas colour for the section dots. Keyed exactly like the group key below
    // (name, falling back to cwd, then id) so blank-name projects still get their colour.
    val projectColors = remember(state.projects) {
        state.projects.associate { p ->
            (p.name.ifBlank { p.cwd?.ifBlank { null } ?: p.id }) to p.color
        }
    }
    val otherLabel = stringResource(R.string.other)
    val groups = remember(state.nodes, otherLabel) {
        state.nodes
            .groupBy { it.projectName.ifBlank { it.cwd ?: otherLabel } }
            .toList()
            .sortedBy { (name, _) -> name.lowercase() }
    }

    // Long-press drag to reorder within a project group: one card lifts and follows the finger
    // while the list live-reorders under it (the ViewModel's nodeOrder map is the source of
    // truth; a per-frame move updates it in memory and a poll/restart keeps the final order).
    val listState = rememberLazyListState()
    var draggedNodeId by remember { mutableStateOf<String?>(null) }
    /** Raw finger delta since the drag started (never corrected, unlike [draggedTranslation]). */
    var dragAccum by remember { mutableIntStateOf(0) }
    /** The lifted card's translation — recomputed EVERY frame from [dragAccum] so list reorders
     *  and edge-scroll shifting the layout underneath never double-counts into the visuals. */
    var draggedTranslation by remember { mutableIntStateOf(0) }
    /** The dragged item's layout offset when its drag started (baseline for the correction). */
    var initialDraggedOffset by remember { mutableIntStateOf(0) }
    /** Coroutine scope for edge auto-scroll — [listState.scrollBy] is suspend and the drag
     *  callback is not. */
    val dragScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Pull-to-refresh indicator state (auto-hides shortly after the refresh is kicked off).
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()

    fun Modifier.nodeDragHandle(nodeId: String): Modifier = this
        .zIndex(if (draggedNodeId == nodeId) 1f else 0f)
        .graphicsLayer {
            if (draggedNodeId == nodeId) translationY = draggedTranslation.toFloat()
        }
        .pointerInput(nodeId) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    // Lifting a card deserves a tactile tick.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    draggedNodeId = nodeId
                    dragAccum = 0
                    draggedTranslation = 0
                    initialDraggedOffset = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == nodeId }?.offset ?: 0
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragAccum += dragAmount.y.roundToInt()
                    val layoutInfo = listState.layoutInfo
                    val dragged = layoutInfo.visibleItemsInfo.firstOrNull { it.key == nodeId }
                        ?: return@detectDragGesturesAfterLongPress
                    val corrected = dragAccum - (dragged.offset - initialDraggedOffset)
                    draggedTranslation = corrected
                    val centerY = dragged.offset + corrected + dragged.size / 2f
                    // Edge auto-scroll so the card can reach the off-screen end of a long group.
                    val threshold = 64.dp.toPx()
                    if (centerY < layoutInfo.viewportStartOffset + threshold) {
                        dragScope.launch { listState.scrollBy(-dragged.size * 0.5f) }
                    } else if (centerY > layoutInfo.viewportEndOffset - threshold) {
                        dragScope.launch { listState.scrollBy(dragged.size * 0.5f) }
                    }
                    // The item under the pointer becomes the drop target; project headers and
                    // other groups are rejected by the ViewModel's group clamping, so drags can
                    // never move a node across project sections.
                    val over = layoutInfo.visibleItemsInfo.firstOrNull {
                        it.key != nodeId && centerY.toInt() in it.offset..(it.offset + it.size)
                    }
                    if (over != null) onMoveNode(nodeId, over.key.toString())
                },
                onDragEnd = {
                    draggedNodeId = null
                    dragAccum = 0
                    draggedTranslation = 0
                    onReorderCommit()
                },
                onDragCancel = {
                    draggedNodeId = null
                    dragAccum = 0
                    draggedTranslation = 0
                    onReorderCommit()
                }
            )
        }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            onRefresh()
            refreshScope.launch {
                delay(700)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        if (state.nodes.isEmpty()) {
            if (state.phase == Phase.DISCONNECTED) {
                EmptyState(
                    title = stringResource(R.string.connection_dropped),
                    subtitle = state.disconnectReason
                        ?: stringResource(R.string.re_pair_empty_subtitle),
                    icon = Icons.Outlined.CloudOff,
                    actionLabel = stringResource(R.string.re_pair),
                    onAction = onRepair
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.no_nodes_yet),
                    subtitle = stringResource(R.string.no_nodes_yet_subtitle),
                    icon = Icons.Outlined.Terminal
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
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
                        // Swipe left reveals quick actions (Files / Hide) — nothing is deleted by
                        // the swipe itself, only by an explicit button tap. Long-press drag reorders.
                        SwipeActionCard(
                            modifier = Modifier.nodeDragHandle(node.nodeId),
                            actionContent = { close ->
                                if (node.cwd != null) {
                                    SwipeActionButton(
                                        icon = Icons.Outlined.Folder,
                                        label = stringResource(R.string.files),
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        onClick = {
                                            close()
                                            onBrowse(node)
                                        }
                                    )
                                }
                                SwipeActionButton(
                                    icon = Icons.Outlined.Delete,
                                    label = stringResource(R.string.hide),
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    onClick = {
                                        close()
                                        onDeleteNode(node)
                                    }
                                )
                            }
                        ) {
                            NodeCard(
                                node = node,
                                status = state.status[node.nodeId] ?: NodeStatus.IDLE,
                                name = state.nodeNames[node.nodeId] ?: node.title,
                                // What the agent is doing right now ("Running npm test") — the mirror's
                                // per-node `now` block; desktop parity for the node-card meta line.
                                activity = now?.activity,
                                // Per-node context-window fill — the same meter the desktop paints on
                                // agent cards, now on the phone's list too.
                                contextPercent = now?.contextPercent,
                                onOpenNode = onOpenNode,
                                onBrowse = onBrowse
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Swipe-to-reveal-actions wrapper (never deletes directly): a horizontal swipe slides the
 * content aside to expose a row of action buttons that must be tapped. Past a threshold the
 * content snaps open; dragging back or tapping the (shifted) content closes it again. The
 * action row sits behind the content, so buttons are only reachable while open.
 */
@Composable
private fun SwipeActionCard(
    /** Row of revealed action buttons; [close] hides the reveal when an action is taken. */
    actionContent: @Composable RowScope.(close: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    actionWidth: Dp = 172.dp,
    content: @Composable () -> Unit
) {
    val maxRevealPx = with(LocalDensity.current) { actionWidth.toPx() }
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        // Keyed on maxRevealPx so a density change restarts the drag closure with fresh bounds.
        modifier = modifier.pointerInput(maxRevealPx) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        reveal.snapTo((reveal.value + dragAmount).coerceIn(-maxRevealPx, 0f))
                    }
                },
                onDragEnd = {
                    scope.launch {
                        reveal.animateTo(
                            if (reveal.value < -maxRevealPx * 0.35f) -maxRevealPx else 0f
                        )
                    }
                },
                onDragCancel = {
                    scope.launch { reveal.animateTo(0f) }
                }
            )
        }
    ) {
        // The action buttons, revealed at the end once the content slides away.
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // close() is handed to the buttons so an action also retracts the reveal.
                actionContent { scope.launch { reveal.animateTo(0f) } }
            }
        }
        // The content slides left to expose the actions.
        Box(Modifier.offset { IntOffset(reveal.value.roundToInt(), 0) }) {
            content()
        }
        // While open, tapping the (shifted) content just closes the reveal instead of acting.
        if (reveal.value < -1f) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset { IntOffset(reveal.value.roundToInt(), 0) }
                    .clickable { scope.launch { reveal.animateTo(0f) } }
            )
        }
    }
}

/** One revealed action button: icon + label on a coloured, rounded, full-height tile. */
@Composable
private fun SwipeActionButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = contentColor)
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
 * nodes, status badge, Files button and a "⋯" button that opens the quick-actions sheet (the
 * mobile mirror of the desktop's right-click node menu — long-press now drags to reorder).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeCard(
    node: NodeRow,
    status: NodeStatus,
    name: String,
    activity: String?,
    contextPercent: Int?,
    onOpenNode: (NodeRow) -> Unit,
    onBrowse: (NodeRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenNode(node) },
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
                        Text(stringResource(R.string.files), fontSize = 12.sp)
                    }
                }
                // Quick actions (Open terminal / Browse files / Copy path) — reachable from the
                // "⋯" button now that long-press is taken by drag-to-reorder.
                IconButton(onClick = { showActions = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.more_actions),
                        modifier = Modifier.size(18.dp)
                    )
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
                ) { Text(stringResource(R.string.open_terminal)) }
                if (node.cwd != null) {
                    TextButton(
                        onClick = {
                            showActions = false
                            onBrowse(node)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.browse_files)) }
                    TextButton(
                        onClick = {
                            val path = node.cwd.orEmpty()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Path", path))
                            Toast.makeText(
                                context,
                                context.getString(R.string.path_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                            showActions = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.copy_path)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxTab(
    state: RelayUiState,
    onAnswer: (nodeId: String, pendingId: String?, decision: String) -> Unit,
    onAnswerQuestion: (nodeId: String, text: String) -> Unit,
    onClearAll: () -> Unit,
    onDismissEvent: (eventId: String) -> Unit,
    onRefresh: () -> Unit
) {
    val actionable = state.inbox.filter { it.kind != "done" }
    // Pull-to-refresh indicator state (auto-hides shortly after the refresh is kicked off).
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            onRefresh()
            refreshScope.launch {
                delay(700)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        if (actionable.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.nothing_needs_you),
                subtitle = stringResource(R.string.nothing_needs_you_subtitle),
                icon = Icons.Outlined.CheckCircle
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                // One-tap "Clear all": dismisses every pending item AND clears the notification shade.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.pending_count, actionable.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearAll) {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.clear_all))
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(actionable, key = { it.id }) { event ->
                        // Swipe reveals a Dismiss button — an explicit tap, not the swipe itself
                        // (the host keeps the pending event until answered or dismissed).
                        SwipeActionCard(
                            actionWidth = 84.dp,
                            actionContent = { close ->
                                SwipeActionButton(
                                    icon = Icons.Outlined.CheckCircle,
                                    label = stringResource(R.string.dismiss),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = {
                                        close()
                                        onDismissEvent(event.id)
                                    }
                                )
                            }
                        ) {
                            InboxCard(
                                event = event,
                                nodeName = state.nodeNames[event.nodeId] ?: state.nodes.firstOrNull { it.nodeId == event.nodeId }?.title ?: event.nodeId,
                                onAnswer = onAnswer,
                                onAnswerQuestion = onAnswerQuestion
                            )
                        }
                    }
                }
            }
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
                    text = if (event.kind == "approval") stringResource(R.string.approval) else stringResource(R.string.question),
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
                stringResource(R.string.on_host, nodeName),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            when (event.kind) {
                "approval" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAnswer(event.nodeId, event.pendingId, "allow") },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.approve)) }
                    TextButton(
                        onClick = { onAnswer(event.nodeId, event.pendingId, "deny") },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.deny)) }
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
                            stringResource(R.string.answer_in_terminal),
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

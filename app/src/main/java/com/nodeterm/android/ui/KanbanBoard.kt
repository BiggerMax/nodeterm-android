package com.nodeterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nodeterm.android.core.model.BoardLogAuthor
import com.nodeterm.android.core.model.CanvasNode
import com.nodeterm.android.core.model.Kanban
import com.nodeterm.android.core.model.KanbanCardMeta
import com.nodeterm.android.core.model.KanbanColumn
import com.nodeterm.android.core.model.KanbanLabel
import com.nodeterm.android.core.model.NodeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PRIORITIES = listOf(
    "low" to "Low",
    "medium" to "Medium",
    "high" to "High",
    "urgent" to "Urgent"
)

/** A card currently being dragged, positioned in window coordinates. */
private data class DragState(val id: String, val pos: Offset)

@Composable
fun KanbanBoard(
    board: KanbanView,
    nodes: List<CanvasNode>,
    status: Map<String, NodeStatus>,
    previews: Map<String, String>,
    nodeNames: Map<String, String>,
    onOpenNode: (NodeRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val allNodes = remember(nodes) { nodes.associateBy { it.id } }
    val columnDefs = remember(board) {
        board.columns + KanbanColumn(id = Kanban.UNGROUPED_ID, title = Kanban.UNGROUPED_TITLE, color = "gray")
    }
    val hostAssignments = board.assignments
    val hostKey = remember(board.columns, hostAssignments, nodes) {
        board.columns.joinToString("|") { it.id + ":" + it.title } + "::" +
            hostAssignments.toList().sortedBy { it.first }.joinToString() + "::" +
            nodes.joinToString(",") { it.id }
    }
    var assignmentOverride by remember(hostKey) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var metaOverride by remember(hostKey) { mutableStateOf<Map<String, KanbanCardMeta>>(emptyMap()) }
    var comments by remember(hostKey) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    fun effectiveColumn(id: String): String? {
        val v = assignmentOverride[id]
        return if (v == Kanban.UNGROUPED_ID) null else (v ?: hostAssignments[id])
    }
    fun columnNodes(colId: String): List<CanvasNode> =
        if (colId == Kanban.UNGROUPED_ID) nodes.filter { effectiveColumn(it.id) == null }
        else nodes.filter { effectiveColumn(it.id) == colId }
    fun metaFor(id: String): KanbanCardMeta =
        metaOverride[id] ?: board.meta[id] ?: KanbanCardMeta(nodeId = id)
    fun cardTitle(id: String): String = nodeNames[id] ?: allNodes[id]?.title ?: id
    fun move(id: String, colId: String?) {
        assignmentOverride = assignmentOverride + (id to (colId ?: Kanban.UNGROUPED_ID))
    }
    fun updateMeta(id: String, t: (KanbanCardMeta) -> KanbanCardMeta) {
        metaOverride = metaOverride + (id to t(metaFor(id)))
    }

    var drag by remember { mutableStateOf<DragState?>(null) }
    var pendingTarget by remember { mutableStateOf<String?>(null) }
    var columnRects by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    var boardRect by remember { mutableStateOf<Rect?>(null) }
    var openCardId by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val r = coords.boundsInWindow()
                    if (boardRect != r) boardRect = r
                }
                // Freeze horizontal scroll while a card is being dragged so the finger stays put.
                .then(if (drag == null) Modifier.horizontalScroll(scrollState) else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            columnDefs.forEach { col ->
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .onGloballyPositioned { coords ->
                            val r = coords.boundsInWindow()
                            if (columnRects[col.id] != r) columnRects = columnRects + (col.id to r)
                        }
                ) {
                    KanbanColumnHeader(col.title, col.id, columnNodes(col.id).size)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (pendingTarget == col.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        columnNodes(col.id).forEach { node ->
                            KanbanCard(
                                id = node.id,
                                title = cardTitle(node.id),
                                kind = node.kind,
                                status = status[node.id] ?: NodeStatus.IDLE,
                                meta = metaFor(node.id),
                                labels = board.labels,
                                preview = previews[node.id].orEmpty(),
                                onTap = { openCardId = node.id },
                                onDragStart = { winPos ->
                                    drag = DragState(node.id, winPos)
                                    pendingTarget = col.id
                                },
                                onDrag = { winPos ->
                                    drag = drag?.copy(pos = winPos)
                                    pendingTarget = columnRects.entries
                                        .firstOrNull { it.value.contains(winPos) }?.key
                                        ?: col.id
                                },
                                onDragEnd = {
                                    drag?.let { d -> move(d.id, pendingTarget) }
                                    drag = null
                                    pendingTarget = null
                                },
                                onDragCancel = {
                                    drag = null
                                    pendingTarget = null
                                }
                            )
                        }
                    }
                }
            }
        }

        // Floating avatar for the card being dragged (local-only move indicator).
        drag?.let { d ->
            val topLeft = boardRect?.topLeft ?: Offset.Zero
            val x = (((d.pos.x - topLeft.x) - 60f).toInt()).coerceAtLeast(0)
            val y = ((d.pos.y - topLeft.y).toInt()).coerceAtLeast(0)
            Box(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .size(120.dp, 54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                Text(
                    cardTitle(d.id),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // Card detail modal.
    openCardId?.let { id ->
        val node = allNodes[id]
        if (node != null) {
            KanbanCardModal(
                id = id,
                title = cardTitle(id),
                kind = node.kind,
                cwd = node.cwd,
                status = status[id] ?: NodeStatus.IDLE,
                meta = metaFor(id),
                labels = board.labels,
                comments = comments[id] ?: emptyList(),
                onAddComment = { text ->
                    comments = comments + (id to ((comments[id] ?: emptyList()) + text))
                },
                onSetPriority = { p -> updateMeta(id) { it.copy(priority = p) } },
                onToggleAssignee = { name ->
                    updateMeta(id) { cur ->
                        val had = (cur.assignees ?: emptyList()).any { it.name == name }
                        val assignees = if (had) {
                            (cur.assignees ?: emptyList()).filter { it.name != name }
                        } else {
                            (cur.assignees ?: emptyList()) + BoardLogAuthor(name = name)
                        }
                        cur.copy(assignees = assignees)
                    }
                },
                onToggleLabel = { lid ->
                    updateMeta(id) { cur ->
                        val had = (cur.labels ?: emptyList()).contains(lid)
                        val labels = if (had) (cur.labels ?: emptyList()).filter { it != lid }
                        else (cur.labels ?: emptyList()) + lid
                        cur.copy(labels = labels)
                    }
                },
                onClearDue = { updateMeta(id) { it.copy(dueAt = null) } },
                onClose = { openCardId = null },
                onOpenTerminal = {
                    openCardId = null
                    onOpenNode(
                        NodeRow(
                            nodeId = node.id,
                            title = node.title,
                            kind = node.kind,
                            agentId = node.agentId,
                            cwd = node.cwd,
                            projectName = board.activeProjectName,
                            color = node.color
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun KanbanColumnHeader(title: String, colId: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(count.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KanbanCard(
    id: String,
    title: String,
    kind: String,
    status: NodeStatus,
    meta: KanbanCardMeta,
    labels: List<KanbanLabel>,
    preview: String,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    var cardRect by remember { mutableStateOf<Rect?>(null) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                if (cardRect != r) cardRect = r
            }
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val tl = cardRect?.topLeft ?: return@detectDragGesturesAfterLongPress
                        onDragStart(tl + offset)
                    },
                    onDrag = { change, _ ->
                        val tl = cardRect?.topLeft ?: return@detectDragGesturesAfterLongPress
                        onDrag(tl + change.position)
                        change.consume()
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
                )
            }
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (kind != "terminal") {
                    Spacer(Modifier.width(6.dp))
                    KindTag(kind)
                }
                Spacer(Modifier.width(6.dp))
                StatusBadge(status)
            }
            val chips = remember(meta, labels) {
                val list = mutableListOf<String>()
                meta.priority?.let { list += priorityLabel(it) }
                (meta.assignees ?: emptyList()).take(2).forEach { list += it.name }
                (meta.labels ?: emptyList()).take(2).mapNotNull { lid -> labels.find { it.id == lid }?.name }
                    .forEach { list += it }
                list
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    chips.forEach { c ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                c,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    preview,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KanbanCardModal(
    id: String,
    title: String,
    kind: String,
    cwd: String?,
    status: NodeStatus,
    meta: KanbanCardMeta,
    labels: List<KanbanLabel>,
    comments: List<String>,
    onAddComment: (String) -> Unit,
    onSetPriority: (String?) -> Unit,
    onToggleAssignee: (String) -> Unit,
    onToggleLabel: (String) -> Unit,
    onClearDue: () -> Unit,
    onClose: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    var commentText by remember(id) { mutableStateOf("") }
    var newAssignee by remember(id) { mutableStateOf("") }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (kind != "terminal") KindTag(kind)
                        Spacer(Modifier.width(6.dp))
                        StatusBadge(status)
                    }
                }
                TextButton(onClick = onClose) { Text("Done") }
            }
            cwd?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Priority")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRIORITIES.forEach { (k, label) ->
                    val sel = meta.priority == k
                    FilterChip(
                        selected = sel,
                        onClick = { onSetPriority(if (sel) null else k) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            if (meta.priority != null) {
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = { onSetPriority(null) }, modifier = Modifier.height(28.dp)) {
                    Text("Clear priority", fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("Members")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (meta.assignees ?: emptyList()).forEach { a ->
                    FilterChip(selected = true, onClick = { onToggleAssignee(a.name) }, label = { Text(a.name, fontSize = 11.sp) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newAssignee,
                    onValueChange = { newAssignee = it },
                    placeholder = { Text("Add member", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(52.dp),
                    textStyle = TextStyle(fontSize = 12.sp)
                )
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = {
                        if (newAssignee.isNotBlank()) {
                            onToggleAssignee(newAssignee.trim())
                            newAssignee = ""
                        }
                    }
                ) { Text("Add") }
            }

            if (labels.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("Labels")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { l ->
                        val on = (meta.labels ?: emptyList()).contains(l.id)
                        FilterChip(selected = on, onClick = { onToggleLabel(l.id) }, label = { Text(l.name, fontSize = 11.sp) })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("Due")
            val due = meta.dueAt
            if (due != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDue(due), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearDue, modifier = Modifier.height(28.dp)) { Text("Clear", fontSize = 11.sp) }
                }
            } else {
                Text("No due date", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("Comments")
            if (comments.isEmpty()) {
                Text("No comments yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                comments.forEach { c ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) { Text(c, fontSize = 12.sp) }
                }
            }
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it },
                placeholder = { Text("Add a comment", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                textStyle = TextStyle(fontSize = 12.sp)
            )
            TextButton(
                onClick = {
                    if (commentText.isNotBlank()) {
                        onAddComment(commentText.trim())
                        commentText = ""
                    }
                }
            ) { Text("Comment") }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) {
                Text("Open terminal", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.3.sp
    )
}

private fun priorityLabel(p: String): String = when (p) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "urgent" -> "Urgent"
    else -> p
}

private fun formatDue(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))



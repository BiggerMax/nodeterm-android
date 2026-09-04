package com.nodeterm.android.ui

import com.nodeterm.android.core.model.InboxEvent
import com.nodeterm.android.core.model.Kanban
import com.nodeterm.android.core.model.Project

/**
 * Pure read-models for the session UI — extracted from RelayViewModel so they are unit-testable
 * without Android (mirrors the `NodeListOrder` pattern). The session core applies these in its
 * event funnel; nothing here touches transport, stores or coroutines.
 */

/**
 * The most recent inbox snippet per node (detail preferred, title as fallback), shown on board
 * cards as a mini terminal preview. Newest wins on `ts`; equal timestamps defer to list order
 * (the mirror publishes events newest-first, so the later entry is treated as newer).
 */
internal fun latestSnippets(inbox: List<InboxEvent>): Map<String, String> {
    val latestTs = HashMap<String, Long>()
    val snippets = HashMap<String, String>()
    for (ev in inbox) {
        if (ev.nodeId.isBlank()) continue
        val snippet = ev.detail?.takeIf { it.isNotBlank() } ?: ev.title.takeIf { it.isNotBlank() } ?: continue
        val prev = latestTs[ev.nodeId]
        if (prev == null || ev.ts >= prev) {
            latestTs[ev.nodeId] = ev.ts
            snippets[ev.nodeId] = snippet.trim()
        }
    }
    return snippets
}

/**
 * Flatten the host workspace's projects into a flat [NodeRow] list in host order. No filtering or
 * ordering is applied here — the caller layers `orderNodes` (dismissed + custom drag order) on top.
 */
internal fun buildNodesRaw(projects: List<Project>): List<NodeRow> {
    val nodes = mutableListOf<NodeRow>()
    projects.forEach { project ->
        project.nodes.forEach { node ->
            nodes.add(
                NodeRow(
                    nodeId = node.id,
                    title = node.title,
                    kind = node.kind,
                    agentId = node.agentId,
                    cwd = node.cwd,
                    projectName = project.name,
                    color = node.color
                )
            )
        }
    }
    return nodes
}

/** Resolve the active project's Trello-style kanban board for the mobile board view. */
internal fun buildKanban(
    projects: List<Project>,
    activeProjectId: String,
    sessionNodeIds: List<String>
): KanbanView? {
    val active = projects.firstOrNull { p -> p.id == activeProjectId } ?: projects.firstOrNull()
        ?: return null
    val k = active.kanban
    val columns = Kanban.columns(k)
    val assignments = k?.assignments
        ?.filter { a -> columns.any { c -> c.id == a.columnId } }
        ?.associateTo(LinkedHashMap()) { it.nodeId to it.columnId }
        ?: emptyMap()
    return KanbanView(
        activeProjectName = active.name.ifBlank { active.id },
        columns = columns,
        assignments = assignments,
        ungrouped = Kanban.unassigned(k, sessionNodeIds),
        meta = (k?.meta ?: emptyList()).associateBy { it.nodeId },
        labels = k?.labels ?: emptyList()
    )
}

internal fun normalizeSnapshotNewlines(bytes: ByteArray): ByteArray {
    // capture-pane joins rows with bare \n; feed \r\n so each row starts at col 0.
    // Normalise any existing CRLF first so it is never double-converted.
    return String(bytes, Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace("\n", "\r\n")
        .toByteArray(Charsets.UTF_8)
}

internal fun parentPath(path: String): String? {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    val idx = trimmed.lastIndexOf('/')
    if (idx < 0) return null
    val parent = trimmed.substring(0, idx)
    return parent.ifEmpty { "/" }
}

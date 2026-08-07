package com.nodeterm.android.core.model

/**
 * Pure kanban board readers — the read side of the desktop's `kanban.ts`. Cards are the
 * project's SESSION NODES; the board stores only column assignments, so a session with no
 * (or dangling) assignment sits in the virtual Ungrouped column. Every function tolerates an
 * absent/malformed board (hand-edited file) exactly as the desktop does.
 */
object Kanban {

    /** Reserved id for the virtual Ungrouped column (never persisted). */
    const val UNGROUPED_ID = "__ungrouped__"
    const val UNGROUPED_TITLE = "Ungrouped"

    /** Default board for a project whose file has no `kanban` yet (not written to disk). */
    fun defaultKanban(): KanbanProject = KanbanProject(
        columns = listOf(
            KanbanColumn(id = "kcol-todo", title = "To Do", color = ""),
            KanbanColumn(id = "kcol-progress", title = "In Progress", color = ""),
            KanbanColumn(id = "kcol-done", title = "Done", color = "")
        )
    )

    /** The user's real columns, or the default three when the board is absent/empty. */
    fun columns(k: KanbanProject?): List<KanbanColumn> =
        if (k == null || k.columns.isEmpty()) defaultKanban().columns else k.columns

    /** Node ids assigned to `columnId`, in board order. */
    fun assignedTo(k: KanbanProject?, columnId: String): List<String> =
        k?.assignments?.filter { it.columnId == columnId }?.map { it.nodeId } ?: emptyList()

    /** Ids from `sessionIds` with no live assignment, in canvas order → virtual Ungrouped column. */
    fun unassigned(k: KanbanProject?, sessionIds: List<String>): List<String> {
        if (k == null) return sessionIds
        val cols = k.columns.map { it.id }.toSet()
        val assigned = k.assignments.filter { cols.contains(it.columnId) }.map { it.nodeId }.toSet()
        return sessionIds.filter { !assigned.contains(it) }
    }

    /** Column a node is assigned to; undefined when unassigned/dangling → Ungrouped. */
    fun columnForNode(k: KanbanProject?, nodeId: String): KanbanColumn? =
        k?.assignments?.find { it.nodeId == nodeId }?.let { a ->
            k.columns.find { it.id == a.columnId }
        }

    /** Tolerant read of a card's metadata. */
    fun cardMeta(k: KanbanProject?, nodeId: String): KanbanCardMeta? =
        k?.meta?.find { it.nodeId == nodeId }

    /** Resolve a board-label id to its definition (dangling-safe). */
    fun labelById(k: KanbanProject?, id: String): KanbanLabel? =
        k?.labels?.find { it.id == id }
}

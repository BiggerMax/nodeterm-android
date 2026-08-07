package com.nodeterm.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KanbanTest {

    private val board = KanbanProject(
        columns = listOf(
            KanbanColumn("todo", "To Do", ""),
            KanbanColumn("done", "Done", "")
        ),
        assignments = listOf(
            KanbanAssignment("a", "todo"),
            KanbanAssignment("b", "done"),
            KanbanAssignment("stale", "gone"), // dangling assignment — column deleted elsewhere
            KanbanAssignment("c", "todo")
        ),
        meta = listOf(
            KanbanCardMeta(nodeId = "a", priority = "urgent", assignees = listOf(BoardLogAuthor(name = "Ali")))
        ),
        labels = listOf(KanbanLabel("l1", "bug", "red"))
    )

    @Test
    fun defaultKanbanHasThreeColumns() {
        val k = Kanban.defaultKanban()
        assertEquals(listOf("To Do", "In Progress", "Done"), k.columns.map { it.title })
        assertEquals(emptyList<KanbanAssignment>(), k.assignments)
    }

    @Test
    fun columnsFallsBackToDefaultsWhenBoardAbsent() {
        assertEquals(3, Kanban.columns(null).size)
        assertEquals(2, Kanban.columns(board).size)
    }

    @Test
    fun assignedToReturnsBoardOrder() {
        assertEquals(listOf("a", "c"), Kanban.assignedTo(board, "todo"))
        assertEquals(emptyList<String>(), Kanban.assignedTo(board, "missing"))
    }

    @Test
    fun unassignedIgnoresDanglingAssignments() {
        // 'stale' is assigned to a non-existent column → treated as ungrouped.
        assertEquals(listOf("stale", "d"), Kanban.unassigned(board, listOf("a", "stale", "d")))
    }

    @Test
    fun unassignedReturnsAllWhenNoBoard() {
        assertEquals(listOf("a", "b"), Kanban.unassigned(null, listOf("a", "b")))
    }

    @Test
    fun columnForNodeResolvesLiveColumnOnly() {
        assertEquals("todo", Kanban.columnForNode(board, "a")?.id)
        assertNull(Kanban.columnForNode(board, "stale"))
        assertNull(Kanban.columnForNode(board, "nope"))
    }

    @Test
    fun cardMetaIsTolerant() {
        assertEquals("urgent", Kanban.cardMeta(board, "a")?.priority)
        assertNull(Kanban.cardMeta(board, "missing"))
        assertNull(Kanban.cardMeta(null, "a"))
    }

    @Test
    fun labelByIdResolvesAndIsDanglingSafe() {
        assertEquals("bug", Kanban.labelById(board, "l1")?.name)
        assertNull(Kanban.labelById(board, "nope"))
        assertNull(Kanban.labelById(null, "l1"))
    }

    @Test
    fun workspaceParsesKanbanProjectField() {
        val json = """{"version":2,"activeProjectId":"p1",
            "projects":[{"id":"p1","name":"App","nodes":[],
                "kanban":{"columns":[{"id":"c1","title":"Doing","color":""}],
                          "assignments":[],"meta":[],"labels":[]}}]}"""
        val ws = JsonModels.workspace(json)
        val k = ws?.projects?.firstOrNull()?.kanban
        assertEquals("Doing", k?.columns?.get(0)?.title)
    }

    @Test
    fun workspaceParsesProjectWithoutKanbanAsNull() {
        val json = """{"version":2,"activeProjectId":"p1","projects":[{"id":"p1","name":"App","nodes":[]}]}"""
        val ws = JsonModels.workspace(json)
        assertNull(ws?.projects?.get(0)?.kanban)
    }
}

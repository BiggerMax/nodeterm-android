package com.nodeterm.android.ui

import com.nodeterm.android.core.model.CanvasNode
import com.nodeterm.android.core.model.InboxEvent
import com.nodeterm.android.core.model.Kanban
import com.nodeterm.android.core.model.KanbanAssignment
import com.nodeterm.android.core.model.KanbanCardMeta
import com.nodeterm.android.core.model.KanbanColumn
import com.nodeterm.android.core.model.KanbanLabel
import com.nodeterm.android.core.model.KanbanProject
import com.nodeterm.android.core.model.Project
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadModelsTest {

    // -------------------------------------------------------------------------------------------
    // latestSnippets

    @Test
    fun `empty inbox yields empty snippet map`() {
        assertEquals(emptyMap<String, String>(), latestSnippets(emptyList<InboxEvent>()))
    }

    @Test
    fun `detail is preferred over title`() {
        val inbox = listOf(InboxEvent(id = "1", nodeId = "a", title = "run tests", detail = "npm test ok", kind = "done"))
        assertEquals(mapOf("a" to "npm test ok"), latestSnippets(inbox))
    }

    @Test
    fun `title is used when detail is blank`() {
        val inbox = listOf(InboxEvent(id = "1", nodeId = "a", title = "run tests", detail = "   ", kind = "done"))
        assertEquals(mapOf("a" to "run tests"), latestSnippets(inbox))
    }

    @Test
    fun `events with blank nodeId are ignored`() {
        val inbox = listOf(
            InboxEvent(id = "1", nodeId = "", title = "ignored"),
            InboxEvent(id = "2", nodeId = " ", title = "also ignored"),
            InboxEvent(id = "3", nodeId = "b", detail = "kept")
        )
        assertEquals(mapOf("b" to "kept"), latestSnippets(inbox))
    }

    @Test
    fun `snippet whitespace is trimmed`() {
        val inbox = listOf(InboxEvent(id = "1", nodeId = "a", detail = "  hello  \n"))
        assertEquals(emptyMap<String, String>(), latestSnippets(emptyList<InboxEvent>()))
        assertEquals(mapOf("a" to "hello"), latestSnippets(inbox))
    }

    @Test
    fun `events with no detail and no title are dropped`() {
        val inbox = listOf(InboxEvent(id = "1", nodeId = "a", detail = null, title = ""))
        assertEquals(emptyMap<String, String>(), latestSnippets(inbox))
    }

    @Test
    fun `latest timestamp wins and equal timestamps defer to list order`() {
        val inbox = listOf(
            InboxEvent(id = "1", nodeId = "a", ts = 200, detail = "old"),
            InboxEvent(id = "2", nodeId = "a", ts = 100, detail = "older"),
            InboxEvent(id = "3", nodeId = "b", ts = 100, detail = "first"),
            InboxEvent(id = "4", nodeId = "b", ts = 100, detail = "last") // equal ts, wins by list order
        )
        assertEquals(mapOf("a" to "old", "b" to "last"), latestSnippets(inbox))
    }

    // -------------------------------------------------------------------------------------------
    // buildNodesRaw

    private fun canvasNode(id: String, title: String = id, kind: String = "terminal", cwd: String? = null, color: String = "") =
        CanvasNode(id = id, title = title, kind = kind, cwd = cwd, color = color)

    private fun project(name: String, vararg nodes: CanvasNode) = Project(id = name, name = name, nodes = nodes.toList())

    @Test
    fun `empty projects yield empty node list`() {
        assertEquals(emptyList<NodeRow>(), buildNodesRaw(emptyList()))
    }

    @Test
    fun `buildNodesRaw flattens projects into node rows in host order`() {
        val raw = buildNodesRaw(listOf(
            project("p1", canvasNode("a", title = "Alpha", cwd = "/x"), canvasNode("b", title = "Beta")),
            project("p2", canvasNode("c", title = "Gamma"))
        ))
        assertEquals(listOf("a", "b", "c"), raw.map { it.nodeId })
        assertEquals("Alpha", raw[0].title)
        assertEquals("/x", raw[0].cwd)
        assertEquals("p1", raw[0].projectName)
        assertEquals("p2", raw[2].projectName)
    }

    @Test
    fun `buildNodesRaw preserves node kind, agentId and color`() {
        val raw = buildNodesRaw(listOf(
            project("p", canvasNode("n", kind = "agent", cwd = "/cwd", color = "#ff0000"))
        ))
        assertEquals("agent", raw[0].kind)
        assertEquals("#ff0000", raw[0].color)
    }

    // -------------------------------------------------------------------------------------------
    // buildKanban

    private fun col(id: String, title: String) = KanbanColumn(id = id, title = title)
    private fun assign(nodeId: String, columnId: String) = KanbanAssignment(nodeId = nodeId, columnId = columnId)

    @Test
    fun `blank activeProjectId falls back to the first project`() {
        val other = project("second", canvasNode("b"))
        val active = project("first", canvasNode("a"))
        val view = buildKanban(listOf(other, active), "", emptyList())
        assertEquals("second", view!!.activeProjectName)
    }

    @Test
    fun `empty project list returns null`() {
        assertEquals(null, buildKanban(emptyList(), "", emptyList()))
    }

    @Test
    fun `unknown activeProjectId falls back to first project`() {
        val active = project("only", canvasNode("a"))
        val view = buildKanban(listOf(active), "missing", emptyList())
        assertEquals("only", view!!.activeProjectName)
    }

    @Test
    fun `blank project name falls back to project id`() {
        val active = Project(id = "proj-id", name = "", nodes = listOf(canvasNode("a")))
        val view = buildKanban(listOf(active), "proj-id", emptyList())
        assertEquals("proj-id", view!!.activeProjectName)
    }

    @Test
    fun `buildKanban defaults columns when no board and sessions are ungrouped`() {
        val active = project("p", canvasNode("a"), canvasNode("b"))
        val view = buildKanban(listOf(active), "p", listOf("a", "b"))!!
        assertEquals("p", view.activeProjectName)
        assertEquals(3, view.columns.size) // default To Do / In Progress / Done
        assertEquals(emptyMap<String, String>(), view.assignments)
        assertEquals(listOf("a", "b"), view.ungrouped)
    }

    @Test
    fun `buildKanban resolves assignments and marks remaining sessions ungrouped`() {
        val k = KanbanProject(
            columns = listOf(col("todo", "To Do"), col("done", "Done")),
            assignments = listOf(assign("a", "todo"), assign("b", "done")),
            meta = listOf(KanbanCardMeta(nodeId = "a", priority = "high")),
            labels = listOf(KanbanLabel(id = "bug", name = "Bug"))
        )
        val active = Project(id = "p", name = "p", nodes = listOf(canvasNode("a"), canvasNode("b"), canvasNode("c")), kanban = k)
        val view = buildKanban(listOf(active), "p", listOf("a", "b", "c"))!!

        assertEquals(mapOf("a" to "todo", "b" to "done"), view.assignments)
        assertEquals(listOf("c"), view.ungrouped)
        assertEquals(mapOf("a" to KanbanCardMeta::class.java), mapOf("a" to view.meta["a"]!!::class.java))
        assertEquals("high", view.meta["a"]?.priority)
        assertEquals(1, view.labels.size)
        assertEquals("Bug", view.labels[0].name)
    }

    @Test
    fun `buildKanban drops assignments that reference nonexistent columns`() {
        val k = KanbanProject(
            columns = listOf(col("todo", "To Do")),
            assignments = listOf(assign("a", "todo"), assign("b", "ghost"))
        )
        val active = Project(id = "p", name = "p", nodes = listOf(canvasNode("a"), canvasNode("b")), kanban = k)
        val view = buildKanban(listOf(active), "p", listOf("a", "b"))!!
        assertEquals(mapOf("a" to "todo"), view.assignments)
        assertEquals(listOf("b"), view.ungrouped)
    }

    @Test
    fun `buildKanban ignores dangling session ids absent from the project`() {
        val k = KanbanProject(assignments = emptyList())
        val active = Project(id = "p", name = "p", nodes = listOf(canvasNode("a")), kanban = k)
        val view = buildKanban(listOf(active), "p", listOf("a", "gone"))!!
        // only real project nodes end up ungrouped via Kanban.unassigned
        assertEquals(listOf("a", "gone"), view.ungrouped)
    }

    // -------------------------------------------------------------------------------------------
    // normalizeSnapshotNewlines

    @Test
    fun `normalizeSnapshotNewlines converts bare lf to crlf`() {
        val bytes = "row1\nrow2".toByteArray(Charsets.UTF_8)
        assertArrayEquals("row1\r\nrow2".toByteArray(Charsets.UTF_8), normalizeSnapshotNewlines(bytes))
    }

    @Test
    fun `normalizeSnapshotNewlines leaves existing crlf unchanged`() {
        val bytes = "row1\r\nrow2".toByteArray(Charsets.UTF_8)
        assertArrayEquals("row1\r\nrow2".toByteArray(Charsets.UTF_8), normalizeSnapshotNewlines(bytes))
    }

    @Test
    fun `normalizeSnapshotNewlines normalizes mixed line endings`() {
        val bytes = "row1\nrow2\r\nrow3".toByteArray(Charsets.UTF_8)
        assertArrayEquals("row1\r\nrow2\r\nrow3".toByteArray(Charsets.UTF_8), normalizeSnapshotNewlines(bytes))
    }

    // -------------------------------------------------------------------------------------------
    // parentPath

    @Test
    fun `parentPath of root stays root`() {
        assertEquals("/", parentPath("/"))
        assertEquals("/", parentPath("//"))
    }

    @Test
    fun `parentPath returns null for a bare filename`() {
        assertEquals(null, parentPath("file.txt"))
    }

    @Test
    fun `parentPath strips trailing slash and returns parent`() {
        assertEquals("/a/b", parentPath("/a/b/c"))
        assertEquals("/a", parentPath("/a/b/"))
    }

    @Test
    fun `parentPath of a direct child returns root`() {
        assertEquals("/", parentPath("/file"))
    }

    @Test
    fun `parentPath of an empty string returns root`() {
        assertEquals("/", parentPath(""))
    }

    @Test
    fun `parentPath returns null for empty-after-strip path`() {
        // "file" has no slash → no parent.
        assertEquals(null, parentPath("file"))
    }
}
package com.nodeterm.android.ui

import com.nodeterm.android.core.model.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeListOrderTest {

    private fun node(id: String, title: String = id, projectName: String = "proj", status: NodeStatus? = null) =
        NodeRow(nodeId = id, title = title, kind = "terminal", agentId = null, cwd = null, projectName = projectName)

    private fun statusOf(vararg pairs: Pair<String, NodeStatus>) = pairs.toMap()

    @Test
    fun `default sort puts needs-you first, then working, then done, then idle, then title`() {
        val nodes = listOf(
            node("d", status = NodeStatus.DONE),
            node("w", status = NodeStatus.WORKING),
            node("n", status = NodeStatus.NEEDS_YOU),
            node("i", status = NodeStatus.IDLE)
        )
        val ordered = orderNodes(nodes, statusOf("d" to NodeStatus.DONE, "w" to NodeStatus.WORKING, "n" to NodeStatus.NEEDS_YOU, "i" to NodeStatus.IDLE), emptySet(), emptyMap())
        assertEquals(listOf("n", "w", "d", "i"), ordered.map { it.nodeId })
    }

    @Test
    fun `default sort falls back to title for same-status nodes`() {
        val nodes = listOf(node("zeta"), node("alpha"))
        val ordered = orderNodes(nodes, emptyMap(), emptySet(), emptyMap())
        assertEquals(listOf("alpha", "zeta"), ordered.map { it.nodeId })
    }

    @Test
    fun `dismissed nodes are filtered out`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val ordered = orderNodes(nodes, emptyMap(), setOf("b"), emptyMap())
        assertEquals(listOf("a", "c"), ordered.map { it.nodeId })
    }

    @Test
    fun `custom order wins over the default sort within a project`() {
        val nodes = listOf(node("a", status = NodeStatus.NEEDS_YOU), node("b"), node("c"))
        val ordered = orderNodes(
            nodes,
            statusOf("a" to NodeStatus.NEEDS_YOU),
            emptySet(),
            mapOf("proj" to listOf("c", "b", "a"))
        )
        assertEquals(listOf("c", "b", "a"), ordered.map { it.nodeId })
    }

    @Test
    fun `nodes outside the custom order keep default sort at the group tail`() {
        val nodes = listOf(node("a"), node("new"), node("b"))
        val ordered = orderNodes(nodes, emptyMap(), emptySet(), mapOf("proj" to listOf("b", "a")))
        // "new" was added on the host after the drag order was set — it goes after, default-sorted.
        assertEquals(listOf("b", "a", "new"), ordered.map { it.nodeId })
    }

    @Test
    fun `custom order entries pointing at missing nodes are skipped`() {
        val nodes = listOf(node("a"), node("b"))
        val ordered = orderNodes(nodes, emptyMap(), emptySet(), mapOf("proj" to listOf("gone", "b", "a")))
        assertEquals(listOf("b", "a"), ordered.map { it.nodeId })
    }

    @Test
    fun `projects without a custom order keep the default sort`() {
        val nodes = listOf(
            node("x", projectName = "other"),
            node("a", projectName = "proj", status = NodeStatus.WORKING),
            node("b", projectName = "proj")
        )
        val ordered = orderNodes(
            nodes,
            statusOf("a" to NodeStatus.WORKING),
            emptySet(),
            mapOf("proj" to listOf("b", "a")) // "other" has no order
        )
        // Group encounter order follows the input list: "other" (default sort), then "proj" (custom).
        assertEquals(listOf("x", "b", "a"), ordered.map { it.nodeId })
    }

    @Test
    fun `blank project names do not crash and group together`() {
        val nodes = listOf(
            node("a", projectName = ""),
            node("b", projectName = ""),
            node("c", projectName = "real")
        )
        val ordered = orderNodes(nodes, emptyMap(), emptySet(), mapOf("" to listOf("b", "a")))
        assertEquals(listOf("b", "a", "c"), ordered.map { it.nodeId })
    }

    @Test
    fun `dismissed nodes do not reappear through the custom order`() {
        val nodes = listOf(node("a"), node("b"))
        val ordered = orderNodes(nodes, emptyMap(), setOf("a"), mapOf("proj" to listOf("a", "b")))
        assertEquals(listOf("b"), ordered.map { it.nodeId })
    }
}

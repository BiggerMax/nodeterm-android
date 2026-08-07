package com.nodeterm.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectsBlobTest {

    private val workspaceJson = """{"version":2,"activeProjectId":"p1","projects":[{"id":"p1","name":"My App","nodes":[{"id":"n1","kind":"terminal","title":"Build","cwd":"/work/app"}]}]}"""

    private val statusJson = """{"v":1,"updatedAt":1700000000000,"nodes":{"n1":{"state":"working","agentId":"claude","name":"Build API","updatedAt":1700000000000}},"inbox":[{"id":"ev1","ts":1700000000000,"nodeId":"n1","kind":"approval","title":"Approve bash command?","pendingId":"n1-1700000000000-42"},{"id":"ev2","ts":1700000000000,"nodeId":"n1","kind":"question","title":"Pick an option","options":["a","b"]}]}"""

    @Test
    fun parseFullBlob() {
        val blob = "$workspaceJson\n--NT-PROJECTS-SPLIT--\nnt-sess-1\nnt-sess-2\n--NT-STATUS-SPLIT--\n$statusJson"
        val parsed = ProjectsBlob.parse(blob)
        assertEquals(workspaceJson, parsed.workspaceJson)
        assertEquals(listOf("nt-sess-1", "nt-sess-2"), parsed.tmuxSessions)
        assertEquals(statusJson, parsed.statusJson)
    }

    @Test
    fun parseMissingSectionsDegradesToEmpty() {
        val parsed = ProjectsBlob.parse("")
        assertEquals("", parsed.workspaceJson)
        assertEquals(emptyList(), parsed.tmuxSessions)
        assertEquals("", parsed.statusJson)
    }

    @Test
    fun parseWorkspaceJson() {
        val ws = JsonModels.workspace(workspaceJson)
        assertNotNull(ws)
        assertEquals(2, ws.version)
        assertEquals("p1", ws.activeProjectId)
        assertEquals(1, ws.projects.size)
        assertEquals("My App", ws.projects[0].name)
        assertEquals("n1", ws.projects[0].nodes[0].id)
        assertEquals("terminal", ws.projects[0].nodes[0].kind)
        assertEquals("/work/app", ws.projects[0].nodes[0].cwd)
    }

    @Test
    fun parseStatusJsonWithInbox() {
        val mirror = JsonModels.mirror(statusJson)
        assertNotNull(mirror)
        assertEquals(1, mirror.v)
        val node = mirror.nodes["n1"]
        assertNotNull(node)
        assertEquals("working", node.state)
        assertEquals("claude", node.agentId)
        assertEquals("Build API", node.name)
        assertEquals(2, mirror.inbox.size)
        val approval = mirror.inbox[0]
        assertEquals("approval", approval.kind)
        assertEquals("Approve bash command?", approval.title)
        assertEquals("n1-1700000000000-42", approval.pendingId)
        val question = mirror.inbox[1]
        assertEquals(listOf("a", "b"), question.options)
    }

    @Test
    fun nodeStatusMappingCollapsesWaitingBlocked() {
        assertEquals(NodeStatus.WORKING, NodeStatus.fromMirrorState("working"))
        assertEquals(NodeStatus.NEEDS_YOU, NodeStatus.fromMirrorState("waiting"))
        assertEquals(NodeStatus.NEEDS_YOU, NodeStatus.fromMirrorState("blocked"))
        assertEquals(NodeStatus.DONE, NodeStatus.fromMirrorState("done"))
        assertEquals(NodeStatus.IDLE, NodeStatus.fromMirrorState(null))
        assertEquals(NodeStatus.IDLE, NodeStatus.fromMirrorState("bogus"))
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(JsonModels.workspace("not json"))
        assertNull(JsonModels.mirror(""))
        assertNull(JsonModels.canvasState("{broken"))
    }

    @Test
    fun canvasStateNotifyParses() {
        val cs = JsonModels.canvasState("""{"nodes":[{"id":"n1","title":"T","kind":"terminal"}]}""")
        assertNotNull(cs)
        assertEquals("n1", cs.nodes[0].id)
        assertEquals("T", cs.nodes[0].title)
    }
}

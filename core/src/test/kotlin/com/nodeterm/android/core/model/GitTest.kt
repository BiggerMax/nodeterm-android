package com.nodeterm.android.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitTest {

    @Test
    fun `parses full status with staged and changes`() {
        val text = """
            {
              "hasRepo": true, "repoName": "owner/repo", "branch": "main",
              "branches": ["main", "dev"], "remoteBranches": ["origin/main"],
              "ahead": 2, "behind": 1, "hasRemote": true, "hasOrigin": true,
              "hasUpstream": true, "ghAvailable": true, "ghAuthed": true,
              "staged": [{"path": "a.txt", "status": "M", "added": 3, "deleted": 1}],
              "changes": [
                {"path": "b.txt", "status": "M", "added": 5, "deleted": 2},
                {"path": "new.txt", "status": "U", "added": 10, "deleted": 0}
              ]
            }
        """.trimIndent()

        val s = GitModels.gitStatus(text)
        assertNotNull(s)
        val g = s!!
        assertTrue(g.hasRepo)
        assertEquals("owner/repo", g.repoName)
        assertEquals("main", g.branch)
        assertEquals(listOf("main", "dev"), g.branches)
        assertEquals(listOf("origin/main"), g.remoteBranches)
        assertEquals(2, g.ahead)
        assertEquals(1, g.behind)
        assertTrue(g.hasOrigin)
        assertTrue(g.ghAuthed)
        assertEquals(1, g.staged.size)
        assertEquals("a.txt", g.staged[0].path)
        assertEquals("M", g.staged[0].status)
        assertEquals(3, g.staged[0].added)
        assertEquals(1, g.staged[0].deleted)
        assertEquals(2, g.changes.size)
        assertTrue(g.changes[1].isUntracked)
        assertEquals(3, g.dirtyCount)
    }

    @Test
    fun `parses status from a JsonObject body`() {
        val obj = Json.parseToJsonElement(
            """{"hasRepo": false, "repoName": "folder", "staged": [], "changes": []}"""
        )
        val g = GitModels.gitStatus(obj)!!
        assertFalse(g.hasRepo)
        assertEquals("folder", g.repoName)
        assertTrue(g.staged.isEmpty())
        assertEquals(0, g.dirtyCount)
    }

    @Test
    fun `missing fields degrade to empty defaults`() {
        val g = GitModels.gitStatus("""{"hasRepo": true}""")!!
        assertTrue(g.hasRepo)
        assertEquals("", g.repoName)
        assertEquals("", g.branch)
        assertEquals(0, g.ahead)
        assertTrue(g.staged.isEmpty())
        assertTrue(g.changes.isEmpty())
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(GitModels.gitStatus("not json"))
        assertNull(GitModels.gitStatus(""))
    }

    @Test
    fun `status letter edge cases`() {
        val text = """{"changes": [{"path": "x", "status": "R", "added": 0, "deleted": 0}]}"""
        val g = GitModels.gitStatus(text)!!
        assertEquals("R", g.changes[0].status)
        assertFalse(g.changes[0].isUntracked)
    }
}

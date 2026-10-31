package com.opc.baize.agent

import com.opc.baize.core.Session
import com.opc.baize.core.Turn
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceManagementTest {
    @Test fun archiveTrashAndRestoreRetainIdentityFilesAndLegacyIndex() {
        val context = RuntimeEnvironment.getApplication(); val files = WorkspaceFiles(context)
        val session = Session("s_${UUID.randomUUID()}", 1, listOf(Turn("original", "reply")))
        val w = files.forSession(session)
        try {
            val material = File(w.root, "memory/pages/lesson.md").apply { writeText("ORIGINAL") }
            val transcript = w.transcript.apply { writeText("AGENT_TRANSCRIPT") }
            files.manage(w, "pin"); files.manage(w, "archive")
            assertTrue(files.query("archived").any { it.id == w.id }); assertFalse(files.query().any { it.id == w.id })
            files.manage(w, "trash")
            assertFalse(files.list().any { it.id == w.id })
            assertEquals(w.id, files.forSession(session).id)
            assertEquals(1, files.all().count { files.sessionId(it) == session.id })
            val reloaded = WorkspaceFiles(context)
            assertTrue(reloaded.query("trash").any { it.id == w.id })
            reloaded.manage(w, "restore")
            assertTrue(reloaded.state(w).archived); assertTrue(reloaded.state(w).pinned)
            reloaded.manage(w, "unarchive")
            assertTrue(reloaded.query().any { it.id == w.id })
            assertEquals("ORIGINAL", material.readText()); assertEquals("AGENT_TRANSCRIPT", transcript.readText())
            assertEquals(session.id, reloaded.sessionId(w))
        } finally { w.container.deleteRecursively() }
    }
    @Test fun searchSortAndPinnedOrderAreStableAcrossReload() {
        val context = RuntimeEnvironment.getApplication(); val files = WorkspaceFiles(context)
        val a = files.create("Alpha ${UUID.randomUUID()}"); val b = files.create("Beta ${UUID.randomUUID()}")
        try {
            files.manage(b, "pin")
            assertEquals(b.id, files.query(sort = "name").filter { it.id in setOf(a.id, b.id) }.first().id)
            assertEquals(listOf(a.id), files.query(text = a.name.uppercase()).map { it.id })
            files.manage(b, "unpin")
            assertEquals(a.id, files.query(sort = "name").filter { it.id in setOf(a.id, b.id) }.first().id)
            files.manage(a, "open")
            assertTrue(WorkspaceFiles(context).state(a).openedAt > 0)
        } finally { a.container.deleteRecursively(); b.container.deleteRecursively() }
    }
    @Test fun firstInputNamesAnEmptyTaskButManualRenameAlwaysWins() {
        val files = WorkspaceFiles(RuntimeEnvironment.getApplication())
        val session = Session("s_${UUID.randomUUID()}", 1, emptyList())
        val w = files.forSession(session)
        try {
            val continued = session.copy(turns = listOf(Turn("English tenses", "reply")))
            assertEquals(continued.summary(), files.forSession(continued).name)
            files.rename(w, "My lesson")
            assertEquals("My lesson", files.forSession(continued.copy(turns = continued.turns + Turn("next", "answer"))).name)
        } finally { w.container.deleteRecursively() }
    }
}

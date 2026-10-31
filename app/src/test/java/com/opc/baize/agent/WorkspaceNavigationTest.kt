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
class WorkspaceNavigationTest {
    @Test fun oldSessionIsIndexedOnceAndContinuingUpdatesTheSameWorkspace() {
        val files = WorkspaceFiles(RuntimeEnvironment.getApplication())
        val session = Session("s_${UUID.randomUUID()}", 1, listOf(Turn("question", "answer")))
        val workspace = files.forSession(session)
        try {
            assertEquals(workspace.id, files.forSession(session).id)
            assertEquals(session.id, files.sessionId(workspace))
            val continued = session.copy(turns = session.turns + Turn("follow-up", "continued"))
            assertEquals(workspace.id, files.forSession(continued).id)
            val text = File(workspace.root, "memory/pages/conversation.md").readText()
            assertTrue(text.contains("question")); assertTrue(text.contains("follow-up"))
            assertEquals(1, files.list().count { files.sessionId(it) == session.id })
        } finally { workspace.container.deleteRecursively() }
    }
    @Test fun linkingAnExistingAgentWorkspacePreservesItsFilesAndConversation() {
        val files = WorkspaceFiles(RuntimeEnvironment.getApplication())
        val workspace = files.create("existing-${UUID.randomUUID()}")
        try {
            val paper = File(workspace.root, "memory/pages/lesson.md").apply { writeText("lesson") }
            workspace.transcript.writeText("agent conversation")
            val id = "w_${workspace.id}"
            files.attachSession(workspace, id)
            assertEquals(workspace.id, files.forSession(Session(id, 1, listOf(Turn("next", "answer")))).id)
            assertEquals("lesson", paper.readText())
            assertEquals("agent conversation", workspace.transcript.readText())
        } finally { workspace.container.deleteRecursively() }
    }
    @Test fun renamingKeepsTheTaskBindingAndEmptyWorkspaceAcrossReload() {
        val files = WorkspaceFiles(RuntimeEnvironment.getApplication())
        val session = Session("s_${UUID.randomUUID()}", 1, emptyList())
        val workspace = files.forSession(session)
        try {
            files.rename(workspace, "renamed")
            val reloaded = WorkspaceFiles(RuntimeEnvironment.getApplication()).forSession(session)
            assertEquals(workspace.id, reloaded.id); assertEquals("renamed", reloaded.name)
            assertEquals(session.id, files.sessionId(reloaded))
        } finally { workspace.container.deleteRecursively() }
    }
}

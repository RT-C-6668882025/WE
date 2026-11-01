package com.opc.baize.agent

import org.junit.Assert.*
import org.junit.Test

class WorkspaceDrawingTest {
    @Test fun defaultDrawingPassesTheSameSchemaUsedByTheOpenAction() {
        val content=WorkspaceDrawing.blankContent()
        val doc=WritableDocument.parse(content)
        assertEquals("drawing",doc.mode)
        assertEquals(500,doc.blocks.single().height)
        val plan=WritablePageLayout.plan(doc) {text,_,_,_->listOf(text.length)}
        assertEquals(1,plan.pageCount)
        assertTrue(plan.regions.any {it.type=="drawing"})
    }
}

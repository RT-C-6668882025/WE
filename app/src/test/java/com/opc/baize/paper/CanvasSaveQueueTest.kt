package com.opc.baize.paper

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class CanvasSaveQueueTest {
    @Test fun failedRevisionsStayOrderedAndBlockExternalWritesUntilRetried() {
        val tasks=java.util.ArrayDeque<Runnable>();val committed=mutableListOf<Long>()
        var fail=true
        val queue=CanvasSaveQueue(Executor {tasks.addLast(it)},{_,_,page ->
            if(fail) throw java.io.IOException("disk full")
            committed.add(page.revision)
        },{})
        val id=java.util.UUID.randomUUID().toString()
        queue.enqueue(id,0,PaperPage(600,840,1,emptyList()))
        queue.enqueue(id,0,PaperPage(600,840,2,emptyList()))
        assertFalse(CanvasWriteGuard.isClear(id,0));assertEquals(1,tasks.size)
        tasks.removeFirst().run()
        assertEquals(2,queue.state().pending);assertEquals("disk full",queue.state().error)
        assertFalse(queue.isIdle());assertTrue(committed.isEmpty())
        fail=false;queue.retry();tasks.removeFirst().run()
        assertEquals(listOf(1L,2L),committed);assertTrue(queue.isIdle());assertTrue(CanvasWriteGuard.isClear(id,0))
    }
    @Test fun oneDocumentDoesNotBlockAnUnrelatedPage() {
        val tasks=java.util.ArrayDeque<Runnable>();val id=java.util.UUID.randomUUID().toString()
        val queue=CanvasSaveQueue(Executor {tasks.add(it)},{_,_,_->},{})
        queue.enqueue(id,0,PaperPage(600,840,1,emptyList()))
        assertTrue(CanvasWriteGuard.isClear(id,1));assertFalse(CanvasWriteGuard.isClear(id,0))
        tasks.removeFirst().run();assertTrue(CanvasWriteGuard.isClear(id,0))
    }
}

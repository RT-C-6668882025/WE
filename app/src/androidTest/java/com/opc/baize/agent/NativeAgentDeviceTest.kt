package com.opc.baize.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAgentDeviceTest {
    @Test fun skillMcpAndWorkspaceRunInTheAppProcess() = NativeRuntimeContract.verify(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test fun generatedLearningPaperPaginatesAndRestoresVectors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.io.File(context.cacheDir, "paper-contract-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = java.io.File(folder, "lesson.md").apply { writeText((1..100).joinToString("\n") { "Question $it: write your answer. 请在下方作答。" }) }
            val output = java.io.File(folder, "lesson.pdf")
            LearningPageRenderer.render(source, output, true)
            val repository = com.opc.baize.paper.PaperRepository(context)
            val id = repository.importPdf(android.net.Uri.fromFile(output))
            com.opc.baize.paper.AndroidPdfEngine(repository.pdf(id)).use { engine ->
                org.junit.Assert.assertTrue(engine.pageCount > 1)
                val rendered = engine.render(0)
                try {
                    val point = com.opc.baize.paper.InkPoint(50f, 100f, 0.5f, 100L)
                    val data = com.opc.baize.paper.PaperPage(rendered.width, rendered.height, 1,
                        listOf(com.opc.baize.paper.PaperStroke(listOf(point))))
                    repository.save(id, 0, data)
                    org.junit.Assert.assertEquals(data, com.opc.baize.paper.PaperRepository(context).load(id, 0, rendered.width, rendered.height))
                    org.junit.Assert.assertEquals(600, rendered.width)
                    org.junit.Assert.assertEquals(840, rendered.height)
                } finally { rendered.bitmap.recycle() }
            }
            val blank = java.io.File(folder, "blank.pdf")
            LearningPageRenderer.render(null, blank, true)
            com.opc.baize.paper.AndroidPdfEngine(blank).use { org.junit.Assert.assertEquals(1, it.pageCount) }
        } finally { folder.deleteRecursively() }
    }
}

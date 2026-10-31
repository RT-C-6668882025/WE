package com.opc.baize.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAgentDeviceTest {
    @Test fun feedbackGeneratesANewPaperAndPreservesOriginalInk() = LearningLoopContract.verify(InstrumentationRegistry.getInstrumentation().targetContext)
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
            val other = java.io.File(folder, "another-blank.pdf")
            LearningPageRenderer.render(null, other, true)
            org.junit.Assert.assertNotEquals(repository.importPdf(android.net.Uri.fromFile(blank)), repository.importPdf(android.net.Uri.fromFile(other)))
        } finally { folder.deleteRecursively() }
    }
    @Test fun workspacePaperOpensAndTouchInkSurvivesReopening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val workspace = WorkspaceFiles(context).create("device-paper-${java.util.UUID.randomUUID()}")
        val pdf = java.io.File(workspace.root, "memory/pages/device-paper.pdf")
        LearningPageRenderer.render(null, pdf, true)
        val activity = instrumentation.startActivitySync(android.content.Intent(context, com.opc.baize.paper.PaperActivity::class.java)
            .putExtra("workspace", workspace.id).putExtra("generatedPdf", pdf.path)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val target = java.util.concurrent.atomic.AtomicReference<com.opc.baize.paper.PaperInkView>()
            fun find(view: android.view.View): com.opc.baize.paper.PaperInkView? {
                if (view is com.opc.baize.paper.PaperInkView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            repeat(100) {
                instrumentation.runOnMainSync {
                    val view = find(activity.window.decorView)
                    if (view?.page?.width == 600 && view.isEnabled && view.width > 0 && view.height > 0) target.set(view)
                }
                if (target.get() == null) android.os.SystemClock.sleep(100)
            }
            org.junit.Assert.assertNotNull("Generated page did not open", target.get())
            instrumentation.runOnMainSync {
                val view = target.get()
                val now = android.os.SystemClock.uptimeMillis()
                // Write at the fitted page centre, independent of device screen size.
                val x = view.width / 2f; val y = view.height / 2f
                for ((action, offset) in listOf(android.view.MotionEvent.ACTION_DOWN to 0f, android.view.MotionEvent.ACTION_UP to 20f)) {
                    val event = android.view.MotionEvent.obtain(now, now + offset.toLong(), action, x + offset, y, 0)
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                }
                org.junit.Assert.assertEquals(1L, view.page.revision)
                org.junit.Assert.assertEquals(1, view.page.strokes.size)
            }
            com.opc.baize.paper.PaperIo.executor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val repository = com.opc.baize.paper.PaperRepository(context)
            val id = repository.importPdf(android.net.Uri.fromFile(pdf))
            org.junit.Assert.assertEquals(1, repository.load(id, 0, 600, 840).strokes.size)
            org.junit.Assert.assertEquals(workspace.id, context.getSharedPreferences("moling", 0).getString("paper_workspace_$id", null))
        } finally { instrumentation.runOnMainSync { activity.finish() }; workspace.container.deleteRecursively() }
    }
}

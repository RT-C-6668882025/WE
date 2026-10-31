package com.opc.baize.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAgentDeviceTest {
    private fun touch(view: com.opc.baize.paper.PaperInkView, action: Int, vararg xy: Pair<Float,Float>) {
        val properties = Array(xy.size) { index -> android.view.MotionEvent.PointerProperties().apply {
            id = index; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(xy.size) { index -> android.view.MotionEvent.PointerCoords().apply {
            x = xy[index].first; y = xy[index].second; pressure = 1f; size = 1f
        } }
        val event = android.view.MotionEvent.obtain(1, android.os.SystemClock.uptimeMillis(), action,
            xy.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { org.junit.Assert.assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun selectionCanBeCancelledWithoutChangingInk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = com.opc.baize.paper.PaperInkView(instrumentation.targetContext)
            view.layout(0,0,400,400)
            view.load(android.graphics.Bitmap.createBitmap(200,200,android.graphics.Bitmap.Config.ARGB_8888),
                com.opc.baize.paper.PaperPage(200,200,0,emptyList()))
            fun bounds() = view.snapshot().let { snapshot -> snapshot.first.recycle(); snapshot.third }
            try {
                view.mode = com.opc.baize.paper.PaperInkView.Mode.SELECT
                touch(view,0,40f to 40f); touch(view,2,120f to 120f); touch(view,1,120f to 120f)
                org.junit.Assert.assertTrue(view.hasSelection)
                org.junit.Assert.assertEquals(40f,bounds().width(),0.01f)
                view.clearSelection()
                org.junit.Assert.assertFalse(view.hasSelection)
                org.junit.Assert.assertEquals(200f,bounds().width(),0.01f)
                touch(view,0,40f to 40f); touch(view,2,120f to 120f); touch(view,1,120f to 120f)
                touch(view,0,180f to 180f); touch(view,1,180f to 180f)
                org.junit.Assert.assertFalse(view.hasSelection)
                org.junit.Assert.assertEquals(200f,bounds().width(),0.01f)
                org.junit.Assert.assertEquals(0L,view.page.revision)
            } finally { view.release() }
        }
    }
    @Test fun pinchPanPreservesFocusAndNeverCommitsGestureInk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = com.opc.baize.paper.PaperInkView(instrumentation.targetContext)
            view.layout(0,0,400,400)
            view.load(android.graphics.Bitmap.createBitmap(200,200,android.graphics.Bitmap.Config.ARGB_8888),
                com.opc.baize.paper.PaperPage(200,200,0,emptyList()))
            try {
                for (penOnly in listOf(false,true)) {
                    view.resetView(); view.stylusOnly = penOnly
                    touch(view,0,100f to 100f)
                    touch(view,5 or (1 shl 8),100f to 100f,200f to 100f)
                    touch(view,2,75f to 75f,225f to 75f)
                    touch(view,6 or (1 shl 8),75f to 75f,225f to 75f)
                    touch(view,2,150f to 120f)
                    touch(view,1,150f to 120f)
                    org.junit.Assert.assertTrue(view.page.strokes.isEmpty())
                    org.junit.Assert.assertEquals(0L,view.page.revision)
                }
                view.stylusOnly = false
                touch(view,0,150f to 75f); touch(view,1,150f to 75f)
                val point = view.page.strokes.single().points.first()
                org.junit.Assert.assertEquals(75f,point.x,0.01f)
                org.junit.Assert.assertEquals(50f,point.y,0.01f)
            } finally { view.release() }
        }
    }
    @Test fun eraserCursorIsVisibleAndGestureCancellationPreservesInk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = com.opc.baize.paper.PaperInkView(instrumentation.targetContext)
            view.layout(0,0,400,400)
            val original = listOf(com.opc.baize.paper.PaperStroke(listOf(
                com.opc.baize.paper.InkPoint(50f,50f,1f,0), com.opc.baize.paper.InkPoint(70f,50f,1f,1))))
            view.load(android.graphics.Bitmap.createBitmap(200,200,android.graphics.Bitmap.Config.ARGB_8888),
                com.opc.baize.paper.PaperPage(200,200,0,original))
            val screen = android.graphics.Bitmap.createBitmap(400,400,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.mode = com.opc.baize.paper.PaperInkView.Mode.ERASE
                view.draw(android.graphics.Canvas(screen))
                org.junit.Assert.assertNotEquals("eraser cursor must be visible immediately",android.graphics.Color.WHITE,screen.getPixel(200,200))
                val snapshot = view.snapshot().first
                try { org.junit.Assert.assertEquals("cursor must not enter AI feedback",android.graphics.Color.WHITE,snapshot.getPixel(200,200)) } finally { snapshot.recycle() }
                touch(view,0,100f to 100f)
                touch(view,5 or (1 shl 8),100f to 100f,200f to 100f)
                touch(view,6 or (1 shl 8),100f to 100f,200f to 100f)
                touch(view,1,100f to 100f)
                org.junit.Assert.assertEquals(original,view.page.strokes)
                org.junit.Assert.assertEquals(0L,view.page.revision)
                touch(view,0,100f to 100f); touch(view,1,100f to 100f)
                org.junit.Assert.assertTrue(view.page.strokes.isEmpty())
                org.junit.Assert.assertEquals(1L,view.page.revision)
                view.undo(); org.junit.Assert.assertEquals(original,view.page.strokes)
            } finally { screen.recycle(); view.release() }
        }
    }
    @Test fun drawpileNativeBrushRendersPressureAndRestoresSavedStrokes() {
        val points = listOf(
            com.opc.baize.paper.InkPoint(30f, 40f, 0.1f, 0),
            com.opc.baize.paper.InkPoint(100f, 40f, 1f, 100)
        )
        val stroke = com.opc.baize.paper.PaperStroke(points, 20f, android.graphics.Color.BLUE, com.opc.baize.paper.DrawpileBrush.ENGINE)
        val page = com.opc.baize.paper.PaperPage(140, 80, 1, listOf(stroke))
        val restored = com.opc.baize.paper.PaperPage.decode(page.encode())
        org.junit.Assert.assertEquals(stroke, restored.strokes.single())
        val bitmap = android.graphics.Bitmap.createBitmap(140, 80, android.graphics.Bitmap.Config.ARGB_8888)
        val replay = android.graphics.Bitmap.createBitmap(140, 80, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            com.opc.baize.paper.PaperStrokeRenderer.draw(android.graphics.Canvas(bitmap), stroke)
            com.opc.baize.paper.PaperStrokeRenderer.draw(android.graphics.Canvas(replay), restored.strokes.single())
            org.junit.Assert.assertTrue(bitmap.sameAs(replay))
            org.junit.Assert.assertTrue(android.graphics.Color.alpha(bitmap.getPixel(100, 40)) > 0)
            org.junit.Assert.assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(10, 10)))
            fun coverage(x: Int) = (0 until 80).count { android.graphics.Color.alpha(bitmap.getPixel(x, it)) > 10 }
            org.junit.Assert.assertTrue("pressure increases brush width", coverage(95) > coverage(32))
            org.junit.Assert.assertTrue(android.graphics.Color.blue(bitmap.getPixel(95,40)) > 200)
        } finally { bitmap.recycle(); replay.recycle() }
    }
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

package com.opc.baize.paper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawingDeviceTest {
    private fun line(color:Int,y:Float=50f,brush:BrushSettings=BrushSettings(hardness=1f,pressure=false)) =
        PaperStroke(listOf(InkPoint(20f,y,1f,0),InkPoint(140f,y,1f,100)),20f,color,"drawdance-v1",1,brush)
    private fun raster(renderer:LayerRenderer,page:PaperPage,preview:PaperStroke?=null): Bitmap =
        Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).also {renderer.draw(Canvas(it),page,preview)}
    @Test fun drawingCanBeCreatedOpenedAndResumedWithoutReplacingLearningPages() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val files=com.opc.baize.agent.WorkspaceFiles(context)
        val workspace=files.create("drawing-open-regression")
        try {
            val first=com.opc.baize.agent.WorkspaceDrawing.open(workspace,true)
            assertTrue(first.pdf.isFile)
            AndroidPdfEngine(first.pdf).use {pdf ->
                assertEquals(1,pdf.pageCount)
                val rendered=pdf.render(0)
                assertTrue(rendered.width>0 && rendered.height>0);rendered.bitmap.recycle()
            }
            val resumed=com.opc.baize.agent.WorkspaceDrawing.open(workspace)
            assertEquals(first.pdf.canonicalPath,resumed.pdf.canonicalPath)
            val second=com.opc.baize.agent.WorkspaceDrawing.open(workspace,true)
            assertNotEquals(first.pdf.canonicalPath,second.pdf.canonicalPath)
            assertTrue(first.pdf.isFile)
        } finally {workspace.container.deleteRecursively()}
    }
    @Test fun backgroundMcpWaitsForFinalQueuedInkSaveBeforeRevisionCheck() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val id="e".repeat(64);val store=PaperRepository(context)
        val root=java.io.File(context.filesDir,"papers/$id");root.deleteRecursively()
        val blocked=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            PaperIo.executor.submit {blocked.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS)}
            assertTrue(blocked.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val user=PaperPage(600,840,1,listOf(PaperStroke(listOf(InkPoint(20f,20f,1f,0)))))
            PaperIo.executor.submit {store.save(id,0,user)}
            val operation=worker.submit<PaperPage> {
                CanvasSession.change(java.util.UUID.randomUUID().toString(),id,0,0,{it}) {
                    store.edit(id,0,600,840,0) {it.copy(strokes=listOf(PaperStroke(listOf(InkPoint(40f,40f,1f,0)))))}
                }
            }
            Thread.sleep(100)
            assertFalse("MCP must not pass an outstanding local save",operation.isDone)
            release.countDown()
            assertThrows(java.util.concurrent.ExecutionException::class.java) {operation.get(10,java.util.concurrent.TimeUnit.SECONDS)}
            PaperIo.executor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(user,store.load(id,0,600,840))
        } finally {
            release.countDown();worker.shutdownNow()
            PaperIo.executor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
    @Test fun mcpEditsTheVisibleCanvasAndExportsTheSameRevision() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val workspace=com.opc.baize.agent.WorkspaceFiles(context).create("统一画布验收")
        val doc=com.opc.baize.agent.WorkspaceDrawing.open(workspace,true)
        val activity=instrumentation.startActivitySync(android.content.Intent(context,PaperActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("workspace",workspace.id)
            .putExtra("generatedPdf",doc.pdf.path).putExtra("sourcePath",doc.sourcePath)) as PaperActivity
        try {
            lateinit var ink:PaperInkView
            val deadline=System.currentTimeMillis()+15000
            var ready=false
            while(!ready && System.currentTimeMillis()<deadline) {
                instrumentation.runOnMainSync {
                    ink=PaperActivity::class.java.getDeclaredField("ink").apply {isAccessible=true}.get(activity) as PaperInkView
                    ready=ink.page.width>1 && activity.hasWindowFocus()
                }
                if(!ready) Thread.sleep(50)
            }
            assertTrue("Canvas did not load",ready)
            val tools=com.opc.baize.mcp.WorkspaceMcpTools(context)
            val input=org.json.JSONObject().put("workspace_id",workspace.id).put("path","/"+doc.pdf.relativeTo(workspace.root).path)
            val live=tools.execute("get_canvas_context",org.json.JSONObject().put("workspace_id",workspace.id))
                .getJSONObject("result").getJSONObject("active")
            assertEquals(0L,live.getLong("revision"))
            val edited=tools.execute("edit_canvas",org.json.JSONObject(input.toString()).put("expected_revision",0)
                .put("operation_id","visible-"+java.util.UUID.randomUUID()).put("operations",org.json.JSONArray("""[
                    {"action":"add_layer","name":"AI 绘图"},
                    {"action":"rectangle","x":100,"y":100,"x2":400,"y2":300,"color":"#1955AF","width":4},
                    {"action":"line","x":100,"y":100,"x2":400,"y2":300,"color":"#BF2828","width":3}
                ]""")))
            assertEquals("succeeded",edited.getString("status"))
            instrumentation.runOnMainSync {assertEquals(1L,ink.page.revision);assertEquals(2,ink.page.strokes.size)}
            val image=tools.execute("export_canvas",org.json.JSONObject(input.toString()).put("expected_revision",1)
                .put("operation_id","visible-export-"+java.util.UUID.randomUUID()))
            assertEquals("succeeded",image.getString("status"))
            assertEquals(1L,image.getJSONObject("result").getLong("revision"))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let {bitmap ->
                val file=java.io.File(activity.getExternalFilesDir("ui-checks"),"unified-canvas.png")
                try {file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
            }
        } finally {
            instrumentation.runOnMainSync {activity.finish()}
            instrumentation.waitForIdleSync(); workspace.container.deleteRecursively()
        }
    }
    @Test fun nativeLayersErasePreviewAndReplayMatch() {
        LayerRenderer().use {renderer ->
            val page=PaperPage(160,100,1,listOf(line(Color.RED)))
            val first=raster(renderer,page)
            assertTrue(Color.alpha(first.getPixel(80,50))>200)
            val erase=line(Color.BLACK,brush=BrushSettings(hardness=1f,pressure=false,erase=true)).copy(width=6f)
            raster(renderer,page,erase).also {assertTrue(Color.alpha(it.getPixel(80,50))<50);it.recycle()}
            raster(renderer,page).also {assertEquals(first.getPixel(80,50),it.getPixel(80,50));it.recycle()}
            val erased=page.copy(strokes=page.strokes+erase)
            val saved=raster(renderer,erased)
            LayerRenderer().use {fresh -> raster(fresh,PaperPage.decode(erased.encode())).also {
                assertEquals(saved.getPixel(80,50),it.getPixel(80,50));it.recycle()
            } }
            raster(renderer,page.copy(layers=listOf(PaperLayer(1,"隐藏",visible=false)))).also {
                assertEquals(0,Color.alpha(it.getPixel(80,50)));it.recycle()
            }
            first.recycle();saved.recycle()
        }
    }
    @Test fun mypaintFillAndLegacySeedAreReplayable() {
        val page=PaperPage(160,100,1,listOf(line(Color.RED).copy(engine="vector"),
            line(Color.BLUE,30f,BrushSettings(mypaint=true)),
            PaperStroke(listOf(InkPoint(1f,1f,1f,0)),color=Color.GREEN,engine="drawdance-v1",operation="fill")))
        LayerRenderer().use {renderer -> raster(renderer,page).also {bitmap ->
            assertEquals(Color.GREEN,bitmap.getPixel(1,1))
            assertTrue(Color.red(bitmap.getPixel(80,50))>100)
            assertTrue(Color.blue(bitmap.getPixel(80,30))>100)
            bitmap.recycle()
        } }
    }
}

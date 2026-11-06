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
            lateinit var capture:Bitmap
            instrumentation.runOnMainSync {
                val root=activity.window.decorView
                assertTrue(root.width>0 && root.height>0)
                capture=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(capture))
            }
            try {
                // AGP can uninstall the target after connected tests; app-specific files vanish.
                // Publish the fixture screenshot to shared test media before that cleanup.
                val values=android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"unified-canvas.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
                    if(android.os.Build.VERSION.SDK_INT>=29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/WE-CI/")
                }
                val uri=requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
                requireNotNull(context.contentResolver.openOutputStream(uri)).use {assertTrue(capture.compress(Bitmap.CompressFormat.PNG,100,it))}
            } finally {capture.recycle()}
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
    @Test fun solidPolygonRendersAndSeedFillStaysInsideTheBoundary() {
        val page=CanvasEdits.apply(PaperPage(160,100,0,emptyList()),org.json.JSONArray("""[
            {"action":"rectangle","x":20,"y":20,"x2":140,"y2":80,"filled":true,"color":"#FF0000"},
            {"action":"fill","x":70,"y":50,"color":"#0000FF"}
        ]"""))
        LayerRenderer().use {renderer ->
            val image=raster(renderer,page)
            try {assertEquals(Color.BLUE,image.getPixel(70,50));assertEquals(0,Color.alpha(image.getPixel(5,5)))}
            finally {image.recycle()}
        }
    }
    @Test fun readyCanvasInboxSurvivesSubscriberAbsence() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val workspace=com.opc.baize.agent.WorkspaceFiles(context).create("MCP-inbox")
        try {
            val page=com.opc.baize.agent.WorkspaceDrawing.open(workspace,true)
            com.opc.baize.agent.WorkspaceDrawing.announce(workspace,page.pdf)
            assertEquals(page.pdf.canonicalPath,com.opc.baize.agent.WorkspaceDrawing.pending(workspace)?.path)
            java.io.File(workspace.container,"ready-canvas.json").writeText("""{"path":"/../../outside.pdf"}""")
            assertNull(com.opc.baize.agent.WorkspaceDrawing.pending(workspace))
        } finally {workspace.container.deleteRecursively()}
    }

    @Test fun navigationWorksImmediatelyInEveryToolAndBlocksExternalEditsUntilRelease() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view=PaperInkView(instrumentation.targetContext)
            view.layout(0,0,160,100)
            view.load(Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888),PaperPage(160,100,0,emptyList()))
            var clock=android.os.SystemClock.uptimeMillis()
            fun touch(action:Int,points:List<Pair<Float,Float>>) {
                val properties=points.indices.map {i -> android.view.MotionEvent.PointerProperties().apply {id=i;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
                val coords=points.map {p -> android.view.MotionEvent.PointerCoords().apply {x=p.first;y=p.second;pressure=1f;size=1f}}.toTypedArray()
                val event=android.view.MotionEvent.obtain(clock,clock+10,action,points.size,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()};clock+=20
            }
            try {
                for(mode in PaperInkView.Mode.values()) {
                    view.resetView();view.mode=mode
                    val before=view.context().getJSONObject("viewport").getDouble("left")
                    touch(android.view.MotionEvent.ACTION_DOWN,listOf(40f to 40f))
                    touch(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(40f to 40f,80f to 40f))
                    assertFalse(view.canAcceptExternal)
                    touch(android.view.MotionEvent.ACTION_MOVE,listOf(50f to 50f,90f to 50f))
                    assertEquals(before+10,view.context().getJSONObject("viewport").getDouble("left"),.01)
                    touch(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(50f to 50f,90f to 50f))
                    touch(android.view.MotionEvent.ACTION_UP,listOf(50f to 50f))
                    assertTrue(view.canAcceptExternal);assertTrue(view.page.strokes.isEmpty())
                }
                view.resetView();view.mode=PaperInkView.Mode.ERASE;view.stylusOnly=true
                touch(android.view.MotionEvent.ACTION_DOWN,listOf(40f to 40f))
                touch(android.view.MotionEvent.ACTION_MOVE,listOf(60f to 50f))
                touch(android.view.MotionEvent.ACTION_UP,listOf(60f to 50f))
                assertEquals(20.0,view.context().getJSONObject("viewport").getDouble("left"),.01)
                assertTrue(view.page.strokes.isEmpty())
            } finally {view.release()}
        }
    }

    @Test fun androidSvgGradientsClippingTextAndImagesUseTheSharedRenderer() {
        val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><defs><linearGradient id="g"><stop offset="0" stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient><clipPath id="c"><rect width="50" height="100"/></clipPath></defs><g clip-path="url(#c)"><rect width="100" height="100" fill="url(#g)"/></g></svg>"""
        fun op(type:String,content:String)=org.json.JSONObject().put("action",type).put("content",content).put("x",10).put("y",10).put("x2",90).put("y2",90)
        val page=CanvasEdits.apply(PaperPage(160,100,0,emptyList()),org.json.JSONArray().put(op("svg",svg)))
        ArtworkRenderer.validate(page.strokes.single())
        LayerRenderer().use {renderer -> val image=raster(renderer,page)
            try {assertTrue(Color.red(image.getPixel(20,50))>Color.blue(image.getPixel(20,50)));assertEquals(0,Color.alpha(image.getPixel(75,50)))} finally {image.recycle()}
        }
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GREEN)}
        val bytes=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle()
        val imagePage=CanvasEdits.apply(PaperPage(160,100,0,emptyList()),org.json.JSONArray().put(op("image",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))))
        LayerRenderer().use {renderer -> val image=raster(renderer,imagePage)
            try {assertEquals(Color.GREEN,image.getPixel(50,50))} finally {image.recycle()}
        }
        val textPage=CanvasEdits.apply(PaperPage(160,100,0,emptyList()),org.json.JSONArray().put(op("text","WE")))
        LayerRenderer().use {renderer -> val image=raster(renderer,textPage)
            try {val pixels=IntArray(160*100);image.getPixels(pixels,0,160,0,0,160,100);assertTrue(pixels.any {Color.alpha(it)>0})} finally {image.recycle()}
        }
    }
    @Test fun malformedSvgDoesNotCommitThroughMcpAndArtworkSurvivesHistory() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val workspace=com.opc.baize.agent.WorkspaceFiles(context).create("SVG-MCP-regression")
        try {
            val doc=com.opc.baize.agent.WorkspaceDrawing.open(workspace,true)
            val tools=com.opc.baize.mcp.WorkspaceMcpTools(context)
            fun args()=org.json.JSONObject().put("workspace_id",workspace.id).put("path","/"+doc.pdf.relativeTo(workspace.root).invariantSeparatorsPath)
            fun svg(source:String)=org.json.JSONObject().put("action","svg").put("content",source).put("x",10).put("y",10).put("x2",100).put("y2",100)
            val failed=tools.execute("edit_canvas",args().put("operation_id","bad-svg-${workspace.id}").put("expected_revision",0)
                .put("operations",org.json.JSONArray().put(svg("<svg viewBox='0 0 10 10'><g></svg>"))))
            assertEquals("failed",failed.getString("status"))
            assertEquals(0L,tools.execute("get_canvas_context",args()).getJSONObject("result").getLong("revision"))
            val source="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="red"/></svg>"""
            val success=tools.execute("edit_canvas",args().put("operation_id","good-svg-${workspace.id}").put("expected_revision",0).put("operations",org.json.JSONArray().put(svg(source))))
            assertEquals("succeeded",success.getString("status"))
            val undo=tools.execute("canvas_history",args().put("operation_id","undo-svg-${workspace.id}").put("expected_revision",1).put("action","undo"))
            assertEquals(0,undo.getJSONObject("result").getInt("stroke_count"))
            val redo=tools.execute("canvas_history",args().put("operation_id","redo-svg-${workspace.id}").put("expected_revision",2).put("action","redo"))
            assertEquals(1,redo.getJSONObject("result").getInt("stroke_count"))
            val exported=tools.execute("export_canvas",args().put("operation_id","export-svg-${workspace.id}").put("expected_revision",3))
            assertEquals("succeeded",exported.getString("status"));assertTrue(exported.getJSONObject("result").has("content"))
        } finally {workspace.container.deleteRecursively()}
    }

    @Test fun photoImportNormalizesExifOrientationBeforeEmbeddingPixels() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File.createTempFile("we-exif-",".jpg",context.cacheDir)
        try {
            val original=Bitmap.createBitmap(8,4,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GREEN)}
            file.outputStream().use {assertTrue(original.compress(Bitmap.CompressFormat.JPEG,100,it))};original.recycle()
            androidx.exifinterface.media.ExifInterface(file.path).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()
            }
            val imported=ArtworkImport.read(context,android.net.Uri.fromFile(file))
            assertEquals(4,imported.width);assertEquals(8,imported.height)
            val data=android.util.Base64.decode(imported.content,android.util.Base64.NO_WRAP)
            val decoded=android.graphics.BitmapFactory.decodeByteArray(data,0,data.size)
            try {assertEquals(4,decoded.width);assertEquals(8,decoded.height);assertTrue(Color.green(decoded.getPixel(1,1))>200)} finally {decoded.recycle()}
        } finally {file.delete()}
    }

    @Test fun pendingLocalSaveBlocksTheOfflineMcpPath() {
        val id=java.util.UUID.randomUUID().toString();val tasks=java.util.ArrayDeque<Runnable>()
        val queue=CanvasSaveQueue(java.util.concurrent.Executor {tasks.addLast(it)},{_,_,_->},{})
        val page=PaperPage(600,840,1,emptyList());var invoked=false
        try {
            queue.enqueue(id,0,page)
            assertThrows(Exception::class.java) {CanvasSession.change("workspace",id,0,0,{it}) {invoked=true;page}}
            assertFalse(invoked)
            tasks.removeFirst().run()
            assertEquals(page,CanvasSession.change("workspace",id,0,1,{it}) {invoked=true;page})
            assertTrue(invoked)
        } finally {while(tasks.isNotEmpty()) tasks.removeFirst().run()}
    }
    @Test fun coreToolsFitPhoneLandscapeAndTabletWithoutHorizontalScrolling() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val workspace=com.opc.baize.agent.WorkspaceFiles(context).create("responsive-canvas")
        val doc=com.opc.baize.agent.WorkspaceDrawing.open(workspace,true)
        val activity=instrumentation.startActivitySync(android.content.Intent(context,PaperActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("workspace",workspace.id).putExtra("generatedPdf",doc.pdf.path)) as PaperActivity
        fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
        try {
            val deadline=System.currentTimeMillis()+15000;var loaded=false
            while(!loaded && System.currentTimeMillis()<deadline) {
                instrumentation.runOnMainSync {loaded=views(activity.window.decorView).filterIsInstance<PaperInkView>().single().page.width>1}
                if(!loaded) Thread.sleep(30)
            }
            assertTrue(loaded)
            instrumentation.runOnMainSync {
                val root=(activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
                val density=activity.resources.displayMetrics.density
                val errors=mutableListOf<String>()
                for((label,size) in listOf("phone" to (320 to 568),"landscape" to (640 to 360),"tablet" to (800 to 600))) {
                    val w=(size.first*density).toInt();val h=(size.second*density).toInt()
                    repeat(3) {root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(h,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)}
                    val all=views(root)
                    val required=listOf("画笔","框选","橡皮","笔刷","图层","发送")+if(label=="landscape") listOf("撤销","重做") else emptyList()
                    for(name in required) {
                        val button=all.filterIsInstance<android.widget.Button>().filter {it.isShown}.single {it.contentDescription?.toString()==name || (name=="发送" && it.text=="发送")}
                        val bounds=android.graphics.Rect();button.getDrawingRect(bounds);root.offsetDescendantRectToMyCoords(button,bounds)
                        if(!(bounds.left>=0 && bounds.right<=w && bounds.top>=0 && bounds.bottom<=h)) errors.add("$label $name outside viewport: $bounds")
                        if(button.width<48*density) errors.add("$label $name touch target: ${button.width}/$density")
                    }
                    val canvasHeight=all.filterIsInstance<PaperInkView>().single().height
                    if(canvasHeight<h*.3f) errors.add("$label canvas too small: $canvasHeight of $h")
                    val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
                    try {
                        val values=android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"canvas-$label.png")
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
                            if(android.os.Build.VERSION.SDK_INT>=29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/WE-CI/")
                        }
                        val uri=requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
                        context.contentResolver.openOutputStream(uri)!!.use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
                    } finally {image.recycle()}
                }
                assertTrue(errors.joinToString("; "),errors.isEmpty())
            }
        } finally {instrumentation.runOnMainSync {activity.finish()};workspace.container.deleteRecursively()}
    }

    @Test fun failedSaveAndRetrySurviveActivityRecreation() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val workspace=com.opc.baize.agent.WorkspaceFiles(context).create("rotation-save-recovery")
        val doc=com.opc.baize.agent.WorkspaceDrawing.open(workspace,true)
        var activity=instrumentation.startActivitySync(android.content.Intent(context,PaperActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("workspace",workspace.id).putExtra("generatedPdf",doc.pdf.path)) as PaperActivity
        fun ink(a:PaperActivity)=PaperActivity::class.java.getDeclaredField("ink").apply {isAccessible=true}.get(a) as PaperInkView
        fun saves(a:PaperActivity)=PaperActivity::class.java.getDeclaredField("saveQueue").apply {isAccessible=true}.get(a) as CanvasSaveQueue
        fun await(check:()->Boolean) {val deadline=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<deadline) {var done=false;instrumentation.runOnMainSync {done=check()};if(done) return;Thread.sleep(30)};fail("Timed out waiting for canvas recovery")}
        var obstacle:java.io.File?=null
        try {
            await {ink(activity).page.width>1}
            var document=""
            instrumentation.runOnMainSync {document=PaperActivity::class.java.getDeclaredField("document").apply {isAccessible=true}.get(activity) as String}
            obstacle=java.io.File(context.filesDir,"papers/$document/page-0.ink.json").apply {assertTrue(mkdir())}
            instrumentation.runOnMainSync {assertTrue(ink(activity).applyArtwork(org.json.JSONArray("""[{"action":"line","x":20,"y":20,"x2":60,"y2":60}]""")))}
            await {saves(activity).state().error!=null}
            val previous=activity
            instrumentation.runOnMainSync {activity.recreate()}
            await {
                val resumed=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<PaperActivity>().firstOrNull {it!==previous}
                if(resumed!=null) activity=resumed
                activity!==previous && ink(activity).page.width>1
            }
            instrumentation.runOnMainSync {assertEquals(1,ink(activity).page.strokes.size);assertNotNull(saves(activity).state().error)}
            assertTrue(obstacle.delete());obstacle=null
            instrumentation.runOnMainSync {saves(activity).retry()}
            await {saves(activity).isIdle()}
            assertTrue(CanvasWriteGuard.isClear(document,0))
            assertEquals(1,PaperRepository(context).load(document,0,ink(activity).page.width,ink(activity).page.height).strokes.size)
        } finally {
            obstacle?.delete()
            instrumentation.runOnMainSync {if(!activity.isDestroyed) {saves(activity).retry();activity.finish()}}
            PaperIo.executor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            workspace.container.deleteRecursively()
        }
    }

}

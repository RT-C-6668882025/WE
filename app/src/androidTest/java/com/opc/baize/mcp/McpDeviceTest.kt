package com.opc.baize.mcp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class McpDeviceTest {
    @Test fun structuredPaperCanBeWrittenOnErasedAndUndoneInPlace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val folder = java.io.File(context.cacheDir, "writable-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = java.io.File(folder, "sample.md").apply { writeText("""{"we_page":1,"title":"WE · 手写学习页","blocks":[{"id":"q1","type":"question","text":"He ____ to school yesterday.\n请填写答案，并圈出判断时态的依据。","answer_lines":3},{"id":"frame","type":"figure","text":"沿辅助线补全形体","height":200,"shapes":[{"type":"line","x":0,"y":0,"x2":1000,"y2":1000},{"type":"line","x":0,"y":1000,"x2":1000,"y2":0},{"type":"rect","x":300,"y":300,"width":400,"height":400}]},{"id":"draw","type":"drawing","height":150,"grid":"dots"}]}""") }
            val pdf = java.io.File(folder, "sample.pdf")
            com.opc.baize.agent.LearningPageRenderer.render(source, pdf, true)
            val layout = com.opc.baize.agent.LearningPageRenderer.pageMap(pdf, 0)!!
            val regions = layout.getJSONArray("regions")
            val answer = (0 until regions.length()).map { regions.getJSONObject(it) }.first { it.getString("id") == "q1-answer" }
            org.junit.Assert.assertEquals("q1", answer.getString("parent_id"))
            com.opc.baize.paper.AndroidPdfEngine(pdf).use { engine ->
                org.junit.Assert.assertTrue(engine.pageCount >= 1)
                val rendered = engine.render(0)
                instrumentation.runOnMainSync {
                    val view = com.opc.baize.paper.PaperInkView(context)
                    view.layout(0, 0, 600, 840)
                    view.load(rendered.bitmap, com.opc.baize.paper.PaperPage(600, 840, 0, emptyList()))
                    val y = answer.getInt("y") + 40f
                    fun touch(action: Int, x: Float) { val event = android.view.MotionEvent.obtain(1, 2, action, x, y, 0); try { view.onTouchEvent(event) } finally { event.recycle() } }
                    try {
                        view.penColor = android.graphics.Color.BLUE; view.penWidth = 5f
                        touch(android.view.MotionEvent.ACTION_DOWN, 100f); touch(android.view.MotionEvent.ACTION_UP, 200f)
                        val snapshot = view.snapshot().first
                        try {
                            val pixel = snapshot.getPixel((150f * snapshot.width / 600).toInt(), (y * snapshot.height / 840).toInt())
                            org.junit.Assert.assertEquals(android.graphics.Color.BLUE, pixel)
                        } finally { snapshot.recycle() }
                        view.mode = com.opc.baize.paper.PaperInkView.Mode.ERASE
                        touch(android.view.MotionEvent.ACTION_DOWN, 150f); touch(android.view.MotionEvent.ACTION_UP, 150f)
                        org.junit.Assert.assertTrue(view.page.strokes.isEmpty()); view.undo()
                        org.junit.Assert.assertEquals(android.graphics.Color.BLUE, view.page.strokes.single().color)
                    } finally { view.release() }
                }
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun nativeHttpWorkspaceInkAndContinuedPages() = McpContract.verify(InstrumentationRegistry.getInstrumentation().targetContext, true)
    @Test fun foregroundEndpointStartsAndStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startForegroundService(android.content.Intent(context, McpService::class.java))
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (!McpService.running && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(25)
            org.junit.Assert.assertTrue(McpService.status, McpService.running)
            val client = okhttp3.OkHttpClient()
            try {
                client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:49327/health")
                    .header("Authorization", "Bearer " + McpService.localToken).build()).execute().use { org.junit.Assert.assertEquals(200, it.code) }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } finally {
            context.stopService(android.content.Intent(context, McpService::class.java))
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (McpService.running && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(25)
        }
        org.junit.Assert.assertFalse(McpService.running)
        org.junit.Assert.assertEquals("", McpService.localToken)
    }
    @Test fun pairingIsEncryptedAndSurvivesRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = McpPairing(context)
        org.junit.Assume.assumeTrue(store.read() == null)
        val token = "pairing-fixture-${java.util.UUID.randomUUID()}"
        try {
            store.save(org.json.JSONObject().put("deviceToken", token).put("base", "https://fixture.example"))
            org.junit.Assert.assertEquals(token, McpPairing(context).read()!!.getString("deviceToken"))
            val bytes = java.io.File(context.noBackupFilesDir, "we-mcp-pairing.enc").readBytes()
            org.junit.Assert.assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(token))
        } finally { store.clear() }
        org.junit.Assert.assertNull(McpPairing(context).read())
    }
}

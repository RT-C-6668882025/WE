package com.opc.baize.mcp

import android.content.Context
import com.opc.baize.agent.*
import com.opc.baize.paper.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual HTTP -> tool executor -> files -> continuation, unchanged on Android and JVM. */
object McpContract {
    fun verify(context: Context, devicePdf: Boolean) {
        val tools = if (devicePdf) WorkspaceMcpTools(context) else WorkspaceMcpTools(context) { s, p -> p.writeText("PDF fixture " + s.readText()) }
        val token = "fixture-secret-${UUID.randomUUID()}"
        val server = McpHttpServer(tools, token, 0); server.start(5000, true)
        val http = OkHttpClient()
        val prefix = UUID.randomUUID().toString()
        var workspace: WeWorkspace? = null
        fun request(method: String, params: JSONObject = JSONObject(), bearer: String = token, origin: String? = null): Pair<Int, JSONObject?> {
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
            val r = Request.Builder().url("http://127.0.0.1:${server.listeningPort}/mcp").header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json, text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType()))
            if (origin != null) r.header("Origin", origin)
            return http.newCall(r.build()).execute().use { it.code to it.body?.string()?.takeIf(String::isNotBlank)?.let(::JSONObject) }
        }
        fun call(name: String, a: JSONObject = JSONObject()): JSONObject = requireNotNull(request("tools/call", JSONObject().put("name", name).put("arguments", a)).second).getJSONObject("result")
        fun result(name: String, a: JSONObject) = call(name, a).getJSONObject("structuredContent")
        fun args(vararg values: Pair<String, Any>): JSONObject = JSONObject().apply { values.forEach { put(it.first, it.second) } }
        try {
            assertEquals(401, request("initialize", bearer = "wrong").first)
            assertEquals(403, request("initialize", origin = "https://untrusted.example").first)
            assertEquals("2025-11-25", request("initialize", args("protocolVersion" to "2025-11-25")).second!!.getJSONObject("result").getString("protocolVersion"))
            assertEquals(9, request("tools/list").second!!.getJSONObject("result").getJSONArray("tools").length())
            val create = args("name" to "MCP contract $prefix", "operation_id" to "$prefix-create")
            val first = result("create_workspace", create)
            val id = first.getJSONObject("result").getString("id")
            workspace = WorkspaceFiles(context).list().first { it.id == id }
            assertEquals(id, result("create_workspace", create).getJSONObject("result").getString("id"))
            assertEquals("failed", result("create_workspace", args("name" to "conflict", "operation_id" to "$prefix-create")).getString("status"))
            assertEquals(id, WorkspaceMcpTools(context).execute("get_operation_result", args("request_id" to "$prefix-create")).getJSONObject("result").getString("id"))
            val write = args("workspace_id" to id, "path" to "/.agents/skills/course/SKILL.md", "content" to "# COURSE_MARKER", "operation_id" to "$prefix-write")
            assertEquals("succeeded", result("write_file", write).getString("status"))
            val read = result("read_file", args("workspace_id" to id, "path" to "/.agents/skills/course/SKILL.md")).getJSONObject("result")
            assertTrue(read.getString("text").contains("COURSE_MARKER"))
            val edit = args("workspace_id" to id, "path" to "/.agents/skills/course/SKILL.md", "content" to "updated", "operation_id" to "$prefix-stale")
            assertEquals("failed", result("write_file", edit).getString("status"))
            edit.put("operation_id", "$prefix-update").put("expected_sha256", read.getString("sha256"))
            assertEquals("succeeded", result("write_file", edit).getString("status"))
            assertEquals("failed", result("read_file", args("workspace_id" to id, "path" to "/memory/../../subscription-session-v1.enc")).getString("status"))
            val external = File(workspace.container, "private.txt").apply { writeText("PRIVATE") }
            java.nio.file.Files.createSymbolicLink(File(workspace.root, "memory/leak.txt").toPath(), external.toPath())
            assertEquals("failed", result("read_file", args("workspace_id" to id, "path" to "/memory/leak.txt")).getString("status"))
            val publish = args("workspace_id" to id, "content" to "FIRST_LESSON\nHe ___ yesterday.\n请作答", "request" to "练习时态", "operation_id" to "$prefix-page")
            val page = result("publish_learning_page", publish).getJSONObject("result")
            val pdf = LearningTurn.latestReady(workspace)!!
            val original = pdf.readBytes()
            assertEquals(page.getString("path"), result("publish_learning_page", publish).getJSONObject("result").getString("path"))
            val repository = PaperRepository(context); val paperId = repository.importPdf(android.net.Uri.fromFile(pdf))
            val ink = PaperPage(600, 840, 1, listOf(PaperStroke(listOf(InkPoint(120f, 220f, 0.5f, 100L), InkPoint(180f, 240f, 0.5f, 120L)))))
            repository.save(paperId, 0, ink)
            if (devicePdf) {
                val response = call("read_page", args("workspace_id" to id, "path" to page.getString("path")))
                assertFalse(response.getBoolean("isError"))
                assertEquals("image", response.getJSONArray("content").getJSONObject(1).getString("type"))
                assertEquals(1L, response.getJSONObject("structuredContent").getJSONObject("result").getJSONObject("ink").getLong("revision"))
            }
            val next = result("publish_learning_page", args("workspace_id" to id, "content" to "SECOND_LESSON\nwent is the past tense of go.",
                "request" to "根据手写答案继续", "source_path" to page.getString("source_path"), "operation_id" to "$prefix-next"))
            assertEquals("succeeded", next.getString("status")); assertNotEquals(pdf, LearningTurn.latestReady(workspace))
            assertArrayEquals(original, pdf.readBytes()); assertEquals(ink, repository.load(paperId, 0, 600, 840))
            // Relay redelivery reaches the SAME native durable operation receipt.
            val relay = MockWebServer(); relay.start()
            try {
                val command = JSONObject().put("id", "$prefix-create").put("name", "create_workspace").put("arguments", args("name" to "MCP contract $prefix"))
                relay.enqueue(MockResponse().setBody(JSONArray().put(command).toString())); relay.enqueue(MockResponse().setBody("{}"))
                McpRelayClient(relay.url("/").toString().trimEnd('/'), "device-fixture", tools).use { assertEquals(1, it.poll()) }
                assertEquals("/device/poll", relay.takeRequest().path)
                val receipt = JSONObject(relay.takeRequest().body.readUtf8())
                assertEquals(id, receipt.getJSONObject("result").getString("id"))
            } finally { relay.shutdown() }
            // A killed operation's intent is NOT replayed or reported as success.
            val pending = File(context.noBackupFilesDir, "we-mcp/operations/$prefix-uncertain.json")
            LearningTurn.write(pending, JSONObject().put("status", "running").put("data_changed", "unknown").toString())
            assertEquals("running", tools.execute("get_operation_result", args("request_id" to "$prefix-uncertain")).getString("status"))
        } finally {
            server.stop(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            workspace?.container?.deleteRecursively()
            File(context.noBackupFilesDir, "we-mcp/operations").listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
        }
    }
}

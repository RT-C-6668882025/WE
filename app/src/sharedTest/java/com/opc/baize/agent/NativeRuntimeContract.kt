@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
package com.opc.baize.agent

import android.content.Context
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.model.OpenAiModelClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Runs unchanged in Robolectric AND an Android instrumentation process. All servers live in that process. */
object NativeRuntimeContract {
    fun verify(context: Context) = runBlocking {
        val id = UUID.randomUUID().toString()
        val workspace = WeWorkspace(id, "contract", File(context.cacheDir, "native-agent-$id").apply { mkdirs() })
        val skill = File(workspace.root, ".agents/skills/fixture/SKILL.md")
        skill.parentFile!!.mkdirs(); skill.writeText("---\nname: fixture\ndescription: Native fixture course\n---\nNATIVE_SKILL_MARKER\n")
        val mcpCalls = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rpc = JSONObject(request.body.readUtf8())
                if (!rpc.has("id")) return MockResponse().setResponseCode(202)
                val result = when (rpc.getString("method")) {
                    "initialize" -> JSONObject("""{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"android-fixture","version":"1"}}""")
                    "tools/list" -> JSONObject("""{"tools":[{"name":"add","description":"Add numbers","inputSchema":{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]},"annotations":{"readOnlyHint":true}}]}""")
                    "tools/call" -> {
                        val args = rpc.getJSONObject("params").getJSONObject("arguments")
                        assertEquals(12, args.getInt("a") + args.getInt("b")); mcpCalls.incrementAndGet()
                        JSONObject("""{"content":[{"type":"text","text":"12"}]}""")
                    }
                    else -> error("Unexpected MCP method")
                }
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("jsonrpc", "2.0").put("id", rpc.get("id")).put("result", result).toString())
            }
        }
        server.start()
        var round = 0
        val transport = SubscriptionAgentTransport({ raw ->
            val request = JSONObject(raw)
            assertFalse(request.getBoolean("store")); assertTrue(request.getBoolean("stream"))
            assertFalse(raw.contains("Bearer"))
            val serialized = request.getJSONArray("input").toString()
            val action = when (round++) {
                0 -> "read_file" to """{"path":"/.agents/skills/fixture/SKILL.md"}"""
                1 -> { assertTrue(serialized.contains("NATIVE_SKILL_MARKER")); "read_file" to """{"path":"/mcp/tools/mcp__fixture__add/schema.json"}""" }
                2 -> "mcp__fixture__add" to """{"a":7,"b":5}"""
                3 -> { assertTrue(serialized.contains("12")); "write_file" to """{"path":"/memory/pages/lesson.md","content":"NATIVE_LESSON_12"}""" }
                4 -> "read_file" to """{"path":"/memory/pages/lesson.md"}"""
                else -> { assertTrue(serialized.contains("NATIVE_LESSON_12")); "finish" to """{"response":"NATIVE_ANDROID_OK_12"}""" }
            }
            sseCall(round, action.first, action.second)
        }, File(workspace.container, "response-checkpoints.json"))
        val client = OpenAiModelClient({ "test-placeholder" }, model = "fixture", endpoint = "responses", stream = true,
            serverSideSearch = false, transport = transport)
        val runtime = NativeAgentHost.buildWithClient(context, workspace, client,
            JSONArray().put(JSONObject().put("id", "fixture").put("url", server.url("/mcp").toString())).toString())
        try {
            val events = runtime.run(AgentRequest("native-$id", "session-$id", "Use the fixture skill, add 7 and 5, then save and read the lesson.")).toList()
            assertTrue(events.toString(), events.none { it is AgentEvent.Failed || it is AgentEvent.ConfirmationRequired })
            assertEquals(1, mcpCalls.get())
            assertEquals("NATIVE_LESSON_12", File(workspace.root, "memory/pages/lesson.md").readText())
            assertTrue(events.toString(), events.filterIsInstance<AgentEvent.ResultAvailable>().any { it.result.response.contains("NATIVE_ANDROID_OK_12") })
            assertTrue(File(workspace.root, "runtime-session-store.sqlite").isFile)
            assertTrue(round >= 6)
        } finally {
            runtime.close(); server.shutdown(); workspace.container.deleteRecursively()
        }
    }
    private fun sseCall(round: Int, name: String, args: String): String {
        val item = JSONObject().put("type", "function_call").put("id", "fc_$round").put("call_id", "call_$round")
            .put("name", name).put("arguments", args).put("status", "completed")
        val response = JSONObject().put("id", "resp_$round").put("status", "completed").put("model", "fixture").put("output", JSONArray().put(item))
        val added = JSONObject().put("type", "response.output_item.added").put("output_index", 0).put("item", item)
        val done = JSONObject().put("type", "response.output_item.done").put("output_index", 0).put("item", item)
        val completed = JSONObject().put("type", "response.completed").put("response", response)
        return "data: $added\n\ndata: $done\n\ndata: $completed\n\n"
    }
}

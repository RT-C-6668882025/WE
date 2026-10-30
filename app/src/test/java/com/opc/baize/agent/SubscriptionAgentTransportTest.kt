package com.opc.baize.agent

import cc.noharry.agent.transport.HttpRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SubscriptionAgentTransportTest {
    @Test fun interruptedOrFailedStreamsExposeNoPartialToolCalls() = runBlocking {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "checkpoint-${UUID.randomUUID()}.json")
        val partial = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"write_file\",\"arguments\":\"{}\"}}\n\n"
        for (wire in listOf(partial, partial + "data: {\"type\":\"response.failed\"}\n\n", partial + "data: {\"type\":\"response.incomplete\"}\n\n")) {
            val seen = mutableListOf<cc.noharry.agent.transport.HttpEvent>()
            try {
                SubscriptionAgentTransport({ wire }, file).stream(request(JSONArray())).toList(seen)
                fail("Incomplete stream accepted")
            } catch (_: IllegalStateException) { assertTrue(seen.isEmpty()) }
        }
        assertFalse(file.exists())
    }
    @Test fun reasoningAndOriginalCallItemsSurviveTransportRecreation() = runBlocking {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "checkpoint-${UUID.randomUUID()}.json")
        val reasoning = JSONObject("""{"type":"reasoning","id":"rs1","encrypted_content":"signed-fixture","summary":[]}""")
        val call = JSONObject("""{"type":"function_call","id":"fc1","call_id":"c1","name":"read_file","arguments":"{}","status":"completed"}""")
        try {
            val initial = SubscriptionAgentTransport({ complete(JSONArray().put(reasoning).put(call)) }, file)
            initial.stream(request(JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))).toList()
            assertTrue(file.isFile)
            val restored = SubscriptionAgentTransport({ body ->
                val input = JSONObject(body).getJSONArray("input")
                assertEquals("signed-fixture", input.getJSONObject(0).getString("encrypted_content"))
                assertEquals("fc1", input.getJSONObject(1).getString("id"))
                assertEquals("c1", input.getJSONObject(2).getString("call_id"))
                assertEquals("reasoning.encrypted_content", JSONObject(body).getJSONArray("include").getString(0))
                complete(JSONArray())
            }, file)
            restored.stream(request(JSONArray().put(call).put(JSONObject().put("type", "function_call_output").put("call_id", "c1").put("output", "ok")))).toList()
        } finally { file.delete() }
    }
    private fun request(input: JSONArray) = HttpRequest("POST", "https://api.openai.com/v1/responses",
        body = JSONObject().put("model", "fixture").put("stream", true).put("input", input).toString().toByteArray())
    private fun complete(output: JSONArray): String = "data: " + JSONObject().put("type", "response.completed").put("response",
        JSONObject().put("id", "response-fixture").put("status", "completed").put("output", output)) + "\n\n"
}

package com.opc.baize.agent

import android.util.AtomicFile
import cc.noharry.agent.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Adapts the existing subscription to the upstream Responses serializer/parser in the same process. */
class SubscriptionAgentTransport(
    private val exchange: (String) -> String,
    checkpointFile: File
) : HttpTransport {
    private val journal = AtomicFile(checkpointFile)
    private val checkpoints = linkedMapOf<String, JSONObject>()
    init {
        if (checkpointFile.exists() || File(checkpointFile.path + ".bak").exists()) {
            val saved = journal.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            require(saved.length <= MAX_CHECKPOINT_BYTES) { "agent_checkpoint_too_large" }
            val values = JSONArray(saved)
            for (i in 0 until values.length()) {
                val record = values.getJSONObject(i)
                checkpoints[record.getString("callId")] = record
            }
        }
    }
    override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
        require(request.method == "POST" && request.url == "https://api.openai.com/v1/responses") { "unsupported_agent_route" }
        val body = JSONObject(requireNotNull(request.body).toString(Charsets.UTF_8))
        body.put("stream", true).put("store", false)
        body.put("include", JSONArray().put("reasoning.encrypted_content"))
        body.put("input", restoreReasoning(body.getJSONArray("input")))
        val wire = exchange(body.toString())
        val completed = completedResponse(wire) // Also validates test/fake transports, not only the real gateway.
        saveReasoning(completed)
        emit(HttpEvent.Response(200, mapOf("Content-Type" to listOf("text/event-stream"))))
        emit(HttpEvent.Data(wire.toByteArray(Charsets.UTF_8)))
        emit(HttpEvent.Done)
    }.flowOn(Dispatchers.IO)

    override suspend fun execute(request: HttpRequest): HttpResponse = error("streaming_responses_required")

    private fun restoreReasoning(input: JSONArray): JSONArray {
        val result = JSONArray()
        val inserted = mutableSetOf<String>()
        for (i in 0 until input.length()) {
            val item = input.getJSONObject(i)
            val record = if (item.optString("type") == "function_call") checkpoints[item.optString("call_id")] else null
            if (record != null) {
                val group = record.getString("group")
                if (inserted.add(group)) {
                    val reasoning = record.getJSONArray("reasoning")
                    for (j in 0 until reasoning.length()) result.put(reasoning.getJSONObject(j))
                }
                result.put(record.getJSONObject("call"))
            } else result.put(item)
        }
        return result
    }
    private fun saveReasoning(response: JSONObject) {
        val output = response.optJSONArray("output") ?: JSONArray()
        val reasoning = JSONArray()
        for (i in 0 until output.length()) if (output.getJSONObject(i).optString("type") == "reasoning") reasoning.put(output.getJSONObject(i))
        var changed = false
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "function_call") continue
            val id = item.getString("call_id")
            checkpoints[id] = JSONObject().put("callId", id).put("call", item).put("reasoning", reasoning).put("group", response.getString("id"))
            changed = true
        }
        if (!changed) return
        while (checkpoints.size > 128) checkpoints.remove(checkpoints.keys.first())
        val bytes = JSONArray(checkpoints.values.toList()).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CHECKPOINT_BYTES) { "agent_checkpoint_too_large" }
        journal.baseFile.parentFile?.mkdirs()
        val out = journal.startWrite()
        try { out.write(bytes); journal.finishWrite(out) }
        catch (e: Exception) { journal.failWrite(out); throw e }
    }
    companion object {
        private const val MAX_CHECKPOINT_BYTES = 8 * 1024 * 1024
        fun completedResponse(wire: String): JSONObject {
            require(wire.length <= 16 * 1024 * 1024) { "agent_response_too_large" }
            for (block in wire.replace("\r\n", "\n").split("\n\n")) {
                val data = block.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
                if (data.isBlank() || data == "[DONE]") continue
                val event = JSONObject(data)
                when (event.optString("type")) {
                    "error", "response.failed", "response.incomplete" -> error("agent_response_failed")
                    "response.completed" -> return event.getJSONObject("response").also { require(it.getString("status") == "completed") }
                }
            }
            error("agent_stream_interrupted")
        }
    }
}

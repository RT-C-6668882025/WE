package com.opc.baize.mcp

import org.json.JSONArray
import org.json.JSONObject

/** Only transports commands and device receipts. Model execution stays outside this transport. */
internal class PairingRevokedException : Exception("配对已失效")

internal class McpRelayClient(private val base: String, private val token: String, private val tools: WorkspaceMcpTools, private val onWorking: () -> Unit = {}) : java.io.Closeable {
    private val transport = WordTokTransport()
    @Volatile private var closed = false
    private var outbox: JSONObject? = null
    fun poll(): Int {
        outbox?.let { request("/device/result", it); outbox = null }
        val pending = JSONArray(request("/device/poll"))
        require(pending.length() <= 10)
        for (i in 0 until pending.length()) {
            if (closed) break
            val command = pending.getJSONObject(i)
            val id = command.getString("id"); require(id.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
            val name = command.getString("name")
            val args = command.getJSONObject("arguments")
            val definition = (0 until tools.definitions.length()).map { tools.definitions.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
            if (definition != null && !definition.getJSONObject("annotations").getBoolean("readOnlyHint")) args.put("operation_id", id)
            onWorking()
            val value = tools.execute(name, args)
            val receipt = JSONObject().put("id", id)
            if (value.optString("status") == "succeeded") receipt.put("result", value.getJSONObject("result"))
            else receipt.put("error", value.optString("error", "Device execution remains unconfirmed; inspect workspace before a new write"))
                .put("data_changed", value.opt("data_changed") ?: "unknown")
            outbox = receipt
            request("/device/result", receipt)
            outbox = null
        }
        return pending.length()
    }
    private fun request(path: String, data: JSONObject? = null): String {
        check(!closed)
        val response = transport.request(base + path, token, data)
        if (response.code == 401) throw PairingRevokedException()
        check(response.code in 200..299) { "中继 HTTP ${response.code}" }
        return response.body
    }
    override fun close() { closed = true; transport.close() }
}

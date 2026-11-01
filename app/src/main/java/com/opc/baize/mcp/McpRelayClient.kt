package com.opc.baize.mcp

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    companion object {
        fun http() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        fun validateBase(raw: String): String {
            val uri = java.net.URI(raw.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/")) { "请输入专用 WE 中继的 HTTPS 根地址" }
            return raw.trim().trimEnd('/')
        }
        fun pair(raw: String): JSONObject {
            val base = validateBase(raw)
            val transport = WordTokTransport()
            try {
                McpSetupFailure.at("检查服务器") {
                    val response = transport.request("$base/health", setup = true)
                    if (response.code !in 200..299) throw McpSetupFailure("检查服务器", "HTTP ${response.code}")
                    val health = JSONObject(response.body)
                    if (health.optString("name") != "write-everything" || health.optInt("protocol") != 2 || !health.optBoolean("persistent"))
                        throw McpSetupFailure("检查服务器", "服务协议不匹配，需要 WE protocol 2")
                }
                return McpSetupFailure.at("建立配对") {
                    // WordTok sends POST /pair without a JSON request body.
                    val response = transport.request("$base/pair", setup = true, pair = true)
                    if (response.code !in 200..299) throw McpSetupFailure("建立配对", "HTTP ${response.code}")
                    val pairing = JSONObject(response.body)
                    if (!pairing.getString("agentToken").matches(Regex("[0-9a-f]{64}")) || !pairing.getString("deviceToken").matches(Regex("[0-9a-f]{64}")))
                        throw McpSetupFailure("建立配对", "服务返回的配对格式不兼容")
                    pairing.put("base", base)
                }
            } finally { transport.close() }
        }
    }
}

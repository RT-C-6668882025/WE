package com.opc.baize.mcp

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Only transports commands and device receipts. Model execution stays outside this transport. */
internal class PairingRevokedException : Exception("配对已失效")

internal class McpRelayClient(private val base: String, private val token: String, private val tools: WorkspaceMcpTools) : java.io.Closeable {
    private val client = http()
    @Volatile private var active: Call? = null
    @Volatile private var closed = false
    fun poll(): Int {
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
            val value = tools.execute(name, args)
            val receipt = JSONObject().put("id", id)
            if (value.optString("status") == "succeeded") receipt.put("result", value.getJSONObject("result"))
            else receipt.put("error", value.optString("error", "Device execution remains unconfirmed; inspect workspace before a new write"))
                .put("data_changed", value.opt("data_changed") ?: "unknown")
            request("/device/result", receipt)
        }
        return pending.length()
    }
    private fun request(path: String, data: JSONObject? = null): String {
        check(!closed)
        val request = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
        if (data != null) request.post(data.toString().toRequestBody("application/json".toMediaType()))
        val call = client.newCall(request.build()); active = call
        if (closed) call.cancel()
        return call.execute().use { response ->
            if(response.code==401) throw PairingRevokedException()
            check(response.isSuccessful) { "中继 HTTP ${response.code}" }
            val body = requireNotNull(response.body)
            body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 1048576); out.write(buffer, 0, n) }
                out.toString("UTF-8")
            }
        }
    }
    override fun close() { closed = true; active?.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    companion object {
        fun http() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        fun validateBase(raw: String): String {
            val uri = java.net.URI(raw.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/")) { "请输入专用 WE 中继的 HTTPS 根地址" }
            return raw.trim().trimEnd('/')
        }
        fun pair(raw: String): JSONObject {
            val base = validateBase(raw); val http = http()
            try {
                McpSetupFailure.at("检查服务器") {http.newCall(Request.Builder().url("$base/health").build()).execute().use {
                    if(!it.isSuccessful) throw McpSetupFailure("检查服务器","HTTP ${it.code}")
                    val health = JSONObject(requireNotNull(it.body).string())
                    if(health.optString("name")!="write-everything" || health.optInt("protocol")!=2 || !health.optBoolean("persistent")) throw McpSetupFailure("检查服务器","服务协议不匹配，需要 WE protocol 2")
                }}
                return McpSetupFailure.at("建立配对") {http.newCall(Request.Builder().url("$base/pair").post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use {
                    if(!it.isSuccessful) throw McpSetupFailure("建立配对","HTTP ${it.code}")
                    val p = JSONObject(requireNotNull(it.body).string())
                    if(!p.getString("agentToken").matches(Regex("[0-9a-f]{64}")) || !p.getString("deviceToken").matches(Regex("[0-9a-f]{64}"))) throw McpSetupFailure("建立配对","服务返回的配对格式不兼容")
                    p.put("base", base)
                }}
            } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
        }
    }
}

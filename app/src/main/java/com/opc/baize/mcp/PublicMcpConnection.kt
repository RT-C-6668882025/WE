package com.opc.baize.mcp

import org.json.JSONObject

/** One-click setup, with a process-wide lock so Activity recreation cannot create two identities. */
internal class PublicMcpConnection(
    private val load: () -> JSONObject?,
    private val save: (JSONObject) -> Unit,
    private val pair: (String) -> JSONObject = {McpRelayClient.pair(it)},
    private val clear: () -> Unit = {}
) {
    companion object {
        const val DEFAULT_RELAY = "https://we.wordrelay.dpdns.org"
        private val lock = Any()
        fun address(pairing: JSONObject): String {
            val base=McpRelayClient.validateBase(pairing.getString("base"))
            require(pairing.getString("agentToken").matches(Regex("[0-9a-f]{64}"))) {"无效的配对配置"}
            require(pairing.getString("deviceToken").matches(Regex("[0-9a-f]{64}"))) {"无效的配对配置"}
            return "$base/mcp/${pairing.getString("agentToken")}"
        }
    }
    fun ensure(): JSONObject = synchronized(lock) {
        val existing=McpSetupFailure.at("读取已保存配对",load)
        if(existing!=null) {McpSetupFailure.at("检查已保存配对") {address(existing)};return@synchronized existing}
        val created=McpSetupFailure.at("连接 WE 服务") {pair(DEFAULT_RELAY)}
        McpSetupFailure.at("检查配对响应") {address(created)}
        McpSetupFailure.at("保存配对凭据") {save(created)}
        created
    }
    fun revoke(closeRemote: (JSONObject) -> Unit) = synchronized(lock) {
        load()?.let(closeRemote)
        clear()
    }
}

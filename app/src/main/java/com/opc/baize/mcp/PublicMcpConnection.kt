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
        val existing=load()
        if(existing!=null) {address(existing);return@synchronized existing}
        val created=pair(DEFAULT_RELAY)
        address(created)
        save(created)
        created
    }
    fun revoke(closeRemote: (JSONObject) -> Unit) = synchronized(lock) {
        load()?.let(closeRemote)
        clear()
    }
}

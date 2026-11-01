package com.opc.baize.mcp

import org.json.JSONObject

/** WordTok connection shape at the WebView boundary; preserve existing WE encrypted identities. */
internal object McpConnectionConfig {
    const val DEFAULT_RELAY = "https://we.wordrelay.dpdns.org:8444"
    /** Only the former official WE 443 endpoint moves. Custom relays and credentials stay intact. */
    fun migrateLegacyEndpoint(pairing: JSONObject): JSONObject? {
        toWordTok(pairing)
        val uri=java.net.URI(pairing.getString("base"))
        if(!uri.host.equals("we.wordrelay.dpdns.org",ignoreCase=true) || uri.port !in setOf(-1,443))return null
        return JSONObject(pairing.toString()).put("base",DEFAULT_RELAY)
    }
    fun validateBase(raw: String): String {
        val uri=java.net.URI(raw)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.orEmpty() in setOf("", "/"))
        return raw.trimEnd('/')
    }
    fun toWordTok(pairing: JSONObject): JSONObject {
        val base=validateBase(pairing.getString("base"))
        val agent=pairing.getString("agentToken");val device=pairing.getString("deviceToken")
        require(agent.matches(Regex("[0-9a-f]{64}")) && device.matches(Regex("[0-9a-f]{64}")))
        return JSONObject().put("url",base).put("token",device).put("mcpUrl","$base/mcp/$agent")
    }
    fun fromWordTok(connection: JSONObject): JSONObject {
        val base=validateBase(connection.getString("url"))
        val address=connection.getString("mcpUrl")
        require(address.startsWith("$base/mcp/"))
        val result=JSONObject().put("base",base).put("deviceToken",connection.getString("token"))
            .put("agentToken",address.removePrefix("$base/mcp/"))
        toWordTok(result)
        return result
    }
}

package com.opc.baize.mcp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class McpConnectionConfigTest {
    @Test fun existingEncryptedPairMigratesWithoutChangingItsIdentity() {
        val old=JSONObject().put("base","https://existing.example").put("agentToken","a".repeat(64)).put("deviceToken","b".repeat(64))
        val c=McpConnectionConfig.toWordTok(old)
        assertEquals("https://existing.example/mcp/"+"a".repeat(64),c.getString("mcpUrl"))
        val roundTrip=McpConnectionConfig.fromWordTok(c)
        assertEquals(old.getString("agentToken"),roundTrip.getString("agentToken"))
        assertEquals(old.getString("deviceToken"),roundTrip.getString("deviceToken"))
    }
    @Test fun credentialUrlMustBelongToTheSavedRelay() {
        val c=JSONObject().put("url",McpConnectionConfig.DEFAULT_RELAY).put("token","b".repeat(64)).put("mcpUrl","https://other.example/mcp/"+"a".repeat(64))
        try {McpConnectionConfig.fromWordTok(c);fail("mismatched URL accepted")}catch(_:IllegalArgumentException){}
    }
}

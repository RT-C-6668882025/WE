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
    @Test fun official443PairsMoveTo8444WithoutChangingCredentialsOrExtraFields() {
        for(base in listOf("https://we.wordrelay.dpdns.org","https://we.wordrelay.dpdns.org/","https://we.wordrelay.dpdns.org:443")) {
            val old=JSONObject().put("base",base).put("agentToken","a".repeat(64)).put("deviceToken","b".repeat(64)).put("fixture","keep")
            val moved=requireNotNull(McpConnectionConfig.migrateLegacyEndpoint(old))
            assertEquals(McpConnectionConfig.DEFAULT_RELAY,moved.getString("base"))
            assertTrue(old.getString("agentToken")==moved.getString("agentToken"))
            assertTrue(old.getString("deviceToken")==moved.getString("deviceToken"))
            assertEquals("keep",moved.getString("fixture"));assertEquals(base,old.getString("base"))
            assertNull(McpConnectionConfig.migrateLegacyEndpoint(moved))
        }
    }
    @Test fun customRelaysAndOtherPortsAreNotMigrated() {
        for(base in listOf("https://custom.example","https://wordrelay.dpdns.org:8443", "https://we.wordrelay.dpdns.org:8443",McpConnectionConfig.DEFAULT_RELAY)) {
            val pair=JSONObject().put("base",base).put("agentToken","a".repeat(64)).put("deviceToken","b".repeat(64))
            assertNull(McpConnectionConfig.migrateLegacyEndpoint(pair))
        }
    }
}

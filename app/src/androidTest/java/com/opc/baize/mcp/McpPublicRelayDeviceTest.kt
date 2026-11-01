package com.opc.baize.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Isolated test pairing; never logs tokens or changes another device's pairing. */
@RunWith(AndroidJUnit4::class)
class McpPublicRelayDeviceTest {
    @Test fun configuredPublicRelayPairsPersistsAndInitializesOnAndroid() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=McpPairing(context)
        var pairing:JSONObject?=null
        try {
            val created=McpRelayClient.pair(PublicMcpConnection.DEFAULT_RELAY)
            pairing=created
            store.save(created)
            val saved=requireNotNull(store.read())
            assertTrue(PublicMcpConnection.address(saved).startsWith(PublicMcpConnection.DEFAULT_RELAY+"/mcp/"))
            val transport=WordTokTransport()
            try {
                val request=JSONObject().put("jsonrpc","2.0").put("id",1).put("method","initialize")
                    .put("params",JSONObject().put("protocolVersion","2025-11-25").put("capabilities",JSONObject())
                        .put("clientInfo",JSONObject().put("name","we-device-check").put("version","1")))
                val response=transport.request(PublicMcpConnection.address(saved),body=request)
                assertTrue("MCP initialize HTTP failure",response.code in 200..299)
                assertTrue("MCP initialize response invalid",JSONObject(response.body).has("result"))
            } finally {transport.close()}
        } catch(e:Exception) {fail(McpSetupFailure.describe(e))}
        finally {
            val cleanup=pairing
            if(cleanup!=null) {
                val transport=WordTokTransport()
                try {
                    val response=transport.request(PublicMcpConnection.DEFAULT_RELAY+"/device/close",
                        cleanup.getString("deviceToken"),JSONObject())
                    assertTrue("Isolated test pairing cleanup failed",response.code in 200..299 || response.code==401)
                } finally {transport.close();store.clear()}
            }
        }
    }
}

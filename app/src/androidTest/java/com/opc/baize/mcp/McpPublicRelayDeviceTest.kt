package com.opc.baize.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
            val http=McpRelayClient.http()
            try {
                val request=JSONObject().put("jsonrpc","2.0").put("id",1).put("method","initialize")
                    .put("params",JSONObject().put("protocolVersion","2025-11-25").put("capabilities",JSONObject())
                        .put("clientInfo",JSONObject().put("name","we-device-check").put("version","1")))
                http.newCall(Request.Builder().url(PublicMcpConnection.address(saved))
                    .post(request.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                    assertTrue("MCP initialize HTTP failure",it.isSuccessful)
                    assertTrue("MCP initialize response invalid",JSONObject(it.body!!.string()).has("result"))
                }
            } finally {http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
        } catch(e:Exception) {fail(McpSetupFailure.describe(e))}
        finally {
            val cleanup=pairing
            if(cleanup!=null) {
                val http=McpRelayClient.http()
                try {
                    http.newCall(Request.Builder().url(PublicMcpConnection.DEFAULT_RELAY+"/device/close")
                        .header("Authorization","Bearer "+cleanup.getString("deviceToken"))
                        .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use {
                            assertTrue("Isolated test pairing cleanup failed",it.isSuccessful || it.code==401)
                        }
                } finally {http.connectionPool.evictAll();http.dispatcher.executorService.shutdown();store.clear()}
            }
        }
    }
}

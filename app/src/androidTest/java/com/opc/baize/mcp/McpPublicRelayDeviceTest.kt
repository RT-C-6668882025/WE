package com.opc.baize.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Uses the production WebView fetch pairing, encrypted store and native service; no native pairing fallback. */
@RunWith(AndroidJUnit4::class)
class McpPublicRelayDeviceTest {
    @Test fun configuredPublicRelayPairsPersistsAndInitializesOnAndroid() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val store=McpPairing(context)
        assumeTrue(store.read()==null)
        var activity:android.app.Activity?=null
        var pairing:JSONObject?=null
        try {
            val opened=instrumentation.startActivitySync(android.content.Intent(context,McpSettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            activity=opened
            val page=McpBrowserTestSupport.webView(opened)
            McpBrowserTestSupport.waitReady(instrumentation,page)
            val result=McpBrowserTestSupport.run(instrumentation,page,
                "try{await window.weConnection.connectAi('${McpConnectionConfig.DEFAULT_RELAY}');return 'paired';}catch(e){return e.message==='Failed to fetch'?'public-fetch-failed':'public-pairing-failed';}")
            pairing=store.read()
            assertEquals("Production WebView fetch could not pair with WE public relay", "\"paired\"",result)
            val saved=requireNotNull(pairing)
            assertTrue("Encrypted pairing identity changed",saved.getString("deviceToken")==McpPairing(context).read()!!.getString("deviceToken"))
            val config=McpConnectionConfig.toWordTok(saved)
            val transport=WordTokTransport()
            try {
                val request=JSONObject().put("jsonrpc","2.0").put("id",1).put("method","initialize")
                    .put("params",JSONObject().put("protocolVersion","2025-11-25").put("capabilities",JSONObject())
                        .put("clientInfo",JSONObject().put("name","we-device-check").put("version","1")))
                val response=transport.request(config.getString("mcpUrl"),body=request)
                assertTrue("MCP initialize HTTP failure",response.code in 200..299)
                assertTrue("MCP initialize response invalid",JSONObject(response.body).has("result"))
            }catch(_:Exception){fail("MCP initialize connection failed")}finally{transport.close()}
            context.startForegroundService(android.content.Intent(context,McpService::class.java))
            val deadline=android.os.SystemClock.elapsedRealtime()+20000
            while(McpService.connectionState!="ready" && android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(50)
            assertEquals("Native device polling never became ready","ready",McpService.connectionState)
            val closed=McpBrowserTestSupport.run(instrumentation,page,"await window.weConnection.disconnectAi(await window.weConnection.loadPair());return 'closed';")
            assertEquals("Browser pairing revocation failed","\"closed\"",closed)
            pairing=null
        } finally {
            context.stopService(android.content.Intent(context,McpService::class.java))
            val cleanup=pairing ?: store.read()
            if(cleanup!=null) {
                val transport=WordTokTransport()
                try {val response=transport.request(cleanup.getString("base")+"/device/close",cleanup.getString("deviceToken"),JSONObject())
                    assertTrue("Isolated pairing cleanup failed",response.code in 200..299 || response.code==401)
                }finally{transport.close();store.clear()}
            }
            activity?.let{a->instrumentation.runOnMainSync{a.finish()}}
        }
    }
}

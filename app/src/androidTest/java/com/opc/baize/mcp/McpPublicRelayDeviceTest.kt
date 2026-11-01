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
        val fixture=com.opc.baize.agent.WorkspaceFiles(context).create("MCP readback probe")
        val expected="WE public relay file readback"
        java.io.File(fixture.root,"memory/relay-probe.md").writeText(expected)
        var activity:android.app.Activity?=null
        var pairing:JSONObject?=null
        try {
            val opened=instrumentation.startActivitySync(android.content.Intent(context,McpSettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            activity=opened
            val page=McpBrowserTestSupport.webView(opened)
            McpBrowserTestSupport.waitReady(instrumentation,page)
            val result=McpBrowserTestSupport.run(instrumentation,page,
                "try{await window.weConnection.connectAi('${McpConnectionConfig.DEFAULT_RELAY}');return 'paired';}catch(e){const we=e.message==='Failed to fetch'?'we-fetch-failed':'we-pairing-failed';try{const r=await fetch('https://wordrelay.dpdns.org:8443/health',{signal:AbortSignal.timeout(15000)});return we+';wordtok-health-http-'+r.status;}catch{return we+';wordtok-fetch-failed';}}")
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
            val toolsTransport=WordTokTransport()
            try {
                var rpcId=10
                fun call(name:String,args:JSONObject):JSONObject {
                    val request=JSONObject().put("jsonrpc","2.0").put("id",rpcId++).put("method","tools/call")
                        .put("params",JSONObject().put("name",name).put("arguments",args))
                    val response=toolsTransport.request(config.getString("mcpUrl"),body=request)
                    assertTrue("$name public HTTP request failed",response.code in 200..299)
                    val result=JSONObject(response.body).getJSONObject("result")
                    assertTrue("$name missing device receipt",result.has("structuredContent"))
                    return result.getJSONObject("structuredContent")
                }
                val workspaces=call("list_workspaces",JSONObject().put("query",fixture.name))
                assertEquals("Public workspace listing did not execute on Android","succeeded",workspaces.optString("status"))
                val rows=workspaces.getJSONObject("result").getJSONArray("workspaces")
                assertTrue("Fixture workspace missing from public result",(0 until rows.length()).any {rows.getJSONObject(it).getString("id")==fixture.id})
                val files=call("list_files",JSONObject().put("workspace_id",fixture.id))
                assertEquals("Public file listing did not return a saved device result","succeeded",files.optString("status"))
                val entries=files.getJSONObject("result").getJSONArray("files")
                assertTrue("Fixture file missing from public result",(0 until entries.length()).any {entries.getJSONObject(it).getString("path")=="/memory/relay-probe.md"})
                val read=call("read_file",JSONObject().put("workspace_id",fixture.id).put("path","/memory/relay-probe.md"))
                assertEquals("Public file reading did not succeed","succeeded",read.optString("status"))
                assertEquals(expected,read.getJSONObject("result").getString("text"))
                val missing=call("list_files",JSONObject().put("workspace_id","00000000-0000-0000-0000-000000000000"))
                assertEquals("Invalid workspace should be a tool error, not a disconnect","failed",missing.optString("status"))
                val recovered=call("list_files",JSONObject().put("workspace_id",fixture.id))
                assertEquals("Connection did not survive a tool error","succeeded",recovered.optString("status"))
            }catch(_:Exception){fail("Public workspace/file tool roundtrip failed")}finally{toolsTransport.close()}
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
            fixture.container.deleteRecursively()
        }
    }
}

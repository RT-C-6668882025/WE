package com.opc.baize.mcp

import android.webkit.*
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class McpWebViewDeviceTest {
    class FixtureBridge(private val activity:android.app.Activity,private val relay:String) {
        lateinit var page:WebView
        private val main=android.os.Handler(android.os.Looper.getMainLooper())
        private val store=McpPairing(activity)
        @JavascriptInterface fun request(id:String,method:String,payload:String) {
            val value:Any?=when(method) {
                "load" -> store.read()?.let{McpConnectionConfig.toWordTok(it)}
                "save" -> {store.save(McpConnectionConfig.fromWordTok(JSONObject(payload)));true}
                "clear" -> {store.clear();true}
                "defaultRelay" -> relay
                "state" -> JSONObject().put("state","ready")
                "backgroundStatus","start","stop" -> true
                else -> null
            }
            val result=JSONObject().put("id",id).put("value",value?:JSONObject.NULL).put("error",JSONObject.NULL)
            main.post{page.evaluateJavascript("window.weNativeReply($result)",null)}
        }
    }
    @Test fun wordTokPagePairsOverRealBrowserHttpsAndUsesFixedAddress() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val store=McpPairing(context);assumeTrue(store.read()==null)
        val held=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(held).build()
        val requests=LinkedBlockingQueue<RecordedRequest>()
        val server=MockWebServer()
        server.useHttps(tls.sslSocketFactory(),false)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            requests.offer(request)
            val response=MockResponse().addHeader("Access-Control-Allow-Origin","https://localhost")
                .addHeader("Access-Control-Allow-Headers","Content-Type,Authorization")
                .addHeader("Access-Control-Allow-Methods","GET,POST,OPTIONS")
            if(request.method=="OPTIONS")return response.setResponseCode(204)
            return response.setBody(when(request.path){
                "/health"->"{\"protocol\":2,\"persistent\":true}"
                "/pair"->JSONObject().put("agentToken","a".repeat(64)).put("deviceToken","b".repeat(64)).toString()
                else->"{}"
            })
        }}
        server.start()
        var activity:android.app.Activity?=null;var browser:WebView?=null
        try {
            val opened=instrumentation.startActivitySync(android.content.Intent(context,McpSettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            activity=opened
            instrumentation.runOnMainSync {
                val bridge=FixtureBridge(opened,server.url("/").toString().trimEnd('/'))
                // Accept only this generated local fixture certificate; production client never overrides TLS errors.
                val client=object:McpWebPageClient(opened){override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){
                    val expected=android.net.http.SslCertificate(held.certificate)
                    if(error.url.startsWith(server.url("/").toString()) && error.certificate.issuedTo.dName==expected.issuedTo.dName)handler.proceed() else handler.cancel()
                }}
                val page=McpWebPage.create(opened,bridge,client);bridge.page=page;browser=page;opened.setContentView(page)
            }
            val page=requireNotNull(browser)
            McpBrowserTestSupport.waitReady(instrumentation,page)
            val flow=McpBrowserTestSupport.run(instrumentation,page,"document.getElementById('pair').click();return 'clicked';")
            assertEquals("\"clicked\"",flow)
            val deadline=android.os.SystemClock.elapsedRealtime()+15000
            while(store.read()==null && android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(50)
            val saved=store.read();assertNotNull("Browser HTTPS pairing was not saved",saved)
            val first=requests.poll(2,TimeUnit.SECONDS)!!;val second=requests.poll(2,TimeUnit.SECONDS)!!
            assertEquals("/health",first.path);assertEquals("GET",first.method)
            assertEquals("/pair",second.path);assertEquals("POST",second.method);assertEquals(0L,second.bodySize)
            assertEquals("https://localhost",second.getHeader("Origin"))
            val shown=McpBrowserTestSupport.run(instrumentation,page,
                "await new Promise(r=>setTimeout(r,150));const a=document.getElementById('ai-address');return !document.getElementById('endpoint').hidden && a.value===(await window.weConnection.loadPair()).mcpUrl && document.querySelector('input[type=checkbox]')===null && !document.body.textContent.includes('高级设置');")
            assertEquals("\"true\"",shown)
            val closed=McpBrowserTestSupport.run(instrumentation,page,"await window.weConnection.disconnectAi(await window.weConnection.loadPair());return 'closed';")
            assertEquals("\"closed\"",closed);assertNull(store.read())
            val preflight=requests.poll(2,TimeUnit.SECONDS)!!
            assertEquals("OPTIONS",preflight.method)
            val close=requests.poll(2,TimeUnit.SECONDS)!!
            assertEquals("POST",close.method);assertEquals("/device/close",close.path)
            assertEquals("Bearer "+"b".repeat(64),close.getHeader("Authorization"))
        }finally{
            store.clear()
            browser?.let{page->instrumentation.runOnMainSync{page.removeJavascriptInterface("WeNative");page.destroy()}}
            activity?.let{a->instrumentation.runOnMainSync{a.finish()}}
            server.shutdown()
        }
    }
}

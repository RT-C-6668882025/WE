package com.opc.baize.mcp

import android.app.Activity
import android.content.*
import android.os.*
import android.webkit.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** WordTok's WebView fetch pairing + native background service, with no native pairing fallback. */
class McpSettingsActivity : Activity() {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var page:WebView
    @Volatile private var alive=true
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        page=McpWebPage.create(this,Bridge())
        setContentView(page)
    }
    inner class Bridge {
        @JavascriptInterface fun request(id:String,method:String,payload:String) {
            if(!id.matches(Regex("[0-9]{1,10}")) || !alive) return
            worker.execute {
                try {
                    val store=McpPairing(this@McpSettingsActivity)
                    when(method) {
                        "load" -> complete(id,store.read()?.let {McpConnectionConfig.toWordTok(it)})
                        "save" -> {store.save(McpConnectionConfig.fromWordTok(JSONObject(payload)));complete(id,true)}
                        "clear" -> {store.clear();complete(id,true)}
                        "defaultRelay" -> complete(id,McpConnectionConfig.DEFAULT_RELAY)
                        "state" -> complete(id,JSONObject().put("state",McpService.connectionState).put("message",McpService.status))
                        "backgroundStatus" -> complete(id,getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                        "start" -> onMain(id) {
                            requireNotNull(store.read())
                            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),44)
                            startForegroundService(Intent(this@McpSettingsActivity,McpService::class.java));true
                        }
                        "stop" -> onMain(id) {stopService(Intent(this@McpSettingsActivity,McpService::class.java));true}
                        "allowBackground" -> onMain(id) {
                            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:$packageName")));true
                        }
                        else -> complete(id,null,"未知连接操作")
                    }
                } catch(_:Exception) {complete(id,null,"连接设置未完成，请重试")}
            }
        }
    }
    private fun onMain(id:String,action:()->Any) {main.post {if(alive) {
        try {complete(id,action())} catch(_:Exception) {complete(id,null,"连接操作未完成，请重试")}
    }}}
    private fun complete(id:String,value:Any?,error:String?=null) {
        val response=JSONObject().put("id",id).put("value",value?:JSONObject.NULL).put("error",error?:JSONObject.NULL)
        main.post {if(alive) page.evaluateJavascript("window.weNativeReply($response)",null)}
    }
    override fun onResume() {
        super.onResume();page.onResume()
        runCatching {McpService.resume(this)}
    }
    override fun onPause() {page.onPause();super.onPause()}
    override fun onDestroy() {
        alive=false;main.removeCallbacksAndMessages(null)
        page.removeJavascriptInterface("WeNative");page.stopLoading();page.destroy();worker.shutdown()
        super.onDestroy()
    }
}

/** A secure localhost origin, matching Capacitor's androidScheme=https default in WordTok. */
internal object McpWebPage {
    const val URL="https://localhost/mcp/index.html"
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun create(context:android.content.Context,bridge:Any,client:McpWebPageClient=McpWebPageClient(context)):WebView = WebView(context).apply {
        settings.javaScriptEnabled=true
        settings.domStorageEnabled=true
        settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        addJavascriptInterface(bridge,"WeNative")
        webViewClient=client
        webChromeClient=object:WebChromeClient() {
            override fun onJsConfirm(view:WebView,url:String,message:String,result:JsResult):Boolean {
                val activity=context as? Activity ?: return false
                android.app.AlertDialog.Builder(activity).setMessage(message).setNegativeButton("取消") {_,_->result.cancel()}
                    .setPositiveButton("确定") {_,_->result.confirm()}.setOnCancelListener {result.cancel()}.show()
                return true
            }
            override fun onConsoleMessage(message:ConsoleMessage):Boolean = true
        }
        loadUrl(URL)
    }
}
internal open class McpWebPageClient(private val context:android.content.Context):WebViewClient() {
    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean = true
    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
        val uri=request.url
        if(uri.scheme!="https" || uri.host!="localhost" || uri.port !in setOf(-1,443)) return null
        val name=uri.path?.removePrefix("/mcp/") ?: return denied()
        val mime=when(name) {"index.html"->"text/html";"connection.js","page.js"->"application/javascript";"style.css"->"text/css";else->return denied()}
        return WebResourceResponse(mime,"UTF-8",context.assets.open("mcp/$name"))
    }
    private fun denied()=WebResourceResponse("text/plain","UTF-8",404,"Not Found",emptyMap(),java.io.ByteArrayInputStream(ByteArray(0)))
}

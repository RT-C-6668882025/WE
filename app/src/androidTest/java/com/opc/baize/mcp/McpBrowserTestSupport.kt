package com.opc.baize.mcp

import android.app.Activity
import android.app.Instrumentation
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object McpBrowserTestSupport {
    fun webView(activity:Activity):WebView {
        fun find(v:android.view.View):WebView? {
            if(v is WebView)return v
            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        return requireNotNull(find(activity.window.decorView))
    }
    fun waitReady(instrumentation:Instrumentation,page:WebView) {
        val deadline=android.os.SystemClock.elapsedRealtime()+15000
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            val done=CountDownLatch(1);var ready=false
            instrumentation.runOnMainSync {page.evaluateJavascript("Boolean(window.weConnection && !document.getElementById('pair').disabled)"){ready=it=="true";done.countDown()}}
            done.await(2,TimeUnit.SECONDS)
            if(ready)return
            android.os.SystemClock.sleep(50)
        }
        error("WordTok browser module did not initialize")
    }
    fun run(instrumentation:Instrumentation,page:WebView,script:String,timeout:Long=40):String {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout)
        val done=CountDownLatch(1);var value=""
        instrumentation.runOnMainSync {page.evaluateJavascript("window.weTestResult=null;Promise.resolve().then(async()=>{$script}).then(v=>window.weTestResult=String(v)).catch(()=>window.weTestResult='browser-operation-failed')",null)}
        while(System.nanoTime()<deadline){
            val tick=CountDownLatch(1)
            instrumentation.runOnMainSync {page.evaluateJavascript("window.weTestResult"){if(it!="null" && it!="undefined"){value=it;done.countDown()};tick.countDown()}}
            tick.await(2,TimeUnit.SECONDS)
            if(done.count==0L)return value
            android.os.SystemClock.sleep(50)
        }
        error("WebView operation timed out")
    }
}

package com.opc.baize

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import com.opc.baize.agent.AgentWorkspaceActivity
import com.opc.baize.subscription.SubscriptionSettingsActivity
import org.junit.Assert.*
import org.junit.Test

/** Actual Android views and system font scaling; no subscription calls or credential fixtures. */
class AppUiDeviceTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    @Test fun nativeSurfacesFitPhoneLandscapeTabletAndLargeSystemFont() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        context.getSharedPreferences("moling",0).edit().putBoolean("firstRunDone",true).commit()
        val oldScale=android.provider.Settings.System.getFloat(context.contentResolver,"font_scale",1f)
        fun scale(value:Float) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $value")).use {it.readBytes()}
            android.os.SystemClock.sleep(700)
        }
        try {
            for(font in listOf(1f,1.3f)) {
                scale(font)
                for((name,cls,required) in listOf(
                    Triple("home",MainActivity::class.java,listOf("画布","对话","工作区","设置","关于")),
                    Triple("conversation",AgentWorkspaceActivity::class.java,listOf("返回","画布","设置","发送")),
                    Triple("models",SubscriptionSettingsActivity::class.java,listOf("返回"))
                )) {
                    val activity=instrumentation.startActivitySync(Intent(context,cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
                    try {
                        instrumentation.waitForIdleSync()
                        instrumentation.runOnMainSync {
                            assertEquals(font,activity.resources.configuration.fontScale,.05f)
                            val root=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup
                            val density=activity.resources.displayMetrics.density
                            for((label,size) in listOf("phone" to (320 to 568),"landscape" to (640 to 360),"tablet" to (800 to 600),"keyboard" to (320 to 300))) {
                                val w=(size.first*density).toInt();val h=(size.second*density).toInt()
                                repeat(3) {root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)}
                                for(text in required) {
                                    val button=views(root).filterIsInstance<Button>().single {it.isShown && it.text==text}
                                    val rect=Rect();button.getDrawingRect(rect);root.offsetDescendantRectToMyCoords(button,rect)
                                    assertTrue("$name/$label/$font $text outside $rect",rect.left>=0 && rect.top>=0 && rect.right<=w && rect.bottom<=h)
                                    assertTrue("$name/$label/$font $text touch target",button.width>=48*density && button.height>=48*density)
                                }
                                val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
                                try {
                                    val values=android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"$name-$label-$font.png")
                                        put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
                                        if(android.os.Build.VERSION.SDK_INT>=29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/WE-CI")
                                    }
                                    val uri=requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
                                    context.contentResolver.openOutputStream(uri)!!.use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
                                } finally {image.recycle()}
                            }
                        }
                    } finally {instrumentation.runOnMainSync {activity.finish()}}
                }
            }
        } finally {scale(oldScale)}
    }
    @Test fun connectionScreenHasBackNavigationAndAccessibleWebActions() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,com.opc.baize.mcp.McpSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        try {
            val page=com.opc.baize.mcp.McpSettingsActivity::class.java.getDeclaredField("page").apply {isAccessible=true}.get(activity) as android.webkit.WebView
            var ready=false;val deadline=android.os.SystemClock.elapsedRealtime()+15000
            while(!ready && android.os.SystemClock.elapsedRealtime()<deadline) {
                val result=java.util.concurrent.CompletableFuture<String>()
                instrumentation.runOnMainSync {page.evaluateJavascript("Boolean(window.weConnection && [...document.querySelectorAll('button')].filter(b=>!b.hidden).every(b=>{const r=b.getBoundingClientRect();return r.height>=48 && r.width>=48 && r.right<=innerWidth+1;}))") {result.complete(it)}}
                ready=result.get(3,java.util.concurrent.TimeUnit.SECONDS)=="true"
                if(!ready) android.os.SystemClock.sleep(50)
            }
            assertTrue("Connection actions did not load or fit",ready)
            instrumentation.runOnMainSync {
                assertTrue(views(activity.window.decorView).filterIsInstance<Button>().any {it.text=="返回" && it.isShown})
            }
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }

}

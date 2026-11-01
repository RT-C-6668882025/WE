package com.opc.baize

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.opc.baize.agent.WorkspaceFiles
import com.opc.baize.core.Session
import com.opc.baize.core.SessionCodec
import com.opc.baize.core.Turn
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceNavigationUiTest {
    private fun text(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(text(view.getChildAt(i)))
    }
    @Test fun settingsContainsConfigurationAndAboutIdentifiesWe() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("moling", 0).edit().putBoolean("firstRunDone", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            fun pane(name: String): List<String> = text(MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) as View)
            val settings = pane("buildSettingsPane")
            assertFalse(settings.contains("PDF 练习工作区"))
            assertFalse(settings.contains("WE 工作区 · Skill / MCP"))
            val about = pane("buildAboutPane").joinToString("\n")
            assertTrue(about.contains("WE · Write Everything"))
            assertTrue(about.contains("RT-C-6668882025/WE"))
            assertTrue(about.contains("Moling")); assertTrue(about.contains("GPL-3.0"))
            assertFalse(about.contains("墨灵 1.0"))
            val home = text(activity.window.decorView)
            assertTrue(home.contains("工作区")); assertTrue(home.contains("画布")); assertTrue(home.contains("对话")); assertFalse(home.contains("绘画"))
        } finally { controller.destroy() }
    }
    @Test fun modelSettingsKeepsTheEstablishedSubscriptionEntryAndSavedChoice() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("moling",0)
        prefs.edit().putBoolean("firstRunDone",true).putString("model_provider","subscription")
            .putString("subscription_model","saved-text").putString("subscription_image_model","saved-image").commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity=controller.get()
            val pane=MainActivity::class.java.getDeclaredMethod("buildSettingsPane").apply {isAccessible=true}.invoke(activity) as View
            val labels=text(pane)
            assertTrue(labels.indexOf("模型通道")<labels.indexOf("字体"))
            fun find(view:View):android.widget.Button? {
                if(view is android.widget.Button && view.text=="ChatGPT 登录与模型选择") return view
                if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let {return it}
                return null
            }
            requireNotNull(find(pane)).performClick()
            assertEquals(com.opc.baize.subscription.SubscriptionSettingsActivity::class.java.name,shadowOf(activity).nextStartedActivity.component!!.className)
            assertEquals("subscription",prefs.getString("model_provider",null))
            assertEquals("saved-text",prefs.getString("subscription_model",null))
            assertEquals("saved-image",prefs.getString("subscription_image_model",null))
        } finally {controller.destroy()}
    }
    @Test fun selectingAndContinuingAnOldTaskAppendsToItsOriginalArchive() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("moling", 0).edit().putBoolean("firstRunDone", true).commit()
        val original = Session("s_${UUID.randomUUID()}", 10, listOf(Turn("old question", "old answer")))
        File(context.filesDir, "sessions.json").writeText(SessionCodec.encode(listOf(original)))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            MainActivity::class.java.getDeclaredMethod("openHistorySession", Session::class.java).apply { isAccessible = true }.invoke(activity, original)
            MainActivity::class.java.getDeclaredMethod("recordTurn", String::class.java, String::class.java).apply { isAccessible = true }
                .invoke(activity, "follow-up", "continued answer")
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val sessions = SessionCodec.decode(File(context.filesDir, "sessions.json").readText())
            assertEquals(1, sessions.size); assertEquals(original.id, sessions.single().id)
            assertEquals(listOf(original.turns.single(), Turn("follow-up", "continued answer")), sessions.single().turns)
            assertEquals(1, WorkspaceFiles(context).list().count { WorkspaceFiles(context).sessionId(it) == original.id })
        } finally { controller.destroy() }
    }
}

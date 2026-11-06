package com.opc.baize.paper

import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PaperToolbarTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun compactToolbarDefaultsToManualAndHidesContextualActions() {
        val controller = Robolectric.buildActivity(PaperActivity::class.java)
            .create().start().resume().visible()
        try {
            val all = views(controller.get().window.decorView)
            val visible = all.filter { it.isShown }.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(visible.contains("发送")); assertTrue(visible.contains("笔刷")); assertTrue(visible.contains("菜单")); assertTrue(visible.contains("页面"))
            assertFalse(visible.contains("指令：识别"))
            val options = all.filterIsInstance<Button>().single { it.text == "AI ▾" }
            options.performClick()
            val panel=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val panelViews=views(panel.window!!.decorView)
            val auto=panelViews.filterIsInstance<Switch>().single {it.text=="自动识别"}
            assertTrue(auto.isShown);assertFalse(auto.isChecked)
            val scope=panelViews.filterIsInstance<Spinner>().single()
            assertEquals("整页",scope.selectedItem)
            panel.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(panel.isShowing)
            for(removed in listOf("缩小","放大","导入 PDF","局部批改","选区解释","批改记录","查看生成页","停止","上一页","下一页")) assertFalse(removed,visible.contains(removed))
            val selection = all.filterIsInstance<Button>().single { it.text.toString().endsWith("框选") }
            selection.performClick(); assertEquals("选区",scope.selectedItem)
            selection.performClick(); assertEquals("整页",scope.selectedItem)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun switchingBrushPresetKeepsTheSameCanvasAndPage() {
        val controller = Robolectric.buildActivity(PaperActivity::class.java).create().start().resume().visible()
        try {
            val activity = controller.get()
            val canvas = views(activity.window.decorView).filterIsInstance<PaperInkView>().single()
            val page = canvas.page
            val preset = PaperActivity::class.java.getDeclaredMethod("setInputMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            preset.invoke(activity,true)
            assertSame(canvas,views(activity.window.decorView).filterIsInstance<PaperInkView>().single())
            assertSame(page,canvas.page)
            preset.invoke(activity,false)
            assertSame(page,canvas.page)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun promptsAndSystemAreEditableAndSurviveRecreation() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("moling",0)
        try {
            PaperPrompts.save(context,0,"按我定义的方式识别")
            PaperPrompts.saveSystem(context,"我的系统规则")
            assertEquals("按我定义的方式识别",PaperPrompts.read(context,0))
            assertEquals("我的系统规则",PaperPrompts.system(context))
        } finally { prefs.edit().remove("paper_prompt_0").remove("paper_system_prompt").commit() }
    }
    @Test fun brushButtonRestoresFreehandAndAiUsesOneAction() {
        val controller=Robolectric.buildActivity(PaperActivity::class.java).create().start().resume().visible()
        try {
            val activity=controller.get();val all=views(activity.window.decorView)
            val canvas=all.filterIsInstance<PaperInkView>().single();canvas.drawingTool="fill"
            all.filterIsInstance<Button>().single {it.text.toString().endsWith("画笔")}.performClick()
            assertEquals("freehand",canvas.drawingTool)
            PaperActivity::class.java.getDeclaredField("submitting").apply {isAccessible=true}.setBoolean(activity,true)
            PaperActivity::class.java.getDeclaredMethod("updateBusy").apply {isAccessible=true}.invoke(activity)
            val stop=all.filterIsInstance<Button>().single {it.text=="停止"}
            assertTrue(stop.isEnabled);stop.performClick();assertEquals("发送",stop.text.toString())
            assertFalse(all.filterIsInstance<Button>().any {it!==stop && it.text=="停止"})
        } finally {controller.pause().stop().destroy()}
    }

    @Test fun canvasMenuRetainsToolsAndAddEntryWhenSecondaryBarCollapses() {
        val controller=Robolectric.buildActivity(PaperActivity::class.java).create().start().resume().visible()
        try {
            PaperActivity::class.java.getDeclaredMethod("showCanvasMenu").apply {isAccessible=true}.invoke(controller.get())
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val labels=(0 until dialog.listView.adapter.count).map {dialog.listView.adapter.getItem(it).toString()}
            assertTrue(labels.contains("绘画工具"));assertTrue(labels.contains("添加内容"));assertTrue(labels.contains("视图与笔刷"))
            dialog.dismiss()
        } finally {controller.pause().stop().destroy()}
    }

}

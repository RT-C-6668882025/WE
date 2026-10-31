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
            val visible = all.filter { it.visibility == View.VISIBLE }.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(visible.contains("发送")); assertTrue(visible.contains("笔刷")); assertTrue(visible.contains("菜单"))
            for (removed in listOf("缩小","放大","导入 PDF","局部批改","选区解释","批改记录","查看生成页","停止","上一页","下一页")) assertFalse(removed,visible.contains(removed))
            assertFalse(all.filterIsInstance<Switch>().single { it.text == "自动识别" }.isChecked)
            val scope = all.filterIsInstance<Spinner>().single()
            assertEquals("整页",scope.selectedItem)
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
}

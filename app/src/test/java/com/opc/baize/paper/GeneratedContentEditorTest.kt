package com.opc.baize.paper

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.opc.baize.agent.WritableDocument
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GeneratedContentEditorTest {
    @Test fun structuredPageCanBeEditedWithoutLosingDrawingOrOriginal() {
        val original = """{"we_page":1,"title":"旧标题","blocks":[{"id":"q","type":"question","text":"原题","answer_lines":3},{"id":"f","type":"figure","height":240,"shapes":[{"type":"line","x":10,"y":10,"x2":900,"y2":900}]}]}"""
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var saved: String? = null
        try {
            GeneratedContentEditor.show(controller.get(),original) { saved = it }
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            fun fields(view: View): List<EditText> = if (view is EditText) listOf(view)
                else if (view is ViewGroup) (0 until view.childCount).flatMap { fields(view.getChildAt(it)) } else emptyList()
            val inputs = fields(dialog.window!!.decorView)
            inputs.first { it.hint == "标题" }.setText("新标题")
            inputs.first { it.text.toString() == "原题" }.setText("新题目")
            inputs.first { it.hint.toString().startsWith("答题留白") }.setText("5")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val edited = JSONObject(saved!!)
            WritableDocument.parse(edited)
            assertEquals("新标题",edited.getString("title"))
            assertEquals("新题目",edited.getJSONArray("blocks").getJSONObject(0).getString("text"))
            assertEquals(5,edited.getJSONArray("blocks").getJSONObject(0).getInt("answer_lines"))
            assertEquals(900,edited.getJSONArray("blocks").getJSONObject(1).getJSONArray("shapes").getJSONObject(0).getInt("x2"))
            assertEquals("旧标题",JSONObject(original).getString("title"))
        } finally { controller.pause().stop().destroy() }
    }
}

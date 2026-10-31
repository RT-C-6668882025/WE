package com.opc.baize

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.opc.baize.agent.WorkspaceFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceManagementUiTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun searchFiltersRowsAndManagementCanArchiveAndRestore() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("moling", 0).edit().putBoolean("firstRunDone", true).commit()
        val files = WorkspaceFiles(context)
        val w = files.create("Manage-${UUID.randomUUID()}")
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val show = MainActivity::class.java.getDeclaredMethod("showPanel", Int::class.javaPrimitiveType).apply { isAccessible = true }
            show.invoke(activity, 0)
            fun root(): View = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            fun click(label: String) { views(root()).filterIsInstance<TextView>().first { it.text.toString() == label }.performClick() }
            views(root()).filterIsInstance<EditText>().first { it.hint.toString() == "搜索工作区名称" }.setText("no-match-${UUID.randomUUID()}")
            assertTrue(views(root()).filterIsInstance<TextView>().any { it.text.toString() == "没有匹配的工作区。" })
            views(root()).filterIsInstance<EditText>().first().setText(w.name)
            assertTrue(views(root()).filterIsInstance<TextView>().any { it.text.toString() == w.name })
            click("管理")
            views(root()).first { it.contentDescription?.toString() == "选择工作区 ${w.name}" }.performClick()
            click("归档所选")
            assertTrue(files.state(w).archived)
            click("已归档")
            assertTrue(views(root()).filterIsInstance<TextView>().any { it.text.toString() == w.name })
            // Trash keeps the data and exposes a restore action without executing deletion.
            files.manage(w, "trash")
            click("回收站")
            click("恢复")
            assertEquals(0L, files.state(w).trashedAt)
            assertTrue(files.state(w).archived)
        } finally { controller.destroy(); w.container.deleteRecursively() }
    }
}

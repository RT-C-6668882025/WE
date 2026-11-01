package com.opc.baize.mcp

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class McpQuickConnectUiTest {
    class TestActivity:McpSettingsActivity() {
        var prepares=0;var starts=0
        override fun preparePairing():JSONObject {prepares++;return JSONObject().put("base",PublicMcpConnection.DEFAULT_RELAY)
            .put("agentToken","c".repeat(64)).put("deviceToken","d".repeat(64))}
        override fun connectionTask(task:()->Unit) {task()}
        override fun startConnection() {starts++}
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    @Test fun oneButtonStartsConnectionAndCopiesUrlWithoutAnyManualAddressOrPairing() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("we-mcp",Context.MODE_PRIVATE);prefs.edit().clear().commit()
        val controller=Robolectric.buildActivity(TestActivity::class.java).create()
        try {
            val activity=controller.get();val all=views(activity.window.decorView)
            assertTrue(all.filterIsInstance<EditText>().isEmpty())
            val buttons=all.filterIsInstance<Button>()
            assertFalse(buttons.any {it.text=="与中继配对" || it.text=="开启连接"})
            val copy=buttons.single {it.text=="复制 MCP 地址"};copy.performClick()
            assertEquals(1,activity.prepares);assertEquals(1,activity.starts)
            val clipboard=activity.getSystemService(ClipboardManager::class.java)
            val address=clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertEquals(PublicMcpConnection.DEFAULT_RELAY+"/mcp/"+"c".repeat(64),address)
            assertFalse(all.filterIsInstance<TextView>().any {it.text.toString().contains("c".repeat(64))})
            copy.performClick()
            assertEquals(address,clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(prefs.getBoolean("enabled",false))
        } finally {controller.destroy();prefs.edit().clear().commit()}
    }
}

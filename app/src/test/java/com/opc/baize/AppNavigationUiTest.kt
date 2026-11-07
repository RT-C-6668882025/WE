package com.opc.baize

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.opc.baize.agent.AgentWorkspaceActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class AppNavigationUiTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    @Test fun homeIsWorkspaceHubAndUnsavedSettingsCanBeKeptOrDiscarded() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("moling",0).edit().putBoolean("firstRunDone",true).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val activity=controller.get()
            fun all()=views(activity.window.decorView)
            assertTrue(all().filterIsInstance<EditText>().any {it.hint=="搜索工作区名称"})
            assertFalse(all().filterIsInstance<BookView>().any {it.isShown})
            all().filterIsInstance<Button>().single {it.text=="设置"}.performClick()
            val edit=all().filterIsInstance<EditText>().single {it.hint=="输入当前服务的 API 密钥"}
            val old=edit.text.toString();edit.setText(old+"draft")
            all().filterIsInstance<Button>().single {it.text=="设置"}.performClick()
            assertEquals(old+"draft",edit.text.toString())
            all().filterIsInstance<Button>().single {it.text=="工作区"}.performClick()
            var dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
            assertEquals(old+"draft",edit.text.toString())
            all().filterIsInstance<Button>().single {it.text=="工作区"}.performClick()
            dialog=ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(all().filterIsInstance<EditText>().any {it.hint=="搜索工作区名称"})
            assertEquals(old,context.getSharedPreferences("moling",0).getString("api_key",""))
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun invalidWorkspaceNameKeepsDialogAndInput() {
        val controller=Robolectric.buildActivity(AgentWorkspaceActivity::class.java).create().start().resume().visible()
        try {
            views(controller.get().window.decorView).filterIsInstance<Button>().single {it.text=="设置"}.performClick()
            val settings=ShadowAlertDialog.getLatestAlertDialog()
            views(settings.window!!.decorView).filterIsInstance<Button>().single {it.text=="新建工作区"}.performClick()
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            val input=views(dialog.window!!.decorView).filterIsInstance<EditText>().single();input.setText(" ")
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing);assertEquals(" ",input.text.toString());assertNotNull(input.error)
            dialog.dismiss();settings.dismiss()
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun invalidEditorInputRemainsAvailableForCorrection() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume().visible()
        try {
            val input=EditText(controller.get()).apply {setText("keep my text")}
            com.opc.baize.ui.WeUi.editDialog(controller.get(),"编辑",input) {require(it.length<3) {"输入过长"}}
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing);assertEquals("keep my text",input.text.toString());assertEquals("输入过长",input.error.toString())
            dialog.dismiss()
        } finally {controller.pause().stop().destroy()}
    }

}

package com.opc.baize.paper

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class DrawingInteractionTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    @Test fun lockedLayerDeletionAndOutOfPageTransformsPreserveOriginalData() {
        val view=PaperInkView(RuntimeEnvironment.getApplication())
        val original=PaperPage(600,840,0,listOf(PaperStroke(listOf(InkPoint(20f,20f,1f,0),InkPoint(40f,40f,1f,1)))),
            listOf(PaperLayer(1,"Locked",locked=true),PaperLayer(2,"Other")),1)
        view.load(Bitmap.createBitmap(600,840,Bitmap.Config.ARGB_8888),original)
        try {
            view.deleteLayer();assertEquals(original,view.page)
            val unlocked=original.copy(layers=original.layers.map {it.copy(locked=false)})
            view.acceptExternal(unlocked)
            PaperInkView::class.java.getDeclaredField("region").apply {isAccessible=true}.set(view,RectF(0f,0f,100f,100f))
            assertFalse(view.transformSelection(1000f,0f,1f));assertEquals(unlocked,view.page);assertTrue(view.hasSelection)
            assertTrue(view.transformSelection(10f,0f,1f));assertEquals(30f,view.page.strokes.single().points.first().x,0f)
        } finally {view.release()}
    }
    @Test fun staleLayerDialogCannotRenameOrDeleteANewActiveLayer() {
        val controller=Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val view=PaperInkView(controller.get());view.load(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888),PaperPage(100,100,0,emptyList()))
        try {
            val panels=DrawingPanels(controller.get(),view,{})
            panels.layers()
            val list=ShadowAlertDialog.getLatestAlertDialog()
            views(list.window!!.decorView).filterIsInstance<Button>().single {it.text=="当前图层设置"}.performClick()
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("stale name")
            view.applyArtwork(JSONArray("""[{"action":"add_layer","name":"remote"}]"""))
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing);assertEquals("remote",view.page.activeLayer.name)
            assertEquals("图层 1",view.page.layers.first().name)
            dialog.dismiss();list.dismiss()
        } finally {view.release();controller.pause().stop().destroy()}
    }
    @Test fun invalidTextSizeKeepsTheEditorAndInput() {
        val controller=Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val view=PaperInkView(controller.get());view.load(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888),PaperPage(100,100,0,emptyList()))
        try {
            DrawingPanels(controller.get(),view,{}).addObjects()
            val menu=ShadowAlertDialog.getLatestAlertDialog();menu.listView.performItemClick(null,0,0)
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            val inputs=views(dialog.window!!.decorView).filterIsInstance<EditText>()
            inputs[0].setText("keep this text");inputs[1].setText("NaN")
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing);assertEquals("keep this text",inputs[0].text.toString());assertTrue(view.page.strokes.isEmpty())
            dialog.dismiss()
        } finally {view.release();controller.pause().stop().destroy()}
    }
    @Test fun toolPanelSelectsShapeAndReturnsToTheCanvas() {
        val controller=Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val view=PaperInkView(controller.get());view.load(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888),PaperPage(100,100,0,emptyList()))
        try {
            assertTrue(view.applyArtwork(JSONArray("""[{"action":"line","x":10,"y":10,"x2":30,"y2":30}]""")))
            DrawingPanels(controller.get(),view,{}).tools()
            val dialog=ShadowAlertDialog.getLatestAlertDialog();val list=dialog.listView
            val labels=(0 until list.adapter.count).map {list.adapter.getItem(it).toString()}
            assertFalse(labels.contains("重做"));assertFalse(labels.contains("撤销"))
            val index=labels.indexOf("直线");list.performItemClick(null,index,index.toLong())
            assertEquals("line",view.drawingTool)
        } finally {view.release();controller.pause().stop().destroy()}
    }

}

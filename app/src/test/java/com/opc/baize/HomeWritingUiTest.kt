package com.opc.baize

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.opc.baize.agent.HomeInkStore
import com.opc.baize.agent.WorkspaceFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class HomeWritingUiTest {
    private fun buttons(view:View):List<Button> = buildList {
        if(view is Button) add(view)
        if(view is ViewGroup) for(i in 0 until view.childCount) addAll(buttons(view.getChildAt(i)))
    }
    @Test fun homeUsesWorkspaceCanvasAndConversationInsteadOfASecondWritingSurface() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("moling",0).edit().putBoolean("firstRunDone",true).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            val ink=MainActivity::class.java.getDeclaredField("ink").apply {isAccessible=true}.get(activity) as InkView
            assertEquals(View.GONE,ink.visibility)
            val controls=buttons(activity.window.decorView)
            assertTrue(controls.any {it.text=="画布"})
            assertFalse(controls.any {it.text=="手写" || it.text=="绘画" || it.text=="撤回"})
            controls.single {it.text=="对话"}.performClick()
            val launched=shadowOf(activity).nextStartedActivity
            assertEquals(com.opc.baize.agent.AgentWorkspaceActivity::class.java.name,launched.component!!.className)
            val workspace=WorkspaceFiles(context).list().single {it.id==launched.getStringExtra("workspace")}
            assertNotNull(WorkspaceFiles(context).sessionId(workspace))
        } finally {controller.destroy()}
    }
    @Test fun legacyDraftUndoStillRemovesOnlyTheLastStrokeAndUpdatesTheDraft() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("moling",0).edit().putBoolean("firstRunDone",true).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        try {
            val ink=MainActivity::class.java.getDeclaredField("ink").apply {isAccessible=true}.get(activity) as InkView
            ink.layout(0,0,600,840)
            for(x in listOf(230f,330f)) {
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(1,2,action,x,300f,0);ink.onTouchEvent(event);event.recycle()
                }
            }
            assertEquals(2,ink.strokes.size)
            val undo=MainActivity::class.java.getDeclaredMethod("undoHomeInk").apply {isAccessible=true}
            undo.invoke(activity);assertEquals(1,ink.strokes.size)
            assertEquals(230f,ink.strokes.single().first().x,0f)
            com.opc.baize.paper.PaperIo.executor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            val sessionId=MainActivity::class.java.getDeclaredField("curSessionId").apply {isAccessible=true}.get(activity) as String
            val files=WorkspaceFiles(context);val workspace=files.list().first {files.sessionId(it)==sessionId}
            assertEquals(1,HomeInkStore.load(workspace)!!.strokes.size)
            undo.invoke(activity);assertTrue(ink.strokes.isEmpty())
            com.opc.baize.paper.PaperIo.executor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertNull(HomeInkStore.load(workspace))
            undo.invoke(activity);assertTrue(ink.strokes.isEmpty())
        } finally {controller.destroy()}
    }
    @Test fun draftsAreTaskScopedAndRestorableAtAnotherViewportSize() {
        val context=RuntimeEnvironment.getApplication();val files=WorkspaceFiles(context)
        val a=files.create("draft A");val b=files.create("draft B")
        val draft=HomeInkStore.Draft(300,420,listOf(listOf(InkView.Pt(100f,200f,3f,20))))
        HomeInkStore.save(a,draft)
        assertEquals(draft,HomeInkStore.load(a));assertNull(HomeInkStore.load(b))
        val ink=InkView(context);ink.layout(0,0,600,840)
        ink.restoreDraft(draft.width,draft.height,draft.strokes)
        assertEquals(200f,ink.strokes.single().single().x,0f)
        assertEquals(400f,ink.strokes.single().single().y,0f)
        HomeInkStore.archive(a,draft);HomeInkStore.save(a,draft.copy(strokes=emptyList()))
        assertNull(HomeInkStore.load(a))
        assertTrue(java.io.File(a.root,"memory/handwriting").listFiles().orEmpty().any {it.name.startsWith("input-")})
    }
}

package com.opc.baize

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import com.opc.baize.agent.AgentWorkspaceActivity
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class AgentConversationUiTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    @Test fun conversationComposerHasOneActionAndCanCancelWithoutStartingANewRun() {
        val controller=Robolectric.buildActivity(AgentWorkspaceActivity::class.java).create().start().resume().visible()
        try {
            val activity=controller.get();val all=views(activity.window.decorView)
            assertEquals(1,all.filterIsInstance<Button>().count {it.text=="发送"})
            val input=all.filterIsInstance<EditText>().single {it.contentDescription=="工作区需求"}
            assertEquals(1,input.minLines);assertEquals(4,input.maxLines)
            val active=Job()
            AgentWorkspaceActivity::class.java.getDeclaredField("job").apply {isAccessible=true}.set(activity,active)
            val action=all.filterIsInstance<Button>().single {it.text=="发送"};action.text="停止"
            input.setText("must not start another request");action.performClick()
            assertTrue(active.isCancelled)
        } finally {controller.pause().stop().destroy()}
    }
}

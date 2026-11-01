package com.opc.baize.paper

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Foreground editor bridge; no queued write is allowed to execute after a timeout. */
object CanvasSession {
    data class Editor(val owner: Any, val workspace: String, val document: String, val page: Int,
        val context: ()->JSONObject, val change: (Long, (PaperPage)->PaperPage)->PaperPage)
    @Volatile private var editor: Editor? = null
    fun attach(value: Editor) { editor=value }
    fun detach(owner: Any) { if(editor?.owner===owner) editor=null }
    private fun <T> main(call: ()->T): T {
        if(Looper.myLooper()==Looper.getMainLooper()) return call()
        val task=FutureTask(java.util.concurrent.Callable {call()}); Handler(Looper.getMainLooper()).post(task)
        return try {task.get(10,TimeUnit.SECONDS)} catch(e:Exception) {task.cancel(false);throw e}
    }
    fun context(workspace: String): JSONObject? = main {editor?.takeIf {it.workspace==workspace}?.context?.invoke()}
    fun change(workspace: String,document: String,page: Int,expected: Long,call: (PaperPage)->PaperPage): PaperPage? = main {
        editor?.takeIf {it.workspace==workspace && it.document==document && it.page==page}?.change?.invoke(expected,call)
    }
}

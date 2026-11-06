package com.opc.baize.paper

import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap

/** A page with uncommitted local edits must not be changed through an offline MCP path. */
object CanvasWriteGuard {
    private val counts=ConcurrentHashMap<String,Int>()
    private fun key(document:String,page:Int)="$document:$page"
    fun retain(document:String,page:Int) {counts.compute(key(document,page)) {_,n -> (n ?: 0)+1}}
    fun release(document:String,page:Int) {counts.computeIfPresent(key(document,page)) {_,n -> if(n<=1) null else n-1}}
    fun isClear(document:String,page:Int)=!counts.containsKey(key(document,page))
}

/** Failed revisions stay ordered and recoverable; later edits never skip a failed save. */
class CanvasSaveQueue(private val executor:Executor,private val save:(String,Int,PaperPage)->Unit,
    private var changed:(State)->Unit) {
    data class State(val pending:Int,val error:String?)
    private data class Entry(val document:String,val index:Int,val page:PaperPage)
    private val entries=java.util.ArrayDeque<Entry>()
    private var running=false
    private var error:String?=null
    @Synchronized fun state()=State(entries.size,error)
    @Synchronized fun observe(observer:(State)->Unit) {changed=observer;notifyState()}
    @Synchronized fun latest(document:String,index:Int)=entries.lastOrNull {it.document==document && it.index==index}?.page
    @Synchronized fun isIdle()=entries.isEmpty() && !running
    @Synchronized fun enqueue(document:String,index:Int,page:PaperPage) {
        CanvasWriteGuard.retain(document,index);entries.addLast(Entry(document,index,page));notifyState();start()
    }
    @Synchronized fun retry() {error=null;notifyState();start()}
    private fun notifyState() {changed(State(entries.size,error))}
    private fun start() {
        if(running || entries.isEmpty() || error!=null) return
        running=true;executor.execute {drain()}
    }
    private fun drain() {
        while(true) {
            val next=synchronized(this) {
                entries.peekFirst() ?: run {running=false;notifyState();return}
            }
            try {save(next.document,next.index,next.page)}
            catch(e:Exception) {synchronized(this) {running=false;error=e.message ?: "保存失败";notifyState()};return}
            synchronized(this) {
                entries.removeFirst();CanvasWriteGuard.release(next.document,next.index);notifyState()
            }
        }
    }
}

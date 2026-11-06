package com.opc.baize.agent

import java.util.concurrent.CopyOnWriteArraySet

/** Workspace index changes may originate from the UI or a background MCP call. */
object WorkspaceEvents {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun publish() { listeners.forEach { runCatching { it() } } }
}

package com.opc.baize.agent

import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** In-process ready-page notifications, including pages published through MCP. */
object WorkspacePageEvents {
    private val listeners = CopyOnWriteArraySet<(String,File) -> Unit>()
    fun subscribe(listener: (String,File) -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: (String,File) -> Unit) { listeners.remove(listener) }
    fun publish(workspace: String,pdf: File) { listeners.forEach { runCatching { it(workspace,pdf) } } }
}

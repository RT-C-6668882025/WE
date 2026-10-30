package com.opc.baize.paper

/** Serial file access across workspace Activity recreation and in-flight result saves. */
internal object PaperIo {
    val executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
}

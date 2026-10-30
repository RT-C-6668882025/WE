package com.opc.baize.model

/** Independent of Activity and ink rendering. Calls run on the existing background worker. */
interface ModelProvider {
    val supportsImages: Boolean
    fun isReady(): Boolean
    fun reply(history: List<Pair<String, String>>, text: String, imageDataUrl: String? = null): String
}

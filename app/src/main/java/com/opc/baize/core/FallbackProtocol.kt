package com.opc.baize.core

import org.json.JSONObject

/**
 * Mac 降级通道（server.py /api/ask）的请求/响应编解码。
 * 请求：{image: dataURL, session, strokes}；
 * 响应：{ok, recognized, reply, ignored, command, elapsed_ms, error}。
 * 纯 org.json，可 JVM 单测。
 */
object FallbackProtocol {

    data class AskResult(
        val ok: Boolean,
        val ignored: Boolean,
        val command: String?,
        val recognized: String,
        val reply: String,
        val error: String?,
    )

    fun buildRequestBody(imageDataUrl: String, session: String, strokes: Int): String =
        JSONObject()
            .put("image", imageDataUrl)
            .put("session", session)
            .put("strokes", strokes)
            .toString()

    fun parseResponse(body: String): AskResult {
        val o = JSONObject(body)
        return AskResult(
            ok = o.optBoolean("ok", false),
            ignored = o.optBoolean("ignored", false),
            command = if (o.has("command")) o.optString("command") else null,
            recognized = o.optString("recognized", ""),
            reply = o.optString("reply", ""),
            error = if (o.has("error")) o.optString("error") else null,
        )
    }
}

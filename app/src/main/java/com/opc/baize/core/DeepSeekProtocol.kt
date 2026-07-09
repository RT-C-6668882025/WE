package com.opc.baize.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 大模型回复（Anthropic Messages 兼容端点）的请求/响应编解码。
 * 纯 org.json，无 Android 依赖——JVM 单测直接肉眼核对请求体。
 *
 * 开源版：端点与模型名改为运行时可配（用户在设置里填），不再写死。
 * 契约：
 *   POST <endpoint>/v1/messages   （endpoint 如 https://api.deepseek.com/anthropic）
 *   headers: x-api-key + anthropic-version: 2023-06-01
 *   body: model=<用户配置>, max_tokens=400, system=PERSONA,
 *         messages=最近6轮(user=识别文字/assistant=回文)+本轮识别文字
 */
object DeepSeekProtocol {

    const val MAX_TOKENS = 400
    const val ANTHROPIC_VERSION = "2023-06-01"

    /** 由用户配置的 endpoint 基址拼出 /v1/messages 完整 URL（容忍末尾斜杠）。 */
    fun messagesUrl(endpoint: String): String {
        val base = endpoint.trim().trimEnd('/')
        return "$base/v1/messages"
    }

    fun buildRequestBody(
        model: String,
        history: List<Pair<String, String>>,
        userText: String,
    ): String {
        val messages = JSONArray()
        for ((recognized, reply) in history) {
            messages.put(JSONObject().put("role", "user").put("content", recognized))
            messages.put(JSONObject().put("role", "assistant").put("content", reply))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        return JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("system", Texts.PERSONA)
            .put("messages", messages)
            .toString()
    }

    /** 拼接响应里所有 text 块（thinking 等其它块一律丢弃，绝不外泄）。 */
    fun parseReplyText(responseBody: String): String {
        val obj = JSONObject(responseBody)
        val content = obj.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().trim()
    }
}

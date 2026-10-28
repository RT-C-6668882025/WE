package com.opc.baize.model

import com.opc.baize.subscription.SubscriptionGateway
import com.opc.baize.core.Texts
import org.json.JSONArray
import org.json.JSONObject

class SubscriptionModelProvider(private val gateway: SubscriptionGateway, private val model: String) : ModelProvider {
    override val supportsImages = true // Actual support still depends on the account model selected.
    override fun isReady() = gateway.isAuthorized && model.isNotBlank()
    override fun reply(history: List<Pair<String, String>>, text: String, imageDataUrl: String?): String =
        gateway.reply(buildRequest(model, history, text, imageDataUrl).toString())

    fun recognizeInk(imageDataUrl: String): String = InkVisionProtocol.parse(
        gateway.reply(InkVisionProtocol.request(model, imageDataUrl).toString()))

    companion object {
        fun buildRequest(model: String, history: List<Pair<String, String>>, text: String, image: String?, persona: String = Texts.PERSONA): JSONObject {
            require(model.isNotBlank()) { "请选择订阅模型" }
            val input = JSONArray()
            fun item(role: String, type: String, value: String) = JSONObject().put("role", role)
                .put("content", JSONArray().put(JSONObject().put("type", type).put("text", value)))
            input.put(item("developer", "input_text", persona))
            history.takeLast(6).forEach { (question, answer) ->
                input.put(item("user", "input_text", question))
                input.put(item("assistant", "output_text", answer))
            }
            val current = item("user", "input_text", text)
            if (image != null) {
                require(image.startsWith("data:image/png;base64,") && image.length <= 2 * 1024 * 1024) { "图片格式或大小不支持" }
                current.getJSONArray("content").put(JSONObject().put("type", "input_image").put("image_url", image))
            }
            input.put(current)
            return JSONObject().put("model", model).put("store", false).put("stream", true).put("input", input)
        }
    }
}

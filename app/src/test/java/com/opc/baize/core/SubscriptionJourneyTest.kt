package com.opc.baize.core

import com.opc.baize.model.*
import com.opc.baize.subscription.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionJourneyTest {
    class Connection : SubscriptionConnection {
        val requests = mutableListOf<JSONObject>()
        override fun isAuthorized() = true
        override fun reply(requestJson: String): String {
            val request = JSONObject(requestJson); requests.add(request)
            return if (request.getString("model") == "vision") "{\"recognized\":\"你好\"}" else "吾在，愿闻其详。"
        }
    }
    @Test fun unavailableRecognizerUsesVisionThenIndependentTextModelAndHistory() {
        val service = Connection(); val choice = SubscriptionChoice("text", "vision")
        choice.validate(setOf("text", "vision"))
        val vision = SubscriptionModelProvider(service, choice.effectiveImageModel())
        val text = SubscriptionModelProvider(service, choice.textModel)
        val recognized = HandwritingRecognition.recognize({ throw IllegalStateException("No local OCR") }, { vision.recognizeInk("data:image/png;base64,AA==") })!!
        val answer = text.reply(listOf("旧问题" to "旧回答"), recognized)
        assertEquals("你好", recognized); assertEquals("吾在，愿闻其详。", answer)
        assertEquals(listOf("vision", "text"), service.requests.map { it.getString("model") })
        val input = service.requests[1].getJSONArray("input")
        assertEquals(4, input.length()); assertEquals("你好", input.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(2, service.requests[0].getJSONArray("input").length())
    }
    @Test fun directVisionSkipsLocalAndSameModelOptionIsExplicit() {
        val service = Connection(); val choice = SubscriptionChoice("vision", "", true)
        choice.validate(setOf("vision")); assertEquals("vision", choice.effectiveImageModel())
        val model = SubscriptionModelProvider(service, choice.effectiveImageModel())
        assertEquals("你好", HandwritingRecognition.recognize({ throw AssertionError("Should skip local") }, { model.recognizeInk("data:image/png;base64,AA==") }, choice.directVision))
        assertEquals(1, service.requests.size)
    }
    @Test(expected = IllegalArgumentException::class) fun disappearedModelCannotBeSilentlyReplaced() {
        SubscriptionChoice("removed", "vision").validate(setOf("new-model", "vision"))
    }
    @Test fun catalogUsesAccountNamesFiltersHiddenAndDeduplicatesIds() {
        val models = ModelCatalog.parse(JSONObject("""{"models":[{"slug":"one","display_name":"模型一","visibility":"list"},{"slug":"hidden","visibility":"hidden"},{"slug":"one","display_name":"duplicate","visibility":"list"},{"slug":"two","display_name":"模型二","visibility":"list"}]}"""))
        assertEquals(listOf("one", "two"), models.map { it.id }); assertEquals("模型一", models[0].name)
        assertTrue(ModelCatalog.contains(models, "two")); assertFalse(ModelCatalog.contains(models, "hidden"))
    }
    @Test fun gradingUsesStudyPersonaAndSelectedImageModel() {
        val requests = mutableListOf<JSONObject>()
        val service = object : SubscriptionConnection {
            override fun isAuthorized() = true
            override fun reply(requestJson: String): String { requests.add(JSONObject(requestJson)); return "答案为12。" }
        }
        SubscriptionModelProvider(service, "image-model").explainImage("批改此题", "data:image/png;base64,AA==")
        val body = requests.single(); assertEquals("image-model", body.getString("model"))
        val input = body.getJSONArray("input")
        assertFalse(input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").contains("浅近文言"))
        assertEquals("input_image", input.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
    }
}

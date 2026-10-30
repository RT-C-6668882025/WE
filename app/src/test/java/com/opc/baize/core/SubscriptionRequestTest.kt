package com.opc.baize.core

import com.opc.baize.model.SubscriptionModelProvider
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRequestTest {
    @Test fun preservesRecentHistoryAndImageWithoutServerStorage() {
        val request = SubscriptionModelProvider.buildRequest("selected-model", (1..8).map { "q$it" to "a$it" }, "grade this", "data:image/png;base64,AA==")
        assertFalse(request.getBoolean("store")); assertTrue(request.getBoolean("stream"))
        assertFalse(request.has("max_output_tokens")); assertFalse(request.has("temperature"))
        val input = request.getJSONArray("input")
        assertEquals(14, input.length())
        assertEquals("developer", input.getJSONObject(0).getString("role"))
        assertEquals("q3", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("output_text", input.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
        val current = input.getJSONObject(13).getJSONArray("content")
        assertEquals("grade this", current.getJSONObject(0).getString("text"))
        assertEquals("input_image", current.getJSONObject(1).getString("type"))
        assertEquals("selected-model", request.getString("model"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnselectedModel() {
        SubscriptionModelProvider.buildRequest("", emptyList(), "test", null)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRemoteImageUrls() {
        SubscriptionModelProvider.buildRequest("model", emptyList(), "test", "https://example.com/private.png")
    }
}

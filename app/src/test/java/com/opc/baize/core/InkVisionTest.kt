package com.opc.baize.core

import com.opc.baize.model.HandwritingRecognition
import com.opc.baize.model.InkVisionProtocol
import org.junit.Assert.*
import org.junit.Test

class InkVisionTest {
    @Test fun localSuccessNeverSpendsSubscriptionQuota() {
        val result = HandwritingRecognition.recognize({ "你好" }, { throw AssertionError("Must not invoke vision") })
        assertEquals("你好", result)
    }
    @Test fun localFailureUsesSelectedVisionAndPreservesNewCommand() {
        val result = HandwritingRecognition.recognize({ throw IllegalStateException("no recognizer") }, { InkVisionProtocol.parse("{\"recognized\":\"/new\"}") })
        assertTrue(NewCommand.isNewCommand(result!!))
    }
    @Test fun emptyLocalResultKeepsExistingNoChargeBehavior() {
        assertEquals("", HandwritingRecognition.recognize({ "" }, { throw AssertionError("No vision for empty candidate") }))
    }
    @Test(expected = IllegalArgumentException::class) fun visionFailureDoesNotInvokeAnotherBillingRoute() {
        HandwritingRecognition.recognize({ throw IllegalStateException() }, { throw IllegalArgumentException("vision unavailable") })
    }
    @Test fun requestContainsOnlyCurrentImageWithoutConversation() {
        val request = InkVisionProtocol.request("selected-model", "data:image/png;base64,AA==")
        val input = request.getJSONArray("input")
        assertEquals(2, input.length())
        assertTrue(input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").contains("recognized"))
        assertEquals("input_image", input.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
        assertFalse(request.getBoolean("store")); assertTrue(request.getBoolean("stream"))
        assertEquals("", InkVisionProtocol.parse("{\"recognized\":\"\"}"))
        assertEquals("你好", InkVisionProtocol.parse("```json\n{\"recognized\":\"你好\"}\n```"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongTranscriptionType() {
        InkVisionProtocol.parse("{\"recognized\":42}")
    }
}

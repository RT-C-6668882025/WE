package com.opc.baize.agent

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class WritablePageLayoutTest {
    private val wrap: (String, Int, Int, Int) -> List<Int> = { text, _, _, _ ->
        text.indices.filter { (it + 1) % 30 == 0 }.map { it + 1 }.plus(text.length).distinct()
    }
    @Test fun promptAndAnswerMoveTogetherAndLeaveAnAnnotationMargin() {
        val d = WritableDocument("", false, false, 20, 32, listOf(WritableBlock("draw", "drawing", height = 500),
            WritableBlock("intro", "text", "short introduction"), WritableBlock("q1", "question", "He ___ yesterday.", answerLines = 3)))
        val plan = WritablePageLayout.plan(d, wrap)
        val prompt = plan.regions.single { it.id == "q1" }; val answer = plan.regions.single { it.id == "q1-answer" }
        assertEquals(prompt.page, answer.page); assertEquals("q1", answer.parentId)
        assertTrue(prompt.page > 0); assertTrue(answer.y >= prompt.y + prompt.height)
        assertTrue(answer.height >= 3 * 36)
        val margin = plan.regions.first { it.page == prompt.page && it.type == "annotation" }
        assertTrue(prompt.x + prompt.width <= margin.x)
    }
    @Test fun longContentPaginatesWithoutDroppingTextOrDrawingOutsideThePaper() {
        val text = (1..700).joinToString(" ") { "word$it" }
        val d = WritableDocument("Paper", false, true, 20, 32, listOf(WritableBlock("reading", "text", text)))
        val plan = WritablePageLayout.plan(d, wrap)
        assertTrue(plan.pageCount > 1)
        assertEquals(text, plan.regions.filter { it.id == "reading" }.joinToString("") { it.text })
        plan.regions.forEach { assertTrue(it.x >= 0 && it.y >= 44 && it.x + it.width <= d.width && it.y + it.height <= d.bottom) }
        assertTrue(plan.regions.first { it.type == "text" }.x >= 112)
    }
    @Test fun structuredPageRejectsDuplicateIdsAndInvalidPenSizing() {
        val blocks = JSONArray().put(JSONObject().put("id", "q1").put("type", "question").put("text", "Question"))
            .put(JSONObject().put("id", "q1-answer").put("type", "writing"))
        assertTrue(runCatching { WritableDocument.parse(JSONObject().put("blocks", blocks)) }.isFailure)
        assertTrue(runCatching { WritableDocument.parse(JSONObject().put("blocks", JSONArray().put(JSONObject().put("id", "q").put("type", "text").put("text", "Text"))).put("font_size", 28).put("line_height", 30)) }.isFailure)
    }
}

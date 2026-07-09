package com.opc.baize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话历史编解码 + 旧档迁移 + 摘要，纯逻辑 JVM 测。 */
class SessionCodecTest {

    @Test fun roundTripEncodeDecode() {
        val sessions = listOf(
            Session("s1", 1000L, listOf(Turn("养生之道", "起居有常。"), Turn("再问", "答之。"))),
            Session("s2", 2000L, listOf(Turn("练字", "日临一帖。"))),
        )
        val json = SessionCodec.encode(sessions)
        val back = SessionCodec.decode(json)
        assertEquals(2, back.size)
        assertEquals("s1", back[0].id)
        assertEquals(1000L, back[0].createdAt)
        assertEquals(2, back[0].turns.size)
        assertEquals("养生之道", back[0].turns[0].question)
        assertEquals("起居有常。", back[0].turns[0].reply)
        assertEquals("练字", back[1].turns[0].question)
    }

    @Test fun emptySessionsDropped() {
        val json = SessionCodec.encode(listOf(Session("empty", 1L, emptyList())))
        assertTrue(SessionCodec.decode(json).isEmpty())
    }

    @Test fun decodeGarbageIsEmptyNotCrash() {
        assertTrue(SessionCodec.decode("not json").isEmpty())
        assertTrue(SessionCodec.decode("").isEmpty())
        assertTrue(SessionCodec.decode("""{"v":99}""").isEmpty())
        assertTrue(SessionCodec.decode("""{"v":2,"sessions":[]}""").isEmpty())
    }

    @Test fun summaryTruncatesAndFallsBack() {
        val short = Session("a", 1L, listOf(Turn("问天下事", "答")))
        assertEquals("问天下事", short.summary())
        val long = Session("b", 1L, listOf(Turn("一二三四五六七八九十一二三四五六七八九十", "答")))
        assertTrue(long.summary().endsWith("…"))
        assertEquals(19, long.summary().length)   // 18 字 + 省略号
        val blank = Session("c", 1L, listOf(Turn("", "答")))
        assertEquals("（无题）", blank.summary())
    }

    @Test fun legacyPagesMigrateToOneSession() {
        // 旧 v1 pages.json：you/book 交替，含网页版回显前缀
        val legacy = """
            {"v":1,"pages":[
              [["you","你写道：养生之道"],["book","起居有常。"]],
              [["you","你写道：练字"],["book","日临一帖。"]]
            ]}
        """.trimIndent()
        val out = SessionCodec.fromLegacyPages(legacy, "s_legacy", 5000L)
        assertEquals(1, out.size)
        assertEquals("s_legacy", out[0].id)
        assertEquals(2, out[0].turns.size)
        assertEquals("养生之道", out[0].turns[0].question)   // 前缀已去
        assertEquals("起居有常。", out[0].turns[0].reply)
        assertEquals("练字", out[0].turns[1].question)
    }

    @Test fun legacyGarbageIsEmpty() {
        assertTrue(SessionCodec.fromLegacyPages("nope", "x", 1L).isEmpty())
        assertTrue(SessionCodec.fromLegacyPages("""{"v":2}""", "x", 1L).isEmpty())
    }
}

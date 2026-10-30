package com.opc.baize.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** DeepSeek 请求体构造与响应解析（打印 JSON 供肉眼核对人设与记忆拼装） */
class DeepSeekProtocolTest {

    @Test fun requestBodyStructure() {
        val history = listOf(
            "养生之道" to "起居有常，饮食有节。",
            "如何练字" to "日临一帖，笔笔用心。",
        )
        val body = DeepSeekProtocol.buildRequestBody("deepseek-v4-flash", history, "今日宜做什么")
        println("=== 大模型请求体（肉眼核对） ===")
        println(JSONObject(body).toString(2))

        val o = JSONObject(body)
        assertEquals("deepseek-v4-flash", o.getString("model"))
        assertEquals(400, o.getInt("max_tokens"))
        assertEquals(Texts.PERSONA, o.getString("system"))

        val msgs = o.getJSONArray("messages")
        assertEquals(5, msgs.length())  // 2轮×2 + 本轮1
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        assertEquals("养生之道", msgs.getJSONObject(0).getString("content"))
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
        assertEquals("起居有常，饮食有节。", msgs.getJSONObject(1).getString("content"))
        assertEquals("user", msgs.getJSONObject(2).getString("role"))
        assertEquals("assistant", msgs.getJSONObject(3).getString("role"))
        assertEquals("user", msgs.getJSONObject(4).getString("role"))
        assertEquals("今日宜做什么", msgs.getJSONObject(4).getString("content"))
    }

    @Test fun emptyHistoryStillValid() {
        val body = DeepSeekProtocol.buildRequestBody("deepseek-v4-flash", emptyList(), "问一句")
        val msgs = JSONObject(body).getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("问一句", msgs.getJSONObject(0).getString("content"))
    }

    @Test fun parseJoinsOnlyTextBlocks() {
        val resp = """
            {"id":"msg_1","content":[
              {"type":"thinking","thinking":"内部思考不得外泄"},
              {"type":"text","text":"晨起"},
              {"type":"text","text":"宜静坐片刻。"}
            ]}
        """.trimIndent()
        assertEquals("晨起宜静坐片刻。", DeepSeekProtocol.parseReplyText(resp))
    }

    @Test fun parseEmptyContent() {
        assertEquals("", DeepSeekProtocol.parseReplyText("""{"content":[]}"""))
        assertEquals("", DeepSeekProtocol.parseReplyText("""{"error":{"message":"x"}}"""))
    }
}

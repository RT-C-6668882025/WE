package com.opc.baize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大模型配置就绪判定 + 端点拼接，纯逻辑 JVM 测（开源版去密钥兜底的核心）。 */
class ModelConfigTest {

    @Test fun readyOnlyWhenAllThreePresent() {
        assertTrue(ModelConfig("sk-x", "https://api.deepseek.com/anthropic", "deepseek-v4-flash").isReady())
        assertFalse(ModelConfig("", "https://ep", "m").isReady())
        assertFalse(ModelConfig("sk-x", "", "m").isReady())
        assertFalse(ModelConfig("sk-x", "https://ep", "").isReady())
        assertFalse(ModelConfig("  ", "https://ep", "m").isReady())   // 纯空白算空
    }

    @Test fun defaults() {
        assertEquals("https://api.deepseek.com/anthropic", ModelConfig.DEFAULT_ENDPOINT)
        assertEquals("deepseek-v4-flash", ModelConfig.DEFAULT_MODEL)
    }

    @Test fun messagesUrlAppendsPath() {
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            DeepSeekProtocol.messagesUrl("https://api.deepseek.com/anthropic"),
        )
        // 容忍末尾斜杠
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            DeepSeekProtocol.messagesUrl("https://api.deepseek.com/anthropic/"),
        )
    }
}

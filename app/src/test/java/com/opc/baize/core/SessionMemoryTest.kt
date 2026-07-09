package com.opc.baize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryTest {

    @Test fun keepsAtMostSixTurns() {
        val m = SessionMemory(6)
        for (i in 1..8) m.remember("问$i", "答$i")
        val h = m.history()
        assertEquals(6, h.size)
        assertEquals("问3" to "答3", h.first())   // 最老两轮被挤出
        assertEquals("问8" to "答8", h.last())
    }

    @Test fun emptyRecognizedNotRemembered() {
        val m = SessionMemory(6)
        m.remember("", "空识别的固定回文")
        assertTrue(m.history().isEmpty())
    }

    @Test fun clearWipesAll() {
        val m = SessionMemory(6)
        m.remember("甲", "乙")
        m.clear()
        assertTrue(m.history().isEmpty())
    }
}

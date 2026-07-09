package com.opc.baize.core

import com.opc.baize.core.TouchGate.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 误触闸分支：点数/包围盒/笔画数三道闸 + 低笔画结果裁决 */
class TouchGateTest {

    @Test fun tooFewPointsDiscarded() {
        assertEquals(Action.DISCARD, TouchGate.gate(3, 14, 200f, 200f))
        assertEquals(Action.DISCARD, TouchGate.gate(1, 0, 0f, 0f))
    }

    @Test fun tinyBoxDiscarded() {
        // 两边都 <48 才算误触（与网页版 exportInkPNG 同口径）
        assertEquals(Action.DISCARD, TouchGate.gate(3, 30, 47f, 47f))
        // 一边够大就放行（横长竖短的一行小字）
        assertEquals(Action.LOCAL_ONLY, TouchGate.gate(3, 30, 200f, 20f))
    }

    @Test fun lowStrokeGoesLocalOnly() {
        assertEquals(Action.LOCAL_ONLY, TouchGate.gate(1, 20, 100f, 100f))
        assertEquals(Action.LOCAL_ONLY, TouchGate.gate(4, 20, 100f, 100f))
    }

    @Test fun fiveStrokesGoFull() {
        assertEquals(Action.FULL, TouchGate.gate(5, 20, 100f, 100f))
        assertEquals(Action.FULL, TouchGate.gate(30, 500, 800f, 300f))
    }

    @Test fun customMinBoxScales() {
        // 高密度屏按 dp 换算后传入更大的阈值
        assertEquals(Action.DISCARD, TouchGate.gate(6, 30, 90f, 90f, minBox = 96f))
        assertEquals(Action.FULL, TouchGate.gate(6, 30, 100f, 20f, minBox = 96f))
    }

    @Test fun lowStrokeAcceptance() {
        assertFalse(TouchGate.acceptLowStroke(""))
        assertFalse(TouchGate.acceptLowStroke("   "))
        assertTrue(TouchGate.acceptLowStroke("1"))
        assertTrue(TouchGate.acceptLowStroke("42"))
        assertTrue(TouchGate.acceptLowStroke("B2"))
        assertTrue(TouchGate.acceptLowStroke("abc"))
        assertTrue(TouchGate.acceptLowStroke("人"))
        assertTrue(TouchGate.acceptLowStroke("你好"))
        // 1~4 笔冒出一长串 = 识别器幻觉，丢弃
        assertFalse(TouchGate.acceptLowStroke("你好世界啊"))
        assertFalse(TouchGate.acceptLowStroke("hello"))
    }
}

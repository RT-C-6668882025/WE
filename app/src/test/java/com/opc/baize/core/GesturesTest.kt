package com.opc.baize.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 两指翻页结算 + 角落长按区域判定，InkView 与 HwWritingContainer 共用的手势内核。 */
class GesturesTest {

    private val minX = 80f

    @Test fun leftSwipeIsNextPage() {
        // 两指都向左移 100px，几乎无垂直位移
        val dir = Gestures.swipeDir(listOf(-100f to 5f, -110f to -3f), minX)
        assertEquals(+1, dir)
    }

    @Test fun rightSwipeIsPrevPage() {
        val dir = Gestures.swipeDir(listOf(120f to 0f, 90f to 10f), minX)
        assertEquals(-1, dir)
    }

    @Test fun shortMoveIsNotSwipe() {
        assertEquals(0, Gestures.swipeDir(listOf(-30f to 0f, -40f to 0f), minX))
    }

    @Test fun verticalDominantIsNotSwipe() {
        // 水平够 80 但垂直更大（比率不足 1.6）
        assertEquals(0, Gestures.swipeDir(listOf(-100f to 90f, -100f to 80f), minX))
    }

    @Test fun emptyDeltasIsNotSwipe() {
        assertEquals(0, Gestures.swipeDir(emptyList(), minX))
    }

    @Test fun cornerDetection() {
        val w = 1000f
        val c = 120f
        assertEquals(Gestures.Corner.TOP_LEFT, Gestures.cornerAt(50f, 50f, w, c))
        assertEquals(Gestures.Corner.TOP_RIGHT, Gestures.cornerAt(950f, 100f, w, c))
        assertEquals(Gestures.Corner.NONE, Gestures.cornerAt(500f, 50f, w, c))    // 顶部中间
        assertEquals(Gestures.Corner.NONE, Gestures.cornerAt(50f, 300f, w, c))    // 左侧偏下
        assertEquals(Gestures.Corner.NONE, Gestures.cornerAt(950f, 300f, w, c))   // 右侧偏下
    }
}

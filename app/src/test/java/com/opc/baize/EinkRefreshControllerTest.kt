package com.opc.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 面板刷新模式控制器的容错底线（桌面 JVM，无 android.eink.EPDCDevice）：
 *   - isAvailable()=false（Class.forName 抛 ClassNotFoundException → 捕获）
 *   - enter/exit/forceRestore 全部不抛、writing 状态机不误判
 *   - "重复 enter 只切一次"：available=false 时 enter 不置 writing，且不抛
 *   - "异常后仍能 exit 恢复"：exit 后 writing 必回 false
 *
 * 真机上反射成功路径依赖设备类，JVM 无法覆盖；此测锁住"不可用即安全空转、绝不遗留快刷态"。
 */
class EinkRefreshControllerTest {

    @Test fun frameworkAbsentReturnsFalse() {
        assertFalse(EinkRefreshController.isFrameworkPresent())
    }

    @Test fun initStaysUnavailableOnDesktopNoCrash() {
        val c = EinkRefreshController()
        c.init()                       // 内部 ClassNotFoundException → 捕获
        assertFalse(c.isAvailable())
        assertFalse(c.available)
        assertFalse(c.writing)
    }

    @Test fun enterExitDoNotThrowWhenUnavailable() {
        val c = EinkRefreshController()
        c.init()
        // 不可用时 enter 不进快刷态（available 短路），不抛
        c.enterWriting()
        assertFalse(c.writing)
        // exit 幂等、不抛，writing 保持 false
        c.exitWriting()
        assertFalse(c.writing)
        // 兜底恢复也不抛
        c.forceRestoreQuality()
        assertFalse(c.writing)
    }

    @Test fun repeatedEnterAndExitAreIdempotent() {
        val c = EinkRefreshController()
        c.init()
        // 重复 enter：多次调用不抛、状态一致（桌面 available=false 恒为 false）
        c.enterWriting()
        c.enterWriting()
        c.enterWriting()
        assertFalse(c.writing)
        // 多次 exit / forceRestore 混调，writing 终点必须是 false（绝不遗留快刷态）
        c.exitWriting()
        c.forceRestoreQuality()
        c.exitWriting()
        assertFalse(c.writing)
    }

    @Test fun exitAlwaysLeavesWritingFalse() {
        // 直接连续调 exit（模拟"异常/未 enter 也强制恢复"路径），writing 终点恒为 false
        val c = EinkRefreshController()
        c.init()
        c.exitWriting()
        assertFalse(c.writing)
        c.forceRestoreQuality()
        assertFalse(c.writing)
    }

    @Test fun modeConstantsHaveExpectedValues() {
        // 锁住字面值不被误改（来自 android.eink.api.EinkMode）
        assertEquals(5, EinkRefreshController.FAST_DU4_PART)
        assertEquals(260, EinkRefreshController.FAST_A2_DITHER_PART)
        assertEquals(2, EinkRefreshController.QUALITY_GC16_PART)
        // 默认快刷用 DU4
        assertEquals(EinkRefreshController.FAST_DU4_PART, EinkRefreshController.FAST)
        assertEquals(EinkRefreshController.QUALITY_GC16_PART, EinkRefreshController.QUALITY)
    }
}

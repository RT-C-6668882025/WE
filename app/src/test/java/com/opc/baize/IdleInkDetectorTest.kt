package com.opc.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直写模式轮询停笔检测内核（IdleInkDetector）的纯逻辑单测。
 *
 * 覆盖 Bug 修复的核心判定（见任务 §修复要求）：喂"指纹序列 + 时间戳"，断言
 *   - 指纹连续 2.5s 无增长且非空 → fire=true（判停笔）；
 *   - 其间任一次有增长 → 重置计时（fire 一直 false）；
 *   - 指纹为空（没写）→ 永不 fire；
 *   - reset 后基线归零、需重新累计。
 * 轮询/反射/UI 全在 MainActivity，与本内核解耦，故本类可桌面 JVM 跑。
 */
class IdleInkDetectorTest {

    // 阈值 2500ms，轮询间隔 400ms（与真机一致）。
    private val idleMs = 2500L
    private val step = 400L

    @Test fun firesAfterThresholdWithoutGrowth() {
        val d = IdleInkDetector(idleMs)
        var now = 0L
        // 第一笔：落笔时刻 t=0，指纹 (1,10)，有增长 → 记最后书写时刻。
        assertFalse(d.onTick(1, 10, now).fire)

        // 之后不再增长，逐 tick 推进时间；未到 2.5s 前 fire 必须 false。
        var fired = false
        while (now < idleMs) {
            now += step
            val tick = d.onTick(1, 10, now)
            if (now < idleMs) {
                assertFalse("t=$now 未到阈值不应触发", tick.fire)
            }
            if (tick.fire) fired = true
        }
        // 累计跨过 2.5s（now=2800）时应已触发一次。
        assertTrue("越过 2.5s 无增长应触发停笔", fired)
    }

    @Test fun growthResetsIdleClock() {
        val d = IdleInkDetector(idleMs)
        // t=0 落笔
        d.onTick(1, 10, 0)
        // t=2000（还没到阈值）又写了一笔 → 增长，重置计时
        val mid = d.onTick(2, 25, 2000)
        assertTrue(mid.growing)
        assertFalse(mid.fire)
        // 从 2000 起再等：到 t=4000（距上次增长 2000ms<2500）不该触发
        assertFalse(d.onTick(2, 25, 4000).fire)
        // 到 t=4500（距上次增长 2500ms）应触发
        assertTrue(d.onTick(2, 25, 4500).fire)
    }

    @Test fun emptyInkNeverFires() {
        val d = IdleInkDetector(idleMs)
        // 全程空指纹：不管过多久都不触发。
        var now = 0L
        repeat(20) {
            now += step
            val tick = d.onTick(0, 0, now)
            assertFalse(tick.fire)
            assertFalse(tick.nonEmpty)
        }
    }

    @Test fun pointOnlyGrowthCounts() {
        // 同一笔画 track 数不变、点数在增（连续书写常态）→ 算增长，刷新计时。
        val d = IdleInkDetector(idleMs)
        d.onTick(1, 10, 0)
        assertTrue(d.onTick(1, 30, 500).growing)   // track 数不变但点数增
        assertFalse(d.onTick(1, 30, 500).fire)
        // 从 500 起停 2.5s → t=3000 触发
        assertTrue(d.onTick(1, 30, 3000).fire)
    }

    @Test fun resetClearsBaseline() {
        val d = IdleInkDetector(idleMs)
        d.onTick(3, 40, 0)
        // 提交后 reset：基线归零。
        d.reset()
        // reset 后（还没喂新墨）msSinceGrow 恒为 0（seenAny=false）。
        assertEquals(0L, d.msSinceGrow(9999L))
        // reset 后立刻喂同样指纹 → 视作新一笔的增长（不应立即 fire）。
        val t = d.onTick(3, 40, 100)
        assertTrue(t.growing)
        assertFalse(t.fire)
    }

    @Test fun noFireBeforeAnyInkEvenAfterLongIdle() {
        // 从没写过（seenAny=false）→ 即便时间戳很大也不触发。
        val d = IdleInkDetector(idleMs)
        assertFalse(d.onTick(0, 0, 99999).fire)
    }

    @Test fun setThresholdTakesEffectImmediately() {
        // 设置面板改档位 → setThreshold 即时影响后续判停（老板 2026-07-08 的可调延迟需求）。
        val d = IdleInkDetector(2500L)
        assertFalse(d.onTick(1, 10, 0L).fire)          // 落笔
        d.setThreshold(300L)                            // 用户调到最快档 300ms
        assertFalse(d.onTick(1, 10, 200L).fire)         // 200ms 未到新阈值
        assertTrue(d.onTick(1, 10, 350L).fire)          // 越过 300ms → 触发（若仍按 2500 则不会）
    }

    @Test fun largerThresholdWaitsLonger() {
        // 调到最慢档 3000ms：写字慢的人中途停顿久也不被提前提交。
        val d = IdleInkDetector(800L)
        d.setThreshold(3000L)
        assertFalse(d.onTick(1, 10, 0L).fire)
        assertFalse(d.onTick(1, 10, 1500L).fire)        // 1.5s 停顿，慢写者尚未写完，不该触发
        assertTrue(d.onTick(1, 10, 3100L).fire)         // 越过 3s 才判停
    }
}

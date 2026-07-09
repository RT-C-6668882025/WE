package com.opc.baize.core

import kotlin.math.abs

/**
 * 手势判定的共用纯逻辑内核（无 Android 依赖，可 JVM 单测）。
 *
 * InkView 老路与 HwWritingContainer 直写容器共用同一套手势语义：
 * 两指平均位移判翻页、角落区域长按判清空/设置。阈值与结算规则只此一份，
 * 两个视图只负责喂事件坐标，绝不各自复制阈值。
 */
object Gestures {

    /** 角落长按区域边长（dp） */
    const val CORNER_DP = 120f

    /** 角落长按判定时长（ms） */
    const val LONG_PRESS_MS = 1200L

    /** 长按容忍的最大移动（dp），一动就不算长按 */
    const val TAP_MOVE_MAX_DP = 8f

    /** 翻页判定：平均水平位移下限（dp） */
    const val SWIPE_MIN_X_DP = 80f

    /** 翻页判定：水平位移须 ≥ 垂直位移的倍数 */
    const val SWIPE_XY_RATIO = 1.6f

    /**
     * 手势结束结算：各指 (dx, dy) 位移取平均，
     * 平均水平位移 ≥ swipeMinXPx 且 ≥ SWIPE_XY_RATIO × 垂直位移才算翻页。
     * @return +1 下一页（左滑）、-1 上一页（右滑）、0 不是翻页。
     */
    fun swipeDir(deltas: List<Pair<Float, Float>>, swipeMinXPx: Float): Int {
        if (deltas.isEmpty()) return 0
        var dx = 0f
        var dy = 0f
        for ((x, y) in deltas) { dx += x; dy += y }
        dx /= deltas.size
        dy /= deltas.size
        if (abs(dx) >= swipeMinXPx && abs(dx) >= abs(dy) * SWIPE_XY_RATIO) {
            return if (dx < 0) +1 else -1   // 左滑=下一页
        }
        return 0
    }

    /** 角落判定结果 */
    enum class Corner { NONE, TOP_LEFT, TOP_RIGHT }

    /** 落点是否在左上/右上角的长按区域内。 */
    fun cornerAt(x: Float, y: Float, viewWidth: Float, cornerPx: Float): Corner = when {
        y > cornerPx -> Corner.NONE
        x <= cornerPx -> Corner.TOP_LEFT
        x >= viewWidth - cornerPx -> Corner.TOP_RIGHT
        else -> Corner.NONE
    }
}

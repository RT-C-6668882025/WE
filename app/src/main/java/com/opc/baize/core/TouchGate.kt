package com.opc.baize.core

/**
 * 误触闸（v7 规则，前端 index.html 的导出过滤 + server.py 的低笔画闸合并）：
 * - 总点数 < 15 或包围盒两边都 < 48px → 连识别都不做，静默丢弃；
 * - 笔画数 1~4 → 只走本地识别（LOCAL_ONLY），识别不出/结果不像简短明确输入 → 静默丢弃；
 * - 笔画数 ≥5 → 完整流程（FULL）。
 * 纯函数，无 Android 依赖，供 JVM 单测。
 */
object TouchGate {

    const val MIN_POINTS = 15
    const val MIN_BOX = 48f          // px（调用方按屏幕密度换算后传入亦可）
    const val MIN_STROKES_FULL = 5

    enum class Action { DISCARD, LOCAL_ONLY, FULL }

    fun gate(
        strokeCount: Int,
        totalPoints: Int,
        bboxW: Float,
        bboxH: Float,
        minPoints: Int = MIN_POINTS,
        minBox: Float = MIN_BOX,
    ): Action {
        if (strokeCount <= 0 || totalPoints < minPoints) return Action.DISCARD
        // 与网页版 exportInkPNG 同口径：两边【都】小于阈值才算误触
        if (bboxW < minBox && bboxH < minBox) return Action.DISCARD
        return if (strokeCount < MIN_STROKES_FULL) Action.LOCAL_ONLY else Action.FULL
    }

    /**
     * 低笔画（1~4 笔）本地识别结果的裁决：
     * 空 → 丢弃；“1”“B2”“人”这类 ≤3 个字符的短而明确结果 → 照常应答；
     * 1~4 笔却识别出一长串 → 视为识别器幻觉，丢弃不打扰。
     */
    fun acceptLowStroke(recognized: String): Boolean {
        val t = recognized.trim()
        if (t.isEmpty()) return false
        return t.codePointCount(0, t.length) <= 3
    }
}

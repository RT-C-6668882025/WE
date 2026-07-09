package com.opc.baize

/**
 * 直写（HWEditView）模式下的**纯逻辑停笔检测内核**（无任何 Android 依赖，可桌面 JVM 单测）。
 *
 * 背景（见任务 §Bug）：HWEditView 作为 SurfaceView 自吃 onTouchEvent，容器观察不到单指的
 * 落笔/抬笔，原有"触摸事件驱动的停笔计时"在直写模式下根本不启动。改为**轮询笔迹增量**：
 * 定期（MainActivity 当前 150ms 一次）读笔迹规模指纹 (tracks, points)，喂给本检测器：
 *   - 指纹相比上次**有增长** → 还在写，刷新"最后书写时刻"。
 *   - 指纹**连续 idleThresholdMs 无增长且非空** → 判定停笔（fire=true）。
 *   - 指纹为空 → 不动作。
 *
 * 本类只吃 (fingerprint, nowMs)，返回该次 tick 的判定结果，完全可测：喂指纹序列 + 时间戳，
 * 断言阈值内无增长时 fire=true、其间有增长则重置。轮询、反射、UI 全在 MainActivity 里，
 * 与本类解耦。
 *
 * @param idleThresholdMs 无增长多久判停笔（默认 800ms）。可运行时经 setThreshold 调整，
 *        因为不同人写字快慢不同——设置面板暴露 300~3000ms 档位让用户自选（老板 2026-07-08）。
 */
class IdleInkDetector(idleThresholdMs: Long = 800L) {

    /** 停笔阈值，运行时可改（设置面板即时生效）。 */
    private var idleThresholdMs: Long = idleThresholdMs

    /** 设置面板改档位时调用，立即影响后续判停。 */
    fun setThreshold(ms: Long) { idleThresholdMs = ms }

    /** 单次 tick 的判定结果。 */
    data class Tick(
        /** 本次是否判定为停笔（应触发提交）。 */
        val fire: Boolean,
        /** 当前是否在写（指纹相对上次有增长）。用于驱动面板快刷 enter/exit。 */
        val growing: Boolean,
        /** 当前笔迹是否非空。 */
        val nonEmpty: Boolean,
    )

    private var lastTracks = 0
    private var lastPoints = 0
    private var lastGrowMs = 0L
    private var seenAny = false

    /** 提交后（或清屏后）复位基线，准备下一轮输入。 */
    fun reset() {
        lastTracks = 0
        lastPoints = 0
        lastGrowMs = 0L
        seenAny = false
    }

    /**
     * 喂一次指纹 + 当前时刻，返回判定。
     * 增长定义：track 数或总点数任一比上次大（HWEditView 只增不减，除非 clear，clear 后应 reset）。
     */
    fun onTick(tracks: Int, points: Int, nowMs: Long): Tick {
        val nonEmpty = tracks > 0 || points > 0
        val grew = tracks > lastTracks || points > lastPoints

        if (grew) {
            lastTracks = tracks
            lastPoints = points
            lastGrowMs = nowMs
            seenAny = true
            return Tick(fire = false, growing = true, nonEmpty = nonEmpty)
        }

        // 无增长：非空且距上次增长已超过阈值 → 停笔
        if (nonEmpty && seenAny && nowMs - lastGrowMs >= idleThresholdMs) {
            return Tick(fire = true, growing = false, nonEmpty = true)
        }
        return Tick(fire = false, growing = false, nonEmpty = nonEmpty)
    }

    /** 距上次增长多久（ms）。仅供日志用。首次/复位后返回 0。 */
    fun msSinceGrow(nowMs: Long): Long = if (seenAny) nowMs - lastGrowMs else 0L
}

package com.opc.baize

/**
 * IHWTrack / HWPoint → 白泽 Stroke 的**纯转换内核**（无任何 Android / 华为类依赖）。
 *
 * 为什么单独抽出来：真正的 IHWTrack/HWPoint 在 BOOTCLASSPATH 上，桌面 JVM 与真机编译期
 * 都不可见，只能反射。若把转换逻辑埋进反射代码里就没法在桌面 JVM 单测。因此把"一串华为
 * 轨迹点 → 白泽笔画结构"这条纯逻辑剥出来，喂普通数据结构（RawTrack/RawPoint），断言坐标、
 * 压力、eventtime 不丢、笔画分组正确。HwHandwritingLayer 的反射层只负责把真正的 IHWTrack
 * 反射读成 RawTrack 再交给这里，转换规则由此可测。
 *
 * 白泽的 Stroke = List<InkView.Pt>；一条 IHWTrack = 一笔 = 一个 Stroke。
 * Pt(x, y, w, t)：x/y 来自 HWPoint（PointF），w=笔宽（来自 track.getPenWidth，压力仅记录
 * 备用），t=eventtime（供 ML Kit 时序识别）。压力单独随点保留，避免识别/持久化丢信息。
 */
object HwTrackConverter {

    /** 一个华为轨迹点的可测镜像：坐标 + 事件时间 + 压力。 */
    data class RawPoint(val x: Float, val y: Float, val eventtime: Long, val pressure: Float)

    /** 一条华为轨迹（=一笔）的可测镜像：点列表 + 笔宽 + 笔色。 */
    data class RawTrack(val points: List<RawPoint>, val penWidth: Float, val penColor: Int)

    /** 一个转换后的白泽点：与 InkView.Pt 同构（x,y,w,t）+ 压力旁挂。 */
    data class ConvPoint(val x: Float, val y: Float, val w: Float, val t: Long, val pressure: Float)

    /**
     * 把华为轨迹列表转成白泽笔画列表。
     * - 每条 track → 一笔（List<ConvPoint>）。
     * - 笔宽 w：取 track.penWidth（>0 时），否则退回一个安全基准（4f，与 InkView.baseWidth
     *   同口径，dp 折算由调用方保证；这里只做无华为依赖的纯数值处理）。
     * - t：直接用 HWPoint.eventtime（ML Kit 用相对时序，绝对值无所谓、只要单调）。
     * - pressure：原样保留在 ConvPoint.pressure，供需要时二次调宽/持久化，不丢。
     * - 空点的 track 直接跳过（不产生空笔）。
     */
    fun convert(tracks: List<RawTrack>, fallbackWidth: Float = 4f): List<List<ConvPoint>> {
        val out = ArrayList<List<ConvPoint>>(tracks.size)
        for (tk in tracks) {
            if (tk.points.isEmpty()) continue
            val w = if (tk.penWidth > 0f) tk.penWidth else fallbackWidth
            val stroke = ArrayList<ConvPoint>(tk.points.size)
            for (p in tk.points) {
                stroke.add(ConvPoint(p.x, p.y, w, p.eventtime, p.pressure))
            }
            out.add(stroke)
        }
        return out
    }

    /** 笔画数 = 非空轨迹数（喂误触闸 / /api/ask 的 strokes 字段）。 */
    fun strokeCount(tracks: List<RawTrack>): Int = tracks.count { it.points.isNotEmpty() }

    /** 总点数（日志与误触统计用）。 */
    fun totalPoints(tracks: List<RawTrack>): Int = tracks.sumOf { it.points.size }
}

package com.opc.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 华为轨迹 → 白泽 Stroke 的纯转换（HwTrackConverter）。用假数据断言：
 *   - 每条 track 转成一笔、点数不丢；
 *   - 坐标 x/y、eventtime、pressure 逐点保真；
 *   - 笔宽取 track.penWidth，缺失（<=0）时退回 fallback；
 *   - 空点 track 被跳过，不产生空笔；
 *   - strokeCount/totalPoints 与误触闸口径一致。
 */
class HwTrackConverterTest {

    private fun pt(x: Float, y: Float, t: Long, p: Float) = HwTrackConverter.RawPoint(x, y, t, p)

    @Test fun convertPreservesCoordsTimePressurePerPoint() {
        val tracks = listOf(
            HwTrackConverter.RawTrack(
                points = listOf(pt(10f, 20f, 1000L, 0.4f), pt(11f, 21f, 1016L, 0.9f)),
                penWidth = 6f, penColor = -16777216
            ),
            HwTrackConverter.RawTrack(
                points = listOf(pt(50f, 60f, 2000L, 0.7f)),
                penWidth = 8f, penColor = -16777216
            ),
        )
        val out = HwTrackConverter.convert(tracks, fallbackWidth = 4f)

        // 两条 track → 两笔
        assertEquals(2, out.size)
        assertEquals(2, out[0].size)
        assertEquals(1, out[1].size)

        // 第一笔逐点保真（坐标 / 时间 / 压力 / 笔宽=6）
        val a0 = out[0][0]
        assertEquals(10f, a0.x, 0f); assertEquals(20f, a0.y, 0f)
        assertEquals(1000L, a0.t); assertEquals(0.4f, a0.pressure, 0f)
        assertEquals(6f, a0.w, 0f)
        val a1 = out[0][1]
        assertEquals(11f, a1.x, 0f); assertEquals(21f, a1.y, 0f)
        assertEquals(1016L, a1.t); assertEquals(0.9f, a1.pressure, 0f)

        // 第二笔笔宽=8
        assertEquals(8f, out[1][0].w, 0f)
        assertEquals(2000L, out[1][0].t)
    }

    @Test fun missingPenWidthFallsBack() {
        val tracks = listOf(
            HwTrackConverter.RawTrack(
                points = listOf(pt(1f, 1f, 0L, 0.5f)),
                penWidth = 0f, penColor = -16777216   // 缺笔宽
            )
        )
        val out = HwTrackConverter.convert(tracks, fallbackWidth = 4f)
        assertEquals(4f, out[0][0].w, 0f)
    }

    @Test fun emptyTracksSkipped() {
        val tracks = listOf(
            HwTrackConverter.RawTrack(emptyList(), 5f, -16777216),               // 空笔，跳过
            HwTrackConverter.RawTrack(listOf(pt(3f, 3f, 5L, 0.5f)), 5f, -16777216),
        )
        val out = HwTrackConverter.convert(tracks)
        assertEquals(1, out.size)            // 只剩非空那笔
        assertEquals(3f, out[0][0].x, 0f)
    }

    @Test fun strokeAndPointCountsMatchGateSemantics() {
        val tracks = listOf(
            HwTrackConverter.RawTrack(listOf(pt(0f, 0f, 0L, 0.5f), pt(1f, 1f, 1L, 0.5f)), 5f, 0),
            HwTrackConverter.RawTrack(emptyList(), 5f, 0),                        // 不计入笔画数
            HwTrackConverter.RawTrack(listOf(pt(2f, 2f, 2L, 0.5f)), 5f, 0),
        )
        assertEquals(2, HwTrackConverter.strokeCount(tracks))   // 空笔不算
        assertEquals(3, HwTrackConverter.totalPoints(tracks))   // 2 + 0 + 1
    }

    @Test fun emptyInputYieldsEmptyOutput() {
        assertTrue(HwTrackConverter.convert(emptyList()).isEmpty())
        assertEquals(0, HwTrackConverter.strokeCount(emptyList()))
        assertEquals(0, HwTrackConverter.totalPoints(emptyList()))
    }
}

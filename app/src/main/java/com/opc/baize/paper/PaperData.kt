package com.opc.baize.paper

import org.json.JSONArray
import org.json.JSONObject

/** Rendered PDF page space in points, independent of view pixels, zoom and pan. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
data class PaperStroke(val points: List<InkPoint>, val width: Float = 1.8f)
data class PaperPage(val width: Int, val height: Int, val revision: Long, val strokes: List<PaperStroke>) {
    fun encode(): String = JSONObject().put("schema", 1).put("space", "android-pdf-renderer-page-points")
        .put("width", width).put("height", height).put("revision", revision).put("strokes", JSONArray().apply {
            strokes.forEach { stroke -> put(JSONObject().put("width", stroke.width).put("points", JSONArray().apply {
                stroke.points.forEach { put(JSONArray(listOf(it.x, it.y, it.pressure, it.time))) }
            })) }
        }).toString()
    companion object {
        fun decode(json: String): PaperPage {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("space") == "android-pdf-renderer-page-points")
            val width = o.getInt("width"); val height = o.getInt("height"); val revision = o.getLong("revision")
            require(width > 0 && height > 0 && revision >= 0)
            val strokes = o.getJSONArray("strokes"); require(strokes.length() <= 100000)
            var total = 0
            return PaperPage(width, height, revision, (0 until strokes.length()).map { i ->
                val stroke = strokes.getJSONObject(i); val points = stroke.getJSONArray("points")
                total += points.length(); require(total <= 1000000)
                val penWidth = stroke.getDouble("width").toFloat(); require(penWidth.isFinite() && penWidth > 0)
                PaperStroke((0 until points.length()).map { j ->
                    val p = points.getJSONArray(j)
                    val x = p.getDouble(0).toFloat(); val y = p.getDouble(1).toFloat(); val pressure = p.getDouble(2).toFloat()
                    require(x.isFinite() && y.isFinite() && pressure.isFinite() && x in 0f..width.toFloat() && y in 0f..height.toFloat())
                    InkPoint(x, y, pressure, p.getLong(3))
                }, penWidth)
            })
        }
    }
}

/** One transform shared by ink rendering, input mapping and selection. */
data class PaperTransform(val scale: Float, val left: Float, val top: Float) {
    fun pageX(viewX: Float) = (viewX - left) / scale
    fun pageY(viewY: Float) = (viewY - top) / scale
    companion object {
        fun fit(viewWidth: Int, viewHeight: Int, pageWidth: Int, pageHeight: Int, zoom: Float, dx: Float, dy: Float): PaperTransform {
            require(viewWidth > 0 && viewHeight > 0 && pageWidth > 0 && pageHeight > 0 && zoom > 0)
            val scale = minOf(viewWidth.toFloat() / pageWidth, viewHeight.toFloat() / pageHeight) * zoom
            return PaperTransform(scale, (viewWidth - pageWidth * scale) / 2 + dx, (viewHeight - pageHeight * scale) / 2 + dy)
        }
    }
}

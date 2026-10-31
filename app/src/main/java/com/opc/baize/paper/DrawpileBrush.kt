package com.opc.baize.paper

import android.graphics.*
import kotlin.math.*

/** Upstream Drawpile classic mask kernel via JNI; Android composites spaced dabs. */
object DrawpileBrush {
    const val ENGINE = "drawpile-classic-v1"
    init { System.loadLibrary("we_drawpile") }
    private external fun nativeStamp(hardness: Int): IntArray
    private val stamps = android.util.LruCache<Int, Bitmap>(8)
    @Synchronized private fun stamp(color: Int): Bitmap = stamps.get(color) ?: run {
        val pixels = nativeStamp(80)
        val side = sqrt(pixels.size.toDouble()).toInt()
        require(side * side == pixels.size && side > 0)
        pixels.indices.forEach { i -> pixels[i] = (pixels[i] and -0x1000000) or (color and 0xffffff) }
        Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).also { stamps.put(color, it) }
    }
    fun draw(canvas: Canvas, stroke: PaperStroke) {
        if (stroke.points.isEmpty()) return
        val bitmap = stamp(stroke.color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = Color.alpha(stroke.color) }
        fun dab(x: Float, y: Float, pressure: Float) {
            val size = stroke.width * (0.25f + 0.75f * pressure.coerceIn(0f, 1f)) * 35f / 32f
            canvas.drawBitmap(bitmap, null, RectF(x-size/2, y-size/2, x+size/2, y+size/2), paint)
        }
        val first = stroke.points.first(); dab(first.x, first.y, first.pressure)
        for (i in 1 until stroke.points.size) {
            val a = stroke.points[i-1]; val b = stroke.points[i]
            val distance = hypot(b.x-a.x, b.y-a.y)
            val steps = ceil(distance / maxOf(0.2f, stroke.width * 0.12f)).toInt().coerceIn(1, 20000)
            for (j in 1..steps) {
                val t = j.toFloat()/steps
                dab(a.x+(b.x-a.x)*t, a.y+(b.y-a.y)*t, a.pressure+(b.pressure-a.pressure)*t)
            }
        }
    }
}

/** Shared by the editor snapshot and MCP so feedback uses identical ink. */
object PaperStrokeRenderer {
    fun draw(canvas: Canvas, stroke: PaperStroke) {
        if (stroke.engine == DrawdanceBrush.ENGINE) { DrawdanceBrush.draw(canvas, stroke); return }
        if (stroke.engine == DrawpileBrush.ENGINE) { DrawpileBrush.draw(canvas, stroke); return }
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color; strokeWidth = stroke.width; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val path = Path(); stroke.points.forEachIndexed { i,p -> if (i==0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y) }
        if (stroke.points.size == 1) canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width/2, Paint(paint).apply { style=Paint.Style.FILL })
        else canvas.drawPath(path, paint)
    }
}

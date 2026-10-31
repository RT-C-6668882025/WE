package com.opc.baize.paper

import android.graphics.*
import android.util.LruCache
import kotlin.math.*

/** Real Drawpile brush -> protocol messages -> 15-bit tile canvas -> flattened pixels. */
object DrawdanceBrush {
    const val ENGINE = "drawdance-v1"
    init { System.loadLibrary("we_drawpile") }
    private external fun nativeRender(points: FloatArray,width: Int,height: Int,size: Float,color: Int): IntArray?
    private data class Raster(val bitmap: Bitmap,val bounds: RectF)
    private val cache = object : LruCache<PaperStroke,Raster>(32*1024*1024) {
        override fun sizeOf(key: PaperStroke,value: Raster) = value.bitmap.allocationByteCount + key.points.size*32
    }
    @Synchronized fun draw(canvas: Canvas,stroke: PaperStroke) {
        if (stroke.points.isEmpty()) return
        val raster = cache.get(stroke) ?: run {
            val padding = stroke.width + 4
            val left = floor(stroke.points.minOf { it.x }-padding)
            val top = floor(stroke.points.minOf { it.y }-padding)
            val right = ceil(stroke.points.maxOf { it.x }+padding)
            val bottom = ceil(stroke.points.maxOf { it.y }+padding)
            val scale = minOf(2f,2040f/maxOf(right-left,bottom-top))
            val width = ceil((right-left)*scale).toInt().coerceIn(1,2048)
            val height = ceil((bottom-top)*scale).toInt().coerceIn(1,2048)
            val start = stroke.points.first().time
            val points = FloatArray(stroke.points.size*4)
            stroke.points.forEachIndexed { i,p ->
                points[i*4]=(p.x-left)*scale; points[i*4+1]=(p.y-top)*scale
                points[i*4+2]=p.pressure.coerceIn(0f,1f); points[i*4+3]=(p.time-start).coerceAtLeast(0).toFloat()
            }
            val pixels = checkNotNull(nativeRender(points,width,height,stroke.width*scale,stroke.color)) { "Drawpile 渲染失败；笔迹坐标仍保留" }
            Raster(Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888),RectF(left,top,left+width/scale,top+height/scale))
                .also { cache.put(stroke,it) }
        }
        canvas.drawBitmap(raster.bitmap,null,raster.bounds,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}

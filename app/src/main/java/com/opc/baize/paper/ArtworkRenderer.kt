package com.opc.baize.paper

import android.graphics.*
import android.util.Base64
import com.caverock.androidsvg.SVG

/** One object renderer for live canvas, saved replay, export and AI screenshots. */
object ArtworkRenderer {
    private val vectors=android.util.LruCache<String,SVG>(4)
    private val images=object:android.util.LruCache<String,Bitmap>(8*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    @Synchronized fun validate(stroke:PaperStroke) {
        ArtworkContent.validate(stroke.operation,stroke.content,stroke.fontSize)
        when(stroke.operation) {
            "svg" -> svg(stroke.content)
            "image" -> image(stroke.content)
        }
    }
    private fun svg(content:String):SVG = vectors.get(content) ?: SVG.getFromString(content).also {
        require(it.documentViewBox!=null) { "SVG requires a viewBox" }
        vectors.put(content,it)
    }
    private fun image(content:String):Bitmap = images.get(content) ?: run {
        val bytes=Base64.decode(content,Base64.NO_WRAP)
        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        require(options.outWidth in 1..2048 && options.outHeight in 1..2048 && options.outMimeType in setOf("image/png","image/jpeg")) { "Image must be PNG/JPEG, at most 2048 per edge" }
        checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)).also {images.put(content,it)}
    }
    @Synchronized fun draw(canvas:Canvas,stroke:PaperStroke) {
        if(stroke.points.size!=4) return
        validate(stroke)
        val p=stroke.points
        val width=kotlin.math.hypot(p[1].x-p[0].x,p[1].y-p[0].y)
        val height=kotlin.math.hypot(p[3].x-p[0].x,p[3].y-p[0].y)
        if(width<=0 || height<=0) return
        val transform=Matrix()
        if(!transform.setPolyToPoly(floatArrayOf(0f,0f,width,0f,0f,height),0,floatArrayOf(p[0].x,p[0].y,p[1].x,p[1].y,p[3].x,p[3].y),0,3)) return
        canvas.save();canvas.concat(transform);canvas.clipRect(0f,0f,width,height)
        val alpha=(stroke.brush.opacity*255).toInt()
        val layer=canvas.saveLayerAlpha(0f,0f,width,height,alpha)
        when(stroke.operation) {
            "svg" -> svg(stroke.content).renderToCanvas(canvas,RectF(0f,0f,width,height))
            "image" -> canvas.drawBitmap(image(stroke.content),null,RectF(0f,0f,width,height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            "text" -> {
                // Box geometry scales independently from font size under object transforms.
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=stroke.color;textSize=stroke.fontSize;typeface=Typeface.DEFAULT}
                var y=-paint.fontMetrics.top
                stroke.content.lines().forEach {line -> canvas.drawText(line,0f,y,paint);y+=paint.fontSpacing}
            }
        }
        canvas.restoreToCount(layer);canvas.restore()
    }
}

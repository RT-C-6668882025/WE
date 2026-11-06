package com.opc.baize.paper

import android.graphics.*
import java.io.Closeable
import kotlin.math.ceil

internal object NativeDrawing {
    init { System.loadLibrary("we_drawpile") }
    external fun create(width: Int,height: Int): Long
    external fun cloneCanvas(handle: Long): Long
    external fun destroy(handle: Long)
    external fun stroke(handle: Long,points: FloatArray,size: Float,color: Int,options: FloatArray): Boolean
    external fun seed(handle: Long,pixels: IntArray,width: Int,height: Int): Boolean
    external fun pixels(handle: Long,width: Int,height: Int): IntArray?
}

/** Native canvases survive between strokes. Undo/edits rebuild; live previews clone tile state. */
class LayerRenderer : Closeable {
    private class LayerState(val width: Int,val height: Int) : Closeable {
        var handle=0L
        var done=emptyList<PaperStroke>()
        var bitmap: Bitmap?=null
        override fun close() { if(handle!=0L) NativeDrawing.destroy(handle);handle=0;bitmap?.recycle();bitmap=null }
    }
    private val states=linkedMapOf<Int,LayerState>()
    private fun apply(state: LayerState,stroke: PaperStroke,scale: Float,handle: Long = state.handle) {
        val start=stroke.points.firstOrNull()?.time ?: return
        val points=FloatArray(stroke.points.size*4)
        stroke.points.forEachIndexed { i,p -> points[i*4]=p.x*scale;points[i*4+1]=p.y*scale
            points[i*4+2]=p.pressure;points[i*4+3]=(p.time-start).coerceAtLeast(0).toFloat() }
        val b=stroke.brush
        check(NativeDrawing.stroke(handle,points,(stroke.width*scale).coerceAtMost(1000f),
            if(b.erase) stroke.color and 0xffffff else stroke.color,
            floatArrayOf(b.hardness,b.opacity*Color.alpha(stroke.color)/255f,b.spacing,b.smudge,if(b.pressure)1f else 0f,if(b.mypaint)1f else 0f)))
    }
    private fun bitmap(state: LayerState,handle: Long): Bitmap = Bitmap.createBitmap(
        checkNotNull(NativeDrawing.pixels(handle,state.width,state.height)),state.width,state.height,Bitmap.Config.ARGB_8888)
    private fun update(state: LayerState,strokes: List<PaperStroke>,scale: Float) {
        if(state.done==strokes && state.bitmap!=null) return
        if(state.done.size>strokes.size || state.done!=strokes.take(state.done.size)) {
            state.close();state.done=emptyList()
        }
        for(stroke in strokes.drop(state.done.size)) {
            if(stroke.operation=="fill" && stroke.points.isNotEmpty()) {
                val raster=if(state.handle!=0L) bitmap(state,state.handle) else state.bitmap?.copy(Bitmap.Config.ARGB_8888,true)
                    ?: Bitmap.createBitmap(state.width,state.height,Bitmap.Config.ARGB_8888)
                val pixels=IntArray(state.width*state.height);raster.getPixels(pixels,0,state.width,0,0,state.width,state.height);raster.recycle()
                val point=stroke.points.first();val x=(point.x*scale).toInt().coerceIn(0,state.width-1);val y=(point.y*scale).toInt().coerceIn(0,state.height-1)
                flood(pixels,state.width,state.height,x,y,stroke.color)
                if(state.handle!=0L) NativeDrawing.destroy(state.handle)
                state.handle=NativeDrawing.create(state.width,state.height);check(state.handle!=0L)
                check(NativeDrawing.seed(state.handle,pixels,state.width,state.height))
            } else if(stroke.operation !in ArtworkContent.types && stroke.operation!="polygon" && stroke.engine==DrawdanceBrush.ENGINE) {
                if(state.handle==0L) {
                    state.handle=NativeDrawing.create(state.width,state.height);check(state.handle!=0L)
                    state.bitmap?.let { old -> val pixels=IntArray(state.width*state.height)
                        old.getPixels(pixels,0,state.width,0,0,state.width,state.height)
                        check(NativeDrawing.seed(state.handle,pixels,state.width,state.height)) }
                }
                apply(state,stroke,scale)
            } else {
                val old=if(state.handle!=0L) bitmap(state,state.handle) else state.bitmap
                if(state.handle!=0L) { NativeDrawing.destroy(state.handle);state.handle=0 }
                val target=old ?: Bitmap.createBitmap(state.width,state.height,Bitmap.Config.ARGB_8888)
                Canvas(target).apply { scale(scale,scale);PaperStrokeRenderer.draw(this,stroke) }
                if(state.bitmap!==target) state.bitmap?.recycle()
                state.bitmap=target
            }
        }
        if(state.handle!=0L) { val raster=bitmap(state,state.handle);state.bitmap?.recycle();state.bitmap=raster }
        if(state.bitmap==null) state.bitmap=Bitmap.createBitmap(state.width,state.height,Bitmap.Config.ARGB_8888)
        state.done=strokes.toList()
    }
    @Synchronized fun draw(canvas: Canvas,page: PaperPage,preview: PaperStroke?=null) {
        // Bound native working memory independently of PDF dimensions and zoom.
        val scale=minOf(1.5f,1536f/maxOf(page.width,page.height))
        val w=ceil(page.width*scale).toInt().coerceAtLeast(1);val h=ceil(page.height*scale).toInt().coerceAtLeast(1)
        states.keys.filter { id -> page.layers.none { it.id==id } }.forEach { states.remove(it)?.close() }
        page.layers.filter { it.visible && it.opacity>0 }.forEach { layer ->
            var state=states[layer.id]
            if(state==null || state.width!=w || state.height!=h) {state?.close();state=LayerState(w,h);states[layer.id]=state}
            val active=state!!
            update(active,page.strokes.filter { it.layerId==layer.id },scale)
            var raster=active.bitmap!!;var temporary: Bitmap?=null
            if(preview!=null && preview.operation!="fill" && preview.layerId==layer.id && preview.points.isNotEmpty()) {
                if(preview.operation !in ArtworkContent.types && preview.operation!="polygon" && preview.engine==DrawdanceBrush.ENGINE) {
                    val copy=if(active.handle!=0L) NativeDrawing.cloneCanvas(active.handle) else NativeDrawing.create(w,h)
                    check(copy!=0L)
                    try {
                        if(active.handle==0L) { val pixels=IntArray(w*h);raster.getPixels(pixels,0,w,0,0,w,h);check(NativeDrawing.seed(copy,pixels,w,h)) }
                        apply(active,preview,scale,copy);temporary=bitmap(active,copy)
                    } finally {NativeDrawing.destroy(copy)}
                } else {
                    temporary=raster.copy(Bitmap.Config.ARGB_8888,true)
                    Canvas(temporary!!).apply {scale(scale,scale);PaperStrokeRenderer.draw(this,preview)}
                }
                raster=temporary!!
            }
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha=(layer.opacity*255).toInt();xfermode=PorterDuffXfermode(when(layer.blend) {
                    "multiply"->PorterDuff.Mode.MULTIPLY;"screen"->PorterDuff.Mode.SCREEN;"overlay"->PorterDuff.Mode.OVERLAY
                    "darken"->PorterDuff.Mode.DARKEN;"lighten"->PorterDuff.Mode.LIGHTEN;else->PorterDuff.Mode.SRC_OVER })
            }
            canvas.drawBitmap(raster,null,RectF(0f,0f,page.width.toFloat(),page.height.toFloat()),paint)
            temporary?.recycle()
            // Keep at most two native layer canvases resident; other layers replay on demand.
            while(states.size>2) {val first=states.keys.first();states.remove(first)?.close()}
        }
    }
    private fun flood(pixels: IntArray,w: Int,h: Int,x: Int,y: Int,color: Int) {
        val target=pixels[y*w+x];if(target==color) return
        val queue=IntArray(pixels.size);var head=0;var tail=0
        queue[tail++]=y*w+x;pixels[y*w+x]=color
        fun visit(i: Int) {if(pixels[i]==target) {pixels[i]=color;queue[tail++]=i}}
        while(head<tail) {val i=queue[head++];val px=i%w
            if(px>0) visit(i-1);if(px+1<w) visit(i+1);if(i>=w) visit(i-w);if(i+w<w*h) visit(i+w)
        }
    }
    @Synchronized override fun close() {states.values.forEach {it.close()};states.clear()}
}

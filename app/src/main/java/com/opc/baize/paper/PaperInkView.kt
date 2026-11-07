package com.opc.baize.paper

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Independent editable vectors over a PDF bitmap; no model/network work in touch callbacks. */
class PaperInkView(context: Context) : View(context) {
    enum class Mode { WRITE, MOVE, SELECT, ERASE }
    var mode = Mode.WRITE
        set(value) {
            cancelInteraction()
            field = value
            eraserPosition = if (value == Mode.ERASE) PointF(page.width / 2f, page.height / 2f) else null
            invalidate()
        }
    var penColor: Int = Color.BLACK
    var brushEngine = "vector"
    var brushSettings = BrushSettings()
    var drawingTool = "freehand"
    var pixelEraser = false
    private val layerRenderer = LayerRenderer()
    var penWidth: Float = 1.8f
    val hasSelection: Boolean get() = region?.let { it.width() > 4 && it.height() > 4 } == true
    fun clearSelection() { cancelInteraction(); region = null; selectionBefore = null; invalidate() }
    var toolFeedback: ((String) -> Unit)? = null
    var changed: ((PaperPage) -> Unit)? = null
    var strokeStarted: (() -> Unit)? = null
    var stylusOnly = false
    private var image: Bitmap? = null
    var page = PaperPage(1, 1, 0, emptyList()); private set
    private var current = mutableListOf<InkPoint>()
    private val history = java.util.ArrayDeque<PaperPage>()
    private val future = java.util.ArrayDeque<PaperPage>()
    private var zoom = 1f; private var dx = 0f; private var dy = 0f
    private var lastX = 0f; private var lastY = 0f
    private var startX = 0f; private var startY = 0f
    private var selecting = false
    private var activePointer = -1
    private var gestureSequence = false
    private var gestureFocus: PointF? = null
    private var gestureSpan = 0f
    private var eraserPosition: PointF? = null
    private var eraseBefore: List<PaperStroke>? = null
    private var selectionBefore: RectF? = null
    var eraserRadius = 12f
        set(value) {field=value.coerceIn(1f,250f);invalidate()}
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun cancelInteraction() {
        current.clear()
        eraseBefore?.let { page = page.copy(strokes = it) }; eraseBefore = null
        if (selecting) region = selectionBefore?.let { RectF(it) }
        selecting = false; activePointer = -1
    }
    private var region: RectF? = null
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    fun load(bitmap: Bitmap, data: PaperPage) {
        cancelInteraction(); gestureSequence = false; gestureFocus = null; layerRenderer.close(); image?.recycle(); image = bitmap; page = data; current.clear(); history.clear(); future.clear(); region = null; zoom = 1f; dx = 0f; dy = 0f; invalidate()
    }
    fun release() { cancelInteraction(); layerRenderer.close(); image?.recycle(); image = null }
    fun resetView() { zoom = 1f; dx = 0f; dy = 0f; invalidate() }
    fun zoom(factor: Float) { zoom = (zoom * factor).coerceIn(.1f, 20f); invalidate() }
    var historyRequest: ((Boolean)->Unit)? = null
    val canAcceptExternal: Boolean get() = activePointer == -1 && current.isEmpty() && eraseBefore == null && !selecting && !gestureSequence
    fun acceptExternal(data: PaperPage) {
        require(canAcceptExternal); cancelInteraction(); page=data; history.clear(); future.clear(); invalidate()
    }
    fun context(): org.json.JSONObject {
        val t=transform()
        return org.json.JSONObject().put("revision",page.revision).put("width",page.width).put("height",page.height)
            .put("drawing_tool",drawingTool).put("tool",mode.name.lowercase()).put("brush_engine",brushEngine).put("brush",brushSettings.encode())
            .put("pen_width",penWidth).put("pen_color",String.format("#%08X",penColor))
            .put("selection",region?.let {org.json.JSONObject().put("left",it.left).put("top",it.top).put("right",it.right).put("bottom",it.bottom)} ?: org.json.JSONObject.NULL)
            .put("viewport",org.json.JSONObject().put("scale",t.scale).put("left",t.left).put("top",t.top).put("view_width",width).put("view_height",height))
            .put("input_in_progress",!canAcceptExternal)
    }
    fun undo() {
        historyRequest?.let {it(false);return}
        cancelInteraction()
        val previous = if(history.isNotEmpty()) history.removeLast() else return
        future.addLast(page); publish(previous)
    }
    fun clearCanvas() { edit(page.copy(strokes=emptyList())) }
    fun redo() { historyRequest?.let {it(true);return}; cancelInteraction(); if(future.isNotEmpty()) { history.addLast(page); publish(future.removeLast()) } }
    private fun publish(data: PaperPage) {
        page=data.copy(revision=page.revision+1); changed?.invoke(page); invalidate()
    }
    private fun edit(data: PaperPage) {
        cancelInteraction(); if(data==page) return
        history.addLast(page);if(history.size>30) history.removeFirst();future.clear();publish(data)
    }
    private fun commit(strokes: List<PaperStroke>) = edit(page.copy(strokes=strokes))
    fun applyArtwork(operations:org.json.JSONArray):Boolean {
        try {
            val next=CanvasEdits.apply(page,operations)
            next.strokes.filter {it.operation in ArtworkContent.types}.forEach {ArtworkRenderer.validate(it)}
            edit(next);return true
        } catch(e:Exception) {toolFeedback?.invoke("未应用：${e.message}");return false}
    }
    fun cancelInput() {cancelInteraction();gestureSequence=false;gestureFocus=null;invalidate()}
    fun selectLayer(id: Int) {applyArtwork(org.json.JSONArray().put(org.json.JSONObject().put("action","select_layer").put("layer_id",id)))}
    fun addLayer() {applyArtwork(org.json.JSONArray().put(org.json.JSONObject().put("action","add_layer")))}
    fun updateLayer(layer:PaperLayer) {applyArtwork(org.json.JSONArray().put(org.json.JSONObject()
        .put("action","update_layer").put("layer_id",layer.id).put("name",layer.name).put("visible",layer.visible)
        .put("locked",layer.locked).put("opacity",layer.opacity).put("blend",layer.blend)))}
    fun deleteLayer() {applyArtwork(org.json.JSONArray().put(org.json.JSONObject().put("action",if(page.layers.size==1) "clear_layer" else "delete_layer")))}
    fun reorderLayer(direction:Int) {
        val index=page.layers.indexOfFirst {it.id==page.activeLayerId};val next=(index+direction).coerceIn(0,page.layers.lastIndex)
        if(index!=next) applyArtwork(org.json.JSONArray().put(org.json.JSONObject().put("action","reorder_layer").put("index",next)))
    }
    fun duplicateLayer() {applyArtwork(org.json.JSONArray().put(org.json.JSONObject().put("action","duplicate_layer")))}
    fun transformSelection(moveX:Float,moveY:Float,scale:Float,duplicate:Boolean=false,delete:Boolean=false,rotation:Float=0f,mirror:Boolean=false):Boolean {
        val bounds=region ?: return false
        val indices=page.strokes.indices.filter {i -> val stroke=page.strokes[i]
            stroke.layerId==page.activeLayerId && stroke.points.isNotEmpty() && stroke.points.all {p ->
                p.x>=bounds.left && p.x<=bounds.right && p.y>=bounds.top && p.y<=bounds.bottom
            }
        }
        if(indices.isEmpty()) {toolFeedback?.invoke("选区内没有完整对象");return false}
        val op=org.json.JSONObject().put("action",if(delete) "delete_strokes" else "transform")
            .put("indices",org.json.JSONArray(indices)).put("dx",moveX).put("dy",moveY).put("scale",scale)
            .put("duplicate",duplicate).put("rotation",rotation).put("mirror",mirror).put("center_x",bounds.centerX()).put("center_y",bounds.centerY())
        val success=applyArtwork(org.json.JSONArray().put(op))
        if(success) clearSelection()
        return success
    }
    fun exportBitmap(transparent: Boolean): Bitmap {
        check(image!=null)
        val scale=minOf(2f,2048f/maxOf(page.width,page.height))
        val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {scale(scale,scale);if(!transparent) {
            drawColor(Color.WHITE);drawBitmap(image!!,null,RectF(0f,0f,page.width.toFloat(),page.height.toFloat()),null)
        };strokes(this,page)}
        return bitmap
    }
    private fun currentStroke(): PaperStroke {
        val erasing=mode==Mode.ERASE && pixelEraser
        val points=if(!erasing && current.size>=2) shapePoints(current.first(),current.last()) else current.toList()
        return PaperStroke(points,if(erasing) eraserRadius*2 else penWidth,penColor,
            if(erasing) DrawdanceBrush.ENGINE else brushEngine,page.activeLayerId,
            if(erasing) BrushSettings(hardness=1f,pressure=false,erase=true) else brushSettings,
            if(!erasing && drawingTool=="fill") "fill" else "stroke")
    }
    private fun shapePoints(a: InkPoint,b: InkPoint): List<InkPoint> = when(drawingTool) {
        "line" -> listOf(a.copy(pressure=1f),b.copy(pressure=1f))
        "rectangle" -> listOf(a,a.copy(x=b.x),b,a.copy(y=b.y),a).mapIndexed {i,p -> p.copy(pressure=1f,time=a.time+i*10)}
        "ellipse" -> (0..96).map {i -> val angle=i*Math.PI*2/96
            a.copy(x=(a.x+b.x)/2+kotlin.math.cos(angle).toFloat()*kotlin.math.abs(b.x-a.x)/2,
                y=(a.y+b.y)/2+kotlin.math.sin(angle).toFloat()*kotlin.math.abs(b.y-a.y)/2,pressure=1f,time=a.time+i*10) }
        else -> current.toList()
    }
    private fun erase(x: Float, y: Float) {
        fun near(a: InkPoint, b: InkPoint, width: Float): Boolean {
            val vx = b.x - a.x; val vy = b.y - a.y; val squared = vx * vx + vy * vy
            val t = if (squared == 0f) 0f else (((x - a.x) * vx + (y - a.y) * vy) / squared).coerceIn(0f, 1f)
            val dx = x - a.x - t * vx; val dy = y - a.y - t * vy
            return dx * dx + dy * dy <= (eraserRadius + width / 2) * (eraserRadius + width / 2)
        }
        val kept = page.strokes.filterNot { stroke -> stroke.layerId==page.activeLayerId && ( stroke.points.firstOrNull()?.let { near(it, it, stroke.width) } == true ||
            (1 until stroke.points.size).any { near(stroke.points[it - 1], stroke.points[it], stroke.width) }) }
        if (kept.size != page.strokes.size) page = page.copy(strokes = kept)
    }
    private fun transform() = PaperTransform.fit(width.coerceAtLeast(1), height.coerceAtLeast(1), page.width, page.height, zoom, dx, dy)
    private fun strokes(canvas: Canvas, data: PaperPage) {
        layerRenderer.draw(canvas,data)
    }
    private fun drawStroke(canvas: Canvas, stroke: PaperStroke) {
        PaperStrokeRenderer.draw(canvas, stroke)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.LTGRAY)
        val t = transform(); canvas.save(); canvas.translate(t.left, t.top); canvas.scale(t.scale, t.scale)
        canvas.clipRect(0f, 0f, page.width.toFloat(), page.height.toFloat())
        canvas.drawColor(Color.WHITE)
        image?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null) }
        layerRenderer.draw(canvas,page,current.takeIf {it.isNotEmpty()}?.let {currentStroke()})
        region?.let { pen.color = Color.DKGRAY; pen.strokeWidth = 1f / t.scale; canvas.drawRect(it, pen) }
        if (mode == Mode.ERASE) eraserPosition?.let { point ->
            cursorPaint.style = Paint.Style.FILL; cursorPaint.color = 0x3371A6FF
            canvas.drawCircle(point.x, point.y, eraserRadius, cursorPaint)
            cursorPaint.style = Paint.Style.STROKE; cursorPaint.color = Color.WHITE; cursorPaint.strokeWidth = 4f / t.scale
            canvas.drawCircle(point.x, point.y, eraserRadius, cursorPaint)
            cursorPaint.color = Color.rgb(25, 85, 175); cursorPaint.strokeWidth = 2f / t.scale
            canvas.drawCircle(point.x, point.y, eraserRadius, cursorPaint)
        }
        canvas.restore()
    }
    private fun fingerGesture(e: MotionEvent): Boolean {
        val fingers = (0 until e.pointerCount).filter {
            e.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER &&
                !(e.actionMasked == MotionEvent.ACTION_POINTER_UP && it == e.actionIndex)
        }
        val onlyFingers = (0 until e.pointerCount).all { e.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        if (!gestureSequence && onlyFingers && fingers.size >= 2) {
            cancelInteraction(); gestureSequence = true; gestureFocus = null
            strokeStarted?.invoke()
        }
        if (!gestureSequence) return false
        if (onlyFingers && fingers.size >= 2) {
            val a = fingers[0]; val b = fingers[1]
            val focus = PointF((e.getX(a)+e.getX(b))/2, (e.getY(a)+e.getY(b))/2)
            val span = kotlin.math.hypot(e.getX(a)-e.getX(b), e.getY(a)-e.getY(b))
            val previous = gestureFocus
            if (e.actionMasked == MotionEvent.ACTION_MOVE && previous != null && gestureSpan > 0f && span > 0f) {
                val old = transform()
                val anchorX = old.pageX(previous.x); val anchorY = old.pageY(previous.y)
                zoom = (zoom * span / gestureSpan).coerceIn(.1f, 20f)
                val fitted = PaperTransform.fit(width.coerceAtLeast(1), height.coerceAtLeast(1), page.width, page.height, zoom, 0f, 0f)
                dx = focus.x - fitted.left - anchorX * fitted.scale
                dy = focus.y - fitted.top - anchorY * fitted.scale
            }
            gestureFocus = focus; gestureSpan = span
        } else gestureFocus = null
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureSequence = false; gestureFocus = null
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        invalidate(); return true
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (image == null || !isEnabled) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        if (fingerGesture(e)) return true
        if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelInteraction(); parent?.requestDisallowInterceptTouchEvent(false); invalidate(); return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) activePointer = e.getPointerId(0)
        val pointer = e.findPointerIndex(activePointer)
        if (pointer < 0) return true
        val tool = e.getToolType(pointer)
        // Finger navigation remains available even with pen-only writing enabled.
        if (stylusOnly && tool == MotionEvent.TOOL_TYPE_FINGER && mode != Mode.MOVE) {
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX=e.getX(pointer);lastY=e.getY(pointer);strokeStarted?.invoke() }
                MotionEvent.ACTION_MOVE -> {
                    dx+=e.getX(pointer)-lastX;dy+=e.getY(pointer)-lastY
                    lastX=e.getX(pointer);lastY=e.getY(pointer);invalidate()
                }
                MotionEvent.ACTION_UP -> { activePointer = -1; parent?.requestDisallowInterceptTouchEvent(false) }
            }
            return true
        }
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> return true
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == activePointer) MotionEvent.ACTION_UP else return true
            else -> e.actionMasked
        }
        val viewX = e.getX(pointer); val viewY = e.getY(pointer)
        val t = transform()
        if (action == MotionEvent.ACTION_DOWN && mode != Mode.MOVE && (t.pageX(viewX) !in 0f..page.width.toFloat() || t.pageY(viewY) !in 0f..page.height.toFloat())) {
            activePointer = -1; return true
        }
        val x = t.pageX(viewX).coerceIn(0f, page.width.toFloat()); val y = t.pageY(viewY).coerceIn(0f, page.height.toFloat())
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                strokeStarted?.invoke()
                lastX = viewX; lastY = viewY; startX = x; startY = y
                if ((mode == Mode.WRITE || mode == Mode.ERASE) && (!page.activeLayer.visible || page.activeLayer.locked)) {
                    toolFeedback?.invoke("当前图层已锁定或隐藏，请在图层面板调整。");activePointer=-1;return true
                }
                if (mode == Mode.WRITE && drawingTool=="eyedropper") {
                    val sample=exportBitmap(false);val px=(x/page.width*sample.width).toInt().coerceIn(0,sample.width-1);val py=(y/page.height*sample.height).toInt().coerceIn(0,sample.height-1)
                    penColor=sample.getPixel(px,py);sample.recycle();drawingTool="freehand";activePointer=-1
                    toolFeedback?.invoke("已取色，继续绘画。");return true
                }
                if (mode == Mode.WRITE || (mode==Mode.ERASE && pixelEraser)) current = mutableListOf(InkPoint(x, y, e.getPressure(pointer), e.eventTime))
                if (mode == Mode.ERASE && !pixelEraser) { eraseBefore = page.strokes; eraserPosition = PointF(x,y); erase(x, y) }
                if (mode == Mode.SELECT) { selectionBefore = region?.let { RectF(it) }; selecting = true; region = RectF(x, y, x, y) }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.WRITE -> if (current.isNotEmpty()) {
                    for (i in 0 until e.historySize) current.add(InkPoint(
                        t.pageX(e.getHistoricalX(pointer, i)).coerceIn(0f, page.width.toFloat()),
                        t.pageY(e.getHistoricalY(pointer, i)).coerceIn(0f, page.height.toFloat()),
                        e.getHistoricalPressure(pointer, i), e.getHistoricalEventTime(i)))
                    current.add(InkPoint(x, y, e.getPressure(pointer), e.eventTime))
                }
                Mode.MOVE -> { dx += viewX - lastX; dy += viewY - lastY }
                Mode.SELECT -> if (selecting) region = RectF(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                Mode.ERASE -> { eraserPosition = PointF(x,y);if(pixelEraser) current.add(InkPoint(x,y,1f,e.eventTime)) else erase(x, y) }
            }
            MotionEvent.ACTION_UP -> {
                if ((mode == Mode.WRITE || (mode==Mode.ERASE && pixelEraser)) && current.isNotEmpty()) {
                    current.add(InkPoint(x, y, e.getPressure(pointer), e.eventTime)); val stroke = currentStroke(); current.clear(); commit(page.strokes + stroke)
                }
                if (mode == Mode.ERASE && !pixelEraser) { eraserPosition = PointF(x,y); erase(x,y) }
                eraseBefore?.let { before ->
                    val after = page.strokes; page = page.copy(strokes = before)
                    if (after != before) { commit(after); toolFeedback?.invoke("橡皮：已擦除 ${before.size - after.size} 条笔迹，可点撤销恢复。") }
                    else toolFeedback?.invoke("橡皮已启用：蓝色圆圈是擦除范围，拖过笔迹即可擦除。")
                }; eraseBefore = null
                if (mode == Mode.SELECT && !hasSelection) { region = null; toolFeedback?.invoke("已取消框选，当前范围为整页。") }
                selecting = false; activePointer = -1; performClick()
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) gestureSequence = true
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        lastX = viewX; lastY = viewY; invalidate(); return true
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (mode != Mode.ERASE || image == null) return super.onHoverEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            val t = transform(); eraserPosition = PointF(t.pageX(event.x), t.pageY(event.y)); invalidate()
            return true
        }
        return super.onHoverEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    /** Immutable revision and derived image; originals/vectors are saved independently. */
    fun snapshot(): Triple<Bitmap, PaperPage, RectF> {
        check(image != null) { "请先导入 PDF" }
        val bounds = region?.takeIf { it.width() > 4 && it.height() > 4 }?.let { RectF(it) }
            ?: RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
        val scale = minOf(2f, 1500f / maxOf(bounds.width(), bounds.height()))
        val bitmap = Bitmap.createBitmap((bounds.width() * scale).toInt().coerceAtLeast(1), (bounds.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap); c.drawColor(Color.WHITE); c.scale(scale, scale); c.translate(-bounds.left, -bounds.top)
        c.drawBitmap(image!!, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null); strokes(c, page)
        return Triple(bitmap, page, bounds)
    }
}

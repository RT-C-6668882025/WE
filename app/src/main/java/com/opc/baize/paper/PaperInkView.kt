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
    var penWidth: Float = 1.8f
    var toolFeedback: ((String) -> Unit)? = null
    var changed: ((PaperPage) -> Unit)? = null
    var strokeStarted: (() -> Unit)? = null
    var stylusOnly = false
    private var image: Bitmap? = null
    var page = PaperPage(1, 1, 0, emptyList()); private set
    private var current = mutableListOf<InkPoint>()
    private val history = java.util.ArrayDeque<List<PaperStroke>>()
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
    private val eraserRadius = 12f
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
        cancelInteraction(); gestureSequence = false; gestureFocus = null; image?.recycle(); image = bitmap; page = data; current.clear(); history.clear(); region = null; zoom = 1f; dx = 0f; dy = 0f; invalidate()
    }
    fun release() { cancelInteraction(); image?.recycle(); image = null }
    fun resetView() { zoom = 1f; dx = 0f; dy = 0f; invalidate() }
    fun zoom(factor: Float) { zoom = (zoom * factor).coerceIn(1f, 5f); invalidate() }
    fun undo() {
        val previous = if (history.isNotEmpty()) history.removeLast() else page.strokes.takeIf { it.isNotEmpty() }?.dropLast(1) ?: return
        page = page.copy(revision = page.revision + 1, strokes = previous); changed?.invoke(page); invalidate()
    }
    private fun commit(strokes: List<PaperStroke>) {
        history.addLast(page.strokes); if (history.size > 30) history.removeFirst()
        page = page.copy(revision = page.revision + 1, strokes = strokes); changed?.invoke(page); invalidate()
    }
    private fun erase(x: Float, y: Float) {
        fun near(a: InkPoint, b: InkPoint, width: Float): Boolean {
            val vx = b.x - a.x; val vy = b.y - a.y; val squared = vx * vx + vy * vy
            val t = if (squared == 0f) 0f else (((x - a.x) * vx + (y - a.y) * vy) / squared).coerceIn(0f, 1f)
            val dx = x - a.x - t * vx; val dy = y - a.y - t * vy
            return dx * dx + dy * dy <= (eraserRadius + width / 2) * (eraserRadius + width / 2)
        }
        val kept = page.strokes.filterNot { stroke -> stroke.points.firstOrNull()?.let { near(it, it, stroke.width) } == true ||
            (1 until stroke.points.size).any { near(stroke.points[it - 1], stroke.points[it], stroke.width) } }
        if (kept.size != page.strokes.size) page = page.copy(strokes = kept)
    }
    private fun transform() = PaperTransform.fit(width.coerceAtLeast(1), height.coerceAtLeast(1), page.width, page.height, zoom, dx, dy)
    private fun strokes(canvas: Canvas, data: PaperPage) {
        data.strokes.forEach { stroke -> drawStroke(canvas, stroke) }
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
        strokes(canvas, page); drawStroke(canvas, PaperStroke(current, penWidth, penColor, brushEngine))
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
                zoom = (zoom * span / gestureSpan).coerceIn(1f, 5f)
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
            if (e.actionMasked == MotionEvent.ACTION_UP) { activePointer = -1; parent?.requestDisallowInterceptTouchEvent(false) }
            return true
        }
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> return true
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == activePointer) MotionEvent.ACTION_UP else return true
            else -> e.actionMasked
        }
        val viewX = e.getX(pointer); val viewY = e.getY(pointer)
        val t = transform()
        if (action == MotionEvent.ACTION_DOWN && (t.pageX(viewX) !in 0f..page.width.toFloat() || t.pageY(viewY) !in 0f..page.height.toFloat())) {
            activePointer = -1; return true
        }
        val x = t.pageX(viewX).coerceIn(0f, page.width.toFloat()); val y = t.pageY(viewY).coerceIn(0f, page.height.toFloat())
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                strokeStarted?.invoke()
                lastX = viewX; lastY = viewY; startX = x; startY = y
                if (mode == Mode.WRITE) current = mutableListOf(InkPoint(x, y, e.getPressure(pointer), e.eventTime))
                if (mode == Mode.ERASE) { eraseBefore = page.strokes; eraserPosition = PointF(x,y); erase(x, y) }
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
                Mode.ERASE -> { eraserPosition = PointF(x,y); erase(x, y) }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.WRITE && current.isNotEmpty()) {
                    current.add(InkPoint(x, y, e.getPressure(pointer), e.eventTime)); val stroke = PaperStroke(current.toList(), penWidth, penColor, brushEngine); current.clear(); commit(page.strokes + stroke)
                }
                if (mode == Mode.ERASE) { eraserPosition = PointF(x,y); erase(x,y) }
                eraseBefore?.let { before ->
                    val after = page.strokes; page = page.copy(strokes = before)
                    if (after != before) { commit(after); toolFeedback?.invoke("橡皮：已擦除 ${before.size - after.size} 条笔迹，可点撤销恢复。") }
                    else toolFeedback?.invoke("橡皮已启用：蓝色圆圈是擦除范围，拖过笔迹即可擦除。")
                }; eraseBefore = null
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

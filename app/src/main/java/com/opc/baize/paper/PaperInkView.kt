package com.opc.baize.paper

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Independent editable vectors over a PDF bitmap; no model/network work in touch callbacks. */
class PaperInkView(context: Context) : View(context) {
    enum class Mode { WRITE, MOVE, SELECT }
    var mode = Mode.WRITE
    var changed: ((PaperPage) -> Unit)? = null
    private var image: Bitmap? = null
    var page = PaperPage(1, 1, 0, emptyList()); private set
    private var current = mutableListOf<InkPoint>()
    private var zoom = 1f; private var dx = 0f; private var dy = 0f
    private var lastX = 0f; private var lastY = 0f
    private var startX = 0f; private var startY = 0f
    private var selecting = false
    private var region: RectF? = null
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    fun load(bitmap: Bitmap, data: PaperPage) {
        image?.recycle(); image = bitmap; page = data; current.clear(); region = null; zoom = 1f; dx = 0f; dy = 0f; invalidate()
    }
    fun release() { image?.recycle(); image = null }
    fun zoom(factor: Float) { zoom = (zoom * factor).coerceIn(1f, 5f); invalidate() }
    fun undo() { if (page.strokes.isNotEmpty()) commit(page.strokes.dropLast(1)) }
    private fun commit(strokes: List<PaperStroke>) { page = page.copy(revision = page.revision + 1, strokes = strokes); changed?.invoke(page); invalidate() }
    private fun transform() = PaperTransform.fit(width.coerceAtLeast(1), height.coerceAtLeast(1), page.width, page.height, zoom, dx, dy)
    private fun strokes(canvas: Canvas, data: PaperPage) {
        data.strokes.forEach { stroke -> drawStroke(canvas, stroke) }
    }
    private fun drawStroke(canvas: Canvas, stroke: PaperStroke) {
        if (stroke.points.isEmpty()) return
        pen.color = Color.BLACK; pen.strokeWidth = stroke.width
        val path = Path(); val first = stroke.points.first(); path.moveTo(first.x, first.y)
        stroke.points.drop(1).forEach { path.lineTo(it.x, it.y) }
        if (stroke.points.size == 1) canvas.drawCircle(first.x, first.y, stroke.width / 2, Paint(pen).apply { style = Paint.Style.FILL })
        else canvas.drawPath(path, pen)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.LTGRAY)
        val t = transform(); canvas.save(); canvas.translate(t.left, t.top); canvas.scale(t.scale, t.scale)
        canvas.clipRect(0f, 0f, page.width.toFloat(), page.height.toFloat())
        canvas.drawColor(Color.WHITE)
        image?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null) }
        strokes(canvas, page); drawStroke(canvas, PaperStroke(current))
        region?.let { pen.color = Color.DKGRAY; pen.strokeWidth = 1f / t.scale; canvas.drawRect(it, pen) }
        canvas.restore()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (image == null || !isEnabled) return false
        parent.requestDisallowInterceptTouchEvent(true)
        if (e.pointerCount > 1) { current.clear(); selecting = false; invalidate(); return true }
        val t = transform()
        if (e.actionMasked == MotionEvent.ACTION_DOWN && (t.pageX(e.x) !in 0f..page.width.toFloat() || t.pageY(e.y) !in 0f..page.height.toFloat())) return false
        val x = t.pageX(e.x).coerceIn(0f, page.width.toFloat()); val y = t.pageY(e.y).coerceIn(0f, page.height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; startX = x; startY = y
                if (mode == Mode.WRITE) current = mutableListOf(InkPoint(x, y, e.pressure, e.eventTime))
                if (mode == Mode.SELECT) { selecting = true; region = RectF(x, y, x, y) }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.WRITE -> if (current.isNotEmpty()) {
                    for (i in 0 until e.historySize) current.add(InkPoint(
                        t.pageX(e.getHistoricalX(i)).coerceIn(0f, page.width.toFloat()),
                        t.pageY(e.getHistoricalY(i)).coerceIn(0f, page.height.toFloat()),
                        e.getHistoricalPressure(i), e.getHistoricalEventTime(i)))
                    current.add(InkPoint(x, y, e.pressure, e.eventTime))
                }
                Mode.MOVE -> { dx += e.x - lastX; dy += e.y - lastY }
                Mode.SELECT -> if (selecting) region = RectF(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.WRITE && current.isNotEmpty()) {
                    current.add(InkPoint(x, y, e.pressure, e.eventTime)); val stroke = PaperStroke(current.toList()); current.clear(); commit(page.strokes + stroke)
                }
                selecting = false; performClick()
            }
            MotionEvent.ACTION_CANCEL -> { current.clear(); selecting = false }
        }
        lastX = e.x; lastY = e.y; invalidate(); return true
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

package com.opc.baize

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.opc.baize.core.Gestures
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 手写墨迹画布——延迟是第一优先级，设计要点（继承网页版 v7 的全部经验）：
 *
 * 1. ACTION_DOWN 时 requestUnbufferedDispatch(event)：要求系统不做输入事件
 *    批处理/对齐 vsync，事件到就派（安卓原生的 pointerrawupdate）。
 * 2. MotionEvent 的 getHistoricalX/Y/Pressure 全部入笔：系统合并进一个
 *    MotionEvent 的中间采样点一个不丢，笔迹平滑度与采样率无损。
 * 3. 增量绘制：笔迹画进持久 offscreen Bitmap；onDraw 只 blit + 画当前笔尾，
 *    绝不全量重绘。全量 rebuild 仅四种场合：撤半笔（两指手势）、消散清场、
 *    长按清空、尺寸变化。
 * 4. 中点二次贝塞尔平滑（与网页版 drawStroke 逐段同构）+ 压力调宽
 *    （0.5~1.5×，基准 4dp）+ 临时笔尾直连最新点（中点法天然滞后半段，
 *    笔尾补直线，笔尖走到哪墨跟到哪；抬笔时笔尾定格进 Bitmap）。
 *
 * 手势（继承网页版 §2）：
 * - 单指 = 书写；第二指落下瞬间进入手势模式：撤销首指半笔、手势全程不进墨；
 * - 全部手指离开后结算：平均水平位移 ≥80dp 且 ≥1.6×垂直位移 → 翻页
 *   （左滑下一页 / 右滑上一页）；
 * - 左上角 120dp 区域长按 1.2s = 清空全书；右上角同尺寸区域长按 = 设置框。
 *
 * strokes 是唯一权威数据（导出 PNG、ML Kit Ink、撤销、清场全从它来）。
 */
class InkView(context: Context) : View(context) {

    data class Pt(val x: Float, val y: Float, val w: Float, val t: Long)

    interface Listener {
        fun onPenDown()
        fun onStrokeFinished()
        /** 手势结束后画布上仍有笔迹：重启停笔计时，别耽误之前写的字提交 */
        fun onInkRemaining()
        /** dir = +1 下一页（左滑），-1 上一页（右滑） */
        fun onSwipe(dir: Int)
        fun onLongPressTopLeft()
        fun onLongPressTopRight()
    }

    var listener: Listener? = null

    /**
     * 输入使能。HWEditView 驱动直写接管书写时置 false：InkView 退位——不吃触摸、不自绘热路径
     * （它在 z 序下方本就收不到触摸，这里再兜一层，保证即便收到也不画墨）。仍保留 exportInkDataUrl
     * / stats 等纯函数供直写层复用（那些只吃传入的 snapshot，不依赖 enabled）。
     */
    private var inputEnabled = true
    fun setInputEnabled(enabled: Boolean) { inputEnabled = enabled }

    private val dp = resources.displayMetrics.density
    private val baseWidth = 4f * dp                 // 基准笔宽 ~4px（dp 口径）
    private val minDist2 = dp * dp                  // 过密点过滤（~1dp）
    // 手势阈值统一取自 Gestures 内核（与 HwWritingContainer 同一份）
    private val cornerSize = Gestures.CORNER_DP * dp
    private val tapMoveMax = Gestures.TAP_MOVE_MAX_DP * dp
    private val swipeMinX = Gestures.SWIPE_MIN_X_DP * dp

    /** 权威笔迹数据：每笔 = 点列表 */
    val strokes = mutableListOf<MutableList<Pt>>()
    private var curStroke: MutableList<Pt>? = null
    private var drawing = false

    private var bitmap: Bitmap? = null
    private var bmpCanvas: Canvas? = null

    // —— 一级：AndroidX 笔迹预测（Kalman 外推） ——
    // 权威数据只存真实点；predict() 外推的点只作"视觉提前"的临时预测笔尾，
    // 画在 onDraw 里、不写进持久 Bitmap、绝不进 strokes/导出/识别/持久化。
    // View 构造后才能 newInstance（要 View 引用），故懒创建；创建失败静默降级为纯自绘。
    private var predictor: MotionEventPredictor? = null
    private var predictorInited = false
    /**
     * 临时预测笔尾：从最后一个真实点起，串预测点，同色画线，随时被覆盖/清除。
     * 不变量：预测点只画在 onDraw 里，绝不进 strokes / 导出 / 识别 / 持久化——
     * 权威数据（strokes）与预测视觉从数据结构上就是分开的两个列表。
     */
    private val predictedTail = ArrayList<Pt>()

    private fun ensurePredictor() {
        if (predictorInited) return
        predictorInited = true
        predictor = try {
            MotionEventPredictor.newInstance(this)
        } catch (t: Throwable) {
            null   // 任何异常→无预测，纯走原自绘临时笔尾，行为与改动前一致
        }
    }

    /** 收到真实事件即喂预测器（DOWN/MOVE 都喂，历史点由库内部批处理消化） */
    private fun recordForPrediction(e: MotionEvent) {
        val p = predictor ?: return
        try { p.record(e) } catch (t: Throwable) { /* 预测器坏了不影响书写 */ }
    }

    /** 取一次预测：把外推出的点收进 predictedTail（临时、非权威）；失败则清空预测尾 */
    private fun refreshPredictedTail() {
        predictedTail.clear()
        val p = predictor ?: return
        val cur = curStroke ?: return
        val predicted: MotionEvent = try { p.predict() ?: return } catch (t: Throwable) { return }
        try {
            val last = cur[cur.size - 1]
            // 预测事件含历史+当前若干外推点；全取，作预测笔尾的后半段
            val h = predicted.historySize
            for (k in 0 until h) {
                predictedTail.add(Pt(predicted.getHistoricalX(k), predicted.getHistoricalY(k), last.w, 0L))
            }
            predictedTail.add(Pt(predicted.x, predicted.y, last.w, 0L))
        } finally {
            predicted.recycle()   // predict() 返回的 MotionEvent 由调用方回收
        }
    }

    /** 清预测笔尾。invalidate 交调用方按需触发，避免重复重绘。 */
    private fun clearPredictedTail() {
        predictedTail.clear()
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        style = Paint.Style.FILL
    }

    // —— 手势状态 ——
    private var gestureActive = false
    private val fingerStart = HashMap<Int, FloatArray>()
    private val fingerLast = HashMap<Int, FloatArray>()

    // —— 长按状态 ——
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f

    // —— 消散状态 ——
    private var fading = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            bmpCanvas = Canvas(it)
        }
        rebuildBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // 临时笔尾：mid(倒数第二点, 最新点) → 最新点 的直线，消掉中点法半段滞后
        val cur = curStroke ?: return
        if (cur.size >= 3) drawTailSeg(canvas, cur)
        // 预测笔尾：从最新真实点起，串预测点画同色直线段（视觉提前，非权威、不入位图）。
        // 与上面的临时笔尾接续——临时笔尾补到真实笔尖，预测尾再往前探一截。
        drawPredictedTail(canvas, cur)
    }

    /** 预测笔尾：真实笔尖 → 各预测点 顺次连线（同色，纯视觉，落新真点即被覆盖清除） */
    private fun drawPredictedTail(c: Canvas, cur: List<Pt>) {
        if (predictedTail.isEmpty() || cur.isEmpty()) return
        val last = cur[cur.size - 1]
        strokePaint.strokeWidth = last.w
        var px = last.x
        var py = last.y
        for (pt in predictedTail) {
            c.drawLine(px, py, pt.x, pt.y, strokePaint)
            px = pt.x
            py = pt.y
        }
    }

    // ---------- 绘制原语（与网页版 drawStroke/drawSegmentsRange 同构） ----------

    private fun drawTailSeg(c: Canvas, pts: List<Pt>) {
        val n = pts.size
        val last = pts[n - 1]
        val prev = pts[n - 2]
        strokePaint.strokeWidth = last.w
        c.drawLine((last.x + prev.x) / 2f, (last.y + prev.y) / 2f, last.x, last.y, strokePaint)
    }

    /** 整笔权威重绘（rebuild / 导出共用口径）：中点二次贝塞尔，不含笔尾 */
    private fun drawWholeStroke(c: Canvas, pts: List<Pt>, widthOf: (Float) -> Float = { it }) {
        if (pts.isEmpty()) return
        if (pts.size < 3) {
            c.drawCircle(pts[0].x, pts[0].y, widthOf(pts[0].w) / 2f, dotPaint)
            if (pts.size == 2) {
                strokePaint.strokeWidth = widthOf(pts[1].w)
                c.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, strokePaint)
            }
            return
        }
        val path = android.graphics.Path()
        var i = 1
        while (i <= pts.size - 2) {
            val w = pts[i].w
            strokePaint.strokeWidth = widthOf(w)
            path.reset()
            path.moveTo((pts[i - 1].x + pts[i].x) / 2f, (pts[i - 1].y + pts[i].y) / 2f)
            while (i <= pts.size - 2 && pts[i].w == w) {
                path.quadTo(pts[i].x, pts[i].y,
                    (pts[i].x + pts[i + 1].x) / 2f, (pts[i].y + pts[i + 1].y) / 2f)
                i++
            }
            c.drawPath(path, strokePaint)
        }
    }

    /** 增量补画：把 curStroke 里第 from 个点之后新增的线段画进 Bitmap（绝不全量重绘） */
    private fun drawSegmentsInto(c: Canvas, pts: List<Pt>, from: Int) {
        val n = pts.size
        if (n < 3) { drawWholeStroke(c, pts); return }
        var i = max(1, from - 1)
        while (i <= n - 2) {
            val w = pts[i].w
            strokePaint.strokeWidth = w
            val path = android.graphics.Path()
            path.moveTo((pts[i - 1].x + pts[i].x) / 2f, (pts[i - 1].y + pts[i].y) / 2f)
            while (i <= n - 2 && pts[i].w == w) {
                path.quadTo(pts[i].x, pts[i].y,
                    (pts[i].x + pts[i + 1].x) / 2f, (pts[i].y + pts[i + 1].y) / 2f)
                i++
            }
            c.drawPath(path, strokePaint)
        }
    }

    private fun rebuildBitmap() {
        val c = bmpCanvas ?: return
        bitmap?.eraseColor(0x00000000)
        for (s in strokes) drawWholeStroke(c, s)
        invalidate()
    }

    // ---------- 触摸处理 ----------

    private fun widthFromPressure(pressure: Float): Float {
        val p = if (pressure > 0f) pressure else 0.5f
        val factor = max(0.5f, min(1.5f, p * 2f))
        return baseWidth * factor
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!inputEnabled) return false   // 直写层接管：InkView 不再处理触摸
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 无缓冲事件直派：系统不再把触摸事件攒到 vsync 批发，
                // 这是原生版消灭书写延迟的第一根支柱
                requestUnbufferedDispatch(e)
                if (fading) return true       // 消散动画那几百毫秒不收新墨（与网页版一致可写，
                                              // 但位图正在退场，避免半透明期间画新墨造成闪烁）
                ensurePredictor()
                recordForPrediction(e)
                val id = e.getPointerId(0)
                fingerStart[id] = floatArrayOf(e.x, e.y)
                fingerLast[id] = floatArrayOf(e.x, e.y)
                startStroke(e.x, e.y, e.getPressure(0), e.eventTime)
                maybeStartLongPress(e.x, e.y)
                listener?.onPenDown()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val id = e.getPointerId(idx)
                fingerStart[id] = floatArrayOf(e.getX(idx), e.getY(idx))
                fingerLast[id] = floatArrayOf(e.getX(idx), e.getY(idx))
                if (!gestureActive) enterGestureMode()
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureActive) {
                    for (i in 0 until e.pointerCount) {
                        fingerLast[e.getPointerId(i)]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
                    }
                } else if (drawing) {
                    recordForPrediction(e)   // 先喂预测器（含此事件的历史点）
                    // 合并采样点全部入笔：历史点在前、当前点在后，一个不丢
                    val h = e.historySize
                    for (k in 0 until h) {
                        addPoint(e.getHistoricalX(0, k), e.getHistoricalY(0, k),
                            e.getHistoricalPressure(0, k), e.getHistoricalEventTime(k))
                    }
                    addPoint(e.x, e.y, e.getPressure(0), e.eventTime)
                    // 真实点已入笔，取一次预测覆盖预测笔尾（新真实点自动盖掉旧预测）
                    refreshPredictedTail()
                    invalidate()
                    fingerLast[e.getPointerId(0)]?.let { it[0] = e.x; it[1] = e.y }
                    if (abs(e.x - downX) > tapMoveMax || abs(e.y - downY) > tapMoveMax) {
                        cancelCornerLongPress()   // 一动就不算长按
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 手势期间中途抬指：fingerLast 里留着末位置，最终统一结算
            }
            MotionEvent.ACTION_UP -> {
                cancelCornerLongPress()
                if (gestureActive) finishGesture() else finishStroke()
                fingerStart.clear()
                fingerLast.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelCornerLongPress()
                if (gestureActive) {
                    gestureActive = false
                } else if (drawing) {
                    finishStroke()
                }
                fingerStart.clear()
                fingerLast.clear()
            }
        }
        return true
    }

    private fun startStroke(x: Float, y: Float, pressure: Float, t: Long) {
        drawing = true
        downX = x; downY = y
        val s = mutableListOf(Pt(x, y, widthFromPressure(pressure), t))
        curStroke = s
        strokes.add(s)
        // 起笔墨点即时上屏（不等下一次 move）
        bmpCanvas?.let { drawWholeStroke(it, s) }
        invalidate()
    }

    private fun addPoint(x: Float, y: Float, pressure: Float, t: Long) {
        val cur = curStroke ?: return
        val last = cur[cur.size - 1]
        val dx = x - last.x
        val dy = y - last.y
        if (dx * dx + dy * dy < minDist2) return
        cur.add(Pt(x, y, widthFromPressure(pressure), t))
        val c = bmpCanvas ?: return
        // 增量：只补画新线段（事件回调内同步落墨，等 rAF 反而白吃一帧）
        drawSegmentsInto(c, cur, cur.size - 1)
        invalidate()
    }

    private fun finishStroke() {
        if (!drawing) return
        drawing = false
        val cur = curStroke
        curStroke = null
        clearPredictedTail()   // 抬笔：预测笔尾即刻消失，只留真实点定格
        // 抬笔：最终笔尾定格进 Bitmap（末端画满到真实笔尖，无缺口）
        if (cur != null && cur.size >= 3) bmpCanvas?.let { drawTailSeg(it, cur) }
        invalidate()
        listener?.onStrokeFinished()
    }

    // ---------- 两指手势 ----------

    private fun enterGestureMode() {
        gestureActive = true
        cancelCornerLongPress()
        clearPredictedTail()   // 进手势：预测笔尾连同半笔一起撤走
        // 首指已画的半笔：从 strokes 删掉并重绘，一点墨都不留
        val cur = curStroke
        if (cur != null) {
            strokes.remove(cur)
            curStroke = null
            rebuildBitmap()
        }
        drawing = false
    }

    private fun finishGesture() {
        gestureActive = false
        val deltas = fingerStart.mapNotNull { (id, start) ->
            fingerLast[id]?.let { end -> (end[0] - start[0]) to (end[1] - start[1]) }
        }
        val dir = Gestures.swipeDir(deltas, swipeMinX)
        if (dir != 0) listener?.onSwipe(dir)
        if (strokes.isNotEmpty()) listener?.onInkRemaining()
    }

    // ---------- 角落长按 ----------

    private fun maybeStartLongPress(x: Float, y: Float) {
        val corner = Gestures.cornerAt(x, y, width.toFloat(), cornerSize)
        if (corner == Gestures.Corner.NONE) return
        val r = Runnable {
            longPressRunnable = null
            // 长按成立：这一笔是按压不是书写，撤掉重绘
            val cur = curStroke
            if (cur != null) { strokes.remove(cur); curStroke = null }
            drawing = false
            clearPredictedTail()
            rebuildBitmap()
            if (corner == Gestures.Corner.TOP_LEFT) listener?.onLongPressTopLeft()
            else listener?.onLongPressTopRight()
        }
        longPressRunnable = r
        handler.postDelayed(r, Gestures.LONG_PRESS_MS)
    }

    private fun cancelCornerLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    // ---------- 提交侧接口 ----------

    /** 当前笔迹快照（引用列表；消散清场按身份把它们从 strokes 移除） */
    fun snapshot(): List<MutableList<Pt>> = strokes.toList()

    fun isWriting(): Boolean = drawing || gestureActive

    /**
     * 墨迹四级离散淡出（0.7→0.4→0.15→0，各 180ms，e-ink 友好的跳变而非渐变），
     * 然后只清掉已导出的笔画——消散期间若又开始写，新笔迹原样保留。
     */
    fun fadeOutAndClear(exported: List<MutableList<Pt>>) {
        if (exported.isEmpty()) return
        fading = true
        val steps = floatArrayOf(0.7f, 0.4f, 0.15f, 0f)
        var i = 0
        val tick = object : Runnable {
            override fun run() {
                if (i < steps.size) {
                    alpha = steps[i]
                    i++
                    handler.postDelayed(this, 180)
                } else {
                    strokes.removeAll { s -> exported.any { it === s } }
                    rebuildBitmap()
                    alpha = 1f
                    fading = false
                }
            }
        }
        handler.post(tick)
    }

    /** 长按清空：掐掉一切进行中状态，全场清墨 */
    fun clearAllInk() {
        cancelCornerLongPress()
        drawing = false
        gestureActive = false
        curStroke = null
        clearPredictedTail()
        strokes.clear()
        fingerStart.clear()
        fingerLast.clear()
        rebuildBitmap()
        alpha = 1f
        fading = false
    }

    // ---------- 导出（识别降级通道用） ----------

    /** 快照包围盒扫描：[minX, minY, maxX, maxY, 总点数]；无点返回 null。导出与统计共用。 */
    private fun bounds(snapshot: List<List<Pt>>): FloatArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var total = 0
        for (s in snapshot) for (p in s) {
            total++
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return if (total == 0) null else floatArrayOf(minX, minY, maxX, maxY, total.toFloat())
    }

    /**
     * 白底 PNG dataURL：包围盒外扩 40dp、笔画加粗 max(6, 2×w)。
     * 口径与网页版 exportInkPNG 一致（细线是识别的天敌）。
     * 返回 null 表示无笔迹。
     */
    fun exportInkDataUrl(snapshot: List<List<Pt>>): String? {
        val b = bounds(snapshot) ?: return null
        val pad = 40f * dp
        val x0 = max(0f, b[0] - pad)
        val y0 = max(0f, b[1] - pad)
        val x1 = min(width.toFloat(), b[2] + pad)
        val y1 = min(height.toFloat(), b[3] + pad)
        val w = max(1, (x1 - x0).toInt())
        val h = max(1, (y1 - y0).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        c.translate(-x0, -y0)
        for (s in snapshot) drawWholeStroke(c, s) { wid -> max(6f, wid * 2f) }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/png;base64,$b64"
    }

    /** 快照统计：总点数 + 包围盒（误触闸输入） */
    fun stats(snapshot: List<List<Pt>>): FloatArray {
        val b = bounds(snapshot) ?: return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf(b[4], b[2] - b[0], b[3] - b[1])
    }

    /** 误触闸的最小包围盒阈值（48dp 换算成 px） */
    fun minBoxPx(): Float = 48f * dp
}

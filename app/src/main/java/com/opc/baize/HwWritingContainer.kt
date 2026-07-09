package com.opc.baize

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout
import com.opc.baize.core.Gestures
import kotlin.math.abs

/**
 * HWEditView 驱动直写模式下的**手势仲裁容器**。
 *
 * 难点：HWEditView 自己 onTouchEvent 消费触摸（驱动直写，我们不参与画墨）。可白泽还需要
 * 两指翻页、角落长按（清空/设置）、以及停笔计时（观察落笔/抬笔）。方案（见任务 §6）：
 * 把 HWEditView 作为唯一子 View 放进本容器，容器在 onInterceptTouchEvent 里：
 *   - 单指 → **不拦截**，事件透给 HWEditView 直写出墨（跟手关键）；
 *   - 第二指落下（两指）→ **拦截**，后续事件走容器自己的 onTouchEvent 结算翻页手势；
 *   - 角落长按：容器在 DOWN 起长按计时，落在左上/右上角且未移动即触发（拦截以吞掉这一笔）。
 *
 * onInterceptTouchEvent 能看到**全部**触摸事件（即便最终不拦截），因此顺便在这里驱动
 * 停笔计时的落笔/抬笔回调（onPenDown/onPenUp）——不需要 HWEditView 回调触摸。
 *
 * 两指进入手势时：HWEditView 已经把首指的半笔画进了自己的轨迹；容器负责在手势结算后
 * 调用方 clear() 掉那半笔（翻页/取消都不该留半笔）。这与 InkView 老路"进手势撤半笔"一致。
 */
class HwWritingContainer(context: Context) : FrameLayout(context) {

    interface Listener {
        /** 单指落下（开始一段可能的书写）：取消停笔计时、切快刷。 */
        fun onPenDown()
        /**
         * 笔离开书写状态：单指抬起、转入两指手势、或角落长按成立。
         * 每次 onPenDown 之后必有且仅有一次 onPenUp——调用方以此维护 penDown 标志，
         * 绝不能让它卡在 true（否则轮询停笔检测挂起、面板快刷态遗留）。
         */
        fun onPenUp()
        /** dir=+1 下一页（左滑），-1 上一页（右滑）。手势已判定为翻页。 */
        fun onSwipe(dir: Int)
        /** 两指手势结束但不是翻页（或翻页后）——请把手写层里残留的半笔清掉。 */
        fun onGestureEndClearInk()
        fun onLongPressTopLeft()
        fun onLongPressTopRight()
    }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density
    // 手势阈值统一取自 Gestures 内核（与 InkView 老路同一份）
    private val cornerSize = Gestures.CORNER_DP * dp
    private val tapMoveMax = Gestures.TAP_MOVE_MAX_DP * dp
    private val swipeMinX = Gestures.SWIPE_MIN_X_DP * dp

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false

    private var gestureActive = false
    private var penIsDown = false
    private var downX = 0f
    private var downY = 0f

    private val fingerStart = HashMap<Int, FloatArray>()
    private val fingerLast = HashMap<Int, FloatArray>()

    /**
     * 只在这里观察全部事件、并决定是否拦截：
     * - DOWN：记落笔、起角落长按计时、发 onPenDown（透给 HWEditView）。
     * - POINTER_DOWN（第二指）：进手势模式并**拦截**，交给本容器 onTouchEvent 结算。
     * - 角落长按成立：拦截（吞掉这一笔，不让它变成墨）。
     * 其余单指 MOVE/UP：不拦截，HWEditView 直写。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                penIsDown = true
                gestureActive = false
                longPressFired = false
                fingerStart.clear(); fingerLast.clear()
                val id = e.getPointerId(0)
                fingerStart[id] = floatArrayOf(e.x, e.y)
                fingerLast[id] = floatArrayOf(e.x, e.y)
                maybeStartCornerLongPress(e.x, e.y)
                listener?.onPenDown()
                return false // 单指透给 HWEditView 直写
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二指落下 → 进手势，拦截后续
                cancelCornerLongPress()
                enterGesture(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 一动就不算长按
                if (abs(e.x - downX) > tapMoveMax || abs(e.y - downY) > tapMoveMax) {
                    cancelCornerLongPress()
                }
                if (longPressFired) return true
                // 单指移动透给 HWEditView（不拦截）
                return false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 手势中途抬指，仍由 onInterceptTouchEvent 观察不到后续（已拦截时走 onTouchEvent）
                return gestureActive
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelCornerLongPress()
                if (penIsDown && !gestureActive && !longPressFired) {
                    penIsDown = false
                    listener?.onPenUp() // 单指一笔写完 → 启动停笔计时
                }
                penIsDown = false
                return false
            }
        }
        return false
    }

    /** 一旦拦截（两指手势 / 长按成立），后续事件走这里结算。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                enterGesture(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureActive) {
                    for (i in 0 until e.pointerCount) {
                        fingerLast[e.getPointerId(i)]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gestureActive) {
                    val idx = e.actionIndex
                    fingerLast[e.getPointerId(idx)]?.let { it[0] = e.getX(idx); it[1] = e.getY(idx) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureActive) finishGesture()
                gestureActive = false
                penIsDown = false
                fingerStart.clear(); fingerLast.clear()
            }
        }
        return true
    }

    private fun enterGesture(e: MotionEvent) {
        if (gestureActive) return
        gestureActive = true
        penIsDown = false // 首指半笔转为手势，不再算书写
        // 笔离开书写状态（关键：调用方靠 onPenUp 复位 penDown 标志，
        // 否则两指翻页后轮询停笔检测挂起、面板被反复切回快刷态）
        listener?.onPenUp()
        // 记录当前所有手指起点
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            fingerStart[id] = floatArrayOf(e.getX(i), e.getY(i))
            fingerLast[id] = floatArrayOf(e.getX(i), e.getY(i))
        }
    }

    private fun finishGesture() {
        val deltas = fingerStart.mapNotNull { (id, start) ->
            fingerLast[id]?.let { end -> (end[0] - start[0]) to (end[1] - start[1]) }
        }
        val dir = Gestures.swipeDir(deltas, swipeMinX)
        if (dir != 0) listener?.onSwipe(dir)
        // 无论翻不翻页，首指落在 HWEditView 上的半笔都要清掉
        listener?.onGestureEndClearInk()
    }

    // ---------- 角落长按 ----------

    private fun maybeStartCornerLongPress(x: Float, y: Float) {
        val corner = Gestures.cornerAt(x, y, width.toFloat(), cornerSize)
        if (corner == Gestures.Corner.NONE) return
        val r = Runnable {
            longPressRunnable = null
            longPressFired = true
            penIsDown = false
            // 这一笔按长按结算，笔离开书写状态（同 enterGesture，防 penDown 卡死）
            listener?.onPenUp()
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
}

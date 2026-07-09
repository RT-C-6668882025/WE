package com.opc.baize

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import android.view.View

/**
 * 终极低延迟：华为开箱手写控件 android.eink.view.edit.HWEditView 的**纯反射封装**。
 *
 * 为什么必须是它（见 reverse/BAIZE_LATENCY_FINDINGS.md 末两节）：面板快刷只加速"应用画出的
 * 墨"的刷新，墨迹仍要过"应用 onDraw → SurfaceFlinger 合成一帧"才上屏——这一帧就是老板实测
 * 的半秒 + 顿挫。原装"写一点出一点"= 驱动直写帧缓冲（AHW super-speed-up），笔迹**完全不经过
 * 应用绘制**。HWEditView 继承链 HWEditView→HWEraserView→HWSurfaceView→SurfaceView，内部自带
 * AHW 直写。hinote 本体就用它。
 *
 * 集成定位：HWEditView 只作**纯输入书写层**，盖在书页内容区之上（match_parent）。驱动替我们
 * 出墨；停笔时 getTrackList() 取笔迹 → 白泽现有误触闸/ML Kit/DeepSeek 管线 → clear() 备下轮。
 * 回复展示仍走龙藏文本逐字渲染（不经 HWEditView）。
 *
 * 健壮性铁律：这些类在 BOOTCLASSPATH 上、编译期不可见 → 全程反射。任一步失败（类找不到 /
 * 构造抛异常 / 方法签名不符 / SecurityException）→ 记 Log、isAvailable=false，调用方无缝
 * 回退现有 InkView 老路。App 绝不能因 HWEditView 不可用而崩溃。
 *
 * 生效判定全靠 Log（TAG=BaizeHW）：真机 `adb logcat -s BaizeHW` 看
 *   - "dump ..." 系列：HWEditView / 父类的 public 方法列表（核对签名）
 *   - "create ok: HWEditView instance"：控件是否真的实例化
 *   - "readStrokes: N tracks, M points"：驱动是否真的收到笔迹（非零 = 出墨且轨迹回得来）
 *   - "clear invoked"
 */
class HwHandwritingLayer private constructor(
    private val view: View,
    private val cls: Class<*>,
) {
    companion object {
        const val TAG = "BaizeHW"
        private const val CLS_EDIT = "android.eink.view.edit.HWEditView"

        // HWEraserView.STATE_HADNWRITE = 1（华为拼写就是 HADNWRITE）。手写态。
        // STATE_NONE = 0：退出手写态（内部 disableAutoHandWrite），退到后台时用，别把驱动直写留着。
        private const val STATE_HANDWRITE = 1
        private const val STATE_NONE = 0
        private const val PEN_COLOR_BLACK = -16777216 // 0xFF000000

        /** 桌面 JVM 单测覆盖这条：HWEditView 不存在时安全返回 false、不抛。 */
        fun isFrameworkPresent(): Boolean = try {
            Class.forName(CLS_EDIT)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "HWEditView not present (${t.javaClass.simpleName}) -> fallback to InkView")
            false
        }

        /**
         * 尝试构造 HWEditView 并封装。全程反射、全程容错。
         * @return HwHandwritingLayer 实例（isAvailable=true）；或 null（不可用，调用方走 InkView）。
         */
        fun create(context: Context): HwHandwritingLayer? {
            return try {
                val cls = Class.forName(CLS_EDIT)
                Log.i(TAG, "reflect ok: Class.forName($CLS_EDIT)")
                dumpMethods(cls)

                // HWEditView(Context)
                val ctor = cls.getConstructor(Context::class.java)
                val instance = ctor.newInstance(context)
                if (instance !is View) {
                    Log.i(TAG, "create failed: instance is not a View (${instance?.javaClass?.name}) -> fallback")
                    return null
                }
                Log.i(TAG, "create ok: HWEditView instance constructed")

                val layer = HwHandwritingLayer(instance, cls)
                layer.enterHandwriteState()
                layer.setPenColorBlack()
                layer
            } catch (t: Throwable) {
                Log.i(TAG, "create failed (${t.javaClass.simpleName}: ${t.message}) -> fallback to InkView")
                null
            }
        }

        private fun dumpMethods(cls: Class<*>) {
            try {
                Log.i(TAG, "dump HWEditView public methods (incl. inherited):")
                for (m in cls.methods) {
                    val n = m.name
                    if (n == "setState" || n == "getTrackList" || n == "setTrackList" ||
                        n == "clear" || n == "undo" || n == "canUndo" ||
                        n == "getState" || n == "setHWTrackCreator" ||
                        n.contains("SuperSpeedUp") || n.contains("AutoHandWrite")
                    ) {
                        Log.i(TAG, "  method: $n(${m.parameterTypes.joinToString { it.simpleName }})")
                    }
                }
            } catch (t: Throwable) { /* dump 失败无所谓 */ }
        }
    }

    /** 供 MainActivity addView 进书写容器的实际 View（就是 HWEditView 本体）。 */
    fun asView(): View = view

    /**
     * 进手写态：setState(STATE_HADNWRITE=1)。华为内部据此 enableAutoHandWrite（见逆向源码
     * HWEraserView.setState），开启驱动直写 + super-speed-up。super-speed-up 默认即开，
     * **不调 setDisableSuperSpeedUp(true)**（那会关掉直写加速）。
     */
    fun enterHandwriteState() = setState(STATE_HANDWRITE, "STATE_HADNWRITE=1")

    /** 退出手写态（内部 disableAutoHandWrite）。退到后台/销毁时调，别把驱动直写留着。 */
    fun leaveHandwriteState() = setState(STATE_NONE, "STATE_NONE=0")

    private fun setState(state: Int, label: String) {
        try {
            val m = cls.getMethod("setState", Int::class.javaPrimitiveType)
            m.invoke(view, state)
            Log.i(TAG, "setState($label) invoked ok")
        } catch (t: Throwable) {
            Log.i(TAG, "setState($label) skipped (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    /**
     * 黑墨默认笔即可（逆向源码 HWEditView 构造里已 new HandPen(1,...) 黑色钢笔）。这里不强配
     * IHWTrackCreator（拿不准就不配，默认笔黑墨最稳）。仅留一处轻量尝试：若有直白的
     * setPenColor(int) 就设黑；没有就跳过（默认已是黑）。
     */
    fun setPenColorBlack() {
        try {
            val m = cls.getMethod("setPenColor", Int::class.javaPrimitiveType)
            m.invoke(view, PEN_COLOR_BLACK)
            Log.i(TAG, "setPenColor(BLACK) invoked ok")
        } catch (t: Throwable) {
            // 没有该方法很正常（默认笔即黑）——不记为错误
            Log.i(TAG, "setPenColor not present, default black pen kept")
        }
    }

    /**
     * 读取当前全部笔迹并转成白泽 Stroke（List<List<InkView.Pt>>）。停笔提交时调用。
     * 反射链：getTrackList() → List<IHWTrack>；每个 IHWTrack.getPoints() → List<HWPoint>；
     * HWPoint extends PointF（x,y） + public long eventtime + public float pressure。
     * 读到 RawTrack 后交给纯内核 HwTrackConverter.convert（可测），再映射成 InkView.Pt。
     *
     * 任一步失败 → 记 Log、返回空列表（调用方视作无笔迹，安全）。
     */
    fun readStrokes(fallbackWidthPx: Float): List<MutableList<InkView.Pt>> {
        val raw = readRawTracks() ?: return emptyList()
        val converted = HwTrackConverter.convert(raw, fallbackWidthPx)
        Log.i(TAG, "readStrokes: ${HwTrackConverter.strokeCount(raw)} tracks, " +
            "${HwTrackConverter.totalPoints(raw)} points")
        return converted.map { stroke ->
            stroke.mapTo(ArrayList(stroke.size)) { p ->
                InkView.Pt(p.x, p.y, p.w, p.t)
            }
        }
    }

    /**
     * 笔迹规模指纹：Pair(track 数, 总点数)。供直写模式的**轮询停笔检测**用（不依赖触摸事件，
     * 因为 HWEditView 自吃 onTouchEvent，容器观察不到单指落笔/抬笔）。轮询间隔与停笔阈值
     * 由 MainActivity 定（当前 150ms 一读、无增长达用户设定档位判停）。反射有开销，但这个
     * 频率可接受。反射失败 → (0,0)（视作没写，安全）。
     */
    fun inkFingerprint(): Pair<Int, Int> {
        val raw = readRawTracks() ?: return 0 to 0
        return HwTrackConverter.strokeCount(raw) to HwTrackConverter.totalPoints(raw)
    }

    /**
     * 反射把 getTrackList() 读成 List<RawTrack>。返回 null 表示反射失败（调用方按无笔迹处理）；
     * 返回空列表表示确实没写。
     */
    private fun readRawTracks(): List<HwTrackConverter.RawTrack>? {
        return try {
            val getTrackList = cls.getMethod("getTrackList")
            val trackList = getTrackList.invoke(view) as? List<*> ?: return emptyList()
            val out = ArrayList<HwTrackConverter.RawTrack>(trackList.size)
            for (track in trackList) {
                if (track == null) continue
                out.add(readOneTrack(track))
            }
            out
        } catch (t: Throwable) {
            Log.i(TAG, "readStrokes failed (${t.javaClass.simpleName}: ${t.message}) -> treat as no ink")
            null
        }
    }

    /** 单条 IHWTrack → RawTrack（反射读 getPoints/getPenWidth/getPenColor）。 */
    private fun readOneTrack(track: Any): HwTrackConverter.RawTrack {
        val tcls = track.javaClass
        val penWidth = try {
            (tcls.getMethod("getPenWidth").invoke(track) as? Float) ?: 0f
        } catch (t: Throwable) { 0f }
        val penColor = try {
            (tcls.getMethod("getPenColor").invoke(track) as? Int) ?: PEN_COLOR_BLACK
        } catch (t: Throwable) { PEN_COLOR_BLACK }

        val points = ArrayList<HwTrackConverter.RawPoint>()
        try {
            val pointList = tcls.getMethod("getPoints").invoke(track) as? List<*>
            if (pointList != null) {
                for (hp in pointList) {
                    if (hp == null) continue
                    points.add(readOnePoint(hp))
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "getPoints failed on a track (${t.javaClass.simpleName}: ${t.message})")
        }
        return HwTrackConverter.RawTrack(points, penWidth, penColor)
    }

    /** 单个 HWPoint → RawPoint。x/y 走 PointF 字段；eventtime/pressure 是 HWPoint public 字段。 */
    private fun readOnePoint(hp: Any): HwTrackConverter.RawPoint {
        // x/y：HWPoint extends PointF，直接当 PointF 取（最稳），退化再反射字段。
        var x = 0f
        var y = 0f
        if (hp is PointF) {
            x = hp.x; y = hp.y
        } else {
            x = readFloatField(hp, "x")
            y = readFloatField(hp, "y")
        }
        val eventtime = try {
            hp.javaClass.getField("eventtime").getLong(hp)
        } catch (t: Throwable) { 0L }
        val pressure = try {
            hp.javaClass.getField("pressure").getFloat(hp)
        } catch (t: Throwable) { 0f }
        return HwTrackConverter.RawPoint(x, y, eventtime, pressure)
    }

    private fun readFloatField(obj: Any, name: String): Float = try {
        obj.javaClass.getField(name).getFloat(obj)
    } catch (t: Throwable) { 0f }

    /** 清屏，备下一轮书写。翻页/清空/提交后调。反射失败只记 Log。 */
    fun clear() {
        try {
            cls.getMethod("clear").invoke(view)
            Log.i(TAG, "clear invoked")
        } catch (t: Throwable) {
            Log.i(TAG, "clear failed (${t.javaClass.simpleName}: ${t.message})")
        }
    }

}

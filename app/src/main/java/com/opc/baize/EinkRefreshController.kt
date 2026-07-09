package com.opc.baize

import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 三级 · 也是最关键的一级：墨水屏面板刷新模式切换（华为墨水屏专属，全程反射，静态方法）。
 *
 * 真正的瓶颈（见 reverse/BAIZE_LATENCY_FINDINGS.md 末节）：普通 App 面板长期处于 GC16
 * 高质量慢刷（防残影），每一笔上屏都要等一次慢刷——这是物理瓶颈，光靠一级预测/二级 AHW
 * 都补不满。原生笔记"跟手"，是书写时把面板切成 DU4/A2 局部快刷，抬笔收尾再切回 GC16
 * 出一张干净的字。
 *
 * 解药：反射 android.eink.EPDCDevice 的静态方法（在 BOOTCLASSPATH 上，编译期不可见 →
 * Class.forName + getMethod + invoke(null, ...)）。它是静态设备控制，不需要绑定任何 Surface。
 *   - setEpdcDefaultMode(int)   设面板默认刷新模式
 *   - setForceNextPostMode(int) 强制下一帧刷新模式
 *   - updatePenStatus(int)      笔状态（1=书写中，0=离开）
 *   - forceRefresh(int)         强制刷新出一张图
 *
 * 铁律 · 绝不能把面板留在快刷态离开 App：否则整台设备屏幕都会低质量直到系统复位。
 * 因此 Activity onPause/onStop/onDestroy 一律强制 exitWriting()，任何异常路径 finally 恢复
 * QUALITY；内部用 writing 标志避免重复切、并保证"异常后仍能 exit"。
 *
 * 健壮性：类找不到 / 方法签名不符 / SecurityException / invoke 抛异常——一律只记 Log、不抛，
 * enter/exit 永不使 App 崩溃。桌面 JVM（无 EPDCDevice）isAvailable()=false，enter/exit 空转。
 *
 * 生效判定全靠 Log（TAG=BaizeEink）：真机 `adb logcat -s BaizeEink` 看
 *   - "static method:" 系列：dump 出 EPDCDevice 的全部 public 静态方法，用于核对签名
 *   - "setEpdcDefaultMode(FAST) invoked ok" / "...(QUALITY) invoked ok"
 *   - 有无 SecurityException（驱动侧是否放行第三方切面板模式）
 */
class EinkRefreshController {

    companion object {
        const val TAG = "BaizeEink"
        private const val CLS_EPDC = "android.eink.EPDCDevice"

        // —— 刷新模式常量（字面值取自 android.eink.api.EinkMode，不必反射常量）——
        // 手写快刷：默认用 DU4_PART=5（4bit 局部快刷，跟手好、残影可控）。
        // 若真机发虚影/拖影严重，可改用 A2_DITHER_PART=260（更快但更糙）。
        const val FAST_DU4_PART = 5            // UI_EINK_DU4_PART_MODE_4BIT
        const val FAST_A2_DITHER_PART = 260    // UI_EINK_A2_DITHER_PART_MODE_4BIT（备选）
        /** 实际生效的快刷模式：默认 DU4；真机虚影严重时改成 FAST_A2_DITHER_PART。 */
        const val FAST = FAST_DU4_PART

        // 收尾高质量：GC16 局部，出一张干净的字（防残影主力）。
        const val QUALITY_GC16_PART = 2        // UI_EINK_GC16_PART_MODE
        const val QUALITY = QUALITY_GC16_PART

        // 笔状态
        private const val PEN_WRITING = 1
        private const val PEN_LEFT = 0

        /**
         * 桌面 JVM 单测能覆盖这条：EPDCDevice 不存在时安全返回 false、不抛。
         */
        fun isFrameworkPresent(): Boolean = try {
            Class.forName(CLS_EPDC)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "EPDCDevice not present (${t.javaClass.simpleName}) -> refresh-mode switching disabled")
            false
        }
    }

    private var epdcClass: Class<*>? = null
    private var mSetEpdcDefaultMode: Method? = null
    private var mSetForceNextPostMode: Method? = null
    private var mUpdatePenStatus: Method? = null
    private var mForceRefresh: Method? = null

    /** 反射初始化是否成功（类找到、至少 setEpdcDefaultMode 拿到）。桌面必然 false。 */
    @Volatile
    var available: Boolean = false
        private set

    /** 当前是否处于快刷（书写）态。用于避免重复切、并守住"绝不遗留快刷态"这条铁律。 */
    @Volatile
    var writing: Boolean = false
        private set

    fun isAvailable(): Boolean = available

    /**
     * 反射解析 EPDCDevice 的静态方法。每个方法 getMethod 失败只记 Log 跳过；
     * 只要类在且拿到 setEpdcDefaultMode 就算 available（其余是加成）。
     * 全程容错——桌面 ClassNotFoundException 直接 available=false 返回，不抛。
     */
    fun init() {
        try {
            val cls = Class.forName(CLS_EPDC)
            epdcClass = cls
            Log.i(TAG, "reflect ok: Class.forName($CLS_EPDC)")
            dumpStaticMethods(cls)

            mSetEpdcDefaultMode = resolve(cls, "setEpdcDefaultMode", Int::class.javaPrimitiveType!!)
            mSetForceNextPostMode = resolve(cls, "setForceNextPostMode", Int::class.javaPrimitiveType!!)
            mUpdatePenStatus = resolve(cls, "updatePenStatus", Int::class.javaPrimitiveType!!)
            mForceRefresh = resolve(cls, "forceRefresh", Int::class.javaPrimitiveType!!)

            available = mSetEpdcDefaultMode != null
            if (available) {
                Log.i(TAG, "EinkRefreshController READY — refresh-mode switching available (FAST=$FAST QUALITY=$QUALITY)")
            } else {
                Log.i(TAG, "setEpdcDefaultMode not resolved -> refresh-mode switching unavailable")
            }
        } catch (t: Throwable) {
            Log.i(TAG, "init failed (${t.javaClass.simpleName}: ${t.message}) -> refresh-mode switching disabled")
            available = false
        }
    }

    /**
     * 进入书写快刷。整段连续书写只切一次（重复调用短路），不要每笔切。
     * updatePenStatus(1) + setEpdcDefaultMode(FAST)。
     */
    fun enterWriting() {
        if (!available) return
        if (writing) return   // 已在快刷态，重复 enter 只切一次
        writing = true
        try {
            invokeStatic(mUpdatePenStatus, PEN_WRITING, "updatePenStatus(WRITING)")
            invokeStatic(mSetEpdcDefaultMode, FAST, "setEpdcDefaultMode(FAST=$FAST)")
        } catch (t: Throwable) {
            Log.i(TAG, "enterWriting error (${t.javaClass.simpleName}: ${t.message})")
            // enter 出错也别把 writing 卡在 true 之外的怪态；保持 writing=true，
            // 让后续 exit 一定会跑一遍恢复（宁可多恢复一次，绝不遗留快刷态）。
        }
    }

    /**
     * 退出到高质量。setEpdcDefaultMode(QUALITY) + updatePenStatus(0) + forceRefresh(QUALITY)
     * 出一张干净的字。可随便重复调（幂等）：即便没在写也会执行一次恢复，作为兜底。
     *
     * 关键：全程 try/catch，任何一步抛异常都在 finally 里把 writing 置回 false，
     * 保证"异常后仍能 exit 恢复"、状态机不误判。
     */
    fun exitWriting() {
        if (!available) { writing = false; return }
        try {
            invokeStatic(mSetEpdcDefaultMode, QUALITY, "setEpdcDefaultMode(QUALITY=$QUALITY)")
            invokeStatic(mUpdatePenStatus, PEN_LEFT, "updatePenStatus(LEFT)")
            invokeStatic(mForceRefresh, QUALITY, "forceRefresh(QUALITY=$QUALITY)")
        } catch (t: Throwable) {
            Log.i(TAG, "exitWriting error (${t.javaClass.simpleName}: ${t.message})")
        } finally {
            writing = false
        }
    }

    /**
     * 强制恢复到高质量——生命周期兜底专用（onPause/onStop/onDestroy 一律调）。
     * 即便当前 writing=false 也照样执行一遍 exit：绝不能因为状态标志误判而把面板留在快刷态。
     */
    fun forceRestoreQuality() {
        if (!available) { writing = false; return }
        // 直接走 exitWriting 的恢复序列（不看 writing 标志），最稳。
        exitWriting()
    }

    // ---------- 反射辅助 ----------

    private fun resolve(cls: Class<*>, name: String, vararg types: Class<*>): Method? = try {
        val m = cls.getMethod(name, *types)
        Log.i(TAG, "reflect ok: resolved static $name(${types.joinToString { it.simpleName }})")
        m
    } catch (t: Throwable) {
        Log.i(TAG, "$name not resolved (${t.javaClass.simpleName}: ${t.message}) -> skip")
        null
    }

    /** 调一个静态方法（invoke(null, arg)）。方法未解析到→跳过；invoke 异常→只记 Log 不抛。 */
    private fun invokeStatic(m: Method?, arg: Int, label: String) {
        val method = m ?: run {
            Log.i(TAG, "$label skipped (method unavailable)")
            return
        }
        try {
            method.invoke(null, arg)
            Log.i(TAG, "$label invoked ok")
        } catch (t: Throwable) {
            Log.i(TAG, "$label invoke failed (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    /** 启动时 dump EPDCDevice 全部 public 静态方法，便于真机核对签名。 */
    private fun dumpStaticMethods(cls: Class<*>) {
        try {
            for (m in cls.methods) {
                if (Modifier.isStatic(m.modifiers) && Modifier.isPublic(m.modifiers)) {
                    Log.i(TAG, "  static method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                }
            }
        } catch (t: Throwable) { /* dump 失败无所谓 */ }
    }
}

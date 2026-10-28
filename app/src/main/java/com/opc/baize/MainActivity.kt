package com.opc.baize

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.opc.baize.core.CnNumber
import com.opc.baize.core.ModelConfig
import com.opc.baize.core.NewCommand
import com.opc.baize.core.Session
import com.opc.baize.core.SessionMemory
import com.opc.baize.core.Texts
import com.opc.baize.core.Turn
import com.opc.baize.core.TouchGate
import com.opc.baize.model.AnthropicModelProvider
import com.opc.baize.model.SubscriptionModelProvider
import com.opc.baize.model.ModelProvider
import com.opc.baize.model.HandwritingRecognition
import com.opc.baize.subscription.SubscriptionGateway
import com.opc.baize.subscription.SubscriptionSettingsActivity
import com.opc.baize.net.DeepSeekClient
import com.opc.baize.net.FallbackClient
import com.opc.baize.net.MlKitRecognizer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 白泽 · 原生版主控。
 *
 * 提交流程（继承网页版 §5 + server.py 的误触闸）：
 *   停笔（延迟档位用户可调，默认 800ms）→ 快照笔迹 → 墨迹四级离散淡出 → 误触闸 →
 *   识别（ML Kit zh-Hani 主通道；异常降级自建 /api/ask）→
 *   /new 指令 / 空识别固定回文 / 大模型文言回复（重试1次）→
 *   龙藏体逐字显现。等待期只有右上角状态墨点 ○/●/×。
 *
 * 书写路径两级：华为墨水屏走 HWEditView 驱动直写（+面板快刷切换）；
 * 其余设备（含非华为墨水屏平板）走 InkView 自绘 + AndroidX 运动预测，功能完全一致。
 *
 * 单飞队列：工作线程串行消化，一轮的书写动画写完才放行下一轮
 * （CountDownLatch 闭环，writeAnimated 被打断也必回调）。
 */
class MainActivity : Activity() {

    private lateinit var ink: InkView
    private lateinit var book: BookView
    private lateinit var statusDot: TextView
    private lateinit var pageNum: TextView
    private lateinit var tipText: TextView
    private lateinit var seal: ImageView

    /** 终极直写书写层（华为 HWEditView 反射封装）。null=不可用/关闭，走 InkView 老路。 */
    private var hwLayer: HwHandwritingLayer? = null
    private lateinit var rootLayout: FrameLayout
    private fun hwActive(): Boolean = hwLayer != null

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val memory = SessionMemory(6)
    private lateinit var store: PageStore
    private lateinit var sessionStore: SessionStore
    private val deepseek = DeepSeekClient()
    private val fallback = FallbackClient()
    private lateinit var recognizer: MlKitRecognizer

    // ---------- 会话历史（开源版：像对话软件按会话分条） ----------
    // BookView 仍以“连续书页”渲染当前对话（观感不变）；这里额外维护一份按会话分条的历史：
    // 每次 /new 或首次开始 = 新会话，一轮问答 record 到 curTurns，随即整表落盘 sessions.json。
    // 回看历史某条 = 只读把该会话铺进书页；用户在回看态继续书写则自动开一个新会话
    // （先清书页再落新问答，新旧会话不混页，见 runRound/fallbackRound 的 viewingHistory 分支）。
    private val allSessions = mutableListOf<Session>()
    private val curTurns = mutableListOf<Turn>()
    private var curSessionId: String = newSessionId()
    private var curSessionTs: Long = System.currentTimeMillis()
    /** true=当前处于“回看历史”只读态，落笔提交时先另起新会话。 */
    private var viewingHistory = false
    /** 回看态下正在展示的历史会话 id（删除该条历史时据此连书页一起清）。 */
    private var viewingSessionId: String? = null

    private var idleRunnable: Runnable? = null
    private var statusState = "idle"

    // ---------- 直写模式 · 轮询停笔检测（不依赖触摸事件） ----------
    // HWEditView 自吃 onTouchEvent，容器观察不到单指落笔/抬笔，老路的"触摸驱动 2.5s 计时"在
    // 直写模式下永不启动。改为轮询笔迹指纹判停笔：每 POLL_MS 读 hwLayer.inkFingerprint()，
    // 有增长=在写（切快刷、记最后书写时刻），连续 IDLE_MS 无增长且非空=停笔（走 onIdleTimeoutHw）。
    // 2026-07-08 老板反馈"停笔半天没反应"：直写让写字变快，2.5s 死等被反衬得更难受。
    // 砍到 800ms——中文字间自然停顿多在 500ms 内，800ms 已能判"写完"又不易把思考停顿误判成提交。
    // 阈值默认 800ms，onCreate 里按偏好套用（设置面板可选 300~3000ms，老板 2026-07-08）。
    // 不在字段初始化时读 prefs：那时 Activity context 未就绪，getSharedPreferences 会崩。
    private val idleDetector = IdleInkDetector(800L)
    private var pollRunnable: Runnable? = null
    /** 提交处理（识别+回复）在途互斥：期间暂停轮询判停笔，避免重复触发。 */
    @Volatile private var submitInFlight = false
    /** 笔是否落在纸上（容器 onPenDown/onPenUp 驱动）：笔未抬起时绝不判停笔，防单笔写久被误判。 */
    @Volatile private var penDown = false
    /** 上一 tick 是否在"写"，仅用于降频日志（状态变化才打）。 */
    private var pollWasGrowing = false

    /** 墨水屏面板刷新模式切换（最关键的低延迟一环）。书写切快刷、收尾切回高质量。反射，全程容错。 */
    private val refresh = EinkRefreshController()

    private val prefs by lazy { getSharedPreferences("moling", Context.MODE_PRIVATE) }

    /** 面板快刷切换开关，默认开（无设置 UI，调试可用 adb 改 prefs；非华为设备自动空转）。 */
    private fun useFastRefresh(): Boolean = prefs.getBoolean("useFastRefresh", true)

    /**
     * 驱动直写（HWEditView）总开关，默认开（无设置 UI，调试可用 adb 改 prefs）。
     * 关闭或设备无华为框架 → 纯 InkView 自绘+预测老路（非华为设备的常规路径）。
     */
    private fun useDirectInk(): Boolean = prefs.getBoolean("useDirectInk", true)

    /** 可选的局域网识别降级通道地址（开源版默认空 = 不启用；设置面板【手写识别】区可填）。 */
    private fun fallbackUrl(): String =
        prefs.getString("fallback_url", BuildConfig.DEFAULT_FALLBACK_ASK_URL)
            ?: BuildConfig.DEFAULT_FALLBACK_ASK_URL

    // ---------- 大模型配置（运行时读 SharedPreferences，开源版无硬编码密钥） ----------

    private fun modelConfig(): ModelConfig = ModelConfig(
        apiKey = prefs.getString("api_key", "") ?: "",
        endpoint = prefs.getString("api_endpoint", ModelConfig.DEFAULT_ENDPOINT)
            ?: ModelConfig.DEFAULT_ENDPOINT,
        model = prefs.getString("api_model", ModelConfig.DEFAULT_MODEL)
            ?: ModelConfig.DEFAULT_MODEL,
    )

    // ---------- 字体设置 ----------

    private fun fontAsset(): String = prefs.getString("font_asset", FONT_LONGCANG) ?: FONT_LONGCANG

    private fun fontSizeDp(): Float = prefs.getFloat("font_size", 38f)

    // 停笔识别延迟档位（ms）：写完停笔多久自动触发识别。写字慢的人调大、快的人调小。
    private val IDLE_STEPS = longArrayOf(300, 500, 800, 1000, 1500, 2000, 2500, 3000)
    private fun idleMs(): Long = prefs.getLong("idle_ms", 800L)

    /** 把当前字体偏好应用到书页渲染。 */
    private fun applyFontToBook() {
        book.applyFont(fontAsset(), fontSizeDp())
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PageStore(File(filesDir, "pages.json"))
        sessionStore = SessionStore(File(filesDir, "sessions.json"), File(filesDir, "pages.json"))
        allSessions.addAll(sessionStore.load())   // 读历史（含旧 pages.json 迁移），失败=空
        buildUi()
        applyImmersive()
        applyFontToBook()   // 套用用户字体偏好（首次为默认龙藏/38dp）
        idleDetector.setThreshold(idleMs())   // 套用停笔识别延迟偏好（默认 800ms）

        recognizer = MlKitRecognizer { status ->
            ui.post {
                if (status == null) {
                    tipText.visibility = View.GONE
                } else {
                    tipText.text = status
                    tipText.visibility = View.VISIBLE
                }
            }
        }
        recognizer.init(Texts.MODEL_DOWNLOADING_TIP)

        // 面板刷新模式切换（默认开）。反射解析 EPDCDevice 静态方法；桌面/非华为设备
        // available=false，enter/exit 全部空转。开关关闭则完全不碰 EPDCDevice（纯预测版）。
        if (useFastRefresh()) refresh.init()

        // 终极直写（默认开）：反射构造华为 HWEditView 作为纯输入书写层，盖在书页之上。
        // 成功 → 停用 InkView 的输入与自绘、书写走驱动直写（写一点出一点）。任一步失败 →
        // hwLayer 保持 null，无缝回退 InkView 自绘+预测老路（非华为设备的常规路径）。
        // 生效与否全靠 adb logcat -s BaizeHW 判断。
        if (useDirectInk() && HwHandwritingLayer.isFrameworkPresent()) {
            installHwLayer()
        }

        // 重启恢复：有存档静态重铺停末页；否则引导语走书写节奏
        book.post {
            val stored = store.load()
            if (stored != null) book.loadFromStore(stored)
            else book.resetBook(Texts.INTRO_TEXT)
            // 首次启动：弹欢迎 → 一键打开设置面板【设置】区，引导填大模型 API
            if (!prefs.getBoolean("firstRunDone", false)) showFirstRunGuide()
        }
    }

    /** 首次打开引导：填过 API（或点“稍后”）即置 firstRunDone，之后不再弹。 */
    private fun showFirstRunGuide() {
        AlertDialog.Builder(this)
            .setTitle("欢迎使用墨灵")
            .setMessage("墨灵是一枚栖于墨水屏中的墨中之灵。\n\n请先在设置中登录 ChatGPT 订阅或配置原 API，方能应答手写之问。")
            .setPositiveButton("去设置") { _, _ ->
                prefs.edit().putBoolean("firstRunDone", true).apply()
                showPanel(TAB_SETTINGS)
            }
            .setNegativeButton("稍后") { _, _ ->
                prefs.edit().putBoolean("firstRunDone", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private var providerSelector: android.widget.Spinner? = null

    override fun onResume() {
        super.onResume()
        providerSelector?.setSelection(if (prefs.getString("model_provider", "anthropic") == "subscription") 1 else 0)
        // 回前台：HWEditView 重新进手写态，恢复驱动直写（onPause 时已退出）。
        hwLayer?.enterHandwriteState()
        // 直写模式：回前台启动轮询停笔检测（onPause 已停）。
        startIdlePolling()
    }

    // 铁律 · 绝不能把面板留在快刷态离开 App：onPause/onStop/onDestroy 一律强制恢复高质量。
    // 直写层同理：退到后台前退出手写态，别把驱动直写（AHW）留在面板上。
    override fun onPause() {
        super.onPause()
        stopIdlePolling()   // 直写轮询停笔检测：退到后台停，避免空转
        refresh.forceRestoreQuality()
        hwLayer?.leaveHandwriteState()
    }

    override fun onStop() {
        super.onStop()
        refresh.forceRestoreQuality()
        hwLayer?.leaveHandwriteState()
    }

    override fun onDestroy() {
        super.onDestroy()
        refresh.forceRestoreQuality()
        hwLayer?.leaveHandwriteState()
        worker.shutdownNow()
    }

    /** 全程沉浸式全屏（原生版不再需要网页版的三击全屏） */
    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ---------- UI 构建（纯代码布局，最短渲染路径） ----------

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(0xFFF6F1E4.toInt()) }
        rootLayout = root
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 顶部书名区：白 泽 + 状态墨点
        val header = FrameLayout(this).apply {
            setPadding(px(22f), px(14f), px(22f), px(8f))
        }
        val title = TextView(this).apply {
            text = "墨 灵"
            textSize = 24f
            letterSpacing = 0.5f
            setTextColor(0xFF3A352C.toInt())
        }
        statusDot = TextView(this).apply {
            text = "○"
            textSize = 18f
            typeface = Typeface.SERIF
            setTextColor(0xFF3A352C.toInt())
        }
        header.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(statusDot, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL))

        val divider = View(this).apply { setBackgroundColor(0xFFD8CFB8.toInt()) }

        book = BookView(this)

        // 底部：页码角标居中 + 模型下载小灰字（左侧，一次性）
        val footer = FrameLayout(this).apply { setPadding(px(22f), px(4f), px(22f), px(12f)) }
        pageNum = TextView(this).apply {
            textSize = 14f
            letterSpacing = 0.35f
            setTextColor(0xFFA39A87.toInt())
            typeface = Typeface.SERIF
            text = "壹 / 壹"
        }
        tipText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFA39A87.toInt())
            visibility = View.GONE
        }
        footer.addView(pageNum, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL))
        footer.addView(tipText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL))

        column.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1))
        column.addView(book, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        ink = InkView(this)

        // 左下角印章：一枚 64dp Logo，【单击】进入设置（老板 2026-07-08：双击改单击）。
        // 放在最顶层（z 序最高），自己拦单击手势，不影响其余区域的书写/手势。
        seal = ImageView(this).apply {
            setImageResource(R.drawable.ic_seal)
            alpha = 0.9f
            contentDescription = "墨灵 · 印章（点击打开设置）"
        }
        val sealDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                // 单击（抬手即触发，不再等双击判定）→ 打开面板，先落在【历史】页
                // （老板 2026-07-08：印章进历史，再从 tab 切设置）
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    showPanel(TAB_HISTORY)
                    return true
                }
                override fun onDown(e: android.view.MotionEvent): Boolean = true
            }
        )
        seal.setOnTouchListener { _, ev -> sealDetector.onTouchEvent(ev) }
        val sealSize = px(64f)
        val sealLp = FrameLayout.LayoutParams(sealSize, sealSize,
            Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = px(12f); bottomMargin = px(12f)
        }

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(ink, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(seal, sealLp)
        setContentView(root)

        wireCallbacks()
    }

    private fun wireCallbacks() {
        book.onPageInfo = { cur, total ->
            pageNum.text = "${CnNumber.numToCn(cur)} / ${CnNumber.numToCn(total)}"
        }
        book.onFlash = {
            pageNum.setTextColor(0xFF1C1A16.toInt())
            ui.postDelayed({ pageNum.setTextColor(0xFFA39A87.toInt()) }, 350)
        }
        book.onPersist = { store.save(book.snapshotForStore()) }

        ink.listener = object : InkView.Listener {
            override fun onPenDown() {
                cancelIdleTimer()
                // 三级：第一笔落下即切面板快刷（整段连续书写只切一次，重复 enter 短路）
                refresh.enterWriting()
                if (statusState == "error") setStatus("idle")
            }
            override fun onStrokeFinished() = startIdleTimer()
            override fun onInkRemaining() = startIdleTimer()
            override fun onSwipe(dir: Int) {
                // 翻页前先出高质量：抬笔停顿态切回 GC16
                refresh.exitWriting()
                if (dir > 0) book.nextPage() else book.prevPage()
            }
            override fun onLongPressTopLeft() = doClearAll()
            override fun onLongPressTopRight() = showPanel(TAB_SETTINGS)
        }
    }

    // ---------- 终极直写：HWEditView 书写层安装 ----------

    /**
     * 构造 HWEditView 反射封装，放进手势仲裁容器 HwWritingContainer，盖在 InkView 之上、
     * 铺满全屏（与 InkView 覆盖同一区域）。成功后：
     *   - InkView 停用输入与自绘（setInputEnabled(false)）——书写完全交给 HWEditView 驱动直写；
     *   - 单指书写 → HWEditView 直写出墨（写一点出一点）；
     *   - 两指/角落长按 → 由容器仲裁，接回原有翻页/清空/设置；
     *   - 停笔判定由轮询笔迹指纹驱动（startIdlePolling），容器的 onPenDown/onPenUp
     *     只维护 penDown 标志（笔未抬绝不判停）。
     * 任一步失败 → hwLayer 保持 null，InkView 老路照常。
     */
    private fun installHwLayer() {
        val layer = HwHandwritingLayer.create(this) ?: return
        val container = HwWritingContainer(this)
        container.addView(
            layer.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.listener = object : HwWritingContainer.Listener {
            // 直写模式的落笔/抬笔在真机上观察不可靠（HWEditView 自吃 onTouchEvent），
            // 停笔判定改由 startIdlePolling() 的轮询指纹负责；这里仅做即时的错误态复位，
            // 面板快刷 enter/exit 也交给轮询驱动（有增长→enter、判停→exit），比挂触摸更可靠。
            override fun onPenDown() {
                // 笔落下 = 正在写，绝不能在此期间判停笔（见 penDown 用途）。
                penDown = true
                if (statusState == "error") setStatus("idle")
            }
            override fun onPenUp() {
                // 笔抬起：这一笔提交，停笔倒计时从此刻起算（轮询下一 tick 见指纹增长即复位）。
                penDown = false
            }
            override fun onSwipe(dir: Int) {
                refresh.exitWriting()
                layer.clear()   // 翻页前清掉手写层残墨
                idleDetector.reset()   // 翻页清墨后复位指纹基线，别把旧墨算成"停笔"
                if (dir > 0) book.nextPage() else book.prevPage()
            }
            override fun onGestureEndClearInk() {
                // 两指手势起手时首指落在 HWEditView 上的半笔：清掉，一点墨不留
                layer.clear()
                idleDetector.reset()   // 半笔清掉后复位，别让残指纹触发停笔
            }
            override fun onLongPressTopLeft() = doClearAll()
            override fun onLongPressTopRight() = showPanel(TAB_SETTINGS)
        }
        rootLayout.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // InkView 让位：不再吃触摸、不再自绘（消散动画这一版省略，直接 clear()；见任务 §2 注释）
        ink.setInputEnabled(false)
        hwLayer = layer
        // 关键 bug 修复（老板 2026-07-08"印章点了没反应"）：直写容器盖在印章之上、吃掉了
        // 落在印章上的触摸。把印章提回最顶层，单击才能命中。
        seal.bringToFront()
    }

    // ---------- 印章闪烁（仅在 API 未配置、需提示用户去设置时，闪两下即停） ----------
    // 老板 2026-07-08：不要一直闪影响观感；只在"没通灵、提示填 API"时闪两下。
    private var sealBlinking = false
    /** 闪两下（离散明暗，墨水屏友好），结束停在正常透明度 0.9。重复调用不叠加。 */
    private fun blinkSealTwice() {
        if (sealBlinking) return
        sealBlinking = true
        // 明暗序列：暗-亮-暗-亮（两下），每步 220ms，末尾复位 0.9。
        val steps = listOf(0.3f, 0.9f, 0.3f, 0.9f)
        steps.forEachIndexed { i, a ->
            ui.postDelayed({
                seal.alpha = a
                if (i == steps.lastIndex) sealBlinking = false
            }, 220L * (i + 1))
        }
    }

    // ---------- 状态墨点 ----------

    private fun setStatus(s: String) {
        statusState = s
        statusDot.text = when (s) { "thinking" -> "●"; "error" -> "×"; else -> "○" }
    }

    // ---------- 直写模式 · 轮询停笔检测 ----------

    private companion object {
        const val TAG_HW = "BaizeHW"
        // 轮询间隔 150ms（原 400ms）：配合 800ms 停笔阈值，停笔到开始识别最多约 0.8~0.95s，
        // 追求"停笔立马就有"（老板 2026-07-08）。150ms 一次 getTrackList 反射开销可接受。
        const val POLL_MS = 150L

        // 半屏面板 tab 序号
        const val TAB_HISTORY = 0
        const val TAB_SETTINGS = 1
        const val TAB_ABOUT = 2

        // 内置手写字体 asset 文件名（均为 SIL OFL 开源）
        const val FONT_LONGCANG = "LongCang-Regular.ttf"
        const val FONT_MASHAN = "MaShanZheng-Regular.ttf"
        const val FONT_ZHIMANG = "ZhiMangXing-Regular.ttf"
    }

    /**
     * 启动轮询停笔检测。仅在直写（HWEditView 可用）时生效；非直写走老路的触摸驱动计时器。
     * 每 POLL_MS：读 hwLayer.inkFingerprint() → 喂 IdleInkDetector：
     *   有增长 → 切面板快刷（enterWriting，重复短路）；
     *   无增长达 idle_ms 档位且非空 → 判停笔：exitWriting 出干净字 → onIdleTimeoutHw 提交；
     *   提交在途（submitInFlight）→ 本 tick 跳过检测，避免重复触发。
     * onResume 调；重复调用先撤旧再起新（幂等）。
     */
    private fun startIdlePolling() {
        val layer = hwLayer ?: return   // 非直写：不轮询，走老路
        stopIdlePolling()
        idleDetector.reset()
        pollWasGrowing = false
        val r = object : Runnable {
            override fun run() {
                pollTickHw(layer)
                ui.postDelayed(this, POLL_MS)
            }
        }
        pollRunnable = r
        ui.postDelayed(r, POLL_MS)
        android.util.Log.i(TAG_HW, "idle polling started (interval=${POLL_MS}ms)")
    }

    private fun stopIdlePolling() {
        pollRunnable?.let { ui.removeCallbacks(it) }
        pollRunnable = null
    }

    /** 单次轮询 tick：读指纹 → 判定 → 驱动快刷 / 触发提交。全程 UI 线程。 */
    private fun pollTickHw(layer: HwHandwritingLayer) {
        // 提交处理期间（识别+回复几秒）暂停判停笔，避免重复触发。
        if (submitInFlight) return

        // 关键修复（老板 2026-07-08"写长句被拆两半"）：getTrackList() 只统计已抬笔提交的笔画，
        // 正在写的这一笔（笔未抬）不计数——单笔写久了指纹就"看似没增长"，被误判成停笔、抢先提交
        // 前半句。故笔未抬起时一律视为"在写"：切快刷、绝不判停，倒计时留到抬笔后才起算。
        if (penDown) {
            refresh.enterWriting()
            if (statusState == "error") setStatus("idle")
            return
        }

        val (tracks, points) = layer.inkFingerprint()
        val tick = idleDetector.onTick(tracks, points, android.os.SystemClock.uptimeMillis())

        // 面板快刷由轮询驱动：有增长→enter（重复短路），非空但停顿→由停笔分支 exit。
        if (tick.growing) {
            refresh.enterWriting()
            if (statusState == "error") setStatus("idle")
        }

        // 降频日志：仅状态变化时打，别刷屏。
        if (tick.growing != pollWasGrowing) {
            val ago = idleDetector.msSinceGrow(android.os.SystemClock.uptimeMillis())
            android.util.Log.i(TAG_HW, "poll: fp=($tracks,$points) growing=${tick.growing} lastGrowMsAgo=$ago")
            pollWasGrowing = tick.growing
        }

        if (tick.fire) {
            android.util.Log.i(TAG_HW, "idle detected -> submit $tracks tracks")
            submitInFlight = true
            refresh.exitWriting()   // 判停：切回高质量出一张干净的字，再识别
            onIdleTimeoutHw(layer)
        }
    }

    // ---------- 停笔计时（自动提交，非直写老路；延迟档位与直写模式共用 idle_ms 偏好） ----------

    private fun startIdleTimer() {
        cancelIdleTimer()
        val r = Runnable {
            idleRunnable = null
            onIdleTimeout()
        }
        idleRunnable = r
        ui.postDelayed(r, idleMs())
    }

    private fun cancelIdleTimer() {
        idleRunnable?.let { ui.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun onIdleTimeout() {
        val layer = hwLayer
        if (layer != null) {
            // 防御分支（直写模式的停笔判定实际由轮询驱动，不走这里）：口径对齐轮询路——先切回高质量
            refresh.exitWriting()
            onIdleTimeoutHw(layer)
            return
        }
        if (ink.isWriting() || ink.strokes.isEmpty()) return
        // 停笔提交：切回高质量出一张干净的字；后续 AI 逐字书写属"展示"，全程 QUALITY。
        refresh.exitWriting()
        val snapshot = ink.snapshot()
        // 降级通道要的 PNG 必须在消散前、UI 线程上导出（快照数据独立离屏重绘）
        val dataUrl = ink.exportInkDataUrl(snapshot)
        val stats = ink.stats(snapshot)
        val minBox = ink.minBoxPx()
        ink.fadeOutAndClear(snapshot)
        submit(snapshot, dataUrl, stats, minBox)
    }

    /**
     * 直写层停笔提交：从 HWEditView.getTrackList() 读笔迹 → 转成白泽 Stroke → 复用 InkView 的
     * 纯导出/统计函数（它们只吃传入 snapshot，与 InkView 自身是否使能无关）→ 走同一条误触闸/
     * 识别/DeepSeek 管线。回复展示仍用龙藏文本渲染（不经 HWEditView）。读完即 clear() 手写层。
     * 消散动画这一版省略：直接 clear()（见任务 §2）。
     */
    private fun onIdleTimeoutHw(layer: HwHandwritingLayer) {
        // 华为 track 若无 penWidth，退回 4dp 基准（与 InkView.baseWidth 同口径）。
        // 注：调用方（pollTickHw 判停分支 / onIdleTimeout 防御分支）已先 exitWriting 切回高质量。
        val fallbackWidthPx = 4f * resources.displayMetrics.density
        val snapshot = layer.readStrokes(fallbackWidthPx)
        if (snapshot.isEmpty()) {
            // 读到空（罕见：指纹非空但转换后无笔画）→ 释放互斥、复位基线，继续轮询下一轮。
            finishHwSubmit()
            return
        }
        // 复用 InkView 的离屏导出/统计（纯函数，吃传入 snapshot）
        val dataUrl = ink.exportInkDataUrl(snapshot)
        val stats = ink.stats(snapshot)
        val minBox = ink.minBoxPx()
        layer.clear()   // 读完即清，备下一轮
        submit(snapshot, dataUrl, stats, minBox)
    }

    /**
     * 直写模式一轮提交收尾：清屏后复位指纹基线 + 释放在途互斥，让轮询恢复检测下一轮输入。
     * 提交主链路的所有出口（成功/失败/丢弃/空读）都汇到这里，保证互斥绝不卡死。
     */
    private fun finishHwSubmit() {
        idleDetector.reset()
        pollWasGrowing = false
        submitInFlight = false
        android.util.Log.i(TAG_HW, "submit done -> cleared")
    }

    // ---------- 提交主链路（工作线程串行） ----------

    private fun submit(
        snapshot: List<MutableList<InkView.Pt>>,
        dataUrl: String?,
        stats: FloatArray,
        minBoxPx: Float,
    ) {
        val gate = TouchGate.gate(
            strokeCount = snapshot.size,
            totalPoints = stats[0].toInt(),
            bboxW = stats[1],
            bboxH = stats[2],
            minBox = minBoxPx,
        )
        if (gate == TouchGate.Action.DISCARD) {
            if (hwActive()) finishHwSubmit()   // 静默丢弃也要释放互斥，别卡死轮询
            return  // 连识别都不做，静默丢弃
        }

        setStatus("thinking")
        worker.execute {
            try {
                runRound(gate, snapshot, dataUrl)
            } catch (t: Throwable) {
                uiWriteAndWait { done -> failOnPage(done) }
            } finally {
                ui.post { if (statusState == "thinking") setStatus("idle") }
                // 直写模式：一轮完整跑完（含 runRound 内部所有早退分支）后释放在途互斥，
                // 恢复轮询检测下一轮输入。放在 finally 保证任何异常路径都会释放。
                if (hwActive()) ui.post { finishHwSubmit() }
            }
        }
    }

    /** 一轮完整处理（工作线程）。任何 ML Kit 异常 → Mac 降级通道。 */
    private fun runRound(
        gate: TouchGate.Action,
        snapshot: List<MutableList<InkView.Pt>>,
        dataUrl: String?,
    ) {
        // —— 识别段 ——
        val recognized = HandwritingRecognition.recognize(
            local = {
                if (recognizer.brokenForever) throw IllegalStateException("local_recognition_unavailable")
                recognizer.recognize(snapshot)
            },
            unavailable = { recognizeUnavailable(dataUrl, snapshot.size) }
        ) ?: return

        // —— 误触闸第二级：低笔画只认短而明确的结果 ——
        if (gate == TouchGate.Action.LOCAL_ONLY && !TouchGate.acceptLowStroke(recognized)) {
            return  // 静默丢弃，不打扰
        }

        // —— 空识别固定回文（不烧模型） ——
        if (recognized.isEmpty()) {
            uiWriteAndWait { done ->
                book.writeAnimated("book", Texts.EMPTY_RECOG_REPLY, true, done)
            }
            return
        }

        // —— /new 手写指令 ——
        if (NewCommand.isNewCommand(recognized)) {
            memory.clear()
            ui.post { startNewSession() }
            uiWriteAndWait { done ->
                clearWritingSurface()
                book.resetBook(Texts.NEW_SESSION_REPLY, done)
                store.clear()
            }
            return
        }

        // —— 大模型未配置：兜底提示，不烧请求、不崩 ——
        val subscription = prefs.getString("model_provider", "anthropic") == "subscription"
        val provider: ModelProvider = if (subscription) SubscriptionModelProvider(
            SubscriptionGateway.get(this), prefs.getString("subscription_model", "") ?: ""
        ) else AnthropicModelProvider(modelConfig())
        if (!provider.isReady()) {
            ui.post { blinkSealTwice() }   // 印章闪两下，提示去设置填 API
            uiWriteAndWait { done ->
                book.writeAnimated("fail", if (subscription) "请在设置中登录 ChatGPT 并选择订阅模型。" else ModelConfig.NOT_CONFIGURED_TIP, false, done)
            }
            return
        }

        // —— 回看历史态下落笔应答：先另起新会话并清掉书页上的旧会话内容，
        //     新问答从干净页开始（旧会话在历史里原样保留，书页与存档不混条）——
        ui.post { leaveHistoryViewIfNeeded() }

        // —— 回显 + 大模型文言回复（重试 1 次） ——
        ui.post { book.appendInstant("you", "你写道：$recognized") }
        val reply = try {
            provider.reply(memory.history(), recognized)
        } catch (t: Throwable) {
            uiWriteAndWait { done -> failOnPage(done) }
            return
        }
        memory.remember(recognized, reply)
        recordTurn(recognized, reply)   // 记入当前会话历史并落盘
        uiWriteAndWait { done -> book.writeAnimated("book", reply, true, done) }
    }

    /** Missing local recognizer uses vision only when the user selected the subscription route. */
    private fun recognizeUnavailable(dataUrl: String?, strokeCount: Int): String? {
        if (prefs.getString("model_provider", "anthropic") != "subscription") {
            fallbackRound(dataUrl, strokeCount)
            return null
        }
        val provider = SubscriptionModelProvider(SubscriptionGateway.get(this), prefs.getString("subscription_model", "") ?: "")
        if (!provider.isReady()) {
            uiWriteAndWait { done -> book.writeAnimated("fail", "请在设置中登录 ChatGPT、获取模型并点击使用所选模型，方可识别此机手写。", false, done) }
            return null
        }
        if (dataUrl == null) {
            uiWriteAndWait { done -> book.writeAnimated("fail", "未取得本次笔迹图片，请重新书写。", false, done) }
            return null
        }
        return try {
            provider.recognizeInk(dataUrl)
        } catch (e: Exception) {
            val detail = if (e is com.opc.baize.subscription.SubscriptionProtocol.Failure)
                e.code + if (e.status != 0) "（HTTP ${e.status}）" else ""
                else e.javaClass.simpleName
            uiWriteAndWait { done -> book.writeAnimated("fail", "订阅手写识别未完成：$detail。请检查网络与所选模型的图片能力。", false, done) }
            null
        }
    }

    /** 降级轮：POST 自建 /api/ask，直接用它的 reply（跳过本地大模型调用） */
    private fun fallbackRound(dataUrl: String?, strokeCount: Int) {
        if (dataUrl == null) return
        // 本机识别不可用且没配识别服务地址：写明确指引而不是笼统的"没看清"，
        // 否则无 GMS 的设备上用户永远不知道该去设置里填什么。
        if (fallbackUrl().isBlank()) {
            uiWriteAndWait { done ->
                setStatus("error")
                book.writeAnimated("fail", Texts.NO_RECOG_TIP, false, done)
            }
            return
        }
        val resp = try {
            fallback.ask(fallbackUrl(), dataUrl, strokeCount)
        } catch (t: Throwable) {
            uiWriteAndWait { done -> failOnPage(done) }
            return
        }
        when {
            !resp.ok -> uiWriteAndWait { done -> failOnPage(done) }
            resp.ignored -> return  // 服务端误触闸：静默
            resp.command == "new" || NewCommand.isNewCommand(resp.recognized) -> {
                memory.clear()
                ui.post { startNewSession() }
                uiWriteAndWait { done ->
                    clearWritingSurface()
                    book.resetBook(Texts.NEW_SESSION_REPLY, done)
                    store.clear()
                }
            }
            else -> {
                // 本地记忆同步跟一轮：ML Kit 若下轮恢复，语境不断档
                if (resp.recognized.isNotEmpty()) {
                    memory.remember(resp.recognized, resp.reply)
                    ui.post { leaveHistoryViewIfNeeded() }
                    recordTurn(resp.recognized, resp.reply)
                }
                uiWriteAndWait { done ->
                    if (resp.recognized.isNotEmpty()) {
                        book.appendInstant("you", "你写道：${resp.recognized}")
                    }
                    book.writeAnimated("book", resp.reply, true, done)
                }
            }
        }
    }

    /** 失败文案：接着当前页写，26dp 灰墨，不入存档 */
    private fun failOnPage(done: Runnable) {
        setStatus("error")
        book.writeAnimated("fail", Texts.FAIL_TEXT, false, done)
    }

    /** 在 UI 线程发起书写并阻塞工作线程直到写完/被打断——保证串行 */
    private fun uiWriteAndWait(block: (Runnable) -> Unit) {
        val latch = CountDownLatch(1)
        ui.post { block(Runnable { latch.countDown() }) }
        try { latch.await() } catch (ie: InterruptedException) { Thread.currentThread().interrupt() }
    }

    // ---------- 长按左上角：清空全书 ----------

    /** 清空当前书写层：直写层活着清 HWEditView，否则清 InkView。 */
    private fun clearWritingSurface() {
        val layer = hwLayer
        if (layer != null) layer.clear() else ink.clearAllInk()
    }

    private fun doClearAll() {
        cancelIdleTimer()
        refresh.exitWriting()   // 清屏前切回高质量
        clearWritingSurface()
        if (hwActive()) { idleDetector.reset(); pollWasGrowing = false }  // 清墨后复位指纹基线
        store.clear()
        memory.clear()
        startNewSession()       // 清空全书 = 另起新会话（历史里旧会话仍保留可回看）
        setStatus("idle")
        book.resetBook(Texts.INTRO_TEXT)
    }

    // ---------- 会话历史：记录 / 新开 / 回看 ----------

    private fun newSessionId(): String = "s_${System.currentTimeMillis()}"

    /**
     * 记一轮问答进当前会话并整表落盘（可能从工作线程调用 → 统一 post 到 UI 线程串行修改，
     * 避免与面板读取 allSessions 竞争）。首轮把当前会话推入 allSessions，之后就地刷新该条。
     */
    private fun recordTurn(question: String, reply: String) {
        if (question.isEmpty()) return
        ui.post {
            val firstTurn = curTurns.isEmpty()
            curTurns.add(Turn(question, reply))
            val snapshot = Session(curSessionId, curSessionTs, curTurns.toList())
            if (firstTurn) {
                allSessions.add(snapshot)
            } else {
                val idx = allSessions.indexOfLast { it.id == curSessionId }
                if (idx >= 0) allSessions[idx] = snapshot else allSessions.add(snapshot)
            }
            sessionStore.save(allSessions)
        }
    }

    /** 另起新会话：清空当前轮缓冲，换新 id/时间戳，退出回看态。 */
    private fun startNewSession() {
        curTurns.clear()
        curSessionId = newSessionId()
        curSessionTs = System.currentTimeMillis()
        viewingHistory = false
        viewingSessionId = null
    }

    /**
     * 回看态下落笔应答的前置（UI 线程）：另起新会话并把书页清成空白页，
     * 旧会话内容不混进新会话的书页与 pages.json 存档。非回看态无操作。
     */
    private fun leaveHistoryViewIfNeeded() {
        if (!viewingHistory) return
        startNewSession()
        book.clearAllPages()
    }

    /**
     * 删除一条历史会话（历史面板的删除按钮触发，已经过确认弹窗）。
     * 从 allSessions 移除并整表落盘；若删的是正在回看的会话或当前进行中的会话，
     * 书页上还铺着它的内容——一并清场回引导语，别让已删内容留在纸上。
     */
    private fun deleteSession(s: Session) {
        allSessions.removeAll { it.id == s.id }
        sessionStore.save(allSessions)
        if (s.id == viewingSessionId || s.id == curSessionId) {
            memory.clear()
            store.clear()
            clearWritingSurface()
            if (hwActive()) { idleDetector.reset(); pollWasGrowing = false }
            startNewSession()
            setStatus("idle")
            book.resetBook(Texts.INTRO_TEXT)
        }
    }

    /**
     * 回看某历史会话（只读铺进书页）。回看态下继续书写 → runRound 里检测 viewingHistory
     * 先 startNewSession + 清书页，新问答记入新会话，旧会话保持不变（策略简单一致）。
     */
    private fun openHistorySession(s: Session) {
        cancelIdleTimer()
        refresh.exitWriting()
        clearWritingSurface()
        if (hwActive()) { idleDetector.reset(); pollWasGrowing = false }
        memory.clear()
        viewingHistory = true
        viewingSessionId = s.id
        // Turn 列表铺成书页：you=问、book=答（复用 BookView.loadFromStore 的页结构）
        val pages = mutableListOf<List<Pair<String, String>>>()
        for (t in s.turns) {
            val blocks = mutableListOf<Pair<String, String>>()
            if (t.question.isNotEmpty()) blocks.add("you" to "你写道：${t.question}")
            if (t.reply.isNotEmpty()) blocks.add("book" to t.reply)
            if (blocks.isNotEmpty()) pages.add(blocks)
        }
        if (pages.isEmpty()) book.resetBook(Texts.INTRO_TEXT)
        else book.loadFromStore(pages)
        setStatus("idle")
        store.save(book.snapshotForStore())   // 让重启也停在回看内容上
    }

    // ================= 半屏面板（印章双击 / 长按右上角触发） =================
    // 墨水屏友好：无花哨动画、暖纸底、高对比。用一个底部对齐的 Dialog 承载半屏内容，
    // 顶部三分段 tab【历史】【设置】【关于】切换。避免引入 Material 依赖，纯代码搭。

    /** 当前打开的面板 Dialog（重开先关旧的，避免叠加）。 */
    private var panelDialog: AlertDialog? = null

    private val paperBg = 0xFFF6F1E4.toInt()
    private val inkDark = 0xFF1C1A16.toInt()
    private val inkGray = 0xFF6B6355.toInt()
    private val inkFaint = 0xFFA39A87.toInt()
    private val hairline = 0xFFD8CFB8.toInt()

    private fun pxOf(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 弹出半屏面板并切到指定 tab。半屏 = 屏高 62%，底部对齐（BottomSheet 观感）。
     * @param tab 见 TAB_HISTORY / TAB_SETTINGS / TAB_ABOUT
     */
    @SuppressLint("SetTextI18n")
    private fun showPanel(tab: Int) {
        panelDialog?.dismiss()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paperBg)
        }

        // 顶部把手 + 分段 tab
        val handle = View(this).apply { setBackgroundColor(hairline) }
        root.addView(handle, LinearLayout.LayoutParams(pxOf(44f), pxOf(4f)).apply {
            topMargin = pxOf(10f); gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = pxOf(8f)
        })

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pxOf(16f), 0, pxOf(16f), pxOf(8f))
        }
        val body = FrameLayout(this)

        fun selectTab(t: Int) {
            for (i in 0 until tabBar.childCount) {
                val tv = tabBar.getChildAt(i) as TextView
                val active = i == t
                tv.setTextColor(if (active) inkDark else inkFaint)
                tv.setBackgroundColor(if (active) 0xFFEDE6D3.toInt() else 0x00000000)
                tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            body.removeAllViews()
            body.addView(when (t) {
                TAB_SETTINGS -> buildSettingsPane()
                TAB_ABOUT -> buildAboutPane()
                else -> buildHistoryPane()
            })
        }

        val labels = arrayOf("历史", "设置", "关于")
        for (i in labels.indices) {
            val tv = TextView(this).apply {
                text = labels[i]
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, pxOf(10f), 0, pxOf(10f))
                setOnClickListener { selectTab(i) }
            }
            tabBar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(hairline) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = AlertDialog.Builder(this).setView(root).create()
        panelDialog = dialog
        dialog.setOnDismissListener { if (panelDialog === dialog) panelDialog = null }
        dialog.show()
        // 底部对齐、半屏高、铺满宽（BottomSheet 观感）
        dialog.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(paperBg))
            val lp = w.attributes
            lp.gravity = Gravity.BOTTOM
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = (resources.displayMetrics.heightPixels * 0.62f).toInt()
            w.attributes = lp
        }
        selectTab(tab)
    }

    // ---------- 面板：滚动容器工具 ----------

    private fun scrollPane(content: View): View =
        android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(inkDark)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(inkFaint)
    }

    // ---------- 面板【设置】 ----------

    @SuppressLint("SetTextI18n")
    private fun buildSettingsPane(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pxOf(22f), pxOf(16f), pxOf(22f), pxOf(24f))
        }
        fun gap(dp: Float) = col.addView(View(this), LinearLayout.LayoutParams(1, pxOf(dp)))

        // (a) 字体设置 —— 字号滑杆
        col.addView(sectionTitle("字体"))
        gap(8f)
        val sizeLabel = TextView(this).apply {
            textSize = 13f; setTextColor(inkGray)
            text = "回复字号：${fontSizeDp().toInt()}"
        }
        col.addView(sizeLabel)
        val slider = android.widget.SeekBar(this).apply {
            max = 24                       // 24..48 → 0..24
            progress = (fontSizeDp().toInt() - 24).coerceIn(0, 24)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    val size = 24 + p
                    sizeLabel.text = "回复字号：$size"
                    prefs.edit().putFloat("font_size", size.toFloat()).apply()
                    applyFontToBook()   // 即时生效
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        col.addView(slider)
        gap(12f)

        // 三款内置手写字体单选
        col.addView(hint("手写字体（回复正文）"))
        val group = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val fonts = listOf(
            "龙藏" to FONT_LONGCANG,
            "马善政" to FONT_MASHAN,
            "智莽行" to FONT_ZHIMANG,
        )
        val curFont = fontAsset()
        fonts.forEachIndexed { i, (name, asset) ->
            val rb = android.widget.RadioButton(this).apply {
                text = name; textSize = 15f; id = i + 1
                isChecked = asset == curFont
                setOnClickListener {
                    prefs.edit().putString("font_asset", asset).apply()
                    applyFontToBook()   // 即时换字体
                }
            }
            group.addView(rb)
        }
        col.addView(group)
        gap(20f)

        // (a2) 书写 —— 停笔识别延迟档位（写完停笔多久自动识别；不同人写字快慢不同）
        col.addView(sectionTitle("书写"))
        gap(8f)
        val curIdle = idleMs()
        val idleIdx0 = IDLE_STEPS.indexOfFirst { it == curIdle }.let { if (it < 0) 2 else it }
        val idleLabel = TextView(this).apply {
            textSize = 13f; setTextColor(inkGray)
            text = "停笔识别延迟：${IDLE_STEPS[idleIdx0]}ms"
        }
        col.addView(idleLabel)
        col.addView(hint("写完抬笔后，停顿多久自动识别。写字慢多停顿→调大；想更快→调小。"))
        val idleSlider = android.widget.SeekBar(this).apply {
            max = IDLE_STEPS.size - 1
            progress = idleIdx0
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    val ms = IDLE_STEPS[p]
                    idleLabel.text = "停笔识别延迟：${ms}ms"
                    prefs.edit().putLong("idle_ms", ms).apply()
                    idleDetector.setThreshold(ms)   // 即时生效，无需重启
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        col.addView(idleSlider)
        gap(20f)

        col.addView(View(this).apply { setBackgroundColor(hairline) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        gap(16f)

        col.addView(android.widget.Button(this).apply {
            text = "PDF 练习工作区"
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, com.opc.baize.paper.PaperActivity::class.java)) }
        })
        col.addView(sectionTitle("模型通道"))
        val providerChoices = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("原 API（Anthropic 兼容）", "ChatGPT 订阅"))
            setSelection(if (prefs.getString("model_provider", "anthropic") == "subscription") 1 else 0)
        }
        providerSelector = providerChoices
        col.addView(providerChoices)
        col.addView(android.widget.Button(this).apply {
            text = "ChatGPT 登录与模型选择"
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, SubscriptionSettingsActivity::class.java)) }
        })
        col.addView(hint("订阅会话仅在本次进程中保留。切换原 API 后沿用下方配置，不自动跨通道扣费。"))
        gap(12f)

        // (b) 大模型 API 设置
        col.addView(sectionTitle("大模型 API"))
        gap(8f)

        val keyEdit = android.widget.EditText(this).apply {
            setSingleLine()   // 先设单行——放在 inputType 之前，否则会清掉密码属性
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // 默认掩码显示（老板 2026-07-08：勾"显示密钥"前必须是密文）。
            // 用 transformationMethod 显式控制，比只靠 inputType 稳（不受 setSingleLine 干扰）。
            transformationMethod =
                android.text.method.PasswordTransformationMethod.getInstance()
            setText(prefs.getString("api_key", "") ?: "")
            hint = "sk-xxxxxx（在 DeepSeek 开放平台获取）"
        }
        col.addView(TextView(this).apply { text = "API 密钥"; textSize = 13f; setTextColor(inkGray) })
        col.addView(keyEdit)
        val showKey = android.widget.CheckBox(this).apply {
            text = "显示密钥"; textSize = 13f
            isChecked = false   // 默认不勾 → 密文
            setOnCheckedChangeListener { _, checked ->
                keyEdit.transformationMethod = if (checked) null
                    else android.text.method.PasswordTransformationMethod.getInstance()
                keyEdit.setSelection(keyEdit.text.length)
            }
        }
        col.addView(showKey)
        gap(8f)

        col.addView(TextView(this).apply { text = "访问节点"; textSize = 13f; setTextColor(inkGray) })
        val endpointEdit = android.widget.EditText(this).apply {
            setText(prefs.getString("api_endpoint", ModelConfig.DEFAULT_ENDPOINT)
                ?: ModelConfig.DEFAULT_ENDPOINT)
            setSingleLine()
        }
        col.addView(endpointEdit)
        gap(8f)

        col.addView(TextView(this).apply { text = "模型名字"; textSize = 13f; setTextColor(inkGray) })
        val modelEdit = android.widget.EditText(this).apply {
            setText(prefs.getString("api_model", ModelConfig.DEFAULT_MODEL)
                ?: ModelConfig.DEFAULT_MODEL)
            setSingleLine()
        }
        col.addView(modelEdit)
        gap(6f)
        col.addView(hint("兼容 Anthropic Messages API 的服务均可（DeepSeek/其他）。"))
        gap(20f)

        col.addView(View(this).apply { setBackgroundColor(hairline) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        gap(16f)

        // (c) 手写识别降级通道（可选）：无 Google 服务的设备（华为等）本机识别不可用，
        //     可自建 server.py 识别服务并在此填地址；留空 = 只用本机 ML Kit。
        col.addView(sectionTitle("手写识别（可选）"))
        gap(8f)
        col.addView(TextView(this).apply { text = "识别服务地址"; textSize = 13f; setTextColor(inkGray) })
        val fallbackEdit = android.widget.EditText(this).apply {
            setText(prefs.getString("fallback_url", BuildConfig.DEFAULT_FALLBACK_ASK_URL)
                ?: BuildConfig.DEFAULT_FALLBACK_ASK_URL)
            setSingleLine()
            hint = "http://<局域网IP>:<端口>/api/ask（留空=用本机识别）"
        }
        col.addView(fallbackEdit)
        gap(6f)
        col.addView(hint("优先本机识别；本机不可用且已启用 ChatGPT 订阅时，自动用所选模型转写笔迹图片（使用订阅额度）。原 API 用户可自建识别服务。"))
        gap(14f)

        val saveBtn = android.widget.Button(this).apply {
            text = "保存"
            setOnClickListener {
                prefs.edit()
                    .putString("model_provider", if (providerChoices.selectedItemPosition == 1) "subscription" else "anthropic")
                    .putString("api_key", keyEdit.text.toString().trim())
                    .putString("api_endpoint", endpointEdit.text.toString().trim()
                        .ifEmpty { ModelConfig.DEFAULT_ENDPOINT })
                    .putString("api_model", modelEdit.text.toString().trim()
                        .ifEmpty { ModelConfig.DEFAULT_MODEL })
                    .putString("fallback_url", fallbackEdit.text.toString().trim())
                    .apply()
                android.widget.Toast.makeText(this@MainActivity, "已保存", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(saveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return scrollPane(col)
    }

    // ---------- 面板【历史】 ----------

    @SuppressLint("SetTextI18n")
    private fun buildHistoryPane(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pxOf(22f), pxOf(16f), pxOf(22f), pxOf(24f))
        }
        // “新对话”入口（= /new 效果；清场口径与 doClearAll 一致，书写层残墨一并清掉，
        // 否则面板打开前写了一半的墨会被轮询当成新输入提交进刚开的会话）
        val newBtn = android.widget.Button(this).apply {
            text = "＋ 新对话"
            setOnClickListener {
                cancelIdleTimer()
                clearWritingSurface()
                if (hwActive()) { idleDetector.reset(); pollWasGrowing = false }
                startNewSession()
                memory.clear()
                store.clear()
                book.resetBook(Texts.INTRO_TEXT)
                setStatus("idle")
                panelDialog?.dismiss()
            }
        }
        col.addView(newBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(View(this).apply { setBackgroundColor(hairline) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = pxOf(12f)
            })

        val sessions = allSessions.filter { !it.isEmpty() }.sortedByDescending { it.createdAt }
        if (sessions.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "\n尚无历史对话。\n手写提问，墨灵作答后即成一条记录。"
                textSize = 14f; setTextColor(inkFaint); gravity = Gravity.CENTER
            })
            return scrollPane(col)
        }

        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (s in sessions) {
            // 每条 = 水平行：左侧信息区（点击回看，占满余宽）+ 右侧"删"字（点击确认后删除）
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pxOf(4f), pxOf(12f), pxOf(4f), pxOf(12f))
                isClickable = true
                setOnClickListener {
                    openHistorySession(s)
                    panelDialog?.dismiss()
                }
            }
            info.addView(TextView(this).apply {
                text = s.summary()
                textSize = 16f; setTextColor(inkDark); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = "${fmt.format(java.util.Date(s.createdAt))} · ${s.turns.size} 轮"
                textSize = 12f; setTextColor(inkFaint)
            })
            row.addView(info, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val divider = View(this).apply { setBackgroundColor(hairline) }
            val delBtn = TextView(this).apply {
                text = "删"
                textSize = 15f
                setTextColor(inkGray)
                gravity = Gravity.CENTER
                // 大按压区（墨水屏上手指/笔都好点中），视觉只是一个"删"字
                setPadding(pxOf(18f), pxOf(14f), pxOf(10f), pxOf(14f))
                contentDescription = "删除此对话"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除此对话？")
                        .setMessage("「${s.summary()}」\n共 ${s.turns.size} 轮，删除后不可恢复。")
                        .setPositiveButton("删除") { _, _ ->
                            deleteSession(s)
                            // 就地从面板列表里摘掉这一行（无需整页重建）
                            col.removeView(row)
                            col.removeView(divider)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            row.addView(delBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            col.addView(row)
            col.addView(divider,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        return scrollPane(col)
    }

    // ---------- 面板【关于】 ----------

    @SuppressLint("SetTextI18n")
    private fun buildAboutPane(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pxOf(22f), pxOf(16f), pxOf(22f), pxOf(24f))
        }
        fun para(text: String, size: Float = 14f, color: Int = inkGray) = col.addView(
            TextView(this).apply {
                this.text = text; textSize = size; setTextColor(color)
                setPadding(0, pxOf(4f), 0, pxOf(4f))
            }
        )
        para("墨灵 1.0", 20f, inkDark)
        para("栖于墨水屏中的墨中之灵 · 手写问答开源应用")
        para("作者：小西校长")
        para("协议：GPL-3.0")
        para("这是一款自由开源软件（GPL-3.0）。你可以自由使用、研究、修改与再分发，" +
            "衍生作品须同样以 GPL-3.0 开源。完整条款见随附的 LICENSE 文件。")
        para("GitHub：https://github.com/xiaoxi668v-prog/moling", 13f, inkFaint)
        col.addView(View(this).apply { setBackgroundColor(hairline) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = pxOf(12f); bottomMargin = pxOf(12f)
            })
        para("第三方与致谢", 15f, inkDark)
        para("· 手写字体：龙藏 / 马善政 / 智莽行（Google Fonts，SIL Open Font License 1.1）")
        para("· 手写识别：Google ML Kit Digital Ink Recognition")
        para("· 界面框架：AndroidX")
        para("· 本应用手写功能针对华为墨水屏进行优化，仅调用设备既有系统能力，非华为设备手写屏" +
            "自动调用纯自绘 + 运动预测算法，可能略有延时")
        return scrollPane(col)
    }
}

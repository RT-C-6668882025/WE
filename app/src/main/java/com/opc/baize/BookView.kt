package com.opc.baize

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max

/**
 * 书页模型（继承网页版 §3/§4）：
 * - 固定整页呈现、零滚动；一次只画 currentPage；
 * - 内容写满自动翻新页续写（逐字分页：这个字放下去溢出 → 挪到新页首继续写，
 *   视图跟着翻过去）；
 * - 回复的“手写显现”：龙藏体逐字瞬现（~180ms/字，实墨直接出完整墨字）。
 *   二期可复刻网页版的 SVG mask 渐显：Android 侧用 PathMeasure 截取字形
 *   Path 分段 + PorterDuff DST_IN 蒙版逐段显影，本期先保证节奏与分页正确。
 * - 块类型：you（你写道，20dp 灰墨）/ book（回文，38dp 墨黑）/ fail（致歉，26dp 灰墨）。
 *   fail 不入存档（与网页版一致）。
 */
class BookView(context: Context) : View(context) {

    class Block(val kind: String) {
        val sb = StringBuilder()
        // 布局缓存：文本长度或宽度变了才重建
        var cachedLayout: StaticLayout? = null
        var cachedLen = -1
    }

    class Page {
        val blocks = mutableListOf<Block>()
        fun isEmpty() = blocks.isEmpty()
    }

    /** (当前页序号从1起, 总页数) —— 页码角标 */
    var onPageInfo: ((Int, Int) -> Unit)? = null
    /** 翻到头：角标闪一下 */
    var onFlash: (() -> Unit)? = null
    /** 持久化钩子（写档由 Activity 管） */
    var onPersist: (() -> Unit)? = null

    val pages = mutableListOf<Page>()
    var currentPage = -1
        private set
    private var writingPage = -1
    private var revealToken = 0

    private val dp = resources.displayMetrics.density
    private val padL = 30f * dp
    private val padR = 30f * dp
    private val padT = 26f * dp
    private val padB = 14f * dp
    private val blockGap = 26f * dp

    private val handler = Handler(Looper.getMainLooper())

    /** 当前手写体（可运行时切换：龙藏/马善政/智莽行）。默认龙藏，加载失败退 serif。 */
    private var handTypeface: Typeface = loadFontAsset("LongCang-Regular.ttf")

    private fun loadFontAsset(name: String): Typeface = try {
        Typeface.createFromAsset(context.assets, name)
    } catch (t: Throwable) {
        Typeface.create("serif", Typeface.NORMAL)
    }

    /** 回复正文字号（sp/dp 同口径，用户可在设置里 24~48 调；默认 38 保持原观感）。 */
    private var bookSizeDp: Float = 38f

    private fun makePaint(sizeDp: Float, color: Long) = TextPaint().apply {
        isAntiAlias = true
        typeface = handTypeface
        textSize = sizeDp * dp
        this.color = color.toInt()
    }

    private val paintYou = makePaint(20f, 0xFF6B6355)
    private val paintBook = makePaint(38f, 0xFF1C1A16)
    private val paintFail = makePaint(26f, 0xFF6B6355)

    /**
     * 运行时应用字体设置：手写体 + 回复字号。三个 Paint 全部换 typeface，
     * 回复 Paint 换字号；清所有块的布局缓存并重绘（下次 layoutFor 用新 Paint 重建）。
     * 由设置面板保存后即时调用。
     */
    fun applyFont(assetName: String, replySizeDp: Float) {
        handTypeface = loadFontAsset(assetName)
        bookSizeDp = replySizeDp.coerceIn(16f, 64f)
        paintYou.typeface = handTypeface
        paintFail.typeface = handTypeface
        paintBook.typeface = handTypeface
        paintBook.textSize = bookSizeDp * dp
        for (p in pages) for (b in p.blocks) { b.cachedLayout = null; b.cachedLen = -1 }
        requestLayout()
        invalidate()
    }

    private fun paintFor(kind: String) = when (kind) {
        "you" -> paintYou
        "fail" -> paintFail
        else -> paintBook
    }

    private fun spacingFor(kind: String) = if (kind == "book") 1.75f else 1.7f

    // ---------- 布局与分页判定 ----------

    private fun contentWidth(): Int = max(1, (width - padL - padR).toInt())

    private fun availHeight(): Float = height - padT - padB

    private fun layoutFor(b: Block): StaticLayout {
        val cached = b.cachedLayout
        if (cached != null && b.cachedLen == b.sb.length && cached.width == contentWidth()) {
            return cached
        }
        val l = StaticLayout.Builder
            .obtain(b.sb, 0, b.sb.length, paintFor(b.kind), contentWidth())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacingFor(b.kind))
            .setIncludePad(true)
            .build()
        b.cachedLayout = l
        b.cachedLen = b.sb.length
        return l
    }

    private fun pageContentHeight(p: Page): Float {
        var h = 0f
        for (b in p.blocks) h += layoutFor(b).height + blockGap
        return h
    }

    private fun overflows(pi: Int): Boolean {
        val p = pages.getOrNull(pi) ?: return false
        return pageContentHeight(p) > availHeight()
    }

    // ---------- 页操作 ----------

    private fun addPage(): Int {
        pages.add(Page())
        notifyPageInfo()
        return pages.size - 1
    }

    private fun goToPage(i: Int) {
        if (i < 0 || i >= pages.size) return
        currentPage = i
        notifyPageInfo()
        invalidate()
    }

    private fun notifyPageInfo() {
        onPageInfo?.invoke(currentPage + 1, max(1, pages.size))
    }

    /** 两指左滑：下一页；末页再翻 = 开新空白页；已站在空白页 → 闪角标 */
    fun nextPage() {
        if (currentPage < pages.size - 1) { flipTo(currentPage + 1); return }
        if (pages.getOrNull(currentPage)?.isEmpty() == true) { onFlash?.invoke(); return }
        addPage()
        flipTo(pages.size - 1)
    }

    /** 两指右滑：上一页；第一页翻不动 → 闪角标 */
    fun prevPage() {
        if (currentPage > 0) flipTo(currentPage - 1) else onFlash?.invoke()
    }

    /** 翻页 + 空白尾页回收（正在书写的页不回收） */
    fun flipTo(target: Int) {
        if (target < 0 || target >= pages.size) return
        val from = currentPage
        goToPage(target)
        if (from != target && from == pages.size - 1 && from != writingPage &&
            pages.size > 1 && pages[from].isEmpty()) {
            pages.removeAt(from)
            if (currentPage >= pages.size) currentPage = pages.size - 1
            notifyPageInfo()
        }
    }

    // ---------- 内容写入 ----------

    /** “你写道：…”瞬显块：追加到末页已有内容之下，整块放不下就整块挪新页 */
    fun appendInstant(kind: String, text: String) {
        if (pages.isEmpty()) addPage()
        var pi = pages.size - 1
        var b = Block(kind).also { it.sb.append(text) }
        pages[pi].blocks.add(b)
        if (overflows(pi) && pages[pi].blocks.size > 1) {
            pages[pi].blocks.remove(b)
            pi = addPage()
            b = Block(kind).also { it.sb.append(text) }
            pages[pi].blocks.add(b)
        }
        goToPage(pi)
        onPersist?.invoke()
    }

    /**
     * 逐字“手写显现”：每字约 180ms 实墨瞬现，从页顶往下写、写满自动翻页跟随。
     * onNewPage=true 固定从新的一页开写（末页本就空白则直接用）。
     * onDone 无论写完还是被打断（revealToken 变化）都必然回调——
     * 提交队列靠它闭环，不许悬空。
     */
    fun writeAnimated(kind: String, text: String, onNewPage: Boolean, onDone: Runnable? = null) {
        val my = revealToken
        if (pages.isEmpty()) addPage()
        var pi = pages.size - 1
        if (onNewPage && !pages[pi].isEmpty()) pi = addPage()
        goToPage(pi)
        writingPage = pi
        var block = Block(kind).also { pages[pi].blocks.add(it) }

        // 按码点切分，别劈开代理对（生僻字）
        val chars = mutableListOf<String>()
        var idx = 0
        while (idx < text.length) {
            val cp = text.codePointAt(idx)
            val n = Character.charCount(cp)
            chars.add(text.substring(idx, idx + n))
            idx += n
        }

        var ci = 0
        val step = object : Runnable {
            override fun run() {
                if (my != revealToken) { onDone?.run(); return }  // 被清空/新篇打断
                if (ci >= chars.size) {
                    writingPage = -1
                    onPersist?.invoke()
                    onDone?.run()
                    return
                }
                val ch = chars[ci]; ci++
                if (ch == "\n") {
                    block.sb.append('\n')
                    block.cachedLen = -1
                    onPersist?.invoke()          // 每段落定即存
                    invalidate()
                    handler.postDelayed(this, 100)
                    return
                }
                block.sb.append(ch)
                block.cachedLen = -1
                if (overflows(pi) && !(pages[pi].blocks.size == 1 && block.sb.length == ch.length)) {
                    // 这个字放下去溢出 → 挪到新页首继续写（页上只有这一个字则不算，防死循环）
                    block.sb.setLength(block.sb.length - ch.length)
                    block.cachedLen = -1
                    if (block.sb.isEmpty()) pages[pi].blocks.remove(block)
                    pi = addPage()
                    writingPage = pi
                    block = Block(kind).also { pages[pi].blocks.add(it) }
                    block.sb.append(ch)
                    goToPage(pi)                 // 书写跟随笔尖
                }
                invalidate()
                handler.postDelayed(this, 180)
            }
        }
        handler.post(step)
    }

    /**
     * 静默清空全书为一张空白页（不写引导语）。回看历史态下落笔另起新会话时用：
     * 把旧会话内容从书页上撤走，新问答从干净页开始，避免新旧会话在书页与存档里混杂。
     */
    fun clearAllPages() {
        revealToken++
        writingPage = -1
        pages.clear()
        addPage()
        goToPage(0)
        invalidate()
        onPersist?.invoke()
    }

    /** 清空全书 → 单页 → 引导语走书写节奏（长按清空 / new 指令共用） */
    fun resetBook(introText: String, onDone: Runnable? = null) {
        clearAllPages()
        writeAnimated("book", introText, false, onDone)
    }

    // ---------- 存档 ----------

    /** 只存成形内容：you/book；fail 不入档；空页不入档 */
    fun snapshotForStore(): List<List<Pair<String, String>>> {
        val out = mutableListOf<List<Pair<String, String>>>()
        for (p in pages) {
            val bl = p.blocks.filter { it.kind == "you" || it.kind == "book" }
                .map { it.kind to it.sb.toString() }
                .filter { it.second.isNotEmpty() }
            if (bl.isNotEmpty()) out.add(bl)
        }
        return out
    }

    /** 重启恢复：静态重铺所有页，停在最后一页 */
    fun loadFromStore(data: List<List<Pair<String, String>>>) {
        revealToken++
        pages.clear()
        for (pg in data) {
            val pi = addPage()
            for ((k, t) in pg) {
                pages[pi].blocks.add(Block(k).also { it.sb.append(t) })
            }
        }
        if (pages.isEmpty()) addPage()
        goToPage(pages.size - 1)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        val p = pages.getOrNull(currentPage) ?: return
        var y = padT
        for (b in p.blocks) {
            val l = layoutFor(b)
            canvas.save()
            canvas.translate(padL, y)
            l.draw(canvas)
            canvas.restore()
            y += l.height + blockGap
        }
    }
}

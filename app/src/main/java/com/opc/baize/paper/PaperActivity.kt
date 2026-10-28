package com.opc.baize.paper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.*
import com.opc.baize.core.ModelConfig
import com.opc.baize.model.*
import com.opc.baize.subscription.SubscriptionGateway
import com.opc.baize.subscription.SubscriptionProtocol
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/** Offline PDF practice workspace. PDF decoding is delegated to Android PdfRenderer. */
class PaperActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ai = Executors.newSingleThreadExecutor()
    private lateinit var repository: PaperStore
    private lateinit var ink: PaperInkView
    private lateinit var status: TextView
    private lateinit var pageLabel: TextView
    private var document: String? = null
    private var pageIndex = 0
    private var pageCount = 0
    private var renderer: PdfEngine? = null // Accessed only on io executor.
    private var renderedDocument: String? = null
    private var loading = false
    @Volatile private var destroyed = false
    private var submitting = false
    private val prefs by lazy { getSharedPreferences("moling", MODE_PRIVATE) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); repository = PaperRepository(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        fun row() = LinearLayout(this).also { root.addView(it) }
        fun button(row: LinearLayout, text: String, call: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 12f; setOnClickListener { call() }
            row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val top = row()
        button(top, "导入 PDF") {
            if (!loading) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 51)
        }
        button(top, "上一页") { if (!loading && pageIndex > 0) document?.let { load(it, pageIndex - 1) } }
        button(top, "下一页") { if (!loading && pageIndex + 1 < pageCount) document?.let { load(it, pageIndex + 1) } }
        button(top, "返回") { finish() }
        pageLabel = TextView(this).apply { text = "本地 PDF 练习 · 笔迹自动独立保存" }; root.addView(pageLabel)
        val tools = row()
        button(tools, "书写") { ink.mode = PaperInkView.Mode.WRITE; status.text = "书写模式" }
        button(tools, "移动") { ink.mode = PaperInkView.Mode.MOVE; status.text = "拖动页面；± 调整缩放" }
        button(tools, "选区") { ink.mode = PaperInkView.Mode.SELECT; status.text = "拖出矩形区域，再手动提交" }
        button(tools, "撤销") { if (!loading) ink.undo() }
        button(tools, "−") { ink.zoom(0.8f) }; button(tools, "+") { ink.zoom(1.25f) }
        ink = PaperInkView(this)
        root.addView(ink, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        ink.changed = { data ->
            val id = document; val page = pageIndex
            if (id != null) io.execute {
                try { repository.save(id, page, data) }
                catch (e: Exception) { message("笔迹保存失败，请暂勿退出或翻页。") }
            }
        }
        val actions = row()
        button(actions, "局部批改") { submit("请用现代中文批改图中的手写作答：指出正确与错误、理由和正确解法；看不清或缺题干时明确说明。") }
        button(actions, "选区解释") { submit("请用现代中文解释图中选区的题目与知识点，给出清晰步骤；信息不足时明确说明。") }
        button(actions, "批改记录") {
            val id = document; val index = pageIndex
            if (id != null) io.execute {
                try {
                    val records = repository.results(id, index)
                    runOnUiThread { if (!destroyed) {
                        if (records.isEmpty()) status.text = "本页暂无批改记录。"
                        else AlertDialog.Builder(this).setTitle("本页批改记录")
                            .setItems(records.map { "版本 ${it.getLong("sourceRevision")} · ${java.util.Date(it.getLong("createdAt"))}" }.toTypedArray()) { _, which ->
                                AlertDialog.Builder(this).setTitle("源笔迹版本 ${records[which].getLong("sourceRevision")}")
                                    .setMessage(records[which].getString("answer")).setPositiveButton("关闭", null).show()
                            }.show()
                    } }
                } catch (e: Exception) { message("批改记录读取失败，文件已保留。") }
            }
        }
        status = TextView(this).apply { text = "可离线书写。未选区时提交整页；仅手动提交才调用模型。"; textSize = 14f }; root.addView(status)
        prefs.getString("paper_last_document", null)?.let { load(it, prefs.getInt("paper_last_page", 0)) }
    }
    @Deprecated("Platform result callback retained to avoid introducing an Activity framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 51 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (loading) return
        loading = true; ink.isEnabled = false; status.text = "正在导入本地副本……"
        io.execute {
            try {
                val id = repository.importPdf(uri)
                runOnUiThread { if (!destroyed) { loading = false; load(id, 0) } }
            } catch (e: Exception) { runOnUiThread { if (!destroyed) { loading = false; ink.isEnabled = true; status.text = "PDF 导入失败，请检查文件格式或大小（最多 100MB）。" } } }
        }
    }
    private fun load(id: String, requestedPage: Int) {
        if (loading || destroyed) return
        loading = true; ink.isEnabled = false; status.text = "正在读取 PDF 和笔迹……"
        io.execute {
            try {
                if (renderedDocument != id) {
                    renderer?.close(); renderer = null; renderedDocument = null
                    renderer = AndroidPdfEngine(repository.pdf(id))
                    renderedDocument = id
                }
                val reader = requireNotNull(renderer); val count = reader.pageCount; require(count > 0)
                val index = requestedPage.coerceIn(0, count - 1)
                val rendered = reader.render(index)
                val result = try { rendered.bitmap to repository.load(id, index, rendered.width, rendered.height) }
                catch (e: Exception) { rendered.bitmap.recycle(); throw e }
                runOnUiThread {
                    if (destroyed) { result.first.recycle(); return@runOnUiThread }
                    document = id; pageIndex = index; pageCount = count
                    ink.load(result.first, result.second); ink.isEnabled = true; loading = false
                    pageLabel.text = "第 ${index + 1} / $count 页 · 笔迹自动保存"
                    prefs.edit().putString("paper_last_document", id).putInt("paper_last_page", index).apply()
                    status.text = "已恢复笔迹。可离线书写，手动提交选区或整页。"
                }
            } catch (e: Exception) { runOnUiThread { if (!destroyed) {
                loading = false; ink.isEnabled = true
                status.text = "无法打开此页：文件可能加密、损坏，或笔迹文件不匹配。原文件已保留。"
            } } }
        }
    }
    private fun submit(prompt: String) {
        if (loading || document == null || submitting) return
        if (prefs.getString("model_provider", "anthropic") != "subscription") {
            status.text = "图片批改请先在墨灵设置中启用 ChatGPT 订阅。原 API 适配器目前只支持文字。"; return
        }
        val provider = SubscriptionModelProvider(SubscriptionGateway.get(this), prefs.getString("subscription_model", "") ?: "")
        if (!provider.isReady()) { status.text = "请返回墨灵设置登录 ChatGPT 并选择模型。"; return }
        val (bitmap, data, region) = ink.snapshot()
        val id = document!!; val index = pageIndex
        val regionJson = JSONObject().put("left", region.left).put("top", region.top).put("right", region.right).put("bottom", region.bottom).toString()
        submitting = true; status.text = "正在提交第 ${index + 1} 页版本 ${data.revision}；可继续书写。"
        val snapshotName = "submission-${java.util.UUID.randomUUID()}"
        val snapshotSave = io.submit { repository.submission(id, index, snapshotName, data) }
        ai.execute {
            try {
                snapshotSave.get()
                val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                val image = "data:image/png;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
                val answer = provider.reply(emptyList(), prompt, image)
                // Store the exact source vectors and metadata independently of the editable current page.
                io.execute {
                    try { repository.result(id, index, data.revision, regionJson, answer, snapshotName) }
                    catch (e: Exception) { message("已收到批改，但结果存储失败；请保留页面内容。") }
                }
                runOnUiThread { if (!destroyed) {
                    status.text = "批改完成：第 ${index + 1} 页版本 ${data.revision}。后续笔迹不属于本次批改。"
                    AlertDialog.Builder(this).setTitle("批改 / 解释结果").setMessage(answer).setPositiveButton("关闭", null).show()
                } }
            } catch (e: Exception) {
                val code = (e as? SubscriptionProtocol.Failure)?.code ?: "request_failed"
                message("提交未完成：$code。笔迹保留，可继续书写。")
            } finally { bitmap.recycle(); runOnUiThread { submitting = false } }
        }
    }
    private fun message(text: String) = runOnUiThread { if (!destroyed) status.text = text }
    override fun onDestroy() {
        destroyed = true
        // Submitted work may finish saving its captured revision after this screen closes.
        ai.execute {
            io.execute { renderer?.close(); renderer = null }
            io.shutdown()
        }
        ai.shutdown()
        ink.release(); super.onDestroy()
    }
}

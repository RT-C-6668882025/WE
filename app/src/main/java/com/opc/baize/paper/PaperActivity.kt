@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
package com.opc.baize.paper

import com.opc.baize.agent.*
import cc.noharry.agent.kernel.api.*
import kotlinx.coroutines.*
import java.util.UUID
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
    private val io = PaperIo.executor
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var studyJob: Job? = null
    @Volatile private var studyRuntime: AgentRuntime? = null
    private var readyPdf: java.io.File? = null
    private var toolsVisible = false
    private val extraTools = mutableListOf<android.view.View>()
    private var autoStudy = false
    private var sourcePath: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoRecognize = false
    private var interactionVersion = 0L
    private var workspaceId: String? = null
    private val recognizeLater = Runnable {
        if (!submitting && studyJob?.isActive != true && !loading && !destroyed) submit(RECOGNIZE, true)
    }
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
        button(top, "工具") { toolsVisible = !toolsVisible; extraTools.forEach { it.visibility = if (toolsVisible) android.view.View.VISIBLE else android.view.View.GONE } }
        button(top, "返回") { finish() }
        pageLabel = TextView(this).apply { text = "本地 PDF 练习 · 笔迹自动独立保存" }; root.addView(pageLabel)
        val tools = row().apply { visibility = android.view.View.GONE }; extraTools += tools
        button(tools, "书写") { ink.mode = PaperInkView.Mode.WRITE; status.text = "书写模式" }
        button(tools, "移动") { ink.mode = PaperInkView.Mode.MOVE; status.text = "拖动页面；± 调整缩放" }
        button(tools, "选区") { ink.mode = PaperInkView.Mode.SELECT; status.text = "拖出矩形区域，再手动提交" }
        button(tools, "撤销") { if (!loading) ink.undo() }
        button(tools, "−") { ink.zoom(0.8f) }; button(tools, "+") { ink.zoom(1.25f) }
        ink = PaperInkView(this)
        root.addView(ink, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        ink.changed = { data ->
            handler.removeCallbacks(recognizeLater)
            if (autoRecognize) handler.postDelayed(recognizeLater, 1800)
            val id = document; val page = pageIndex
            if (id != null) io.execute {
                try { repository.save(id, page, data) }
                catch (e: Exception) { message("笔迹保存失败，请暂勿退出或翻页。") }
            }
        }
        ink.strokeStarted = { interactionVersion++; handler.removeCallbacks(recognizeLater) }
        val requestRow = row()
        val request = EditText(this).apply { hint = "写下需求，或直接在纸上写、画"; textSize = 16f }
        requestRow.addView(request, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        button(requestRow, "完成需求") {
            val text = request.text.toString().trim()
            if (text.isNotBlank() && !loading && !submitting && studyJob?.isActive != true) {
                study(text, "用户在学习页输入的需求"); request.text.clear()
            }
        }
        button(requestRow, "查看生成页") {
            val workspace = workspaceId?.let { id -> WorkspaceFiles(this).list().find { it.id == id } }
            val pdf = readyPdf ?: workspace?.let { LearningTurn.latestReady(it) }
            if (pdf != null && workspace != null) openGenerated(pdf, workspace.id, LearningTurn.sourcePath(pdf))
        }
        button(requestRow, "停止") { interactionVersion++; studyJob?.cancel(); autoRecognize = false; handler.removeCallbacks(recognizeLater); status.text = "已停止自动继续，原页保留。" }
        val recognition = row()
        button(recognition, "识别选区 / 整页") { submit(RECOGNIZE) }
        recognition.addView(Switch(this).apply {
            text = "停笔后自动继续"; isChecked = intent.getBooleanExtra("autoStudy", false); autoRecognize = isChecked; setOnCheckedChangeListener { _, enabled ->
                autoRecognize = enabled; interactionVersion++; handler.removeCallbacks(recognizeLater)
                status.text = if (enabled) "停笔后理解反馈并更新学习页，使用订阅额度。" else "已关闭停笔识别。"
            }
        })
        recognition.addView(Switch(this).apply {
            text = "仅笔书写"; setOnCheckedChangeListener { _, enabled -> ink.stylusOnly = enabled }
        })
        val actions = row().apply { visibility = android.view.View.GONE }; extraTools += actions
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
        status = TextView(this).apply { text = "可离线书写；自动继续开启时，停笔后使用订阅理解并生成页面。"; textSize = 14f }; root.addView(status)
        val generated = intent.getStringExtra("generatedPdf")
        workspaceId = intent.getStringExtra("workspace")
        autoStudy = intent.getBooleanExtra("autoStudy", false)
        if (generated != null && workspaceId != null) {
            openGenerated(java.io.File(generated), workspaceId!!, intent.getStringExtra("sourcePath"))
        } else prefs.getString("paper_last_document", null)?.let { load(it, prefs.getInt("paper_last_page", 0)) }
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
        handler.removeCallbacks(recognizeLater)
        interactionVersion++
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
                    workspaceId = prefs.getString("paper_workspace_$id", null)
                    sourcePath = prefs.getString("paper_source_$id", null)
                    autoStudy = workspaceId != null
                    ink.load(result.first, result.second); ink.isEnabled = true; loading = false
                    pageLabel.text = "第 ${index + 1} / $count 页 · 笔迹自动保存"
                    prefs.edit().putString("paper_last_document", id).putInt("paper_last_page", index).apply()
                    status.text = if (autoRecognize) "写下答案或修改要求，停笔后自动继续。" else "已恢复笔迹。可离线书写。"
                }
            } catch (e: Exception) { runOnUiThread { if (!destroyed) {
                loading = false; ink.isEnabled = true
                status.text = "无法打开此页：文件可能加密、损坏，或笔迹文件不匹配。原文件已保留。"
            } } }
        }
    }
    private fun submit(prompt: String, automatic: Boolean = false) {
        handler.removeCallbacks(recognizeLater)
        if (loading || document == null || submitting || studyJob?.isActive == true) return
        if (prefs.getString("model_provider", "anthropic") != "subscription") {
            status.text = "图片批改请先在墨灵设置中启用 ChatGPT 订阅。原 API 适配器目前只支持文字。"; return
        }
        val provider = SubscriptionSelection.provider(this, image = true)
        if (!provider.isReady()) { status.text = "请返回墨灵设置登录 ChatGPT 并选择模型。"; return }
        val (bitmap, data, region) = ink.snapshot()
        val id = document!!; val index = pageIndex; val workspace = workspaceId; val interaction = interactionVersion
        val regionJson = JSONObject().put("left", region.left).put("top", region.top).put("right", region.right).put("bottom", region.bottom).toString()
        submitting = true; status.text = "正在提交第 ${index + 1} 页版本 ${data.revision}；可继续书写。"
        val snapshotName = "submission-${java.util.UUID.randomUUID()}"
        val snapshotSave = io.submit { repository.submission(id, index, snapshotName, data) }
        ai.execute {
            try {
                snapshotSave.get()
                val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                val image = "data:image/png;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
                val answer = provider.explainImage(prompt, image)
                // Store the exact source vectors and metadata independently of the editable current page.
                io.execute {
                    try { repository.result(id, index, data.revision, regionJson, answer, snapshotName) }
                    catch (e: Exception) { message("已收到批改，但结果存储失败；请保留页面内容。") }
                }
                runOnUiThread { if (!destroyed) {
                    val unchanged = document == id && pageIndex == index && ink.page.revision == data.revision && interactionVersion == interaction
                    if (autoStudy && workspace != null && unchanged) {
                        study("以下是纸面识别/批改结果。请结合原学习材料理解我的作答或修改意图并继续：\n" + answer.take(24000),
                            "文档 $id，第 ${index + 1} 页，笔迹版本 ${data.revision}，区域 $regionJson")
                    } else if (automatic) {
                        status.text = "识别已保存；页面已有新笔迹，等待下一次停笔后继续。"
                    } else {
                        status.text = "识别已保存。"
                        AlertDialog.Builder(this).setTitle("识别 / 批改结果 · 版本 ${data.revision}")
                            .setMessage(answer).setPositiveButton("关闭", null).show()
                    }
                } }
            } catch (e: Exception) {
                message("提交未完成：" + com.opc.baize.subscription.SubscriptionErrors.describe(e) + "。笔迹保留，可继续书写。")
            } finally { bitmap.recycle(); runOnUiThread {
                submitting = false
                if (!destroyed && studyJob?.isActive != true && autoRecognize && document == id && pageIndex == index && ink.page.revision != data.revision)
                    handler.postDelayed(recognizeLater, 1800)
            } }
        }
    }
    private fun openGenerated(pdf: java.io.File, workspace: String, source: String?) {
        if (loading || destroyed) return
        loading = true; ink.isEnabled = false
        io.execute {
            try {
                val owner = WorkspaceFiles(this).list().first { it.id == workspace }
                val file = pdf.canonicalFile
                require(file.path.startsWith(owner.root.canonicalPath + "/memory/pages/") && file.extension == "pdf")
                val sourceFile = source?.let { java.io.File(owner.root, it.removePrefix("/")).canonicalFile }
                    ?.takeIf { it.isFile && it.path.startsWith(owner.root.canonicalPath + "/memory/pages/") }
                val id = repository.importPdf(android.net.Uri.fromFile(file))
                prefs.edit().putString("paper_workspace_$id", workspace).putString("paper_source_$id",
                    sourceFile?.let { "/" + it.relativeTo(owner.root).path }).apply()
                runOnUiThread { if (!destroyed) { loading = false; load(id, 0) } }
            } catch (e: Exception) { runOnUiThread { if (!destroyed) {
                loading = false; ink.isEnabled = true; status.text = "学习页打开失败，原文件已保留。"
            } } }
        }
    }
    private fun study(request: String, origin: String) {
        if (studyJob?.isActive == true || destroyed) return
        val workspace = workspaceId?.let { id -> WorkspaceFiles(this).list().find { it.id == id } }
        if (workspace == null) { status.text = "请从 WE 工作区打开学习纸，再提交需求。"; return }
        val source = sourcePath
        val id = document; val index = pageIndex; val revision = ink.page.revision; val version = interactionVersion
        handler.removeCallbacks(recognizeLater)
        studyJob = scope.launch {
            try {
                status.text = "正在理解并完成需求……可继续书写。"
                val pdf = withContext(Dispatchers.IO) {
                    val turn = LearningTurn.prepare(workspace, request, source, origin)
                    val runtime = NativeAgentHost.build(applicationContext, workspace, SubscriptionSelection.read(this@PaperActivity).textModel)
                    studyRuntime = runtime
                    try {
                        turn.execute(runtime) { event -> withContext(Dispatchers.Main) {
                            when (event) {
                                is AgentEvent.NativeToolCallStarted -> status.text = "正在生成学习页……"
                                is AgentEvent.ConfirmationRequired -> {
                                    fun decide(choice: ConfirmationChoice) { scope.launch {
                                        runtime.resumeConfirmation(ConfirmationDecision(event.pending.pendingId, UUID.randomUUID().toString(), choice))
                                    } }
                                    AlertDialog.Builder(this@PaperActivity).setTitle("继续工具操作")
                                        .setMessage("当前任务需要工具操作确认。")
                                        .setPositiveButton("执行") { _, _ -> decide(ConfirmationChoice.APPROVE) }
                                        .setNegativeButton("取消") { _, _ -> decide(ConfirmationChoice.DENY) }
                                        .setOnCancelListener { decide(ConfirmationChoice.DENY) }.show()
                                }
                                else -> Unit
                            }
                        } }
                    } finally { runtime.close(); studyRuntime = null }
                }
                readyPdf = pdf
                if (document == id && pageIndex == index && ink.page.revision == revision && interactionVersion == version) {
                    openGenerated(pdf, workspace.id, LearningTurn.sourcePath(pdf))
                } else status.text = "新学习页已生成。你正在书写，点“查看生成页”再打开。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "本轮未完成：${e.message?.take(160)}。原页和笔迹已保留。" }
            finally {
                // Do not submit ink from an old page to the newly generated page.
                if (!destroyed && !loading && autoRecognize && document == id && pageIndex == index && ink.page.revision != revision)
                    handler.postDelayed(recognizeLater, 1800)
            }
        }
    }
    private fun message(text: String) = runOnUiThread { if (!destroyed) status.text = text }
    override fun onDestroy() {
        destroyed = true
        scope.cancel(); studyRuntime?.close()
        handler.removeCallbacks(recognizeLater)
        // Submitted work may finish saving its captured revision after this screen closes.
        io.execute { renderer?.close(); renderer = null }
        ai.shutdown()
        ink.release(); super.onDestroy()
    }
    companion object {
        private const val RECOGNIZE = "请直接识别图中可见的文字、手写和图形关系，按原布局顺序转写。不要解答题目。看不清的位置标记[不清晰]，不补写内容。"
    }
}

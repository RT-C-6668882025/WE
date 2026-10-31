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
    private lateinit var autoContinueSwitch: Switch
    private var interactionVersion = 0L
    private var workspaceId: String? = null
    private val recognizeLater = Runnable {
        if (!submitting && studyJob?.isActive != true && !loading && !destroyed) sendCurrent(true)
    }
    private val prefs by lazy { getSharedPreferences("moling", MODE_PRIVATE) }
    private lateinit var previousPage: Button
    private lateinit var nextPage: Button
    private lateinit var requestField: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var generatedButton: Button
    private lateinit var scopeChoice: Spinner
    private lateinit var modeButton: Button
    private var command = 0
    private var stopped = false
    private var visionGeneration = 0
    private var visionTask: java.util.concurrent.Future<*>? = null
    private lateinit var instructionButton: Button
    private var chooseTool: (PaperInkView.Mode) -> Unit = {}
    private val readyListener: (String,java.io.File) -> Unit = { owner,pdf ->
        handler.post { if (!destroyed && workspaceId == owner) {
            readyPdf = pdf; generatedButton.visibility = android.view.View.VISIBLE
            status.text = "工作区有新生成的页面，可点击打开；当前笔迹保留。"
        } }
    }
    private fun updateBusy() {
        val busy = submitting || studyJob?.isActive == true
        sendButton.isEnabled = !busy
        stopButton.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); repository = PaperRepository(this)
        workspaceId = intent.getStringExtra("workspace")
        command = prefs.getInt("paper_command", 0).coerceIn(0,3)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        status = TextView(this).apply { text = "手动识别 · 双指移动与缩放 · 笔迹自动保存"; textSize = 13f }
        fun row() = LinearLayout(this).also { root.addView(it) }
        fun button(row: LinearLayout, text: String, call: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 12f; setOnClickListener { call() }
            row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        ink = PaperInkView(this)
        ink.stylusOnly = prefs.getBoolean("paper_stylus_only", false)
        val top = row()
        button(top,"工作区") { finish() }
        previousPage = button(top,"上一页") { if (!loading && pageIndex > 0) document?.let { load(it,pageIndex-1) } }.apply { visibility = android.view.View.GONE }
        nextPage = button(top,"下一页") { if (!loading && pageIndex+1 < pageCount) document?.let { load(it,pageIndex+1) } }.apply { visibility = android.view.View.GONE }
        modeButton = button(top,"手写") {
            AlertDialog.Builder(this).setTitle("同一工作区 · 输入模式")
                .setItems(arrayOf("手写","绘画")) { _,which -> setInputMode(which == 1) }.show()
        }
        button(top,"菜单") { showCanvasMenu() }
        pageLabel = TextView(this).apply { text = "当前工作区 · 笔迹自动保存" }; root.addView(pageLabel)
        val tools = row()
        val modes = mutableMapOf<PaperInkView.Mode,Button>()
        chooseTool = { mode ->
            interactionVersion++; handler.removeCallbacks(recognizeLater); ink.mode = mode
            modes.forEach { (key,value) ->
                value.text = (if (key == mode) "✓ " else "") + when(key) {
                    PaperInkView.Mode.WRITE -> "画笔"; PaperInkView.Mode.ERASE -> "橡皮"; else -> "框选"
                }
                value.backgroundTintList = android.content.res.ColorStateList.valueOf(if (key == mode) Color.rgb(205,225,255) else Color.rgb(238,238,238))
            }
        }
        modes[PaperInkView.Mode.WRITE] = button(tools,"画笔") { chooseTool(PaperInkView.Mode.WRITE) }
        modes[PaperInkView.Mode.SELECT] = button(tools,"框选") {
            if (ink.mode == PaperInkView.Mode.SELECT || ink.hasSelection) { ink.clearSelection(); scopeChoice.setSelection(0); chooseTool(PaperInkView.Mode.WRITE); status.text = "已取消框选，发送范围恢复整页。" }
            else { scopeChoice.setSelection(1); chooseTool(PaperInkView.Mode.SELECT); status.text = "拖出矩形；再次点框选或轻点纸面取消。" }
        }
        modes[PaperInkView.Mode.ERASE] = button(tools,"橡皮") { chooseTool(PaperInkView.Mode.ERASE); status.text = "蓝色圆圈是擦除范围；当前擦除整条笔迹，可撤销。" }
        button(tools,"撤销") { if (!loading) ink.undo() }
        button(tools,"笔刷") { showBrushPanel() }
        button(tools,"居中") { ink.resetView() }
        chooseTool(PaperInkView.Mode.WRITE)
        root.addView(ink, LinearLayout.LayoutParams(-1,0,1f))
        ink.toolFeedback = {
            if (it.startsWith("已取消框选")) scopeChoice.setSelection(0)
            status.text = it
        }
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
        val controls = row()
        scopeChoice = Spinner(this).apply {
            adapter = ArrayAdapter(this@PaperActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("整页","选区","仅文字"))
            contentDescription = "发送范围"
        }; controls.addView(scopeChoice,LinearLayout.LayoutParams(0,-2,1f))
        instructionButton = button(controls,"指令：${PaperPrompts.labels[command]}") { showInstructions() }
        autoContinueSwitch = Switch(this).apply {
            text = "自动识别"; isChecked = false
            setOnCheckedChangeListener { _,enabled ->
                autoRecognize = enabled; interactionVersion++; this@PaperActivity.handler.removeCallbacks(recognizeLater)
                status.text = if (enabled) "自动识别已开启；停笔后按当前范围和指令处理。" else "手动模式：按需点击发送。"
            }
        }; controls.addView(autoContinueSwitch)
        val requestRow = row()
        requestField = EditText(this).apply { hint = "输入指令，留空只识别"; textSize = 16f; maxLines = 3 }
        requestRow.addView(requestField,LinearLayout.LayoutParams(0,-2,3f))
        sendButton = button(requestRow,"发送") { sendCurrent() }
        stopButton = button(requestRow,"停止") {
            stopped = true; visionGeneration++; visionTask?.cancel(true); submitting = false
            interactionVersion++; studyJob?.cancel(); autoContinueSwitch.isChecked = false; updateBusy()
            handler.removeCallbacks(recognizeLater); status.text = "已停止；原页保留。"
        }.apply { visibility = android.view.View.GONE }
        generatedButton = Button(this).apply {
            text = "打开新生成的页面"; visibility = android.view.View.GONE
            setOnClickListener {
                val w = workspaceId?.let { id -> WorkspaceFiles(this@PaperActivity).list().find { it.id == id } }
                val pdf = w?.let { LearningTurn.latestReady(it) } ?: readyPdf
                if (pdf != null && w != null) openGenerated(pdf,w.id,LearningTurn.sourcePath(pdf))
            }
        }; root.addView(generatedButton)
        root.addView(status)
        WorkspacePageEvents.subscribe(readyListener)
        val generated = intent.getStringExtra("generatedPdf")
        setInputMode(if (intent.hasExtra("entryMode")) intent.getStringExtra("entryMode") == "drawing" else prefs.getBoolean("paper_drawing_${workspaceId.orEmpty()}",false))
        workspaceId = intent.getStringExtra("workspace")
        if (intent.getBooleanExtra("focusInput",false)) {
            scopeChoice.setSelection(2)
            requestField.post {
                requestField.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(requestField,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        autoStudy = false
        if (generated != null && workspaceId != null) {
            openGenerated(java.io.File(generated), workspaceId!!, intent.getStringExtra("sourcePath"))
        } else prefs.getString("paper_last_document_${workspaceId.orEmpty()}", null)?.let { load(it, prefs.getInt("paper_last_page_${workspaceId.orEmpty()}", 0)) }
    }
    @Deprecated("Platform result callback retained to avoid introducing an Activity framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 51 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (loading) return
        loading = true; ink.isEnabled = false; status.text = "正在导入本地副本……"
        val ownerId = workspaceId
        io.execute {
            try {
                val id = repository.importPdf(uri)
                if (ownerId != null) {
                    val owner = WorkspaceFiles(this).list().first { it.id == ownerId }
                    val copy = java.io.File(owner.root,"memory/pages/import-$id.pdf")
                    if (!copy.exists()) repository.pdf(id).copyTo(copy)
                    prefs.edit().putString("paper_workspace_$id",ownerId).remove("paper_source_$id").putString("paper_last_pdf_$ownerId",copy.path).apply()
                }
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
                    autoStudy = false
                    previousPage.visibility = if (count > 1) android.view.View.VISIBLE else android.view.View.GONE
                    nextPage.visibility = previousPage.visibility
                    previousPage.isEnabled = index > 0; nextPage.isEnabled = index+1 < count
                    prefs.edit().putString("paper_last_document_${workspaceId.orEmpty()}", id).putInt("paper_last_page_${workspaceId.orEmpty()}",index).apply()
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
        val generation = ++visionGeneration
        stopped = false; submitting = true; updateBusy(); status.text = "正在提交第 ${index + 1} 页版本 ${data.revision}；可继续书写。"
        val snapshotName = "submission-${java.util.UUID.randomUUID()}"
        val snapshotSave = io.submit { repository.submission(id, index, snapshotName, data) }
        visionTask = ai.submit {
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
                    if (stopped || generation != visionGeneration) return@runOnUiThread
                    if (workspace != null && unchanged) {
                        study(prompt + "\n\n纸面理解结果：\n" + answer.take(24000),
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
                if (generation == visionGeneration && !stopped) message("提交未完成：" + com.opc.baize.subscription.SubscriptionErrors.describe(e) + "。笔迹保留，可继续书写。")
            } finally { bitmap.recycle(); runOnUiThread {
                if (generation == visionGeneration) { submitting = false; updateBusy() }
                if (generation == visionGeneration && !destroyed && studyJob?.isActive != true && autoRecognize && document == id && pageIndex == index && ink.page.revision != data.revision)
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
                runOnUiThread { if (!destroyed) {
                    generatedButton.visibility = android.view.View.GONE
                    prefs.edit().putString("paper_last_pdf_$workspace",file.path).apply()
                    val previous = prefs.getString("paper_last_document_$workspace",null)
                    loading = false; load(id,if (previous == id) prefs.getInt("paper_last_page_$workspace",0) else 0)
                } }
            } catch (e: Exception) { runOnUiThread { if (!destroyed) {
                loading = false; ink.isEnabled = true; status.text = "学习页打开失败，原文件已保留。"
            } } }
        }
    }
    private fun setInputMode(drawing: Boolean) {
        ink.brushEngine = if (drawing) DrawdanceBrush.ENGINE else "vector"
        ink.penWidth = if (drawing) prefs.getFloat("drawing_width",5f) else prefs.getFloat("handwriting_width",1.8f)
        modeButton.text = if (drawing) "绘画 ▾" else "手写 ▾"
        chooseTool(PaperInkView.Mode.WRITE)
        workspaceId?.let { prefs.edit().putBoolean("paper_drawing_$it",drawing).apply() }
    }
    private fun sendCurrent(automatic: Boolean = false) {
        if (loading || submitting || studyJob?.isActive == true) return
        val scope = scopeChoice.selectedItemPosition
        if (scope == 1 && !ink.hasSelection) {
            if (!automatic) { chooseTool(PaperInkView.Mode.SELECT); status.text = "请先框选要发送的区域，或切换为整页。" }
            return
        }
        if (scope == 0) ink.clearSelection()
        val prompt = requestField.text.toString().trim().ifBlank { PaperPrompts.read(this,command) }
        if (scope == 2) {
            if (!automatic && prompt.isNotBlank()) study(prompt,"用户输入")
        } else submit(prompt,automatic)
    }
    private fun showInstructions() {
        AlertDialog.Builder(this).setTitle("指令 · 当前：${PaperPrompts.labels[command]}")
            .setItems(PaperPrompts.labels + arrayOf("编辑当前预设","编辑系统提示词","编辑页面任务模板")) { _,which ->
                when(which) {
                    in 0..3 -> { command = which; instructionButton.text = "指令：${PaperPrompts.labels[which]}"; prefs.edit().putInt("paper_command",which).apply(); requestField.setText(PaperPrompts.read(this,which)) }
                    6 -> {
                        val w = workspaceId?.let { id -> WorkspaceFiles(this).list().find { it.id == id } }
                        if (w != null) {
                            val file = java.io.File(w.root,"memory/agent-prompt.txt")
                            val editor = EditText(this).apply { minLines = 6; maxLines = 14; setText(if (file.isFile) file.readText() else LearningTurn.DEFAULT_TEMPLATE) }
                            AlertDialog.Builder(this).setTitle("页面任务模板 · 当前工作区").setMessage("变量：{request} 需求、{source} 原材料、{origin} 来源、{output} 输出路径。")
                                .setView(editor).setNegativeButton("取消",null)
                                .setNeutralButton("恢复默认") { _,_ -> file.delete() }
                                .setPositiveButton("保存") { _,_ ->
                                    val text = editor.text.toString()
                                    if (text.length <= 48000) LearningTurn.write(file,text) else status.text = "模板最多 48000 字"
                                }.show()
                        }
                    }
                    4,5 -> {
                        val system = which == 5
                        val editor = EditText(this).apply { minLines = 5; maxLines = 12; setText(if (system) PaperPrompts.system(this@PaperActivity) else PaperPrompts.read(this@PaperActivity,command)) }
                        AlertDialog.Builder(this).setTitle(if (system) "系统提示词" else "${PaperPrompts.labels[command]} · 可修改")
                            .setView(editor).setNegativeButton("取消",null)
                            .setNeutralButton("恢复默认") { _,_ ->
                                if (system) PaperPrompts.saveSystem(this,PaperPrompts.DEFAULT_SYSTEM)
                                else { PaperPrompts.save(this,command,PaperPrompts.defaults[command]); requestField.setText(PaperPrompts.read(this,command)) }
                            }
                            .setPositiveButton("保存") { _,_ ->
                                try {
                                    if (system) PaperPrompts.saveSystem(this,editor.text.toString())
                                    else { PaperPrompts.save(this,command,editor.text.toString()); requestField.setText(editor.text) }
                                } catch (e: Exception) { status.text = e.message }
                            }.show()
                    }
                }
            }.show()
    }
    private fun showBrushPanel() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        val colors = intArrayOf(Color.BLACK,Color.rgb(25,85,175),Color.rgb(190,40,40),Color.rgb(35,125,75))
        val color = Spinner(this).apply {
            adapter = ArrayAdapter(this@PaperActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("黑","蓝","红","绿"))
            setSelection(colors.indexOf(ink.penColor).coerceAtLeast(0))
        }; box.addView(color)
        val label = TextView(this); box.addView(label)
        val size = SeekBar(this).apply { max = 190; progress = (ink.penWidth*10-10).toInt().coerceIn(0,190) }; box.addView(size)
        fun describe() { label.text = "粗细：${(size.progress+10)/10f}" }; describe()
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?,value: Int,fromUser: Boolean) { describe() }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        AlertDialog.Builder(this).setTitle("笔刷").setView(box).setNegativeButton("取消",null).setPositiveButton("使用") { _,_ ->
            ink.penColor = colors[color.selectedItemPosition]; ink.penWidth = (size.progress+10)/10f
            prefs.edit().putFloat(if (ink.brushEngine == "vector") "handwriting_width" else "drawing_width",ink.penWidth).apply()
            chooseTool(PaperInkView.Mode.WRITE)
        }.show()
    }
    private fun showCanvasMenu() {
        AlertDialog.Builder(this).setTitle("画布")
            .setItems(arrayOf("导入 PDF","单指移动","修改生成内容","画布设置","操作说明","返回工作区")) { _,which ->
                when(which) {
                    0 -> if (!loading) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE) },51)
                    1 -> { chooseTool(PaperInkView.Mode.MOVE); status.text = "单指拖动画布；也可随时双指移动。" }
                    2 -> editGeneratedContent()
                    3 -> {
                        val toggle = Switch(this).apply { text = "仅笔写画（双指仍可导航）"; isChecked = ink.stylusOnly }
                        AlertDialog.Builder(this).setTitle("画布设置").setView(toggle).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
                            ink.stylusOnly = toggle.isChecked; prefs.edit().putBoolean("paper_stylus_only",toggle.isChecked).apply()
                        }.show()
                    }
                    4 -> AlertDialog.Builder(this).setTitle("操作说明").setMessage("手写与绘画共用当前工作区、页面和笔迹；模式只改变接下来的笔刷。\n双指捏合缩放、双指拖动画布；居中恢复整页视图。\n框选后选择“选区”再发送；再次点框选或在框选模式下轻点纸面可取消。\n橡皮蓝圈表示范围，擦掉整条笔迹；一趟擦除可一次撤销。\n发送范围可选整页、选区、仅文字。指令预设与系统提示词都能编辑。\n默认手动发送；自动识别开启后才会停笔处理。\n修改生成内容可直接编辑文字、留白、图形参数并另存版本。\n识别与批改记录在工作区中查看。")
                        .setPositiveButton("关闭",null).show()
                    5 -> finish()
                }
            }.show()
    }
    private fun editGeneratedContent() {
        if (loading || submitting || studyJob?.isActive == true) { status.text = "请等待当前操作完成后修改内容。"; return }
        val workspace = workspaceId?.let { id -> WorkspaceFiles(this).list().find { it.id == id } }
        val source = sourcePath
        if (workspace == null || source == null) { status.text = "当前是导入的 PDF，可直接批注；请打开 AI 或 MCP 生成页以修改其内容。"; return }
        interactionVersion++; handler.removeCallbacks(recognizeLater)
        val originalDocument = document
        io.execute {
            try {
                val file = java.io.File(workspace.root, source.removePrefix("/")).canonicalFile
                require(file.path.startsWith(workspace.root.canonicalPath + "/memory/pages/") && file.isFile && file.length() <= 256000)
                val original = file.readText()
                runOnUiThread { if (!destroyed && document == originalDocument) {
                    try { GeneratedContentEditor.show(this, original) { edited ->
                        val revision = ink.page.revision; val version = interactionVersion
                        status.text = "正在保存修改后的页面……"
                        io.execute {
                            try {
                                require(file.readText() == original) { "原内容已经更新，请重新打开编辑器" }
                                val turn = LearningTurn.prepare(workspace, "手动修改生成内容", source, "用户直接编辑")
                                turn.publishEdited(edited) { input, output -> LearningPageRenderer.render(input, output, true) }
                                runOnUiThread { if (!destroyed) {
                                    readyPdf = turn.pdf; generatedButton.visibility = android.view.View.VISIBLE
                                    if (document == originalDocument && ink.page.revision == revision && interactionVersion == version)
                                        openGenerated(turn.pdf, workspace.id, LearningTurn.sourcePath(turn.pdf))
                                    else status.text = "修改已保存；点查看生成页打开，当前笔迹保留。"
                                } }
                            } catch (e: Exception) { message("修改未保存：${e.message}。原页保留。") }
                        }
                    } } catch (e: Exception) { status.text = "内容暂时无法编辑：${e.message}" }
                } }
            } catch (e: Exception) { message("源内容读取失败：${e.message}") }
        }
    }
    private fun study(request: String, origin: String) {
        if (studyJob?.isActive == true || destroyed) return
        val workspace = workspaceId?.let { id -> WorkspaceFiles(this).list().find { it.id == id } }
        if (workspace == null) { status.text = "请从 WE 工作区打开学习纸，再提交需求。"; return }
        val source = sourcePath
        val id = document; val index = pageIndex; val revision = ink.page.revision; val version = interactionVersion
        handler.removeCallbacks(recognizeLater)
        stopped = false
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
                if (stopped) return@launch
                readyPdf = pdf; generatedButton.visibility = android.view.View.VISIBLE
                if (document == id && pageIndex == index && ink.page.revision == revision && interactionVersion == version) {
                    openGenerated(pdf, workspace.id, LearningTurn.sourcePath(pdf))
                } else status.text = "新学习页已生成。你正在书写，点“查看生成页”再打开。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "本轮未完成：${e.message?.take(160)}。原页和笔迹已保留。" }
            finally {
                handler.post { if (!destroyed) updateBusy() }
                // Do not submit ink from an old page to the newly generated page.
                if (!destroyed && !loading && autoRecognize && document == id && pageIndex == index && ink.page.revision != revision)
                    handler.postDelayed(recognizeLater, 1800)
            }
        }
        updateBusy()
    }
    private fun message(text: String) = runOnUiThread { if (!destroyed) status.text = text }
    override fun onDestroy() {
        destroyed = true
        WorkspacePageEvents.unsubscribe(readyListener)
        scope.cancel(); studyRuntime?.close()
        handler.removeCallbacks(recognizeLater)
        // Submitted work may finish saving its captured revision after this screen closes.
        io.execute { renderer?.close(); renderer = null }
        visionGeneration++; visionTask?.cancel(true); ai.shutdownNow()
        ink.release(); super.onDestroy()
    }
}

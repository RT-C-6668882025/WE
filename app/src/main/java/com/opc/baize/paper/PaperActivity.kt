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
    private data class RetainedCanvas(val saves:CanvasSaveQueue,val document:String?,val index:Int)
    private lateinit var saveQueue:CanvasSaveQueue
    private lateinit var saveLabel:TextView
    private lateinit var retrySave:Button
    private lateinit var generatedButton: Button
    private lateinit var scopeChoice: Spinner
    private lateinit var modeButton: Button
    private var command = 0
    private var stopped = false
    private var visionGeneration = 0
    private var visionTask: java.util.concurrent.Future<*>? = null
    private lateinit var instructionButton: Button
    private var pendingExport: String? = null
    private var chooseTool: (PaperInkView.Mode) -> Unit = {}
    private val readyListener: (String,java.io.File) -> Unit = { owner,pdf ->
        handler.post { if (!destroyed && workspaceId == owner) {
            if (prefs.getString("paper_last_pdf_$owner",null) == pdf.canonicalPath) return@post
            readyPdf = pdf; generatedButton.visibility = android.view.View.VISIBLE
            status.text = "工作区有新生成的页面，可点击打开；当前笔迹保留。"
        } }
    }
    private fun updateBusy() {
        val busy = submitting || studyJob?.isActive == true
        sendButton.text=if(busy) "停止" else "发送"
        sendButton.contentDescription=if(busy) "停止当前 AI 任务" else "发送给 AI"
        sendButton.isEnabled=busy || !loading
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); pendingExport=state?.getString("drawing_export"); repository = PaperRepository(applicationContext)
        workspaceId = intent.getStringExtra("workspace")
        command = prefs.getInt("paper_command", 0).coerceIn(0,3)
        var responsiveLayout:(Int)->Unit = {}
        val root=object:LinearLayout(this@PaperActivity) {
            override fun onMeasure(widthSpec:Int,heightSpec:Int) {
                responsiveLayout(android.view.View.MeasureSpec.getSize(heightSpec))
                super.onMeasure(widthSpec,heightSpec)
            }
        }.apply {orientation=LinearLayout.VERTICAL}
        root.setBackgroundColor(Color.rgb(246,241,228))
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        status = TextView(this).apply { text = "手动识别 · 双指移动与缩放 · 笔迹自动保存"; textSize = 13f }
        fun row() = LinearLayout(this).also { root.addView(it) }
        fun button(row: LinearLayout, text: String, call: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 13f; isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = dp(48); minimumHeight = dp(48)
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;contentDescription=text
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(246,241,228));cornerRadius=dp(8).toFloat();setStroke(dp(1),Color.rgb(221,213,195))
            }
            setTextColor(Color.rgb(58,53,44)); setPadding(dp(12),0,dp(12),0)
            setOnClickListener { call() }
            row.addView(this, LinearLayout.LayoutParams(-2, -2))
        }
        ink = PaperInkView(this)
        ink.stylusOnly = prefs.getBoolean("paper_stylus_only", false)
        ink.historyRequest = history@{ redo ->
            if(::saveQueue.isInitialized && !saveQueue.isIdle()) {status.text="正在保存，完成后可撤销或重做。";return@history}
            val id=document
            if(id!=null && !loading && ink.canAcceptExternal && (!::saveQueue.isInitialized || saveQueue.isIdle())) try {
                val next=PaperRepository(this).history(id,pageIndex,ink.page.width,ink.page.height,ink.page.revision,redo)
                ink.acceptExternal(next); interactionVersion++
                status.text=if(redo) "已重做" else "已撤销"
            } catch(e:Exception) { status.text="暂未撤销／重做：${e.message}" }
        }
        val top = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        button(top,"返回") { if(canLeaveCanvas()) finish() }
        val workspaceName = workspaceId?.let { id -> WorkspaceFiles(this).list().find { it.id == id }?.name } ?: "画布"
        top.addView(TextView(this).apply {
            text = workspaceName; textSize = 17f; setTextColor(Color.rgb(58,53,44))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,-2,1f))
        button(top,"页面") { showWorkspacePages() }
        button(top,"菜单") { showCanvasMenu() }
        val navigation = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        previousPage = button(navigation,"上一页") { if (!loading && pageIndex > 0) document?.let { load(it,pageIndex-1) } }.apply { visibility = android.view.View.GONE }
        pageLabel = TextView(this).apply {
            text = "笔迹自动保存"; textSize = 12f; setTextColor(Color.rgb(163,154,135)); setPadding(dp(16),0,dp(16),0)
        }; navigation.addView(pageLabel,LinearLayout.LayoutParams(0,-2,1f))
        nextPage = button(navigation,"下一页") { if (!loading && pageIndex+1 < pageCount) document?.let { load(it,pageIndex+1) } }.apply { visibility = android.view.View.GONE }
        val tools = row().apply {gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(2),dp(6),dp(2))}
        modeButton = button(navigation,"手写笔 ▾") {
            AlertDialog.Builder(this).setTitle("笔刷预设 · 保留当前页面与笔迹")
                .setItems(arrayOf("手写笔","绘画笔")) { _,which -> setInputMode(which == 1) }.show()
        }.apply {textSize=12f}
        navigation.removeView(modeButton);navigation.addView(modeButton,0,LinearLayout.LayoutParams(dp(90),-2))
        val modes = mutableMapOf<PaperInkView.Mode,Button>()
        chooseTool = { mode ->
            interactionVersion++; handler.removeCallbacks(recognizeLater); ink.mode = mode
            modes.forEach { (key,value) ->
                value.text=when(key) {PaperInkView.Mode.WRITE->"画笔";PaperInkView.Mode.ERASE->"橡皮";else->"框选"}
                value.isSelected=key==mode
                value.backgroundTintList = android.content.res.ColorStateList.valueOf(if (key == mode) Color.rgb(216,207,184) else Color.rgb(246,241,228))
            }
        }
        modes[PaperInkView.Mode.WRITE] = button(tools,"画笔") { ink.drawingTool="freehand";chooseTool(PaperInkView.Mode.WRITE) }
        modes[PaperInkView.Mode.SELECT] = button(tools,"框选") {
            if (ink.mode == PaperInkView.Mode.SELECT || ink.hasSelection) { ink.clearSelection(); scopeChoice.setSelection(0); chooseTool(PaperInkView.Mode.WRITE); status.text = "已取消框选，发送范围恢复整页。" }
            else { scopeChoice.setSelection(1); chooseTool(PaperInkView.Mode.SELECT); status.text = "拖出矩形；再次点框选或轻点纸面取消。" }
        }
        modes[PaperInkView.Mode.ERASE] = button(tools,"橡皮") { chooseTool(PaperInkView.Mode.ERASE); status.text = if(ink.pixelEraser) "局部橡皮：仅擦除当前图层，可撤销。" else "整笔橡皮：仅擦除当前图层，可撤销。" }
        button(tools,"笔刷") { if(canEditCanvas()) showBrushPanel() }
        button(tools,"图层") { if(canEditCanvas()) drawingPanels().layers() }
        val actions=row().apply {setPadding(dp(6),0,dp(6),dp(2))}
        button(actions,"撤销") { if(canEditCanvas()) ink.undo() }
        button(actions,"重做") { if(canEditCanvas()) ink.redo() }
        button(actions,"添加") { if(canEditCanvas()) drawingPanels().addObjects() }
        button(actions,"居中") { ink.resetView() }
        button(actions,"更多") { if(canEditCanvas()) drawingPanels().tools() }
        for(strip in listOf(tools,actions)) for(i in 0 until strip.childCount) {
            (strip.getChildAt(i) as Button).apply {textSize=12f;setPadding(dp(4),0,dp(4),0)}
            strip.getChildAt(i).layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(2),0,dp(2),0)}
        }
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
            if(id!=null) saveQueue.enqueue(id,page,data)
        }
        ink.strokeStarted = { interactionVersion++; handler.removeCallbacks(recognizeLater) }
        val controls = row().apply { visibility = android.view.View.GONE; gravity = android.view.Gravity.CENTER_VERTICAL }
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
        }
        val aiOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        root.removeView(controls); controls.visibility = android.view.View.VISIBLE
        aiOptions.addView(controls); aiOptions.addView(autoContinueSwitch)
        root.addView(aiOptions)
        val requestRow = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        button(requestRow,"AI ▾") {
            aiOptions.visibility = if (aiOptions.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
        }
        requestField = EditText(this).apply { hint = "告诉 AI 要做什么"; textSize = 16f; maxLines = 3 }
        requestRow.addView(requestField,LinearLayout.LayoutParams(0,-2,1f))
        sendButton = button(requestRow,"发送") {
            if(submitting || studyJob?.isActive==true) stopCurrent() else sendCurrent()
        }
        generatedButton = Button(this).apply {
            text = "新页面已就绪 · 打开"; visibility = android.view.View.GONE
            setOnClickListener {
                val w = workspaceId?.let { id -> WorkspaceFiles(this@PaperActivity).list().find { it.id == id } }
                val pdf = readyPdf ?: w?.let { LearningTurn.latestReady(it) }
                if (pdf != null && w != null) openGenerated(pdf,w.id,LearningTurn.sourcePath(pdf))
            }
        }; root.addView(generatedButton)
        val saveRow=row().apply {gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0)}
        saveLabel=TextView(this).apply {text="自动保存";textSize=12f;setTextColor(Color.rgb(120,111,92))}
        saveRow.addView(saveLabel,LinearLayout.LayoutParams(0,-2,1f))
        retrySave=button(saveRow,"重试保存") {saveQueue.retry()}.apply {visibility=android.view.View.GONE}
        status.setPadding(dp(12),dp(2),dp(12),dp(6));root.addView(status)
        val retained=lastNonConfigurationInstance as? RetainedCanvas
        val store=repository
        saveQueue=retained?.saves ?: CanvasSaveQueue(io,{id,index,data -> store.save(id,index,data)},{})
        saveQueue.observe {state ->
            runOnUiThread {if(!destroyed) {
                saveLabel.text=when {state.error!=null->"保存失败 · 笔迹仍保留在当前画布";state.pending>0->"正在保存…";else->"已保存"}
                retrySave.visibility=if(state.error!=null) android.view.View.VISIBLE else android.view.View.GONE
                ink.isEnabled=!loading && state.error==null
                if(state.error!=null) status.text="请重试保存后再翻页或退出。"
            }}
        }
        responsiveLayout={height ->
            val compact=height in 1 until dp(480)
            val actionVisibility=if(compact) android.view.View.GONE else android.view.View.VISIBLE
            if(actions.visibility!=actionVisibility) actions.visibility=actionVisibility
            val navigationVisibility=if(compact && pageCount<=1) android.view.View.GONE else android.view.View.VISIBLE
            if(navigation.visibility!=navigationVisibility) navigation.visibility=navigationVisibility
        }
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
        if(retained?.document!=null) {
            load(retained.document,retained.index,true)
        } else if (generated != null && workspaceId != null) {
            openGenerated(java.io.File(generated), workspaceId!!, intent.getStringExtra("sourcePath"))
        } else prefs.getString("paper_last_document_${workspaceId.orEmpty()}", null)?.let { load(it, prefs.getInt("paper_last_page_${workspaceId.orEmpty()}", 0)) }
    }
    @Deprecated("Platform result callback retained to avoid introducing an Activity framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==54) {
            if(resultCode!=RESULT_OK || loading) return
            val uri=data?.data ?: return
            val target=document ?: return;val revision=ink.page.revision;val index=pageIndex
            scope.launch {
                try {
                    val image=withContext(Dispatchers.IO) {ArtworkImport.read(this@PaperActivity,uri)}
                    require(document==target && pageIndex==index && ink.page.revision==revision && ink.canAcceptExternal && !loading) {"画布已变化，请重新导入图片"}
                    val width=ink.page.width*.8f
                    val height=minOf(ink.page.height*.8f,width*image.height/image.width)
                    val fittedWidth=minOf(width,height*image.width/image.height)
                    ink.applyArtwork(org.json.JSONArray().put(org.json.JSONObject().put("action","image").put("content",image.content)
                        .put("x",ink.page.width*.1f).put("y",ink.page.height*.1f)
                        .put("x2",ink.page.width*.1f+fittedWidth).put("y2",ink.page.height*.1f+height)))
                } catch(e:Exception) {status.text="图片未导入：${e.message}"}
            }
            return
        }
        if(requestCode==53) {
            val path=pendingExport;pendingExport=null
            val file=path?.let {java.io.File(it)}
            if(resultCode==RESULT_OK && data?.data!=null && file?.isFile==true) {
                val uri=data.data!!
                io.execute {try { contentResolver.openOutputStream(uri,"w")!!.use {out -> file.inputStream().use {it.copyTo(out)}}
                    message("PNG 已导出。")
                } catch(_:Exception) {message("导出失败，请重试。")} finally {file.delete()} }
            } else file?.delete()
            return
        }
        if (requestCode != 51 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (loading || !canLeaveCanvas()) return
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
            } catch (e: Exception) { runOnUiThread { if (!destroyed) { loading = false; ink.isEnabled = true; attachCanvas(); status.text = "PDF 导入失败，请检查文件格式或大小（最多 100MB）。" } } }
        }
    }
    private fun load(id: String, requestedPage: Int,restorePending:Boolean=false) {
        if (loading || destroyed || (!restorePending && !canLeaveCanvas())) return
        CanvasSession.detach(this)
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
                val result = try { rendered.bitmap to (saveQueue.latest(id,index) ?: repository.load(id, index, rendered.width, rendered.height)) }
                catch (e: Exception) { rendered.bitmap.recycle(); throw e }
                runOnUiThread {
                    if (destroyed) { result.first.recycle(); return@runOnUiThread }
                    // A committed MCP edit must win over an earlier decoded IO snapshot.
                    val latest=try {saveQueue.latest(id,index) ?: repository.load(id,index,result.second.width,result.second.height)} catch(e:Exception) {
                        result.first.recycle();loading=false;ink.isEnabled=true
                        status.text="笔迹同步失败，原页面保留：${e.message}";return@runOnUiThread
                    }
                    document = id; pageIndex = index; pageCount = count
                    workspaceId = prefs.getString("paper_workspace_$id", null)
                    sourcePath = prefs.getString("paper_source_$id", null)
                    autoStudy = false
                    previousPage.visibility = if (count > 1) android.view.View.VISIBLE else android.view.View.GONE
                    nextPage.visibility = previousPage.visibility
                    previousPage.isEnabled = index > 0; nextPage.isEnabled = index+1 < count
                    prefs.edit().putString("paper_last_document_${workspaceId.orEmpty()}", id).putInt("paper_last_page_${workspaceId.orEmpty()}",index).apply()
                    ink.load(result.first,latest);loading=false;ink.isEnabled=saveQueue.state().error==null;attachCanvas()
                    pageLabel.text = "第 ${index + 1} / $count 页"
                    prefs.edit().putString("paper_last_document", id).putInt("paper_last_page", index).apply()
                    status.text = if (autoRecognize) "写下答案或修改要求，停笔后自动继续。" else "已恢复笔迹。可离线书写。"
                }
            } catch (e: Exception) { runOnUiThread { if (!destroyed) {
                loading = false; ink.isEnabled = true; attachCanvas()
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
        if (loading || destroyed || !canLeaveCanvas()) return
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
                loading = false; ink.isEnabled = true; attachCanvas(); status.text = "学习页打开失败，原文件已保留。"
            } } }
        }
    }
    private fun setInputMode(drawing: Boolean) {
        if(drawing) {
            ink.penColor=prefs.getInt("drawing_color",Color.BLACK)
            ink.brushSettings=try {BrushSettings.decode(prefs.getString("drawing_brush",null)?.let {JSONObject(it)})} catch(_:Exception) {BrushSettings()}
        } else {
            ink.brushSettings=BrushSettings()
            ink.penColor=prefs.getInt("handwriting_color",Color.BLACK)
        }
        ink.pixelEraser=drawing
        ink.drawingTool="freehand"
        ink.brushEngine = if (drawing) DrawdanceBrush.ENGINE else "vector"
        ink.penWidth = if (drawing) prefs.getFloat("drawing_width",5f) else prefs.getFloat("handwriting_width",1.8f)
        modeButton.text = if (drawing) "绘画笔 ▾" else "手写笔 ▾"
        chooseTool(PaperInkView.Mode.WRITE)
        workspaceId?.let { prefs.edit().putBoolean("paper_drawing_$it",drawing).apply() }
    }
    private fun sendCurrent(automatic: Boolean = false) {
        if (loading || submitting || studyJob?.isActive == true || !canEditCanvas() || !canLeaveCanvas()) return
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
    private fun drawingPanels()=DrawingPanels(this,ink,write={chooseTool(PaperInkView.Mode.WRITE)},importImage={
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},54)
    },setTool={chooseTool(it)})
    private fun showBrushPanel() {
        if(ink.brushEngine==DrawdanceBrush.ENGINE) {drawingPanels().brushes();return}
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
    private fun attachCanvas() {
        val id=document ?: return; val owner=workspaceId ?: return
        if(loading || destroyed || !hasWindowFocus()) return
        val index=pageIndex
        CanvasSession.attach(CanvasSession.Editor(this,owner,id,index,{
            ink.context().put("workspace_id",owner).put("document_id",id).put("page",index)
                .put("path",prefs.getString("paper_last_pdf_$owner",null)?.let { path ->
                    currentWorkspace()?.let { "/"+java.io.File(path).relativeTo(it.root).path }
                } ?: JSONObject.NULL)
        },{ expected,change ->
            require(!loading && document==id && pageIndex==index && ink.canAcceptExternal && ink.page.revision==expected) { "Canvas is busy or revision changed; read again" }
            val next=change(ink.page)
            ink.acceptExternal(next); interactionVersion++; handler.removeCallbacks(recognizeLater)
            status.text="AI 已更新当前画布 · 版本 ${next.revision}"
            next
        }))
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && ::ink.isInitialized) attachCanvas()
    }
    override fun onResume() {
        super.onResume()
        if(::ink.isInitialized) {
            val id=document
            if(id!=null && !loading && ink.canAcceptExternal) {
                try {
                    val next=PaperRepository(this).load(id,pageIndex,ink.page.width,ink.page.height)
                    if(next.revision>ink.page.revision) ink.acceptExternal(next)
                } catch(e:Exception) {status.text="画布同步失败，当前笔迹保留：${e.message}"}
            }
            currentWorkspace()?.let { w -> WorkspaceDrawing.pending(w)?.let { readyListener(w.id,it) } }
            attachCanvas()
        }
    }
    override fun onPause() { CanvasSession.detach(this);if(::ink.isInitialized) ink.cancelInput();super.onPause() }
    private fun canEditCanvas():Boolean {
        val ready=!loading && document!=null && (!::saveQueue.isInitialized || saveQueue.state().error==null)
        if(!ready) status.text="请先打开页面，并完成当前保存。"
        return ready
    }
    private fun canLeaveCanvas():Boolean {
        val ready=!::saveQueue.isInitialized || saveQueue.isIdle()
        if(!ready && ::status.isInitialized) status.text="当前笔迹尚未保存，请等待或点击重试保存。"
        return ready
    }
    @Deprecated("Platform back callback")
    override fun onBackPressed() {if(canLeaveCanvas()) super.onBackPressed()}
    private fun stopCurrent() {
        stopped=true;visionGeneration++;visionTask?.cancel(true);submitting=false;interactionVersion++
        studyJob?.cancel();autoContinueSwitch.isChecked=false;handler.removeCallbacks(recognizeLater)
        status.text="已停止；原页保留。";updateBusy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun currentWorkspace() = workspaceId?.let { id -> WorkspaceFiles(this).list().find { it.id == id } }

    private fun showWorkspacePages() {
        if (loading || !canLeaveCanvas()) return
        val workspace = currentWorkspace() ?: return
        val pages = java.io.File(workspace.root,"memory/pages").walkTopDown().maxDepth(10)
            .filter { it.isFile && it.extension.lowercase() == "pdf" }
            .sortedByDescending { it.lastModified() }.take(200).toList()
        val labels = arrayOf("＋ 新建空白页", "导入 PDF") + pages.map { it.relativeTo(java.io.File(workspace.root,"memory/pages")).path }
        AlertDialog.Builder(this).setTitle("${workspace.name} · 页面").setItems(labels) { _,which ->
            when (which) {
                0 -> scope.launch {
                    try {
                        val page = withContext(Dispatchers.IO) { WorkspaceDrawing.open(workspace,true) }
                        openGenerated(page.pdf,workspace.id,page.sourcePath)
                    } catch (e: Exception) { status.text = "新建页面失败：${e.message}" }
                }
                1 -> importPdf()
                else -> pages[which-2].let { openGenerated(it,workspace.id,WorkspaceDrawing.sourcePath(workspace,it)) }
            }
        }.setNegativeButton("关闭",null).show()
    }

    private fun importPdf() {
        if (!loading && canLeaveCanvas()) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
        },51)
    }

    private fun showCanvasMenu() {
        AlertDialog.Builder(this).setTitle("画布菜单")
            .setItems(arrayOf("居中显示","修改生成内容","画布设置","导出图片","工作区对话","AI 模型设置","操作说明","笔刷预设")) { _,which ->
                when(which) {
                    0 -> ink.resetView()
                    1 -> editGeneratedContent()
                    2 -> {
                        val toggle = Switch(this).apply { text = "仅笔写画（双指仍可导航）"; isChecked = ink.stylusOnly }
                        AlertDialog.Builder(this).setTitle("画布设置").setView(toggle).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
                            ink.stylusOnly = toggle.isChecked; prefs.edit().putBoolean("paper_stylus_only",toggle.isChecked).apply()
                        }.show()
                    }
                    3 -> AlertDialog.Builder(this).setTitle("导出图片")
                        .setItems(arrayOf("PNG","透明 PNG")) { _,index -> if (!loading) exportDrawing(index == 1) }.show()
                    4 -> workspaceId?.let { startActivity(Intent(this,AgentWorkspaceActivity::class.java).putExtra("workspace",it)) }
                    5 -> startActivity(Intent(this,com.opc.baize.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openSettings",true))
                    7 -> AlertDialog.Builder(this).setTitle("笔刷预设").setItems(arrayOf("手写笔","绘画笔")) {_,index -> setInputMode(index==1)}.show()
                    6 -> AlertDialog.Builder(this).setTitle("操作说明").setMessage("一个工作区保存手写、绘画、PDF 和 AI 生成页。\n手写笔／绘画笔只切换笔刷预设，当前页面和笔迹保留。\n双指拖动与缩放；菜单中可居中显示。常用工具固定显示，更多工具从“更多”打开。\n页面入口可新建、导入和切换；笔迹自动保存。\n框选后发送会使用选区；再次点击框选取消。\nAI 按钮展开范围、指令与自动识别；默认手动发送。运行中“发送”切换为“停止”。\n新生成页通过底部入口打开，保留当前笔迹。")
                        .setPositiveButton("关闭",null).show()
                }
            }.show()
    }
    private fun exportDrawing(transparent: Boolean) {
        try {
            val bitmap=ink.exportBitmap(transparent)
            val file=java.io.File.createTempFile("we-drawing-",".png",cacheDir)
            try {file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
            pendingExport?.let {java.io.File(it).delete()};pendingExport=file.path
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type="image/png";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"WE-drawing.png")
            },53)
        } catch(_:Exception) {status.text="导出失败，请先打开页面。"}
    }
    @Deprecated("Platform configuration retention")
    override fun onRetainNonConfigurationInstance():Any? = if(::saveQueue.isInitialized) RetainedCanvas(saveQueue,document,pageIndex) else null
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("drawing_export",pendingExport);super.onSaveInstanceState(outState)
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
        CanvasSession.detach(this)
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

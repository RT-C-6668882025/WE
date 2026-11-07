@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
package com.opc.baize.agent

import com.opc.baize.ui.WeUi
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import cc.noharry.agent.kernel.api.*
import com.opc.baize.model.SubscriptionSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Native host surface; the same device runs Skills, MCP, tools and subscription transport. */
class AgentWorkspaceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var files: WorkspaceFiles
    private lateinit var choice: Spinner
    private lateinit var output: TextView
    private lateinit var info: TextView
    private lateinit var stop: Button
    private lateinit var input: EditText
    private val controls = mutableListOf<android.view.View>()
    private var workspaces = emptyList<WeWorkspace>()
    private var runtime: AgentRuntime? = null
    private var runtimeWorkspace = ""
    private var job: Job? = null
    private var openingPage=false
    private var draftWorkspaceId=""
    private val drafts=mutableMapOf<String,String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        if(intent.getBooleanExtra("canvasPanel",false)) setTheme(android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        super.onCreate(savedInstanceState); files = WorkspaceFiles(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),0,dp(12),dp(8)) }
        root.setBackgroundColor(android.graphics.Color.rgb(246,241,228))
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; root.addView(this) }
        val header=WeUi.header(this,"工作区对话") {onBackPressed()}
        root.addView(header)
        output=TextView(this).apply {textSize=17f;setTextColor(WeUi.ink);setTextIsSelectable(true);setPadding(dp(4),dp(12),dp(4),dp(12))}

        choice = Spinner(this).apply {contentDescription="当前工作区"}; root.addView(choice); controls += choice
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        button(settings, "新建工作区") {
            val name = EditText(this).apply { hint = "工作区名称" }
            AlertDialog.Builder(this).setTitle("新建工作区").setView(name).setNegativeButton("取消", null)
                .setPositiveButton("创建",null).create().apply {
                    show();getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {val created=files.create(name.text.toString());reload(created.id);dismiss()}
                        catch(e:Exception) {name.error=e.message ?: "请检查名称"}
                    }
                }
        }
        info = TextView(this).apply { textSize = 12f;setTextColor(WeUi.secondary);maxLines=2;accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE };root.addView(info)
        val navigation = LinearLayout(this)
        header.addView(navigation)
        button(navigation, "画布") { openCanvas() }
        button(navigation, "设置") {
            (settings.parent as? android.view.ViewGroup)?.removeView(settings)
            AlertDialog.Builder(this).setTitle("工作区设置").setView(ScrollView(this).apply { addView(settings) })
                .setPositiveButton("关闭",null).show()
        }
        button(settings, "AI 模型设置") {
            startActivity(Intent(this,com.opc.baize.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openSettings",true))
        }
        button(settings, "导入 Skill 文件夹") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT_SKILL)
        }
        button(settings, "设置 MCP") {
            val workspace = current()
            val editor = EditText(this).apply { setText(files.mcp(workspace)); minLines = 5; gravity = android.view.Gravity.TOP }
            AlertDialog.Builder(this).setTitle("公开 MCP（Streamable HTTP）")
                .setMessage("填写 JSON 数组，例如 [{\"id\":\"wiki\",\"url\":\"https://mcp.deepwiki.com/mcp\"}]。当前支持无需认证的 HTTPS 服务。")
                .setView(editor).setNegativeButton("取消",null).setPositiveButton("保存",null).create().apply {
                    show();getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {files.saveMcp(workspace,editor.text.toString());resetRuntime();showInfo();dismiss()}
                        catch(e:Exception) {editor.error=e.message ?: "请检查配置格式"}
                    }
                }
        }
        button(settings, "查看工作区文件 / 打开手写页") { showFiles() }
        input = EditText(this).apply { hint = "告诉 AI 要做什么";minLines=1;maxLines=4;gravity=android.view.Gravity.TOP;textSize=16f;contentDescription="工作区需求" }
        val composer=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        composer.addView(input,LinearLayout.LayoutParams(0,-2,1f));controls+=input
        button(settings, "验证本机工具调用（使用订阅额度）") {
            run("请使用工作区文件工具创建 /memory/pages/we-native-probe-${System.currentTimeMillis()}.md，内容为 WE_NATIVE_OK；然后用读取工具读取该文件，最后报告读取结果。必须实际调用工具。")
        }
        stop = Button(this).apply {
            text="发送";tag=WeUi.PRIMARY;isAllCaps=false;minHeight=dp(48);contentDescription="发送或停止当前任务"
            setOnClickListener {
                if(job?.isActive==true) {job?.cancel();output.append("\n已请求停止。")}
                else if(openingPage) info.text="正在打开画布，请稍候…"
                else {val text=input.text.toString().trim();if(text.isNotEmpty()) run(text) else input.error="请输入需求后发送"}
            }
        };composer.addView(stop,LinearLayout.LayoutParams(dp(64),dp(48)))
        root.addView(ScrollView(this).apply {addView(output)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(composer)
        WeUi.style(root);WeUi.style(settings)
        choice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedId=current().id
                if(draftWorkspaceId!=selectedId) {
                    if(draftWorkspaceId.isNotEmpty()) drafts[draftWorkspaceId]=input.text.toString()
                    input.setText(drafts[selectedId].orEmpty())
                    draftWorkspaceId=selectedId
                }
                resetRuntime(); showInfo()
                output.text = current().transcript.takeIf { it.isFile }?.readText()?.takeLast(24000) ?: ""
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (files.list().isEmpty()) files.create("我的工作区")
        savedInstanceState?.getBundle("workspace_drafts")?.let { saved ->
            saved.keySet().forEach { drafts[it]=saved.getString(it).orEmpty() }
        }
        reload(savedInstanceState?.getString("selected_workspace") ?: intent.getStringExtra("workspace") ?: getPreferences(MODE_PRIVATE).getString("workspace", ""))
        draftWorkspaceId=current().id
        input.setText(savedInstanceState?.getString("draft") ?: intent.getStringExtra("feedback").orEmpty())
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun button(root: LinearLayout, title: String, call: () -> Unit) {
        val button = Button(this).apply {
            text=title;isAllCaps=false;textSize=14f;minHeight=dp(48);setTextColor(android.graphics.Color.rgb(58,53,44))
            setPadding(dp(12),dp(4),dp(12),dp(4));contentDescription=title;setOnClickListener {safe(call)}
        }
        WeUi.style(button);controls += button; root.addView(button)
    }
    private fun safe(call: () -> Unit) { try { call() } catch (e: Exception) { Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show() } }
    private fun current() = workspaces[choice.selectedItemPosition.coerceAtLeast(0)]
    private fun reload(selected: String? = null) {
        workspaces = files.list()
        if(workspaces.isEmpty()) { files.create("我的工作区");workspaces=files.list() }
        choice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, workspaces.map { it.name })
        choice.setSelection(workspaces.indexOfFirst { it.id == selected }.coerceAtLeast(0)); showInfo()
    }
    private fun showInfo() {
        val workspace = current(); getPreferences(MODE_PRIVATE).edit().putString("workspace", workspace.id).apply()
        info.text = "就绪 · ${files.skillNames(workspace).size} 个工作区 Skill"
    }
    private fun resetRuntime() { runtime?.close(); runtime = null; runtimeWorkspace = "" }
    private fun run(text: String) {
        if (job?.isActive == true || openingPage) return
        val workspace = current(); controls.forEach {it.isEnabled=false};stop.text="停止"
        output.append("\n你：$text\n")
        job = scope.launch {
            val answer = StringBuilder()
            var lastTool = ""
            try {
                val model = SubscriptionSelection.read(this@AgentWorkspaceActivity).textModel
                if (runtime == null || runtimeWorkspace != workspace.id + model) {
                    resetRuntime()
                    runtime = withContext(Dispatchers.IO) { NativeAgentHost.build(applicationContext, workspace, model) }
                    runtimeWorkspace = workspace.id + model
                }
                val owner = requireNotNull(runtime)
                val turn = withContext(Dispatchers.IO) { LearningTurn.prepare(workspace, text) }
                val pdf = withContext(Dispatchers.IO) {
                    turn.execute(owner) { event -> withContext(Dispatchers.Main) {
                        when (event) {
                            is AgentEvent.TextDelta -> { answer.append(event.text); output.append(event.text) }
                            is AgentEvent.ResultAvailable -> output.append("\n" + event.result.response)
                            is AgentEvent.NativeToolCallStarted -> {
                                lastTool = event.toolName + "\n" + event.arguments.take(2000)
                                info.text = "正在完成需求……"
                            }
                            is AgentEvent.ConfirmationRequired -> confirm(owner, event.pending, lastTool)
                            else -> Unit
                        }
                    } }
                }
                resetRuntime()
                if(input.text.toString().trim()==text) input.text.clear()
                openPaper(pdf, true)
            } catch (e: CancellationException) { resetRuntime(); throw e }
            catch (e: Exception) { output.append("\n未完成：${e.message?.take(160) ?: e.javaClass.simpleName}"); resetRuntime() }
            finally {if(!openingPage) controls.forEach {it.isEnabled=true};stop.text="发送";choice.post {indexChanged()}}
        }
    }
    private fun confirm(owner: AgentRuntime, pending: PendingConfirmation, tool: String) {
        fun decide(choice: ConfirmationChoice) { scope.launch {
            owner.resumeConfirmation(ConfirmationDecision(pending.pendingId, UUID.randomUUID().toString(), choice))
        } }
        AlertDialog.Builder(this).setTitle("工具操作").setMessage(tool.ifBlank { "运行时请求继续当前任务，是否执行？" })
            .setPositiveButton("执行") { _, _ -> decide(ConfirmationChoice.APPROVE) }
            .setNegativeButton("取消") { _, _ -> decide(ConfirmationChoice.DENY) }
            .setOnCancelListener { decide(ConfirmationChoice.DENY) }.show()
    }
    private fun showFiles() {
        val root = current().root
        val entries = root.walkTopDown().maxDepth(10).onEnter { !it.name.startsWith('.') }
            .filter { it.isFile && it.extension.lowercase() in setOf("md", "txt", "json", "html", "pdf") }.take(200).toList()
        AlertDialog.Builder(this).setTitle("工作区文件").setItems(entries.map { it.relativeTo(root).path }.toTypedArray()) { _, index ->
            val file = entries[index]
            if (file.extension.lowercase() == "pdf") { openPaper(file, true); return@setItems }
            val content = TextView(this).apply {
                text = file.reader().use { reader -> val buffer = CharArray(256000); val count = reader.read(buffer); String(buffer, 0, count.coerceAtLeast(0)) }
                textSize = 18f; setPadding(24, 16, 24, 16); setTextIsSelectable(true)
            }
            AlertDialog.Builder(this).setTitle(file.name).setView(ScrollView(this).apply { addView(content) })
                .setPositiveButton("留白手写页") { _, _ -> openPaper(file, true) }
                .setNeutralButton("紧凑手写页") { _, _ -> openPaper(file, false) }
                .setNegativeButton("关闭", null).show()
        }.setNegativeButton("关闭", null).show()
    }
    private fun openCanvas() {
        if(openingPage) return
        openingPage=true
        val workspace = current()
        controls.forEach {it.isEnabled=false}
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { WorkspaceDrawing.open(workspace) }
                val prefs = getSharedPreferences("moling",MODE_PRIVATE)
                val last = prefs.getString("paper_last_pdf_${workspace.id}",null)?.let { java.io.File(it).canonicalFile }
                    ?.takeIf { it.isFile && it.path.startsWith(workspace.root.canonicalPath+"/memory/pages/") }
                val pdf = last ?: page.pdf
                startActivity(Intent(this@AgentWorkspaceActivity,com.opc.baize.paper.PaperActivity::class.java)
                    .putExtra("workspace",workspace.id).putExtra("generatedPdf",pdf.path)
                    .putExtra("sourcePath",WorkspaceDrawing.sourcePath(workspace,pdf)))
            } catch (e: Exception) { output.append("\n打开画布失败：${e.message}") }
            finally {openingPage=false;controls.forEach {it.isEnabled=true};indexChanged()}
        }
    }
    private fun openPaper(source: java.io.File?, spacious: Boolean) {
        if(openingPage) return
        openingPage=true
        val workspace = current()
        controls.forEach { it.isEnabled = false }
        scope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) {
                    if (source?.extension?.lowercase() == "pdf") source else
                        java.io.File(workspace.root, "memory/pages/paper-${UUID.randomUUID()}.pdf").also {
                            LearningPageRenderer.render(source, it, spacious)
                        }
                }
                startActivity(Intent(this@AgentWorkspaceActivity, com.opc.baize.paper.PaperActivity::class.java)
                    .putExtra("workspace", workspace.id).putExtra("generatedPdf", pdf.absolutePath)
                    .putExtra("sourcePath", source?.takeIf { it.extension == "pdf" }?.let { LearningTurn.sourcePath(it) }
                        ?: source?.let { "/" + it.relativeTo(workspace.root).path })
                    .putExtra("autoStudy", true))
            } catch (e: Exception) { output.append("\n打开学习页失败：${e.message}") }
            finally {openingPage=false;controls.forEach {it.isEnabled=true}}
        }
    }
    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_SKILL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return; val workspace = current()
        controls.forEach { it.isEnabled = false }
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { files.importSkill(workspace, uri) }
                resetRuntime(); showInfo(); Toast.makeText(this@AgentWorkspaceActivity, "已导入 $name", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { Toast.makeText(this@AgentWorkspaceActivity, e.message ?: "导入失败", Toast.LENGTH_LONG).show() }
            finally { controls.forEach { it.isEnabled = true } }
        }
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putString("draft",input.text.toString())
        state.putString("selected_workspace",current().id)
        if(draftWorkspaceId.isNotEmpty()) drafts[draftWorkspaceId]=input.text.toString()
        state.putBundle("workspace_drafts",Bundle().apply {drafts.forEach {(id,text)->putString(id,text)}})
        super.onSaveInstanceState(state)
    }
    @Deprecated("Platform back callback")
    override fun onBackPressed() {
        if(job?.isActive==true) AlertDialog.Builder(this).setTitle("停止任务并返回？").setMessage("已生成的工作区文件会保留。")
            .setNegativeButton("继续任务",null).setPositiveButton("停止并返回") {_,_->job?.cancel();finish()}.show()
        else super.onBackPressed()
    }
    private val indexChanged: () -> Unit = { runOnUiThread {
        if (!isFinishing && job?.isActive != true && !openingPage && ::choice.isInitialized) {
            val selected = workspaces.getOrNull(choice.selectedItemPosition)?.id
            if (files.list() != workspaces) reload(selected)
        }
    } }
    override fun onResume() {
        super.onResume()
        if(intent.getBooleanExtra("canvasPanel",false)) {
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,(resources.displayMetrics.heightPixels*.78f).toInt())
            window.setGravity(android.view.Gravity.BOTTOM)
            setFinishOnTouchOutside(false)
        }
        WorkspaceEvents.subscribe(indexChanged)
        indexChanged()
    }
    override fun onPause() { WorkspaceEvents.unsubscribe(indexChanged); super.onPause() }
    override fun onDestroy() { scope.cancel(); resetRuntime(); super.onDestroy() }
    companion object { private const val IMPORT_SKILL = 704 }
}

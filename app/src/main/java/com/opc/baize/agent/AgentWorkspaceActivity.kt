@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
package com.opc.baize.agent

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
    private lateinit var input: EditText
    private val controls = mutableListOf<android.view.View>()
    private var workspaces = emptyList<WeWorkspace>()
    private var runtime: AgentRuntime? = null
    private var runtimeWorkspace = ""
    private var job: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); files = WorkspaceFiles(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; root.addView(this) }
        label("WE 工作区", 26f)
        label("Skill、MCP 和工具循环在 Android 内执行，模型使用已登录的订阅。当前是原生运行入口，纸面排版与笔迹操作将在此基础上接入。")
        choice = Spinner(this); root.addView(choice); controls += choice
        button(root, "新建工作区") {
            val name = EditText(this).apply { hint = "工作区名称" }
            AlertDialog.Builder(this).setTitle("新建工作区").setView(name).setNegativeButton("取消", null)
                .setPositiveButton("创建") { _, _ -> safe { val created = files.create(name.text.toString()); reload(created.id) } }.show()
        }
        info = label("")
        button(root, "导入 Skill 文件夹") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT_SKILL)
        }
        button(root, "设置 MCP") {
            val workspace = current()
            val editor = EditText(this).apply { setText(files.mcp(workspace)); minLines = 5; gravity = android.view.Gravity.TOP }
            AlertDialog.Builder(this).setTitle("公开 MCP（Streamable HTTP）")
                .setMessage("填写 JSON 数组，例如 [{\"id\":\"wiki\",\"url\":\"https://mcp.deepwiki.com/mcp\"}]。当前支持无需认证的 HTTPS 服务。")
                .setView(editor).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ -> safe {
                    files.saveMcp(workspace, editor.text.toString()); resetRuntime(); showInfo()
                } }.show()
        }
        button(root, "查看工作区文件") { showFiles() }
        input = EditText(this).apply { hint = "写下目标，或指定要调用的 Skill"; minLines = 3; gravity = android.view.Gravity.TOP; textSize = 18f }
        root.addView(input); controls += input
        button(root, "执行") { val text = input.text.toString().trim(); if (text.isNotEmpty()) run(text) }
        button(root, "验证本机工具调用（使用订阅额度）") {
            run("请使用工作区文件工具创建 /memory/pages/we-native-probe-${System.currentTimeMillis()}.md，内容为 WE_NATIVE_OK；然后用读取工具读取该文件，最后报告读取结果。必须实际调用工具。")
        }
        root.addView(Button(this).apply { text = "停止当前任务"; setOnClickListener { job?.cancel(); output.append("\n已请求停止。") } })
        output = label("", 17f).apply { setTextIsSelectable(true) }
        choice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                resetRuntime(); showInfo()
                output.text = current().transcript.takeIf { it.isFile }?.readText()?.takeLast(24000) ?: ""
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (files.list().isEmpty()) files.create("我的工作区")
        reload(getPreferences(MODE_PRIVATE).getString("workspace", ""))
    }
    private fun button(root: LinearLayout, title: String, call: () -> Unit) {
        val button = Button(this).apply { text = title; setOnClickListener { safe(call) } }
        controls += button; root.addView(button)
    }
    private fun safe(call: () -> Unit) { try { call() } catch (e: Exception) { Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show() } }
    private fun current() = workspaces[choice.selectedItemPosition.coerceAtLeast(0)]
    private fun reload(selected: String? = null) {
        workspaces = files.list()
        choice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, workspaces.map { it.name })
        choice.setSelection(workspaces.indexOfFirst { it.id == selected }.coerceAtLeast(0)); showInfo()
    }
    private fun showInfo() {
        val workspace = current(); getPreferences(MODE_PRIVATE).edit().putString("workspace", workspace.id).apply()
        info.text = "本机工作区：${workspace.name}\nSkills：${files.skillNames(workspace).joinToString().ifEmpty { "尚未导入" }}"
    }
    private fun resetRuntime() { runtime?.close(); runtime = null; runtimeWorkspace = "" }
    private fun run(text: String) {
        if (job?.isActive == true) return
        val workspace = current(); controls.forEach { it.isEnabled = false }
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
                owner.run(AgentRequest(UUID.randomUUID().toString(), workspace.id, text)).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> { answer.append(event.text); output.append(event.text) }
                        is AgentEvent.ResultAvailable -> if (event.result.response.isNotBlank() && event.result.response != answer.toString()) {
                            if (answer.isNotEmpty()) output.append("\n")
                            answer.clear(); answer.append(event.result.response); output.append(event.result.response)
                        }
                        is AgentEvent.NativeToolCallStarted -> {
                            lastTool = event.toolName + "\n" + event.arguments.take(2000)
                            output.append("\n[${event.toolName}]\n")
                        }
                        is AgentEvent.ConfirmationRequired -> confirm(owner, event.pending, lastTool)
                        is AgentEvent.Failed -> { output.append("\n任务未完成：${event.code}"); error(event.code) }
                        else -> Unit
                    }
                }
                withContext(Dispatchers.IO) {
                    val previous = workspace.transcript.takeIf { it.isFile }?.readText().orEmpty()
                    val atomic = android.util.AtomicFile(workspace.transcript); val out = atomic.startWrite()
                    try { out.write((previous + "\n你：$text\nWE：$answer\n").takeLast(256000).toByteArray()); atomic.finishWrite(out) }
                    catch (e: Exception) { atomic.failWrite(out); throw e }
                }
                output.append("\n")
            } catch (e: CancellationException) { resetRuntime(); throw e }
            catch (e: Exception) { output.append("\n未完成：${e.message?.take(160) ?: e.javaClass.simpleName}"); resetRuntime() }
            finally { controls.forEach { it.isEnabled = true } }
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
            .filter { it.isFile && it.extension.lowercase() in setOf("md", "txt", "json", "html") }.take(200).toList()
        AlertDialog.Builder(this).setTitle("工作区文件").setItems(entries.map { it.relativeTo(root).path }.toTypedArray()) { _, index ->
            val file = entries[index]
            val content = TextView(this).apply {
                text = file.reader().use { reader -> val buffer = CharArray(256000); val count = reader.read(buffer); String(buffer, 0, count.coerceAtLeast(0)) }
                textSize = 18f; setPadding(24, 16, 24, 16); setTextIsSelectable(true)
            }
            AlertDialog.Builder(this).setTitle(file.name).setView(ScrollView(this).apply { addView(content) }).setPositiveButton("关闭", null).show()
        }.setNegativeButton("关闭", null).show()
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
    override fun onDestroy() { scope.cancel(); resetRuntime(); super.onDestroy() }
    companion object { private const val IMPORT_SKILL = 704 }
}

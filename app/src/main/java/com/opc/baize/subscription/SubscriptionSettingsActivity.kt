package com.opc.baize.subscription

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.*
import android.view.View
import android.widget.*
import com.opc.baize.model.*
import java.util.concurrent.Executors
import java.io.ByteArrayOutputStream

/** Native UI observes application-owned auth; model discovery and tests remain independent actions. */
class SubscriptionSettingsActivity : Activity() {
    private lateinit var gateway: SubscriptionGateway
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var session: TextView
    private lateinit var active: TextView
    private lateinit var tests: TextView
    private lateinit var diagnostics: TextView
    private lateinit var textChoice: Spinner
    private lateinit var imageChoice: Spinner
    private lateinit var directVision: CheckBox
    private lateinit var cancel: Button
    private val buttons = mutableListOf<Button>()
    private var catalog = emptyList<ModelCatalog.Entry>()
    @Volatile private var destroyed = false
    private var busy = false
    private var resumed = false
    private var observedLogin = -1L
    private var observedCatalog: List<ModelCatalog.Entry>? = null
    private val heartbeat = android.os.Handler(android.os.Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() { if (!destroyed && resumed) { update(); heartbeat.postDelayed(this, 500) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); gateway = SubscriptionGateway.get(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String, size: Float = 15f) = TextView(this).apply { this.text = text; textSize = size; root.addView(this) }
        label("ChatGPT 订阅模型", 24f)
        label("版本：${com.opc.baize.BuildConfig.VERSION_NAME}", 12f)
        label("列表来自当前账号，不内置固定模型。文字和图片/手写可分别选择；图片能力以实际测试为准。登录信息通过 Android Keystore 加密保存在本机，退出页面或重启应用会恢复；明确退出登录会删除本地凭据。")
        session = label(""); active = label("")
        status = label("先登录，再刷新列表、选择模型并保存。已有会话不必重复登录。").apply { setTextIsSelectable(true) }
        button(root, "Continue with ChatGPT") { gateway.startSignIn(); observedLogin = -1; update() }
        action(root, "刷新账号模型列表") { val found = gateway.catalog(); "已读取 ${found.size} 个模型。请选择文字和图片模型并保存。" }
        label("文字回答模型")
        textChoice = Spinner(this); root.addView(textChoice)
        label("图片 / 手写识别 / PDF 批改模型")
        imageChoice = Spinner(this); root.addView(imageChoice)
        directVision = CheckBox(this).apply { text = "直接使用订阅图片识别（跳过本机识别等待）"; isChecked = SubscriptionSelection.read(this@SubscriptionSettingsActivity).directVision }; root.addView(directVision)
        label("关闭时优先本机识别，失败后使用订阅图片。图片转写和文字回答分别请求，均使用订阅额度。")
        button(root, "保存选择并启用订阅") {
            try {
                check(gateway.isAuthorized) { "请先完成登录" }
                SubscriptionSelection.save(this, selected(), catalog)
                status.text = "已保存并启用。返回墨灵即可书写；PDF 使用图片模型。"; update()
            } catch (e: Exception) { status.text = if (!gateway.isAuthorized) "请先完成登录。" else "请从当前账号列表中选择模型后保存。" }
        }
        tests = label("")
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { showChecks() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        textChoice.onItemSelectedListener = listener; imageChoice.onItemSelectedListener = listener
        button(root, "测试文字模型（使用订阅额度）") {
            val choice = selected()
            task {
                choice.validate(catalog.map { it.id }.toSet())
                try {
                    val answer = SubscriptionModelProvider(gateway, choice.textModel).reply(emptyList(), "连通性测试：请只回答 MOLING_OK。")
                    gateway.recordCheck(choice.textModel, false, "请求已完成")
                    "文字模型 ${choice.textModel} 请求已完成。\n输出：$answer"
                } catch (e: Exception) { gateway.recordCheck(choice.textModel, false, "失败，见操作结果"); throw e }
            }
        }
        button(root, "测试图片及手写转写（使用订阅额度）") {
            val choice = selected()
            task {
                choice.validate(catalog.map { it.id }.toSet()); val id = choice.effectiveImageModel()
                try {
                    val answer = SubscriptionModelProvider(gateway, id).recognizeInk(fixture())
                    require(answer.contains("7") && answer.contains("5")) { "image_transcription_mismatch" }
                    gateway.recordCheck(id, true, "图片转写已通过")
                    "图片模型 $id 已完成手写转写流程。\n识别结果：$answer"
                } catch (e: Exception) { gateway.recordCheck(id, true, "失败或结果不符，见操作结果"); throw e }
            }
        }
        label("测试使用当前下拉选择；只有点击保存才会更改主界面正在使用的模型。")
        label("Skill / MCP 引擎连接", 20f)
        label("连接电脑上的 OpenCode，复用当前订阅登录。手动开启，仅监听本机；通过 ADB 转发连接。此阶段需保持应用运行，尚未集成纸面工具。")
        action(root, "开启 Skill / MCP 引擎连接") { gateway.startAgentBridge() }
        button(root, "查看引擎连接信息") { status.text = gateway.agentBridgeInfo() }
        button(root, "关闭引擎连接") { gateway.stopAgentBridge(); status.text = gateway.agentBridgeInfo() }
        action(root, "恢复保存的登录 / 重试身份校验") { gateway.restoreSavedSession() }
        action(root, "刷新登录令牌（接近过期时）") { gateway.refreshSession() }
        action(root, "退出并撤销订阅会话") { gateway.signOut() }
        cancel = Button(this).apply { text = "取消正在进行的授权"; setOnClickListener { gateway.cancel(); observedLogin = -1; update() } }; root.addView(cancel)
        root.addView(Button(this).apply { text = "ChatGPT 用量设置"; setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) } catch (e: Exception) { status.text = "请手动打开 ChatGPT 设置 → 用量。" } } })
        root.addView(Button(this).apply { text = "返回墨灵"; setOnClickListener { finish() } })
        diagnostics = label("").apply { setTextIsSelectable(true) }
        showModels(gateway.cachedCatalog())
    }
    private fun selected(): SubscriptionChoice {
        val text = catalog.getOrNull(textChoice.selectedItemPosition - 1)?.id ?: ""
        val image = catalog.getOrNull(imageChoice.selectedItemPosition - 1)?.id ?: ""
        return SubscriptionChoice(text, image, directVision.isChecked)
    }
    private fun showModels(found: List<ModelCatalog.Entry>) {
        catalog = found; observedCatalog = found
        val saved = SubscriptionSelection.read(this)
        textChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("请选择文字模型") + found.map { it.toString() })
        imageChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("与文字模型相同") + found.map { it.toString() })
        textChoice.setSelection(found.indexOfFirst { it.id == saved.textModel }.let { if (it < 0) 0 else it + 1 })
        imageChoice.setSelection(found.indexOfFirst { it.id == saved.imageModel }.let { if (it < 0) 0 else it + 1 })
        showChecks()
    }
    private fun showChecks() {
        if (!::tests.isInitialized || !::textChoice.isInitialized || !::imageChoice.isInitialized) return
        val choice = selected()
        tests.text = "当前候选测试状态：\n文字：${gateway.modelCheck(choice.textModel, false)}\n图片：${gateway.modelCheck(choice.effectiveImageModel(), true)}"
    }
    private fun update() {
        if (!::diagnostics.isInitialized) return
        val running = gateway.isLoginRunning
        val url = if (resumed) gateway.takeBrowserUrl() else null
        if (url != null) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { gateway.cancel(); status.text = "无法打开系统浏览器，请重新登录。" }
        session.text = if (running) "授权处理中，浏览器完成后返回本页查看最终结果。" else if (gateway.isAuthorized) "登录：已通过订阅授权；请求前自动检查令牌刷新。" else "登录：未连接或需要重新登录。"
        val choice = SubscriptionSelection.read(this)
        val selectedProvider = getSharedPreferences("moling", MODE_PRIVATE).getString("model_provider", "anthropic")
        active.text = "生效通道：${if (selectedProvider == "subscription") "ChatGPT 订阅" else "原 API"}\n已保存文字：${choice.textModel.ifBlank { "未选择" }}\n已保存图片：${choice.effectiveImageModel().ifBlank { "未选择" }}"
        val version = gateway.loginVersion(); val result = gateway.loginResult()
        if (!busy && !running && version != observedLogin && result.isNotEmpty()) {
            status.text = result + if (gateway.isAuthorized) "\n请刷新账号模型列表并选择模型。" else ""; observedLogin = version
        }
        if (observedCatalog !== gateway.cachedCatalog() && !busy) showModels(gateway.cachedCatalog())
        buttons.forEach { it.isEnabled = !busy && !running }
        textChoice.isEnabled = !busy && !running; imageChoice.isEnabled = !busy && !running; directVision.isEnabled = !busy && !running
        cancel.visibility = if (running) View.VISIBLE else View.GONE
        showChecks(); diagnostics.text = gateway.diagnostics()
    }
    private fun button(root: LinearLayout, label: String, call: () -> Unit) {
        val b = Button(this).apply { text = label; setOnClickListener { call() } }; buttons.add(b); root.addView(b)
    }
    private fun action(root: LinearLayout, label: String, call: () -> String) = button(root, label) { task(call) }
    private fun task(call: () -> String) {
        if (busy || gateway.isLoginRunning) return
        busy = true; update(); status.text = "正在执行，请等待结果……"
        worker.execute {
            val result = try { call() } catch (e: Exception) { SubscriptionErrors.describe(e) }
            runOnUiThread { if (!destroyed) { busy = false; observedLogin = gateway.loginVersion(); update(); status.text = result } }
        }
    }
    private fun fixture(): String {
        val bitmap = Bitmap.createBitmap(400, 180, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawText("7 + 5 = ?", 35f, 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
            val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            "data:image/png;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
    override fun onResume() { super.onResume(); resumed = true; heartbeat.removeCallbacks(monitor); heartbeat.post(monitor) }
    override fun onPause() { resumed = false; heartbeat.removeCallbacks(monitor); super.onPause() }
    override fun onDestroy() { destroyed = true; heartbeat.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy() }
}

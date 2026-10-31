package com.opc.baize.mcp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import android.content.*
import java.util.concurrent.Executors

class McpSettingsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var state: TextView
    private var alive = true
    private val monitor = object : Runnable { override fun run() { if (alive) { state.text = McpService.status; handler.postDelayed(this, 1000) } } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 16f; setPadding(0, 12, 0, 12); root.addView(this) }
        fun button(text: String, action: () -> Unit) = root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        label("Agent 连接 · MCP")
        label("外部 Agent 可查看工作区和已保存笔迹、读写资料与 Skill，并生成新的学习页。不会读取 ChatGPT 登录凭据。连接开启后在通知栏运行，停止后立即断开。")
        state = label(McpService.status)
        button("开启连接") {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 44)
            startForegroundService(Intent(this, McpService::class.java))
        }
        button("停止连接") { stopService(Intent(this, McpService::class.java)) }
        label("公网连接：填写独立部署的 WE 中继 HTTPS 根地址，首次配对后 MCP 地址固定。中继仅转发指令，工具在此 Android 执行。不要填写 WordTok 中继。当前版本未附带已托管公网地址。")
        val address = EditText(this).apply { hint = "https://你的 WE 中继域名"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true }; root.addView(address)
        button("与中继配对") {
            if (McpService.running) { state.text = "请先停止连接再更换配对。"; return@button }
            val raw = address.text.toString()
            worker.execute {
                val message = try {
                    check(McpPairing(this).read() == null) { "已有配对，请先解除" }
                    McpPairing(this).save(McpRelayClient.pair(raw)); "配对已保存，请开启连接并复制公网 MCP 地址。"
                } catch (e: Exception) { e.message?.takeIf { !it.contains("http") } ?: "配对未完成，请检查中继服务。" }
                runOnUiThread { if (alive) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
        }
        button("复制公网 MCP 地址") {
            try {
                val p = requireNotNull(McpPairing(this).read()) { "请先配对" }
                copy(p.getString("base") + "/mcp/" + p.getString("agentToken"))
            } catch (_: Exception) { state.text = "尚无可用配对。" }
        }
        button("解除配对") {
            AlertDialog.Builder(this).setTitle("解除外部 Agent 配对？").setMessage("会停止连接并请求中继撤销旧 MCP 地址；工作区资料保留。离线时撤销不会成功。")
                .setNegativeButton("取消", null).setPositiveButton("解除") { _, _ ->
                    stopService(Intent(this, McpService::class.java))
                    worker.execute {
                        val message = try {
                            val store = McpPairing(this); val p = store.read()
                            if (p != null) {
                                val http = McpRelayClient.http()
                                try {
                                    http.newCall(okhttp3.Request.Builder().url(McpRelayClient.validateBase(p.getString("base")) + "/device/close")
                                        .header("Authorization", "Bearer " + p.getString("deviceToken"))
                                        .post(okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute().use { check(it.isSuccessful || it.code == 401) }
                                } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
                            }
                            store.clear(); "已解除配对，旧地址已撤销。"
                        } catch (_: Exception) { "撤销未确认，配对信息已保留，请联网后重试。" }
                        runOnUiThread { if (alive) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                    }
                }.show()
        }
        label("本机直连：仅供同一 Android 上支持自定义 Authorization 请求头的 MCP 客户端使用。地址 http://127.0.0.1:49327/mcp；连接密钥每次启动更新。公网 Agent 使用上面的固定配对地址。")
        button("复制本机 MCP 配置") {
            if (!McpService.running) { state.text = "请先开启连接。"; return@button }
            val config = org.json.JSONObject().put("mcpServers", org.json.JSONObject().put("we", org.json.JSONObject()
                .put("url", "http://127.0.0.1:49327/mcp").put("headers", org.json.JSONObject().put("Authorization", "Bearer " + McpService.localToken))))
            copy(config.toString(2))
        }
        label("MCP 地址和配置包含访问密钥，请只粘贴给你信任的 Agent。系统强制停止或后台限制可能断开连接；本版不在开机时启动。")
    }
    private fun copy(text: String) {
        val clip = ClipData.newPlainText("WE MCP", text).apply {
            if (android.os.Build.VERSION.SDK_INT >= 33) description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "已复制，请妥善保管", Toast.LENGTH_SHORT).show()
    }
    override fun onResume() { super.onResume(); handler.post(monitor) }
    override fun onPause() { handler.removeCallbacks(monitor); super.onPause() }
    override fun onDestroy() { alive = false; handler.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy() }
}

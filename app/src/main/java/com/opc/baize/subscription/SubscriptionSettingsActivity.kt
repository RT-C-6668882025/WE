package com.opc.baize.subscription

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.*
import java.util.concurrent.Executors

/** Native settings page; no credential export and no request on the handwriting UI thread. */
class SubscriptionSettingsActivity : Activity() {
    private lateinit var gateway: SubscriptionGateway
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var choices: Spinner
    private val buttons = mutableListOf<Button>()
    @Volatile private var destroyed = false
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = SubscriptionGateway.get(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text = "ChatGPT 订阅"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "使用已验证的官方授权通道。模型请求使用订阅额度。当前版本令牌仅保留在内存，进程结束后需重新登录；书写和笔迹保存不依赖登录。"
        })
        status = TextView(this).apply { text = if (gateway.isAuthorized) "已授权，可读取模型列表。" else "请登录后读取并选择模型。"; setTextIsSelectable(true) }
        root.addView(status)
        action(root, "Continue with ChatGPT") {
            val result = gateway.signIn { url -> runOnUiThread {
                if (!destroyed) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (e: Exception) { gateway.cancel(); status.text = "无法打开浏览器，请重试。" }
            } }
            showModels(gateway.models())
            result + "\n请选择模型并启用。"
        }
        action(root, "刷新模型列表") { showModels(gateway.models()); "已获取账号模型，请选择并启用。" }
        choices = Spinner(this); root.addView(choices)
        val apply = Button(this).apply {
            text = "使用所选模型"
            setOnClickListener {
                val model = choices.selectedItem?.toString()
                if (!gateway.isAuthorized || model.isNullOrBlank()) { status.text = "请先登录并获取模型。"; return@setOnClickListener }
                getSharedPreferences("moling", MODE_PRIVATE).edit().putString("model_provider", "subscription")
                    .putString("subscription_model", model).apply()
                status.text = "已启用 $model，返回墨灵即可书写提问。"
            }
        }; root.addView(apply); buttons.add(apply)
        action(root, "退出并撤销订阅会话") { gateway.signOut() }
        root.addView(Button(this).apply { text = "返回墨灵"; setOnClickListener { finish() } })
        if (gateway.isAuthorized) task { showModels(gateway.models()); "请选择模型并启用。" }
    }
    private fun showModels(models: List<String>) = runOnUiThread {
        if (destroyed) return@runOnUiThread
        choices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        val saved = getSharedPreferences("moling", MODE_PRIVATE).getString("subscription_model", "")
        val index = models.indexOf(saved); if (index >= 0) choices.setSelection(index)
    }
    private fun action(root: LinearLayout, label: String, call: () -> String) {
        val button = Button(this).apply { text = label; setOnClickListener { task(call) } }
        buttons.add(button); root.addView(button)
    }
    private fun task(call: () -> String) {
        busy = true; buttons.forEach { it.isEnabled = false }; status.text = "处理中，请完成浏览器授权后返回……"
        worker.execute {
            val result = try { call() } catch (e: SubscriptionProtocol.Failure) {
                "未通过：${e.code}" + (if (e.status != 0) "（HTTP ${e.status}）" else "") + "\n可重试登录，或返回设置切换原 API。"
            } catch (e: Exception) { "连接未完成，请重试。令牌未输出。" }
            runOnUiThread { if (!destroyed) { busy = false; buttons.forEach { it.isEnabled = true }; status.text = result } }
        }
    }
    override fun onDestroy() {
        destroyed = true
        if (busy) gateway.cancel()
        worker.shutdownNow(); super.onDestroy()
    }
}

package com.opc.baize.mcp

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** The normal flow is one tap: pair if needed, persist identity, start connection, copy one URL. */
open class McpSettingsActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var state:TextView
    private lateinit var copyButton:Button
    private lateinit var connectionSwitch:Switch
    private var alive=true
    private var busy=false
    private var failure:String?=null
    private var updatingSwitch=false
    private val prefs by lazy {getSharedPreferences("we-mcp",MODE_PRIVATE)}
    private val monitor=object:Runnable {override fun run() {if(alive) {renderState();handler.postDelayed(this,1000)}}}
    protected open fun preparePairing():JSONObject {
        val store=McpPairing(this)
        return PublicMcpConnection(store::read,store::save,clear=store::clear).ensure()
    }
    protected open fun connectionTask(task:()->Unit) {worker.execute(task)}
    protected open fun startConnection() {
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),44)
        startForegroundService(Intent(this,McpService::class.java))
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        setContentView(ScrollView(this).apply {addView(root)})
        fun label(text:String)=TextView(this).apply {this.text=text;textSize=16f;setPadding(0,12,0,12);root.addView(this)}
        label("Agent 连接 · MCP")
        label("复制一个地址，粘贴给 Muse 或其他 Agent，即可连接这个设备的工作区。首次使用会自动准备连接，以后沿用同一个地址。")
        state=label("点击下方按钮，复制并开启连接")
        copyButton=Button(this).apply {text="复制 MCP 地址";setOnClickListener {connect(true)}};root.addView(copyButton)
        connectionSwitch=Switch(this).apply {
            text="保持连接";isChecked=prefs.getBoolean("enabled",false)
            setOnCheckedChangeListener {_,checked -> if(!updatingSwitch) {
                if(checked) connect(false) else stopConnection()
            }}
        };root.addView(connectionSwitch)
        root.addView(Button(this).apply {text="高级设置";setOnClickListener {if(!busy) advanced()}})
        label("地址包含访问密钥，只交给你信任的 Agent。连接期间通知栏会显示状态；网络或系统后台限制可能导致断开。")
        if(prefs.getBoolean("enabled",false)) connect(false)
    }
    private fun connect(copyWhenReady:Boolean) {
        if(busy) return
        busy=true;failure=null;copyButton.isEnabled=false;renderState()
        connectionTask {
            try {
                val pairing=preparePairing()
                val address=PublicMcpConnection.address(pairing)
                runOnUiThread {if(alive) {
                    try {
                        startConnection();prefs.edit().putBoolean("enabled",true).apply();setSwitch(true)
                        if(copyWhenReady) copy(address)
                    } catch(_:Exception) {failure="连接未启动，请重试；已保存的地址保留。"}
                    busy=false;copyButton.isEnabled=true;renderState()
                }}
            } catch(_:Exception) {runOnUiThread {if(alive) {
                busy=false;copyButton.isEnabled=true;failure="连接准备失败，请检查网络后点击重试。";renderState()
            }}}
        }
    }
    private fun setSwitch(value:Boolean) {updatingSwitch=true;connectionSwitch.isChecked=value;updatingSwitch=false}
    private fun stopConnection() {
        prefs.edit().putBoolean("enabled",false).apply()
        stopService(Intent(this,McpService::class.java));setSwitch(false)
        failure=null;renderState()
    }
    private fun renderState() {
        state.text=when {
            busy -> "正在准备连接…"
            failure!=null -> failure
            !prefs.getBoolean("enabled",false) -> "已停止 · 再次复制地址即可连接"
            McpService.status=="已停止" -> "正在连接…"
            else -> McpService.status
        }
        copyButton.text=if(failure!=null) "重试并复制 MCP 地址" else "复制 MCP 地址"
    }
    private fun advanced() {
        AlertDialog.Builder(this).setTitle("高级设置")
            .setItems(arrayOf("复制本机 MCP 配置","解除配对")) {_,which ->
                if(which==0) {
                    if(!McpService.running) {Toast.makeText(this,"请先开启连接",Toast.LENGTH_SHORT).show();return@setItems}
                    val config=JSONObject().put("mcpServers",JSONObject().put("we",JSONObject()
                        .put("url","http://127.0.0.1:49327/mcp")
                        .put("headers",JSONObject().put("Authorization","Bearer "+McpService.localToken))))
                    copy(config.toString(2))
                } else confirmRevoke()
            }.setNegativeButton("关闭",null).show()
    }
    private fun confirmRevoke() {
        AlertDialog.Builder(this).setTitle("解除配对？").setMessage("原 MCP 地址将失效，工作区资料保留。")
            .setNegativeButton("取消",null).setPositiveButton("解除") {_,_->
                stopConnection();busy=true;copyButton.isEnabled=false;renderState()
                connectionTask {
                    val success=try {
                        val store=McpPairing(this)
                        PublicMcpConnection(store::read,store::save,clear=store::clear).revoke {p ->
                            val http=McpRelayClient.http()
                            try {
                                http.newCall(okhttp3.Request.Builder().url(McpRelayClient.validateBase(p.getString("base"))+"/device/close")
                                    .header("Authorization","Bearer "+p.getString("deviceToken"))
                                    .post(okhttp3.RequestBody.create(null,ByteArray(0))).build()).execute().use {
                                        check(it.isSuccessful || it.code==401)
                                    }
                            } finally {http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
                        };true
                    } catch(_:Exception) {false}
                    runOnUiThread {if(alive) {
                        busy=false;copyButton.isEnabled=true
                        failure=if(success) null else "撤销未确认，原地址已保留，请联网后重试。"
                        renderState()
                        if(success) Toast.makeText(this,"配对已解除",Toast.LENGTH_SHORT).show()
                    }}
                }
            }.show()
    }
    private fun copy(text:String) {
        val clip=ClipData.newPlainText("WE MCP",text).apply {
            if(android.os.Build.VERSION.SDK_INT>=33) description.extras=android.os.PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this,"MCP 地址已复制",Toast.LENGTH_SHORT).show()
    }
    override fun onResume() {super.onResume();handler.post(monitor)}
    override fun onPause() {handler.removeCallbacks(monitor);super.onPause()}
    override fun onDestroy() {alive=false;handler.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy()}
}

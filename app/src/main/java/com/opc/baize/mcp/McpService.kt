package com.opc.baize.mcp

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.content.pm.ServiceInfo
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Explicit opt-in foreground endpoint, independent of Activity lifetime. No boot startup. */
class McpService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var server: McpHttpServer? = null
    @Volatile private var relay: McpRelayClient? = null
    @Volatile private var stopped = false
    private var started = false
    companion object {
        @Volatile var running = false; private set
        @Volatile var status = "已停止"; private set
        @Volatile var localToken = ""; private set
        const val STOP = "com.opc.baize.mcp.STOP"
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("we-mcp", "WE Agent 连接", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, McpSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "we-mcp").setSmallIcon(com.opc.baize.R.mipmap.ic_launcher)
            .setContentTitle("WE · MCP 连接已开启").setContentText("外部 Agent 可操作工作区；点此查看状态")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(4401, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(4401, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { getSharedPreferences("we-mcp",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();status="已停止";stopSelf(); return START_NOT_STICKY }
        if (started) {
            if(relay==null) worker.execute { if(relay==null && !stopped) runRelay(WorkspaceMcpTools(this)) }
            return START_STICKY
        }
        getSharedPreferences("we-mcp",MODE_PRIVATE).edit().putBoolean("enabled",true).apply()
        started = true
        worker.execute {
            try {
                val tools = WorkspaceMcpTools(this)
                val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val http = McpHttpServer(tools, token)
                http.start(5000, true); server = http
                if (stopped) { http.stop(); return@execute }
                localToken = token; running = true
                status = "本机 MCP 已就绪"
                runRelay(tools)
            } catch (_: Exception) { status = "连接未启动，请检查端口占用或配对配置后重试"; stopSelf() }
        }
        return START_STICKY
    }
    private fun runRelay(tools:WorkspaceMcpTools) {
        val pairing=McpPairing(this).read() ?: return
        val connection=McpRelayClient(McpRelayClient.validateBase(pairing.getString("base")),pairing.getString("deviceToken"),tools)
        status="正在连接…"
        relay=connection
        var failures=0
        while(!stopped) {
            try {connection.poll();failures=0;status="MCP 已连接"}
            catch(_:PairingRevokedException) {status="配对已失效，请在高级设置中重新配对";break}
            catch(_:Exception) {if(stopped) break;failures++;status="正在重新连接…"}
            try {Thread.sleep(if(failures>0) minOf(30000L,2000L*failures) else 500L)} catch(_:InterruptedException) {break}
        }
        connection.close()
    }
    override fun onDestroy() {
        stopped = true; relay?.close(); server?.stop(); worker.shutdownNow(); running = false; localToken = ""
        if(!status.startsWith("连接未启动") && !status.startsWith("配对已失效")) status = "已停止"
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}

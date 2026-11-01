package com.opc.baize.mcp

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors

/** WordTok native session lifecycle: foreground polling, redelivered intent, no boot startup. */
class McpService : Service() {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var relay:McpRelayClient?=null
    @Volatile private var stopped=false
    private var started=false
    companion object {
        @Volatile var running=false;private set
        @Volatile var status="未配对";private set
        @Volatile var connectionState="unpaired";private set
        const val STOP="com.opc.baize.mcp.STOP"
        /** Same as WordTok loading a saved pair and attaching its native service on app startup. */
        fun resume(context:android.content.Context) {
            if(McpPairing(context).read()!=null) context.startForegroundService(Intent(context,McpService::class.java))
        }
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("we-mcp","AI 连接",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,McpSettingsActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"we-mcp").setSmallIcon(com.opc.baize.R.mipmap.ic_launcher)
            .setContentTitle("WE · AI 连接").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun state(value:String,text:String) {
        connectionState=value;status=text
        if(!stopped) getSystemService(NotificationManager::class.java).notify(4401,notification(text))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent==null || intent.action==STOP) {stopSelf();return START_NOT_STICKY}
        if(started)return START_REDELIVER_INTENT
        started=true;running=true
        if(android.os.Build.VERSION.SDK_INT>=34) startForeground(4401,notification("正在连接"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(4401,notification("正在连接"))
        state("connecting","正在连接")
        worker.execute {
            try {
                val pairing=McpPairing(this).read()
                if(pairing==null){state("unpaired","未配对");stopSelf();return@execute}
                val config=McpConnectionConfig.toWordTok(pairing)
                val connection=McpRelayClient(config.getString("url"),config.getString("token"),WorkspaceMcpTools(this)) {
                    state("working","AI 正在操作工作区")
                }
                relay=connection
                if(stopped){connection.close();return@execute}
                loop(connection)
            } catch(_:Exception) {
                if(!stopped){state("reconnecting","后台连接启动失败，请回到应用重连");stopSelf()}
            }
        }
        return START_REDELIVER_INTENT
    }
    private fun loop(connection:McpRelayClient) {
        val lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"WE:AgentSession")
        lock.setReferenceCounted(false)
        var failures=0
        try {while(!stopped) {
            lock.acquire(60000)
            try {
                val count=connection.poll()
                if(stopped)break
                failures=0;state("ready","已就绪 · 切换应用后仍可接收指令")
                if(count==0)Thread.sleep(4000)
            } catch(_:PairingRevokedException) {
                if(!stopped){state("revoked","配对已失效，请打开应用处理");stopSelf()};break
            } catch(_:Exception) {
                if(stopped)break
                state("reconnecting","连接中断，正在重连")
                try {Thread.sleep(minOf(15000L,1000L shl minOf(failures++,4)))}catch(_:InterruptedException){break}
            }
        }} finally {if(lock.isHeld)lock.release();connection.close()}
    }
    override fun onTaskRemoved(rootIntent:Intent?) {stopSelf()}
    override fun onDestroy() {
        stopped=true;relay?.close();worker.shutdownNow();running=false
        if(connectionState!="revoked") {connectionState="unpaired";status="已停止"}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}

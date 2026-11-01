package com.opc.baize.mcp

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException
import org.json.JSONException

/** User-visible diagnostics contain stages and categories, never response bodies or credentials. */
internal class McpSetupFailure(val stage:String,val detail:String,cause:Exception?=null):Exception("$stage：$detail",cause) {
    companion object {
        fun describe(error:Exception):String {
            if(error is McpSetupFailure) return "${error.stage}：${error.detail}"
            return when(error) {
                is UnknownHostException -> "无法解析 WE 服务器域名，请检查 DNS 或网络"
                is SocketTimeoutException -> "WE 服务器响应超时，请重试"
                is SSLException -> "HTTPS 连接或证书验证失败，请检查网络和系统时间"
                is ConnectException -> "无法连接 WE 服务器，请检查网络"
                is java.net.SocketException -> "连接被中断或重置，请检查服务器公网入口"
                is java.io.EOFException -> "服务器提前关闭了连接，请重试"
                is GeneralSecurityException -> "设备密钥库不可用，配对凭据未能读取或保存"
                is JSONException -> "配对配置格式不正确"
                else -> "连接准备未完成，请重试"
            }
        }
        fun <T> at(stage:String,action:()->T):T = try {action()} catch(e:Exception) {
            if(e is McpSetupFailure) throw e
            throw McpSetupFailure(stage,describe(e),e)
        }
    }
}

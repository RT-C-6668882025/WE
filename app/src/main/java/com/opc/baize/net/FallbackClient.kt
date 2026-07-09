package com.opc.baize.net

import com.opc.baize.core.FallbackProtocol
import java.net.HttpURLConnection
import java.net.URL

/**
 * 降级通道：设备无 GMS、ML Kit 初始化/识别抛异常时，把笔迹 PNG POST 给
 * 用户自建的 server.py /api/ask（它自带 OCR→大模型→回复全链路），直接用
 * 它返回的 reply，跳过本地大模型调用。
 * 地址是设置项（设置面板【手写识别】区可填），开源版默认空 = 不启用。
 */
class FallbackClient {

    fun ask(url: String, imageDataUrl: String, strokes: Int, session: String = "default"):
        FallbackProtocol.AskResult {
        val body = FallbackProtocol.buildRequestBody(imageDataUrl, session, strokes)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 90_000   // 后端链路可能 3~30 秒，口径同网页版 FETCH_TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else
                (conn.errorStream ?: throw RuntimeException("fallback HTTP $code 无响应体"))
            val resp = stream.bufferedReader().use { it.readText() }
            return FallbackProtocol.parseResponse(resp)
        } finally {
            conn.disconnect()
        }
    }
}

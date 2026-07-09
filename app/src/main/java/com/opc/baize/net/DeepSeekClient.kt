package com.opc.baize.net

import com.opc.baize.core.DeepSeekProtocol
import com.opc.baize.core.ModelConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * 大模型直连（Anthropic 兼容端点）。开源版：密钥/节点/模型名全部运行时由用户配置传入，
 * 源码不含任何硬编码密钥。
 * 失败重试 1 次（共 2 次尝试），再失败抛异常由调用方写失败文案。
 * 4xx（除 408/429）不重试——密钥错、请求非法这类错误重试只会白烧一次超时等待。
 * 教训：Anthropic 兼容端点用 x-api-key，不用 Bearer。
 */
class DeepSeekClient {

    /** 非 2xx 响应。code 供重试策略判断可否重试。 */
    class HttpException(val code: Int, message: String) : RuntimeException(message)

    fun reply(
        config: ModelConfig,
        history: List<Pair<String, String>>,
        userText: String,
    ): String {
        if (!config.isReady()) {
            throw IllegalStateException("大模型未配置（密钥/节点/模型名有空项）")
        }
        val url = DeepSeekProtocol.messagesUrl(config.endpoint)
        val body = DeepSeekProtocol.buildRequestBody(config.model.trim(), history, userText)
        var lastErr: Exception? = null
        repeat(2) {
            try {
                val text = postOnce(url, config.apiKey.trim(), body)
                if (text.isBlank()) throw RuntimeException("大模型返回空文本")
                return text
            } catch (e: HttpException) {
                // 408 超时 / 429 限流值得再试一次；其余 4xx 是配置或请求问题，重试无意义
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("大模型调用失败")
    }

    private fun postOnce(url: String, apiKey: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", DeepSeekProtocol.ANTHROPIC_VERSION)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { r -> r.readText() } ?: ""
                throw HttpException(code, "大模型 HTTP $code：${err.take(200)}")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            return DeepSeekProtocol.parseReplyText(resp)
        } finally {
            conn.disconnect()
        }
    }
}

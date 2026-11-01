package com.opc.baize.mcp

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Mirrors WordTok AiConnectionService.request; never logs URLs or credentials. */
internal class WordTokTransport : java.io.Closeable {
    data class Response(val code: Int, val body: String)
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var closed = false

    fun request(url: String, token: String? = null, body: JSONObject? = null,
                setup: Boolean = false, pair: Boolean = false): Response {
        check(!closed)
        val connection = URL(url).openConnection() as HttpURLConnection
        active = connection
        try {
            check(!closed)
            connection.connectTimeout = if (setup) 15000 else 5000
            connection.readTimeout = if (setup) 15000 else 10000
            connection.instanceFollowRedirects = false
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (pair) connection.requestMethod = "POST"
            if (body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            // Error bodies may contain sensitive data and are never surfaced.
            if (code !in 200..299) return Response(code, "")
            val value = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 4 * 1024 * 1024) { "Response too large" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
            return Response(code, value)
        } finally {
            connection.disconnect()
            if (active === connection) active = null
        }
    }
    override fun close() { closed = true; active?.disconnect() }
}

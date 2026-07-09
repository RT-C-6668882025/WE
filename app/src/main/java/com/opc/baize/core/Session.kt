package com.opc.baize.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话历史数据模型（开源版新增，像对话软件一样按会话分条）。
 *
 * 一个 [Session] = 一次连续问答：从某次 /new（或首次开始）到下一次 /new 之前。
 * 一轮 [Turn] = 手写问题识别文本 + 墨灵回复（回显与回复成对，fail 文案不入档）。
 *
 * 持久化为 JSON（{"v":2,"sessions":[...]}）。SessionCodec 是纯逻辑（只依赖 org.json），
 * 便于 JVM 单测；文件读写交给 [com.opc.baize.SessionStore]。
 *
 * 向后兼容：旧版 pages.json（{"v":1,"pages":[...]}）由 SessionCodec.fromLegacyPages 读入，
 * 整本旧书折成一个历史会话，绝不因旧存档崩溃（读不出就当空）。
 */
data class Turn(val question: String, val reply: String)

data class Session(
    val id: String,
    val createdAt: Long,
    val turns: List<Turn>,
) {
    /** 会话首问摘要（历史列表每条显示）：取第一轮问题前 18 字（按码点截，不劈代理对），空则给占位。 */
    fun summary(): String {
        val first = turns.firstOrNull()?.question?.trim().orEmpty()
        if (first.isEmpty()) return "（无题）"
        if (first.codePointCount(0, first.length) <= 18) return first
        return first.substring(0, first.offsetByCodePoints(0, 18)) + "…"
    }

    fun isEmpty(): Boolean = turns.isEmpty()
}

object SessionCodec {

    const val VERSION = 2

    fun encode(sessions: List<Session>): String {
        val arr = JSONArray()
        for (s in sessions) {
            if (s.isEmpty()) continue   // 空会话不落盘
            val turns = JSONArray()
            for (t in s.turns) {
                turns.put(
                    JSONObject().put("q", t.question).put("a", t.reply)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("ts", s.createdAt)
                    .put("turns", turns)
            )
        }
        return JSONObject().put("v", VERSION).put("sessions", arr).toString()
    }

    /** 解析 v2 sessions.json。任何异常/格式不符 → 空列表（绝不崩）。 */
    fun decode(text: String): List<Session> {
        return try {
            val obj = JSONObject(text)
            if (obj.optInt("v") != VERSION) return emptyList()
            val arr = obj.optJSONArray("sessions") ?: return emptyList()
            val out = mutableListOf<Session>()
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                val id = so.optString("id").ifEmpty { "s_${so.optLong("ts", i.toLong())}" }
                val ts = so.optLong("ts", 0L)
                val ta = so.optJSONArray("turns") ?: continue
                val turns = mutableListOf<Turn>()
                for (j in 0 until ta.length()) {
                    val to = ta.optJSONObject(j) ?: continue
                    val q = to.optString("q")
                    val a = to.optString("a")
                    if (q.isNotEmpty() || a.isNotEmpty()) turns.add(Turn(q, a))
                }
                if (turns.isNotEmpty()) out.add(Session(id, ts, turns))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 旧存档兼容：把 v1 的 pages.json（{"v":1,"pages":[[["you","…"],["book","…"]],…]}）
     * 折成一个历史会话。you=问、book=答，按出现顺序两两配对成 Turn。
     * 读不出 → 空列表（当作没有历史，不崩）。
     */
    fun fromLegacyPages(text: String, sessionId: String, createdAt: Long): List<Session> {
        return try {
            val obj = JSONObject(text)
            if (obj.optInt("v") != 1) return emptyList()
            val pages = obj.optJSONArray("pages") ?: return emptyList()
            // 先把所有 (kind,text) 顺序摊平，再配对成轮
            val flat = mutableListOf<Pair<String, String>>()
            for (i in 0 until pages.length()) {
                val pa = pages.optJSONArray(i) ?: continue
                for (j in 0 until pa.length()) {
                    val b = pa.optJSONArray(j) ?: continue
                    val k = b.optString(0)
                    val t = b.optString(1)
                    if ((k == "you" || k == "book") && t.isNotEmpty()) flat.add(k to t)
                }
            }
            val turns = mutableListOf<Turn>()
            var pendingQ: String? = null
            for ((k, t) in flat) {
                if (k == "you") {
                    // 连续两个 you：前一个当无答落一轮
                    if (pendingQ != null) turns.add(Turn(pendingQ!!, ""))
                    // 去掉网页版回显前缀「你写道：」
                    pendingQ = t.removePrefix("你写道：")
                } else { // book
                    turns.add(Turn(pendingQ ?: "", t))
                    pendingQ = null
                }
            }
            if (pendingQ != null) turns.add(Turn(pendingQ!!, ""))
            if (turns.isEmpty()) emptyList()
            else listOf(Session(sessionId, createdAt, turns))
        } catch (t: Throwable) {
            emptyList()
        }
    }
}

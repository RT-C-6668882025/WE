package com.opc.baize

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话持久化：pages 结构存 App 内部 JSON 文件，重启恢复停末页。
 * 格式 {"v":1,"pages":[[["you","…"],["book","…"]], …]}，
 * 对话记忆（6轮）只在内存，不落盘——同网页版口径。
 */
class PageStore(private val file: File) {

    fun save(pages: List<List<Pair<String, String>>>) {
        try {
            val arr = JSONArray()
            for (pg in pages) {
                val pa = JSONArray()
                for ((k, t) in pg) pa.put(JSONArray().put(k).put(t))
                arr.put(pa)
            }
            val obj = JSONObject().put("v", 1).put("pages", arr)
            file.writeText(obj.toString())
        } catch (t: Throwable) {
            // 存档失败不挡使用
        }
    }

    fun load(): List<List<Pair<String, String>>>? {
        return try {
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            if (obj.optInt("v") != 1) return null
            val arr = obj.optJSONArray("pages") ?: return null
            val out = mutableListOf<List<Pair<String, String>>>()
            for (i in 0 until arr.length()) {
                val pa = arr.optJSONArray(i) ?: continue
                val page = mutableListOf<Pair<String, String>>()
                for (j in 0 until pa.length()) {
                    val b = pa.optJSONArray(j) ?: continue
                    val k = b.optString(0)
                    val t = b.optString(1)
                    if ((k == "you" || k == "book") && t.isNotEmpty()) page.add(k to t)
                }
                if (page.isNotEmpty()) out.add(page)
            }
            if (out.isEmpty()) null else out
        } catch (t: Throwable) {
            null
        }
    }

    fun clear() {
        try { file.delete() } catch (t: Throwable) {}
    }
}

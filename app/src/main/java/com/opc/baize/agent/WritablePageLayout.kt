package com.opc.baize.agent

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, declarative paper content. Coordinates are PDF page points, shared with ink. */
data class WritableBlock(val id: String, val type: String, val text: String = "", val height: Int = 144,
    val answerLines: Int = 3, val grid: String = "ruled", val shapes: JSONArray = JSONArray())
data class WritableDocument(val title: String, val landscape: Boolean, val leftHanded: Boolean,
    val fontSize: Int, val lineHeight: Int, val blocks: List<WritableBlock>, val mode: String = "handwriting") {
    val width get() = if (landscape) 840 else 600
    val height get() = if (landscape) 600 else 840
    val left get() = if (leftHanded) 112 else 40
    val contentWidth get() = width - 152
    val bottom get() = height - 64
    companion object {
        fun parse(o: JSONObject): WritableDocument {
            require(o.optInt("we_page", 1) == 1)
            val orientation = o.optString("orientation", "portrait"); require(orientation in setOf("portrait", "landscape"))
            val hand = o.optString("handedness", "right"); require(hand in setOf("left", "right"))
            val mode = o.optString("mode", "handwriting"); require(mode in setOf("handwriting", "drawing", "typing"))
            val font = o.optInt("font_size", 20); val line = o.optInt("line_height", 32)
            require(font in 16..28 && line in (font + 6)..48) { "字号应为 16～28，行高应至少比字号大 6" }
            val title = o.optString("title"); require(title.length <= 120)
            val input = o.getJSONArray("blocks"); require(input.length() in 1..120) { "页面需要 1～120 个内容块" }
            val seen = mutableSetOf<String>()
            val blocks = (0 until input.length()).map { index ->
                val b = input.getJSONObject(index); val id = b.getString("id")
                require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) && seen.add(id)) { "内容块 id 必须唯一" }
                val type = b.getString("type"); require(type in setOf("heading", "text", "question", "writing", "drawing", "figure"))
                val text = b.optString("text"); require(text.length <= 6000)
                if (type in setOf("heading", "text", "question")) require(text.isNotBlank())
                val height = b.optInt("height", if (type in setOf("drawing", "figure")) 240 else 144)
                require(height in 64..500)
                val lines = b.optInt("answer_lines", 3); require(lines in 1..10)
                val grid = b.optString("grid", if (type == "drawing" || type == "figure") "blank" else "ruled")
                require(grid in setOf("blank", "ruled", "square", "dots"))
                val shapes = b.optJSONArray("shapes") ?: JSONArray(); require(shapes.length() <= 200)
                for (i in 0 until shapes.length()) {
                    val s = shapes.getJSONObject(i); require(s.getString("type") in setOf("line", "rect", "ellipse", "text"))
                    for (key in listOf("x", "y", "x2", "y2", "width", "height")) if (s.has(key)) {
                        val value = s.getDouble(key); require(value.isFinite() && value in 0.0..1000.0) { "图形坐标使用 0～1000 的相对坐标" }
                    }
                    require(s.optString("text").length <= 120)
                    require(s.optString("color", "#353535").matches(Regex("#[0-9a-fA-F]{6}")))
                }
                WritableBlock(id, type, text, height, lines, grid, shapes)
            }
            val identities = blocks.map { it.id } + blocks.filter { it.type == "question" }.map { "${it.id}-answer" }
            require(identities.distinct().size == identities.size) { "答题区 id 与内容块冲突" }
            return WritableDocument(title, orientation == "landscape", hand == "left", font, line, blocks, mode)
        }
        fun fromText(text: String, spacious: Boolean): WritableDocument {
            val blocks = text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.mapIndexed { i, part ->
                WritableBlock("text-$i", "text", part)
            }.ifEmpty { listOf(WritableBlock("blank", "drawing", height = 480, grid = "blank")) }
            return WritableDocument("", false, false, if (spacious) 20 else 17, if (spacious) 34 else 26, blocks)
        }
    }
}
data class WritableRegion(val id: String, val type: String, val page: Int, val x: Int, val y: Int,
    val width: Int, val height: Int, val text: String = "", val parentId: String = "", val grid: String = "blank", val fontSize: Int = 20, val lineHeight: Int = 32,
    val shapes: JSONArray = JSONArray()) {
    fun json() = JSONObject().put("id", id).put("type", type).put("page", page).put("x", x).put("y", y)
        .put("width", width).put("height", height).put("text", text).put("parent_id", parentId)
}
data class WritablePlan(val document: WritableDocument, val regions: List<WritableRegion>, val pageCount: Int) {
    fun json() = JSONObject().put("schema", 1).put("space", "android-pdf-renderer-page-points")
        .put("width", document.width).put("height", document.height).put("mode", document.mode)
        .put("handedness", if (document.leftHanded) "left" else "right")
        .put("pages", JSONArray((0 until pageCount).map { page -> JSONObject().put("index", page)
            .put("regions", JSONArray(regions.filter { it.page == page }.map { it.json() })) }))
}

object WritablePageLayout {
    /** wrap returns line end offsets. Keeping text and answer space together takes priority. */
    fun plan(d: WritableDocument, wrap: (String, Int, Int, Int) -> List<Int>): WritablePlan {
        val regions = mutableListOf<WritableRegion>(); var page = 0; var y = 44
        val available = d.bottom - 44
        fun next() { page++; require(page < 200) { "内容超过 200 页" }; y = 44 }
        fun reserve(height: Int) { require(height <= available) { "书写或绘图区超过一页可用高度，请降低 height 或改用纵向" }; if (y + height > d.bottom) next() }
        val blocks = (if (d.title.isNotBlank()) listOf(WritableBlock("__title", "heading", d.title)) else emptyList()) + d.blocks
        for (b in blocks) {
            val font = if (b.type == "heading") d.fontSize + 4 else d.fontSize
            val line = if (b.type == "heading") maxOf(d.lineHeight, font + 6) else d.lineHeight
            if (b.type in setOf("writing", "drawing", "figure")) {
                val height = minOf(b.height, available)
                reserve(height)
                regions += WritableRegion(b.id, b.type, page, d.left, y, d.contentWidth, height, b.text,
                    grid = b.grid, fontSize = font, lineHeight = line, shapes = b.shapes)
                y += height + 18
                continue
            }
            val ends = wrap(b.text, d.contentWidth - 16, font, line)
            require(ends.isNotEmpty() && ends.last() == b.text.length && ends.zipWithNext().all { it.first < it.second })
            val answer = if (b.type == "question") b.answerLines * maxOf(36, d.lineHeight) + 20 else 0
            require(answer <= available - line - 20) { "答题留白过大，请减少 answer_lines" }
            val fullHeight = ends.size * line + 8 + if (answer > 0) answer + 12 else 0
            if (fullHeight <= available) reserve(fullHeight)
            var first = 0; var start = 0
            while (first < ends.size) {
                val remainingLines = ends.size - first
                if (y + line + 8 > d.bottom) next()
                var count = minOf(remainingLines, (d.bottom - y - 8) / line)
                if (count == remainingLines && answer > 0 && y + count * line + 20 + answer > d.bottom) {
                    if (count * line + 20 + answer <= available) { next(); count = remainingLines }
                    else count = minOf(count, remainingLines - 1).coerceAtLeast(1)
                }
                val end = ends[first + count - 1]
                regions += WritableRegion(b.id, b.type, page, d.left, y, d.contentWidth, count * line + 8,
                    b.text.substring(start, end), fontSize = font, lineHeight = line)
                y += count * line + 20; first += count; start = end
            }
            if (answer > 0) {
                reserve(answer)
                regions += WritableRegion("${b.id}-answer", "writing", page, d.left, y, d.contentWidth, answer,
                    parentId = b.id, grid = b.grid, fontSize = d.fontSize, lineHeight = maxOf(36, d.lineHeight))
                y += answer + 18
            } else y += 8
        }
        for (p in 0..page) regions += WritableRegion("__annotations", "annotation", p,
            if (d.leftHanded) 24 else d.width - 96, 44, 72, available)
        return WritablePlan(d, regions, page + 1)
    }
}

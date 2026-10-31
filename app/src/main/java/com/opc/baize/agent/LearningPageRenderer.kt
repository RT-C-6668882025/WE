package com.opc.baize.agent

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.io.File

/** Native paper content + bounded layout map. Generated content and editable ink share coordinates. */
object LearningPageRenderer {
    private fun textLayout(text: String, width: Int, font: Int, line: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 39, 45); textSize = font.toFloat() }
        val natural = paint.fontMetrics.let { it.descent - it.ascent }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing((line - natural).coerceAtLeast(0f), 1f)
            .setIncludePad(false).build()
    }
    fun spec(text: String, spacious: Boolean): WritableDocument {
        val json = if (text.trimStart().startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null
        return if (json?.has("we_page") == true) WritableDocument.parse(json) else WritableDocument.fromText(text, spacious)
    }
    fun render(source: File?, destination: File, spacious: Boolean) {
        require(source == null || (source.isFile && source.length() <= 256000)) { "学习页内容最多 256 KB" }
        val d = spec(source?.readText().orEmpty(), spacious)
        val plan = WritablePageLayout.plan(d) { text, width, font, line ->
            val layout = textLayout(text, width, font, line)
            (0 until layout.lineCount).map { layout.getLineEnd(it) }.filter { it > 0 }.distinct()
        }
        val document = PdfDocument()
        try {
            for (index in 0 until plan.pageCount) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(d.width, d.height, index + 1).create())
                val c = page.canvas; c.drawColor(Color.WHITE)
                for (r in plan.regions.filter { it.page == index }) {
                    c.save(); c.clipRect(r.x, r.y, r.x + r.width, r.y + r.height)
                    if (r.type in setOf("heading", "text", "question")) {
                        c.translate(r.x + 8f, r.y + 4f)
                        textLayout(r.text.trimEnd('\n'), r.width - 16, r.fontSize, r.lineHeight).draw(c)
                    } else drawArea(c, r)
                    c.restore()
                }
                val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.GRAY }
                c.drawText("WE · ${index + 1} · ${destination.nameWithoutExtension.takeLast(12)} · 可直接书写", 40f, d.height - 24f, footer)
                document.finishPage(page)
            }
            destination.parentFile?.mkdirs()
            val stage = File(destination.path + ".tmp")
            try { stage.outputStream().use { document.writeTo(it) }; check(stage.renameTo(destination)) }
            finally { stage.delete() }
            LearningTurn.write(mapFile(destination), plan.json().toString())
        } finally { document.close() }
    }
    fun mapFile(pdf: File) = File(pdf.parentFile, pdf.nameWithoutExtension + ".layout.json")
    fun pageMap(pdf: File, page: Int): JSONObject? {
        val file = mapFile(pdf)
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return null
        val map = JSONObject(file.readText()); val pages = map.getJSONArray("pages")
        if (page !in 0 until pages.length()) return null
        return JSONObject().put("width", map.getInt("width")).put("height", map.getInt("height"))
            .put("space", map.getString("space")).put("mode", map.optString("mode", "handwriting"))
            .put("regions", pages.getJSONObject(page).getJSONArray("regions"))
    }
    private fun drawArea(c: Canvas, r: WritableRegion) {
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 224, 227); strokeWidth = 0.7f; style = Paint.Style.STROKE }
        if (r.type == "annotation") {
            pen.pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f)
            c.drawLine(r.x.toFloat(), r.y.toFloat(), r.x.toFloat(), (r.y + r.height).toFloat(), pen)
            return
        }
        c.drawRoundRect(RectF(r.x.toFloat(), r.y.toFloat(), (r.x + r.width).toFloat(), (r.y + r.height).toFloat()), 6f, 6f, pen)
        val caption = r.text.takeIf { it.isNotBlank() }?.let { textLayout(it, r.width - 20, 14, 22) }
        val labelHeight = caption?.height?.plus(16) ?: 8
        require(labelHeight + 40 <= r.height) { "区域说明过长或区域过小，请增加 height 或把说明放在 text 块中" }
        if (caption != null) { c.save(); c.translate(r.x + 10f, r.y + 8f); caption.draw(c); c.restore() }
        if (r.grid != "blank") {
            val step = if (r.grid == "ruled") r.lineHeight.coerceAtLeast(36) else 24
            var y = r.y + labelHeight + step
            while (y < r.y + r.height - 8) {
                if (r.grid == "dots") {
                    var x = r.x + 16
                    while (x < r.x + r.width - 8) { c.drawCircle(x.toFloat(), y.toFloat(), 0.7f, pen); x += step }
                } else c.drawLine(r.x + 8f, y.toFloat(), r.x + r.width - 8f, y.toFloat(), pen)
                y += step
            }
            if (r.grid == "square") {
                var x = r.x + 8 + step
                while (x < r.x + r.width - 8) { c.drawLine(x.toFloat(), r.y + labelHeight.toFloat(), x.toFloat(), r.y + r.height - 8f, pen); x += step }
            }
        }
        if (r.type == "figure") {
            c.save(); c.translate(r.x + 8f, r.y + labelHeight.toFloat())
            c.scale((r.width - 16) / 1000f, (r.height - labelHeight - 8) / 1000f)
            for (i in 0 until r.shapes.length()) {
                val s = r.shapes.getJSONObject(i)
                val x = s.optDouble("x", 0.0).toFloat(); val y = s.optDouble("y", 0.0).toFloat()
                val shape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(s.optString("color", "#353535")); strokeWidth = 3f; style = Paint.Style.STROKE }
                when (s.getString("type")) {
                    "line" -> c.drawLine(x, y, s.optDouble("x2", 1000.0).toFloat(), s.optDouble("y2", 1000.0).toFloat(), shape)
                    "rect", "ellipse" -> {
                        val rect = RectF(x, y, (x + s.optDouble("width", 100.0)).toFloat().coerceAtMost(1000f), (y + s.optDouble("height", 100.0)).toFloat().coerceAtMost(1000f))
                        if (s.getString("type") == "rect") c.drawRect(rect, shape) else c.drawOval(rect, shape)
                    }
                    "text" -> { shape.style = Paint.Style.FILL; shape.textSize = 32f; c.drawText(s.optString("text"), x, y, shape) }
                }
            }
            c.restore()
        }
    }
}

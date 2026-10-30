package com.opc.baize.agent

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Native, paginated paper layout. Plain text is never executed as HTML or script. */
object LearningPageRenderer {
    fun render(source: File?, destination: File, spacious: Boolean) {
        require(source == null || (source.isFile && source.length() <= 256000)) { "学习页文本最多 256 KB" }
        val text = source?.readText().orEmpty().ifBlank { "自由书写 / 绘画" }
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 39, 45); textSize = 17f }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 504)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(if (spacious) 48f else 12f, 1f)
            .setIncludePad(false).build()
        val document = PdfDocument()
        try {
            var first = 0; var number = 1
            while (first < layout.lineCount) {
                require(number <= 200) { "内容超过 200 页，请拆分文件" }
                val top = layout.getLineTop(first)
                var end = first + 1
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= 710) end++
                val bottom = layout.getLineBottom(end - 1)
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 840, number++).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.save(); page.canvas.translate(48f, 44f - top)
                page.canvas.clipRect(0, top, 504, bottom)
                layout.draw(page.canvas); page.canvas.restore()
                val footer = TextPaint(paint).apply { textSize = 10f; color = Color.GRAY }
                page.canvas.drawText("WE · ${number - 1} · ${destination.nameWithoutExtension.takeLast(12)}", 48f, 814f, footer)
                document.finishPage(page); first = end
            }
            destination.parentFile?.mkdirs()
            val stage = File(destination.path + ".tmp")
            try { stage.outputStream().use { document.writeTo(it) }; check(stage.renameTo(destination)) }
            finally { stage.delete() }
        } finally { document.close() }
    }
}

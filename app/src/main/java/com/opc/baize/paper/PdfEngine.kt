package com.opc.baize.paper

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

interface PdfEngine : Closeable {
    val pageCount: Int
    fun render(page: Int): RenderedPdfPage
}
data class RenderedPdfPage(val bitmap: Bitmap, val width: Int, val height: Int)

/** Platform decoder behind a replaceable interface; bounded rendering allocation. */
class AndroidPdfEngine(file: File) : PdfEngine {
    private val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    override val pageCount get() = renderer.pageCount
    override fun render(page: Int): RenderedPdfPage = renderer.openPage(page).use {
        val ratio = minOf(2f, 1800f / maxOf(it.width, it.height))
        val bitmap = Bitmap.createBitmap((it.width * ratio).toInt().coerceAtLeast(1), (it.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        catch (e: Exception) { bitmap.recycle(); throw e }
        RenderedPdfPage(bitmap, it.width, it.height)
    }
    override fun close() = renderer.close()
}

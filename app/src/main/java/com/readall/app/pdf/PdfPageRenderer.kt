package com.readall.app.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

class PdfPageRenderer(file: File) : Closeable {
    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(descriptor)
    private val lock = Any()

    val pageCount: Int get() = renderer.pageCount

    fun render(pageIndex: Int, maxWidthPx: Int): Bitmap {
        synchronized(lock) {
            val index = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
            renderer.openPage(index).use { page ->
                val width = maxWidthPx.coerceAtLeast(320)
                val scale = width / page.width.toFloat()
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(0xFFFFFFFF.toInt())
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }
}

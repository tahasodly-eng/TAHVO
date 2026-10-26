package com.taha.sahb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

/** PDF tools. Page rendering uses Android's PdfRenderer; merging, page extraction and watermarks use PdfBox. */
object Pdf {

    private fun local(ctx: Context, src: Src): File {
        // PdfRenderer and PdfBox need a seekable file, so work on a private copy
        val f = Engine.tmp(ctx, "pdf")
        ctx.contentResolver.openInputStream(src.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    fun pageCount(ctx: Context, src: Src): Int = runCatching {
        val f = local(ctx, src)
        try { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } } } finally { f.delete() }
    }.getOrDefault(0)

    private fun renderPage(r: PdfRenderer, i: Int, maxSide: Int): Bitmap {
        r.openPage(i).use { page ->
            val scale = min(maxSide.toFloat() / page.width, maxSide.toFloat() / page.height)
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return b
        }
    }

    /** Every page becomes an image (JPG or PNG). */
    suspend fun toImages(ctx: Context, src: Src, fmt: String, onProgress: (Int) -> Unit): List<Saved> = withContext(Dispatchers.IO) {
        val f = local(ctx, src)
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    (0 until r.pageCount).map { i ->
                        coroutineContext.ensureActive()
                        onProgress(100 * i / r.pageCount)
                        val b = renderPage(r, i, 2200)
                        val out = Engine.tmp(ctx, fmt)
                        out.outputStream().use { b.compress(if (fmt == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
                        b.recycle()
                        Engine.save(ctx, out, "${src.base}_p${i + 1}.$fmt", if (fmt == "png") "image/png" else "image/jpeg")
                    }
                }
            }
        } finally { f.delete() }
    }

    /** Redraws each page as a compressed JPEG page. Good for scans; text stops being selectable. */
    suspend fun compress(ctx: Context, src: Src, onProgress: (Int) -> Unit): Saved = withContext(Dispatchers.IO) {
        val f = local(ctx, src)
        val doc = PdfDocument()
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    for (i in 0 until r.pageCount) {
                        coroutineContext.ensureActive()
                        onProgress(100 * i / r.pageCount)
                        val (pw, ph) = r.openPage(i).use { it.width to it.height }
                        val b = renderPage(r, i, 1400)
                        // round-trip through JPEG so the page image is stored compressed
                        val jpg = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
                        b.recycle()
                        val img = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
                        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                        page.canvas.drawBitmap(img, null, Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
                        doc.finishPage(page)
                        img.recycle()
                    }
                }
            }
            val out = Engine.tmp(ctx, "pdf")
            out.outputStream().use { doc.writeTo(it) }
            Engine.save(ctx, out, "${src.base}_small.pdf", "application/pdf")
        } finally { doc.close(); f.delete() }
    }

    suspend fun merge(ctx: Context, srcs: List<Src>): Saved = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val locals = srcs.map { local(ctx, it) }
        try {
            val out = Engine.tmp(ctx, "pdf")
            PDFMergerUtility().apply {
                locals.forEach { addSource(it) }
                destinationFileName = out.absolutePath
            }.mergeDocuments(null)
            Engine.save(ctx, out, "${srcs[0].base}_merged.pdf", "application/pdf")
        } finally { locals.forEach { it.delete() } }
    }

    /** Saves pages [from]..[to] (1-based, inclusive) as a new PDF, keeping their original content. */
    suspend fun extract(ctx: Context, src: Src, from: Int, to: Int): Saved = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val f = local(ctx, src)
        try {
            PDDocument.load(f).use { doc ->
                PDDocument().use { outDoc ->
                    for (i in (from - 1) until to) outDoc.importPage(doc.getPage(i))
                    val out = Engine.tmp(ctx, "pdf")
                    outDoc.save(out)
                    Engine.save(ctx, out, "${src.base}_p$from-$to.pdf", "application/pdf")
                }
            }
        } finally { f.delete() }
    }

    /** Text drawn by Android's text engine, so Arabic is shaped correctly. */
    fun textBitmap(text: String, widthPx: Int): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 40, 40, 40); typeface = Typeface.DEFAULT_BOLD; textSize = widthPx / 12f
        }
        val tw = p.measureText(text).coerceAtLeast(1f)
        if (tw > widthPx * 0.9f) p.textSize *= widthPx * 0.9f / tw
        val w = (p.measureText(text) + p.textSize).toInt().coerceAtLeast(2)
        val h = (p.textSize * 1.6f).toInt().coerceAtLeast(2)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawText(text, p.textSize / 2, h * 0.68f, p)
        return b
    }

    suspend fun watermark(ctx: Context, src: Src, text: String): Saved = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val f = local(ctx, src)
        try {
            PDDocument.load(f).use { doc ->
                val bmp = textBitmap(text, 1600)
                val img = LosslessFactory.createFromImage(doc, bmp)
                val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.22f; strokingAlphaConstant = 0.22f }
                for (page in doc.pages) {
                    val box = page.mediaBox
                    val w = box.width * 0.8f
                    val h = w * bmp.height / bmp.width
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(gs)
                        cs.saveGraphicsState()
                        cs.transform(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(35.0), box.width / 2, box.height / 2))
                        cs.drawImage(img, -w / 2, -h / 2, w, h)
                        cs.restoreGraphicsState()
                    }
                }
                val out = Engine.tmp(ctx, "pdf")
                doc.save(out)
                Engine.save(ctx, out, "${src.base}_watermark.pdf", "application/pdf")
            }
        } finally { f.delete() }
    }
}

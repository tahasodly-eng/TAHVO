package com.taha.sahb

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

enum class Kind { IMAGE, VIDEO, AUDIO, PDF, OTHER }

class Src(val uri: Uri, val name: String, val mime: String, val kind: Kind) {
    val base: String get() = name.substringBeforeLast('.', name)
}

class Saved(val uri: Uri, val folder: String, val mime: String, val name: String, val size: Long)

object Engine {

    // ------------------------------------------------------------ input
    fun describe(ctx: Context, uri: Uri): Src {
        var name = "file"
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        if (name == "file") uri.lastPathSegment?.substringAfterLast('/')?.let { if (it.isNotBlank()) name = it }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = ctx.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("video/") -> Kind.VIDEO
            mime.startsWith("audio/") -> Kind.AUDIO
            mime == "application/pdf" || ext == "pdf" -> Kind.PDF
            else -> Kind.OTHER
        }
        return Src(uri, name, mime, kind)
    }

    fun kindOf(srcs: List<Src>): Kind = srcs.map { it.kind }.distinct().singleOrNull() ?: Kind.OTHER

    fun tmp(ctx: Context, ext: String): File =
        File(ctx.cacheDir, "out_${System.nanoTime()}.$ext").also { it.parentFile?.mkdirs() }

    // ------------------------------------------------------------ saving to the shared folders (no permission needed on Android 10+)
    fun save(ctx: Context, file: File, displayName: String, mime: String): Saved {
        val (collection, dir) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val rel = "$dir/Sahb"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val size = file.length()
        if (size <= 0) throw IllegalStateException("empty output")
        val uri = r.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
        try {
            r.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, values, null, null)
        } catch (e: Exception) {
            r.delete(uri, null, null); throw e
        } finally {
            file.delete()
        }
        // confirm the saved file is really there and readable before reporting success
        r.openInputStream(uri)?.use { if (it.read() < 0) throw IllegalStateException("unreadable output") }
            ?: throw IllegalStateException("unreadable output")
        return Saved(uri, rel, mime, displayName, size)
    }

    // ------------------------------------------------------------ images
    private fun decode(ctx: Context, src: Src): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, src.uri)
        return ImageDecoder.decodeBitmap(source) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = false
        }
    }

    private fun onWhite(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        return out
    }

    private fun encode(ctx: Context, b: Bitmap, fmt: String, quality: Int): File {
        val f = tmp(ctx, fmt)
        val cf = when (fmt) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val img = if (cf == Bitmap.CompressFormat.JPEG && b.hasAlpha()) onWhite(b) else b
        f.outputStream().use { img.compress(cf, quality, it) }
        return f
    }

    private fun mimeOf(fmt: String) = when (fmt) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }

    private fun sameFormat(src: Src): String = when {
        src.mime.contains("png") -> "png"
        src.mime.contains("webp") -> "webp"
        else -> "jpg"
    }

    /** Re-encodes an image. Re-encoding through a bitmap writes no EXIF/GPS data. */
    suspend fun image(ctx: Context, src: Src, fmt: String?, tag: String?, quality: Int = 92,
                      scale: Double? = null, maxDim: Int? = null, aspect: Double? = null): Saved = withContext(Dispatchers.IO) {
        var b = decode(ctx, src)
        if (aspect != null) {
            val w: Int; val h: Int
            if (b.width.toDouble() / b.height > aspect) { h = b.height; w = (h * aspect).roundToInt() }
            else { w = b.width; h = (w / aspect).roundToInt() }
            b = Bitmap.createBitmap(b, (b.width - w) / 2, (b.height - h) / 2, w, h)
        }
        val s = scale ?: maxDim?.let { m -> if (max(b.width, b.height) > m) m.toDouble() / max(b.width, b.height) else 1.0 } ?: 1.0
        if (s != 1.0) b = Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
        val out = fmt ?: sameFormat(src)
        val f = encode(ctx, b, out, quality)
        save(ctx, f, name(src, tag, out), mimeOf(out))
    }

    /** Draws the text diagonally across the image, semi-transparent. */
    suspend fun watermarkImage(ctx: Context, src: Src, text: String): Saved = withContext(Dispatchers.IO) {
        val b = decode(ctx, src)
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(b, 0f, 0f, null)
        val t = Pdf.textBitmap(text, (b.width * 0.9f).toInt().coerceAtLeast(200))
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = 90
            colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val shadow = Paint(p).apply { colorFilter = android.graphics.PorterDuffColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN); alpha = 60 }
        c.save()
        c.rotate(-30f, b.width / 2f, b.height / 2f)
        val l = (b.width - t.width) / 2f; val top = (b.height - t.height) / 2f
        c.drawBitmap(t, l + 3, top + 3, shadow)
        c.drawBitmap(t, l, top, p)
        c.restore()
        val fmt = sameFormat(src)
        save(ctx, encode(ctx, out, fmt, 92), name(src, "watermark", fmt), mimeOf(fmt))
    }

    suspend fun pdf(ctx: Context, srcs: List<Src>): Saved = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val pw = 595; val ph = 842; val margin = 24
        srcs.forEachIndexed { i, s ->
            val b = decode(ctx, s)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val scale = minOf((pw - 2 * margin).toFloat() / b.width, (ph - 2 * margin).toFloat() / b.height)
            val w = (b.width * scale).toInt(); val h = (b.height * scale).toInt()
            val l = (pw - w) / 2; val t = (ph - h) / 2
            page.canvas.drawBitmap(b, null, Rect(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
        }
        val f = tmp(ctx, "pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        val n = if (srcs.size == 1) srcs[0].base else "Sahb_${srcs.size}_pages"
        save(ctx, f, "$n.pdf", "application/pdf")
    }

    suspend fun frame(ctx: Context, src: Src): Saved = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, src.uri)
            val b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: throw IllegalStateException("no frame")
            save(ctx, encode(ctx, b, "jpg", 92), name(src, "frame", "jpg"), "image/jpeg")
        } finally { r.release() }
    }

    // ------------------------------------------------------------ zip (any files)
    suspend fun zip(ctx: Context, srcs: List<Src>): Saved = withContext(Dispatchers.IO) {
        val f = tmp(ctx, "zip")
        ZipOutputStream(f.outputStream()).use { z ->
            val used = HashSet<String>()
            for (s in srcs) {
                var n = s.name; var k = 2
                while (!used.add(n)) { n = "${s.base} ($k).${s.name.substringAfterLast('.', "bin")}"; k++ }
                z.putNextEntry(ZipEntry(n))
                ctx.contentResolver.openInputStream(s.uri)!!.use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        save(ctx, f, (if (srcs.size == 1) srcs[0].base else "Sahb_files") + ".zip", "application/zip")
    }

    // ------------------------------------------------------------ video / audio (Media3 Transformer, hardware codecs)
    class MediaOpts(
        val removeAudio: Boolean = false,
        val removeVideo: Boolean = false,
        val height: Int? = null,
        val aspect: Float? = null,
        val clipStartMs: Long? = null,
        val clipEndMs: Long? = null,
        val videoBitrate: Int? = null,
    )

    fun info(ctx: Context, src: Src): Triple<Long, Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, src.uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) Triple(dur, h, w) else Triple(dur, w, h)
        } catch (e: Exception) { Triple(0L, 0, 0) } finally { r.release() }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun media(ctx: Context, src: Src, opts: MediaOpts, tag: String?, onProgress: (Int) -> Unit): Saved {
        val audioOnly = opts.removeVideo || src.kind == Kind.AUDIO
        val ext = if (audioOnly) "m4a" else "mp4"
        val out = tmp(ctx, ext)

        val item = MediaItem.Builder().setUri(src.uri).apply {
            if (opts.clipStartMs != null || opts.clipEndMs != null) {
                val clip = MediaItem.ClippingConfiguration.Builder()
                opts.clipStartMs?.let { clip.setStartPositionMs(it) }
                opts.clipEndMs?.let { clip.setEndPositionMs(it) }
                setClippingConfiguration(clip.build())
            }
        }.build()

        val effects = mutableListOf<androidx.media3.common.Effect>()
        if (opts.aspect != null) effects += Presentation.createForAspectRatio(opts.aspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        if (opts.height != null) effects += Presentation.createForHeight(opts.height)

        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(opts.removeAudio)
            .setRemoveVideo(opts.removeVideo)
            .setEffects(Effects(emptyList(), effects))
            .build()

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val builder = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                if (opts.videoBitrate != null) {
                    builder.setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(opts.videoBitrate).build())
                            .build()
                    )
                }
                val handler = Handler(Looper.getMainLooper())
                lateinit var transformer: Transformer
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        handler.postDelayed(this, 300)
                    }
                }
                transformer = builder.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll); if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll); if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }).build()
                cont.invokeOnCancellation { handler.removeCallbacks(poll); transformer.cancel() }
                transformer.start(edited, out.absolutePath)
                handler.post(poll)
            }
        }
        val mime = if (audioOnly) "audio/mp4" else "video/mp4"
        return withContext(Dispatchers.IO) { save(ctx, out, name(src, tag, ext), mime) }
    }

    private fun name(src: Src, tag: String?, ext: String) =
        (if (tag.isNullOrBlank()) src.base else "${src.base}_$tag") + "." + ext
}

package com.taha.sahb

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Conversion screen: the chosen file, the ring of formats/tools, settings, progress and result. */
class RingActivity : AppCompatActivity() {

    /** One choice in the ring. [options] builds the settings shown in the sheet and returns a runner. */
    private class Action(
        val label: String, val sub: String?, val icon: Int,
        val summary: String, val startLabel: Int,
        val options: ((LinearLayout) -> (() -> (() -> Unit)?))? = null,
        val direct: (() -> Unit)? = null,
        /** part of Sahb Pro */
        val pro: Boolean = true,
        /** free use allowed up to this media length (ms); 0 = no length rule */
        val freeMaxMs: Long = 0,
    )

    private val proListener: () -> Unit = { if (findViewById<View>(R.id.picker).isVisible && srcs.isNotEmpty()) refreshChoices() }

    private lateinit var ring: RingView
    private var srcs: List<Src> = emptyList()
    private var tools = false
    private var actions: List<Action> = emptyList()
    private var job: Job? = null
    private var lastRun: (() -> Unit)? = null

    private val changePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { srcs = uris.map { Engine.describe(this, it) }; tools = false; showPicker() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ring)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right); insets
        }
        ring = findViewById(R.id.ring)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { onBack() }
        onBackPressedDispatcher.addCallback(this) { onBack() }
        findViewById<View>(R.id.remove).setOnClickListener { finish() }
        findViewById<View>(R.id.change).setOnClickListener { changePicker.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.cancel_job).setOnClickListener { job?.cancel() }
        ring.onPick = { i -> actions.getOrNull(i)?.let { choose(it) } }
        ring.onCenterTap = { tools = !tools; refreshChoices() }

        if (BuildConfig.DEBUG && intent.hasExtra("pro")) Pro.debugOverride = intent.getBooleanExtra("pro", false)
        Pro.init(this)
        Pro.addListener(proListener)

        srcs = incomingUris().map { Engine.describe(this, it) }
        if (srcs.isEmpty()) { showFailure(getString(R.string.nothing), retry = false); return }
        tools = intent.getBooleanExtra("tools", false)
        showPicker()
    }

    override fun onDestroy() { Pro.removeListener(proListener); super.onDestroy() }

    override fun onResume() { super.onResume(); Pro.refresh() }

    /** Free users get one file at a time and the free tools only. */
    private fun locked(a: Action) = !Pro.isPro && (a.pro || srcs.size > 1)

    private fun onBack() {
        when {
            job?.isActive == true -> job?.cancel()
            findViewById<View>(R.id.result).isVisible && srcs.isNotEmpty() -> showPicker()
            else -> finish()
        }
    }

    private fun incomingUris(): List<Uri> {
        val i = intent
        val list = mutableListOf<Uri>()
        if (BuildConfig.DEBUG) i.getStringExtra("test_path")?.let { p -> p.split('|').forEach { list += Uri.fromFile(File(it)) } }
        when (i.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { list += it }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { list += it }
            Intent.ACTION_VIEW -> i.data?.let { list += it }
        }
        if (list.isEmpty()) {
            i.data?.let { list += it }
            i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { list += it } }
        }
        return list.distinct()
    }

    // ------------------------------------------------------------------ screen states
    private fun show(state: Int) {
        findViewById<View>(R.id.picker).isVisible = state == R.id.picker
        findViewById<View>(R.id.busy).isVisible = state == R.id.busy
        findViewById<View>(R.id.result).isVisible = state == R.id.result
        findViewById<View>(R.id.file_card).isVisible = state != R.id.result
    }

    private fun showPicker() {
        show(R.id.picker)
        val kind = Engine.kindOf(srcs)
        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(when (kind) {
            Kind.IMAGE -> R.string.title_image; Kind.VIDEO -> R.string.title_video
            Kind.AUDIO -> R.string.title_audio; Kind.PDF -> R.string.title_pdf; Kind.OTHER -> R.string.title_files
        })
        bindFileCard()
        refreshChoices()
    }

    private fun bindFileCard() {
        val name = findViewById<TextView>(R.id.file_name)
        val meta = findViewById<TextView>(R.id.file_meta)
        val thumb = findViewById<ImageView>(R.id.thumb)
        val first = srcs[0]
        name.text = if (srcs.size == 1) first.name else getString(R.string.files_selected, srcs.size)
        thumb.setImageResource(iconFor(first.kind)); thumb.scaleType = ImageView.ScaleType.CENTER
        thumb.imageTintList = ContextCompat.getColorStateList(this, R.color.text2)
        meta.text = ""
        lifecycleScope.launch {
            val (size, details, bmp) = withContext(Dispatchers.IO) { describeMeta(first) }
            val total = if (srcs.size == 1) size else withContext(Dispatchers.IO) { srcs.sumOf { sizeOf(it.uri) } }
            meta.text = listOfNotNull(
                if (total > 0) Formatter.formatShortFileSize(this@RingActivity, total) else getString(R.string.unknown_size),
                details.takeIf { srcs.size == 1 }
            ).joinToString("  •  ")
            if (bmp != null) { thumb.imageTintList = null; thumb.scaleType = ImageView.ScaleType.CENTER_CROP; thumb.setImageBitmap(bmp) }
        }
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        } ?: if (uri.scheme == "file") File(uri.path!!).length() else 0L
    }.getOrDefault(0L)

    private fun describeMeta(s: Src): Triple<Long, String?, Bitmap?> {
        val size = sizeOf(s.uri)
        var details: String? = null
        var bmp: Bitmap? = null
        runCatching {
            when (s.kind) {
                Kind.IMAGE -> {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(s.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                    if (o.outWidth > 0) details = "${o.outWidth} × ${o.outHeight}"
                    bmp = contentResolver.loadThumbnailCompat(s.uri)
                }
                Kind.VIDEO, Kind.AUDIO -> {
                    val (dur, w, h) = Engine.info(this, s)
                    details = listOfNotNull(if (s.kind == Kind.VIDEO && w > 0) "$w × $h" else null, if (dur > 0) fmtTime(dur / 1000) else null).joinToString("  •  ").ifEmpty { null }
                    if (s.kind == Kind.VIDEO) bmp = contentResolver.loadThumbnailCompat(s.uri)
                }
                Kind.PDF -> { val n = Pdf.pageCount(this, s); if (n > 0) details = resources.getString(R.string.pages_count, n) }
                Kind.OTHER -> {}
            }
        }
        return Triple(size, details, bmp)
    }

    private fun android.content.ContentResolver.loadThumbnailCompat(uri: Uri): Bitmap? = runCatching {
        if (uri.scheme == "content") loadThumbnail(uri, Size(240, 180), null)
        else {
            val r = android.media.MediaMetadataRetriever()
            try {
                if (Engine.kindOf(srcs) == Kind.VIDEO) { r.setDataSource(this@RingActivity, uri); r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 180) }
                else openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }
            } finally { r.release() }
        }
    }.getOrNull()

    private fun iconFor(k: Kind) = when (k) {
        Kind.IMAGE -> R.drawable.ic_image; Kind.VIDEO -> R.drawable.ic_video; Kind.AUDIO -> R.drawable.ic_audio
        Kind.PDF -> R.drawable.ic_pdf; Kind.OTHER -> R.drawable.ic_file
    }

    private fun refreshChoices() {
        actions = if (tools) toolActions() else convertActions()
        val single = actions.size == 1
        ring.isVisible = !single
        findViewById<View>(R.id.single).isVisible = single
        val singleTools = findViewById<MaterialButton>(R.id.single_tools)
        // a single choice gets a card instead of a one-slice ring; still offer the other side when it has something else
        singleTools.isVisible = single && Engine.kindOf(srcs) != Kind.OTHER
        singleTools.text = getString(if (tools) R.string.tap_for_convert else R.string.tap_for_tools)
        singleTools.setOnClickListener { tools = !tools; refreshChoices() }
        findViewById<TextView>(R.id.hint).setText(if (tools) R.string.choose_tool else R.string.choose_format)
        if (single) {
            val a = actions[0]
            findViewById<ImageView>(R.id.single_icon).setImageResource(a.icon)
            findViewById<TextView>(R.id.single_label).text = a.label
            val sub = listOfNotNull(a.sub, if (locked(a)) "🔒 Pro" else if (!Pro.isPro && a.freeMaxMs > 0) getString(R.string.free_limit_note) else null).joinToString("  ·  ")
            findViewById<TextView>(R.id.single_sub).apply { text = sub; isVisible = sub.isNotEmpty() }
            findViewById<View>(R.id.single).setOnClickListener { choose(a) }
        } else {
            ring.items = actions.map { a -> RingItem(a.label, a.sub, a.icon, locked(a)) { choose(a) } }
            ring.lockedA11y = getString(R.string.locked_a11y)
            ring.centerTitle = getString(if (tools) R.string.tools else R.string.convert)
            ring.centerHint = getString(if (tools) R.string.tap_for_convert else R.string.tap_for_tools)
            ring.centerA11y = getString(if (tools) R.string.a11y_center_tools else R.string.a11y_center_convert)
        }
    }

    // ------------------------------------------------------------------ what is offered (only real, implemented operations)
    private val one get() = if (srcs.size == 1) srcs[0].name else getString(R.string.files_selected, srcs.size)

    private fun convertActions(): List<Action> = when (Engine.kindOf(srcs)) {
        Kind.IMAGE -> listOf(
            fmt("JPG") { s -> Engine.image(this, s, "jpg", null) },
            fmt("PNG") { s -> Engine.image(this, s, "png", null) },
            fmt("WEBP") { s -> Engine.image(this, s, "webp", null, quality = 88) },
            Action("PDF", getString(R.string.one_pdf), R.drawable.ic_pdf, getString(R.string.sum_pdf_join), R.string.start_convert,
                direct = { launchJob(R.string.converting) { listOf(Engine.pdf(this, srcs)) } }, pro = false),
        )
        Kind.VIDEO -> listOf(
            Action("MP4", getString(R.string.compatible), R.drawable.ic_video, getString(R.string.sum_convert, one, "MP4"), R.string.start_convert,
                direct = { media(Engine.MediaOpts(), "h264") }, pro = false, freeMaxMs = FREE_MS),
            Action("M4A", getString(R.string.audio_only), R.drawable.ic_audio, getString(R.string.sum_audio, one), R.string.start_convert,
                direct = { media(Engine.MediaOpts(removeVideo = true), null) }, pro = false, freeMaxMs = FREE_MS),
            Action(getString(R.string.snapshot), "JPG", R.drawable.ic_frame, getString(R.string.sum_snapshot, one), R.string.start_convert,
                direct = { each { Engine.frame(this, it) } }),
        )
        Kind.AUDIO -> listOf(
            Action("M4A", "AAC", R.drawable.ic_audio, getString(R.string.sum_convert, one, "M4A"), R.string.start_convert,
                direct = { media(Engine.MediaOpts(), null) }, pro = false, freeMaxMs = FREE_MS),
        )
        Kind.PDF -> buildList {
            add(Action("JPG", getString(R.string.pages_as_images), R.drawable.ic_image, getString(R.string.sum_pdf_pages, one, "JPG"), R.string.start_convert,
                direct = { pdfPages("jpg") }))
            add(Action("PNG", getString(R.string.pages_as_images), R.drawable.ic_image, getString(R.string.sum_pdf_pages, one, "PNG"), R.string.start_convert,
                direct = { pdfPages("png") }))
            if (srcs.size > 1) add(Action(getString(R.string.t_merge), getString(R.string.s_join), R.drawable.ic_merge, getString(R.string.sum_merge), R.string.start_merge,
                direct = { launchJob(R.string.working) { listOf(Pdf.merge(this@RingActivity, srcs)) } }))
        }
        Kind.OTHER -> listOf(zipAction())
    }

    private fun toolActions(): List<Action> {
        val l = mutableListOf<Action>()
        when (Engine.kindOf(srcs)) {
            Kind.IMAGE -> {
                l += Action(getString(R.string.t_compress), getString(R.string.s_smaller), R.drawable.ic_compress, getString(R.string.sum_compress_img, one), R.string.start_compress,
                    direct = { each { s -> val jpg = !s.mime.contains("png") && !s.mime.contains("webp"); Engine.image(this, s, if (jpg) "jpg" else "webp", "small", quality = 72, maxDim = 2560) } }, pro = false)
                l += Action(getString(R.string.t_resize), getString(R.string.s_scale), R.drawable.ic_resize, getString(R.string.sum_resize, one), R.string.start_resize,
                    options = scaleOptions { f -> each { Engine.image(this, it, null, "${(f * 100).toInt()}pct", scale = f) } })
                l += Action(getString(R.string.t_crop), getString(R.string.s_aspect), R.drawable.ic_crop, getString(R.string.sum_crop, one), R.string.start_crop,
                    options = aspectOptions { a, tag -> each { Engine.image(this, it, null, tag, aspect = a) } })
                l += Action(getString(R.string.t_watermark), getString(R.string.s_text), R.drawable.ic_watermark, getString(R.string.sum_watermark, one), R.string.start_watermark,
                    options = textOptions { t -> each { Engine.watermarkImage(this, it, t) } })
                l += Action(getString(R.string.t_clean), getString(R.string.s_metadata), R.drawable.ic_clean, getString(R.string.sum_clean, one), R.string.start_clean,
                    direct = { each { Engine.image(this, it, null, "clean", quality = 95) } })
            }
            Kind.VIDEO -> {
                l += Action(getString(R.string.t_compress), getString(R.string.s_smaller), R.drawable.ic_compress, getString(R.string.sum_compress_vid, one), R.string.start_compress,
                    direct = { val h = Engine.info(this, srcs[0]).third; media(Engine.MediaOpts(height = if (h > 720 || h == 0) 720 else h, videoBitrate = 2_000_000), "small") })
                l += Action(getString(R.string.t_resize), getString(R.string.s_scale), R.drawable.ic_resize, getString(R.string.sum_resize, one), R.string.start_resize,
                    options = scaleOptions { f -> val h = Engine.info(this, srcs[0]).third.takeIf { it > 0 } ?: 720
                        media(Engine.MediaOpts(height = ((h * f / 2).toInt() * 2).coerceAtLeast(2)), "${(f * 100).toInt()}pct") })
                l += Action(getString(R.string.t_crop), getString(R.string.s_aspect), R.drawable.ic_crop, getString(R.string.sum_crop, one), R.string.start_crop,
                    options = aspectOptions { a, tag -> media(Engine.MediaOpts(aspect = a.toFloat()), tag) })
                l += Action(getString(R.string.t_trim), getString(R.string.s_duration), R.drawable.ic_trim, getString(R.string.sum_trim, one), R.string.start_trim,
                    options = trimOptions())
                l += Action(getString(R.string.t_mute), null, R.drawable.ic_mute, getString(R.string.sum_mute, one), R.string.start_mute,
                    direct = { media(Engine.MediaOpts(removeAudio = true), "mute") })
                l += Action(getString(R.string.t_clean), getString(R.string.s_metadata), R.drawable.ic_clean, getString(R.string.sum_clean, one), R.string.start_clean,
                    direct = { media(Engine.MediaOpts(), "clean") })
            }
            Kind.AUDIO -> {
                l += Action(getString(R.string.t_trim), getString(R.string.s_duration), R.drawable.ic_trim, getString(R.string.sum_trim, one), R.string.start_trim,
                    options = trimOptions())
                l += Action(getString(R.string.t_clean), getString(R.string.s_metadata), R.drawable.ic_clean, getString(R.string.sum_clean, one), R.string.start_clean,
                    direct = { media(Engine.MediaOpts(), "clean") })
            }
            Kind.PDF -> {
                l += Action(getString(R.string.t_compress), getString(R.string.s_scanned), R.drawable.ic_compress, getString(R.string.sum_compress_pdf, one), R.string.start_compress,
                    direct = { launchJob(R.string.working) { p -> srcs.mapIndexed { i, s -> Pdf.compress(this, s) { x -> p((100 * i + x) / srcs.size) } } } })
                if (srcs.size == 1) l += Action(getString(R.string.t_extract), getString(R.string.s_extract), R.drawable.ic_split, getString(R.string.sum_extract, one), R.string.start_extract,
                    options = pageOptions())
                l += Action(getString(R.string.t_watermark), getString(R.string.s_text), R.drawable.ic_watermark, getString(R.string.sum_watermark, one), R.string.start_watermark,
                    options = textOptions { t -> launchJob(R.string.working) { srcs.map { Pdf.watermark(this, it, t) } } })
            }
            Kind.OTHER -> {}
        }
        l += zipAction()
        return l
    }

    private fun zipAction() = Action("ZIP", getString(R.string.s_archive), R.drawable.ic_zip, getString(R.string.sum_zip), R.string.start_zip,
        direct = { launchJob(R.string.working) { listOf(Engine.zip(this, srcs)) } })

    private fun fmt(name: String, op: suspend (Src) -> Saved) =
        Action(name, null, R.drawable.ic_image, getString(R.string.sum_convert, one, name), R.string.start_convert, direct = { each(op) }, pro = false)

    // ------------------------------------------------------------------ settings sheet
    private fun choose(a: Action) {
        if (job?.isActive == true) return
        if (locked(a)) {
            debugLog("PAYWALL ${a.label}")
            Pro.showPaywall(this, if (!a.pro && srcs.size > 1) R.string.pro_reason_multi else R.string.pro_reason_tool)
            return
        }
        if (!Pro.isPro && a.freeMaxMs > 0) {
            lifecycleScope.launch {
                val dur = withContext(Dispatchers.IO) { Engine.info(this@RingActivity, srcs[0]).first }
                if (dur > a.freeMaxMs) { debugLog("PAYWALL long ${a.label}"); Pro.showPaywall(this@RingActivity, R.string.pro_reason_long) }
                else open(a)
            }
            return
        }
        open(a)
    }

    private fun open(a: Action) {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("auto", false) && a.direct != null) { a.direct.invoke(); return }
        val sheet = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_action, null)
        v.findViewById<ImageView>(R.id.sheet_icon).setImageResource(a.icon)
        v.findViewById<TextView>(R.id.sheet_title).text = listOfNotNull(a.label, a.sub).joinToString(" · ")
        v.findViewById<TextView>(R.id.sheet_summary).text = a.summary
        val box = v.findViewById<LinearLayout>(R.id.sheet_options)
        val collect = a.options?.invoke(box)
        box.isVisible = collect != null
        val start = v.findViewById<MaterialButton>(R.id.sheet_start)
        start.setText(a.startLabel)
        start.setOnClickListener {
            val runner: (() -> Unit)? = if (collect != null) collect() else a.direct
            if (runner != null) { start.isEnabled = false; sheet.dismiss(); runner() }
        }
        v.findViewById<View>(R.id.sheet_cancel).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(v)
        sheet.show()
    }

    private fun chips(box: LinearLayout, labels: List<String>, checked: Int): ChipGroup {
        val g = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        labels.forEachIndexed { i, s ->
            g.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = s; id = View.generateViewId(); isCheckable = true; isChecked = i == checked
                setEnsureMinTouchTargetSize(true)
            })
        }
        box.addView(g)
        return g
    }

    private fun checkedIndex(g: ChipGroup) = (0 until g.childCount).indexOfFirst { (g.getChildAt(it) as Chip).isChecked }.coerceAtLeast(0)

    private fun scaleOptions(then: (Double) -> Unit): (LinearLayout) -> (() -> (() -> Unit)?) = { box ->
        val vals = doubleArrayOf(0.25, 0.5, 0.75, 1.5, 2.0)
        val g = chips(box, listOf("25%", "50%", "75%", "150%", "200%"), 1)
        ({ val f = vals[checkedIndex(g)]; { then(f) } })
    }

    private fun aspectOptions(then: (Double, String) -> Unit): (LinearLayout) -> (() -> (() -> Unit)?) = { box ->
        val vals = doubleArrayOf(1.0, 0.8, 16.0 / 9, 9.0 / 16, 4.0 / 3)
        val tags = arrayOf("1x1", "4x5", "16x9", "9x16", "4x3")
        val g = chips(box, listOf("1:1", "4:5", "16:9", "9:16", "4:3"), 0)
        ({ val i = checkedIndex(g); { then(vals[i], tags[i]) } })
    }

    private fun field(box: LinearLayout, label: Int, value: String, type: Int): EditText {
        box.addView(TextView(this).apply { setText(label); setTextColor(getColor(R.color.text2)); setPadding(0, dp(8), 0, 0) })
        return EditText(this).apply { setText(value); inputType = type; setTextColor(getColor(R.color.text)); textDirection = View.TEXT_DIRECTION_LTR; box.addView(this) }
    }

    private fun error(box: LinearLayout): TextView =
        TextView(this).apply { setTextColor(getColor(R.color.danger)); isVisible = false; setPadding(0, dp(8), 0, 0); box.addView(this) }

    private fun trimOptions(): (LinearLayout) -> (() -> (() -> Unit)?) = { box ->
        val durMs = Engine.info(this, srcs[0]).first
        box.addView(TextView(this).apply { text = getString(R.string.full_length, fmtTime(durMs / 1000)); setTextColor(getColor(R.color.text)) })
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val from = field(box, R.string.trim_from, "0", num)
        val to = field(box, R.string.trim_to, String.format(Locale.US, "%.0f", if (durMs > 0) durMs / 1000.0 else 10.0), num)
        val err = error(box)
        ({
            val s = from.text.toString().trim().toDoubleOrNull()
            var e = to.text.toString().trim().toDoubleOrNull()
            val len = durMs / 1000.0
            if (e != null && len > 0 && e > len) e = len
            if (s == null || e == null || s < 0 || e <= s || (len > 0 && s >= len)) { err.setText(R.string.bad_time); err.isVisible = true; null }
            else { val sMs = (s * 1000).toLong(); val eMs = (e * 1000).toLong(); { media(Engine.MediaOpts(clipStartMs = sMs, clipEndMs = eMs), "cut") } }
        })
    }

    private fun pageOptions(): (LinearLayout) -> (() -> (() -> Unit)?) = { box ->
        val n = Pdf.pageCount(this, srcs[0])
        box.addView(TextView(this).apply { text = resources.getString(R.string.pages_count, n); setTextColor(getColor(R.color.text)) })
        val from = field(box, R.string.page_from, "1", InputType.TYPE_CLASS_NUMBER)
        val to = field(box, R.string.page_to, n.coerceAtLeast(1).toString(), InputType.TYPE_CLASS_NUMBER)
        val err = error(box)
        ({
            val a = from.text.toString().trim().toIntOrNull(); val b = to.text.toString().trim().toIntOrNull()
            if (a == null || b == null || a < 1 || b < a || (n > 0 && b > n)) { err.setText(R.string.bad_pages); err.isVisible = true; null }
            else { { launchJob(R.string.working) { listOf(Pdf.extract(this, srcs[0], a, b)) } } }
        })
    }

    private fun textOptions(then: (String) -> Unit): (LinearLayout) -> (() -> (() -> Unit)?) = { box ->
        val prefs = getSharedPreferences("sahb", MODE_PRIVATE)
        val f = EditText(this).apply {
            hint = getString(R.string.watermark_hint); setText(prefs.getString("watermark", "")); setTextColor(getColor(R.color.text))
            inputType = InputType.TYPE_CLASS_TEXT; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; box.addView(this)
        }
        val err = error(box)
        ({
            val t = f.text.toString().trim()
            if (t.isEmpty()) { err.setText(R.string.need_text); err.isVisible = true; null }
            else { prefs.edit().putString("watermark", t).apply(); { then(t) } }
        })
    }

    // ------------------------------------------------------------------ running
    private fun each(op: suspend (Src) -> Saved) = launchJob(R.string.converting) { p -> srcs.mapIndexed { i, s -> p(100 * i / srcs.size); op(s) } }
    private fun pdfPages(fmt: String) = launchJob(R.string.converting) { p -> srcs.flatMapIndexed { i, s -> Pdf.toImages(this, s, fmt) { x -> p((100 * i + x) / srcs.size) } } }
    private fun media(opts: Engine.MediaOpts, tag: String?) = launchJob(R.string.converting, determinate = true) { p ->
        srcs.mapIndexed { i, s -> Engine.media(this, s, opts, tag) { x -> p((100 * i + x) / srcs.size) } }
    }

    private fun launchJob(label: Int, determinate: Boolean = false, work: suspend ((Int) -> Unit) -> List<Saved>) {
        if (job?.isActive == true) return // no double start
        lastRun = { launchJob(label, determinate, work) }
        show(R.id.busy)
        findViewById<TextView>(R.id.busy_label).setText(label)
        val prog = findViewById<CircularProgressIndicator>(R.id.progress)
        val pct = findViewById<TextView>(R.id.percent)
        prog.isIndeterminate = true; pct.text = ""
        job = lifecycleScope.launch {
            try {
                val out = work { p ->
                    // only show a number when the engine really reports progress
                    if (determinate || srcs.size > 1 || p > 0) runOnUiThread { prog.isIndeterminate = false; prog.setProgressCompat(p, true); pct.text = "$p%" }
                }
                debugLog("OK ${out.joinToString { it.uri.toString() }}")
                showSuccess(out)
            } catch (e: CancellationException) {
                debugLog("CANCEL")
                showPicker()
            } catch (e: Throwable) {
                Log.e("Sahb", "job failed", e)
                debugLog("FAIL ${e.javaClass.simpleName}: ${e.message}")
                showFailure(getString(R.string.failed_body), retry = true)
            }
        }
    }

    private fun showSuccess(out: List<Saved>) {
        show(R.id.result)
        findViewById<ImageView>(R.id.result_icon).setImageResource(R.drawable.ic_check_circle)
        findViewById<TextView>(R.id.result_title).text = if (out.size == 1) getString(R.string.done) else getString(R.string.done_many, out.size)
        val total = out.sumOf { it.size }
        findViewById<TextView>(R.id.result_name).apply {
            isVisible = true
            text = if (out.size == 1) "${out[0].name}  •  ${Formatter.formatShortFileSize(this@RingActivity, total)}"
                   else Formatter.formatShortFileSize(this@RingActivity, total)
        }
        findViewById<TextView>(R.id.result_msg).text = getString(R.string.saved_in, out[0].folder)
        val open = findViewById<MaterialButton>(R.id.btn_open)
        val share = findViewById<MaterialButton>(R.id.btn_share)
        open.isVisible = out.size == 1; share.isVisible = true
        open.setText(R.string.open)
        open.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(out[0].uri, out[0].mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }
        share.setOnClickListener {
            val i = if (out.size == 1) Intent(Intent.ACTION_SEND).setType(out[0].mime).putExtra(Intent.EXTRA_STREAM, out[0].uri)
                    else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(out.map { it.uri }))
            i.clipData = ClipData.newRawUri("", out[0].uri).apply { out.drop(1).forEach { addItem(ClipData.Item(it.uri)) } }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, null))
        }
        findViewById<MaterialButton>(R.id.btn_again).apply { setText(R.string.another); setOnClickListener { changePicker.launch(arrayOf("*/*")) } }
    }

    private fun showFailure(msg: String, retry: Boolean) {
        show(R.id.result)
        findViewById<ImageView>(R.id.result_icon).setImageResource(R.drawable.ic_error_circle)
        findViewById<TextView>(R.id.result_title).setText(R.string.failed)
        findViewById<TextView>(R.id.result_name).isVisible = false
        findViewById<TextView>(R.id.result_msg).text = msg
        findViewById<MaterialButton>(R.id.btn_share).isVisible = false
        val open = findViewById<MaterialButton>(R.id.btn_open)
        open.isVisible = retry && lastRun != null
        open.setText(R.string.try_again)
        open.setOnClickListener { lastRun?.invoke() }
        findViewById<MaterialButton>(R.id.btn_again).apply {
            setText(if (srcs.isEmpty()) R.string.close else R.string.back)
            setOnClickListener { if (srcs.isEmpty()) finish() else showPicker() }
        }
    }

    private fun debugLog(line: String) {
        if (!BuildConfig.DEBUG) return
        Log.i("SahbJob", line)
        runCatching { File(getExternalFilesDir(null), "jobs.log").appendText(line + "\n") }
    }

    companion object { private const val FREE_MS = 3 * 60 * 1000L }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmtTime(sec: Long) = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)

    // debug-only hook so the emulator test can pick a slice by index
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent.hasExtra("pick")) {
            if (intent.getBooleanExtra("tools", false) != tools) { tools = !tools; refreshChoices() }
            this.intent.putExtra("auto", intent.getBooleanExtra("auto", false))
            actions.getOrNull(intent.getIntExtra("pick", -1))?.let { choose(it) }
        }
    }
}

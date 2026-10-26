package com.taha.sahb

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Transparent screen that shows the ring over the app the file was shared from. */
class RingActivity : AppCompatActivity() {

    private lateinit var ring: RingView
    private lateinit var busy: View
    private lateinit var result: View
    private var srcs: List<Src> = emptyList()
    private var tools = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ring)
        ring = findViewById(R.id.ring)
        busy = findViewById(R.id.busy)
        result = findViewById(R.id.result)
        findViewById<View>(R.id.scrim).setOnClickListener { if (!busy.isVisible && !result.isVisible) finish() }

        srcs = incomingUris().map { Engine.describe(this, it) }
        if (srcs.isEmpty()) {
            showResult(false, getString(R.string.nothing_to_do), null)
            return
        }
        ring.onPick = { i -> currentItems.getOrNull(i)?.run?.invoke() }
        ring.onCenterTap = { tools = !tools; refresh() }
        ring.onOutsideTap = { finish() }
        tools = intent.getBooleanExtra("tools", false)
        refresh()
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

    private var currentItems: List<RingItem> = emptyList()

    private fun refresh() {
        currentItems = if (tools) toolItems() else convertItems()
        ring.items = currentItems
        val count = if (srcs.size > 1) " (${srcs.size})" else ""
        ring.centerTitle = getString(if (tools) R.string.tools else R.string.convert) + count
        ring.centerHint = getString(if (tools) R.string.tap_center_convert else R.string.tap_center_tools)
    }

    // ------------------------------------------------------------ what the ring offers
    private fun convertItems(): List<RingItem> = when (Engine.kindOf(srcs)) {
        Kind.IMAGE -> listOf(
            RingItem("JPG", null) { each("jpg") { Engine.image(this, it, "jpg", null) } },
            RingItem("PNG", null) { each("png") { Engine.image(this, it, "png", null) } },
            RingItem("WEBP", null) { each("webp") { Engine.image(this, it, "webp", null, quality = 88) } },
            RingItem("PDF", getString(R.string.s_pages)) { single { Engine.pdf(this, srcs) } },
        )
        Kind.VIDEO -> listOf(
            RingItem("MP4", getString(R.string.s_compatible)) { media(Engine.MediaOpts(), "h264") },
            RingItem("M4A", getString(R.string.s_audio)) { media(Engine.MediaOpts(removeVideo = true), null) },
            RingItem(getString(R.string.t_frame), getString(R.string.s_frame)) { each("frame") { Engine.frame(this, it) } },
        )
        Kind.AUDIO -> listOf(
            RingItem("M4A", "AAC") { media(Engine.MediaOpts(), null) },
            RingItem("ZIP", getString(R.string.s_archive)) { single { Engine.zip(this, srcs) } },
        )
        Kind.OTHER -> listOf(RingItem("ZIP", getString(R.string.s_archive)) { single { Engine.zip(this, srcs) } })
    }

    private fun toolItems(): List<RingItem> {
        val k = Engine.kindOf(srcs)
        val l = mutableListOf<RingItem>()
        when (k) {
            Kind.IMAGE -> {
                l += RingItem(getString(R.string.t_compress), getString(R.string.s_smaller)) {
                    each("small") { s ->
                        val jpg = !s.mime.contains("png") && !s.mime.contains("webp")
                        Engine.image(this, s, if (jpg) "jpg" else "webp", "small", quality = 72, maxDim = 2560)
                    }
                }
                l += RingItem(getString(R.string.t_size), getString(R.string.s_scale)) {
                    askScale { f -> each("resize") { Engine.image(this, it, null, "${(f * 100).toInt()}pct", scale = f) } }
                }
                l += RingItem(getString(R.string.t_crop), getString(R.string.s_aspect)) {
                    askAspect { a, tag -> each("crop") { Engine.image(this, it, null, tag, aspect = a) } }
                }
                l += RingItem(getString(R.string.t_clean), getString(R.string.s_meta)) {
                    each("clean") { Engine.image(this, it, null, "clean", quality = 95) }
                }
            }
            Kind.VIDEO -> {
                l += RingItem(getString(R.string.t_compress), getString(R.string.s_smaller)) {
                    val h = Engine.info(this, srcs[0]).third
                    media(Engine.MediaOpts(height = if (h > 720 || h == 0) 720 else h, videoBitrate = 2_000_000), "small")
                }
                l += RingItem(getString(R.string.t_size), getString(R.string.s_scale)) {
                    askScale { f ->
                        val h = Engine.info(this, srcs[0]).third.takeIf { it > 0 } ?: 720
                        media(Engine.MediaOpts(height = ((h * f / 2).toInt() * 2).coerceAtLeast(2)), "${(f * 100).toInt()}pct")
                    }
                }
                l += RingItem(getString(R.string.t_crop), getString(R.string.s_aspect)) {
                    askAspect { a, tag -> media(Engine.MediaOpts(aspect = a.toFloat()), tag) }
                }
                l += RingItem(getString(R.string.t_trim), getString(R.string.s_part)) { askTrim { s, e -> media(Engine.MediaOpts(clipStartMs = s, clipEndMs = e), "cut") } }
                l += RingItem(getString(R.string.t_mute), getString(R.string.s_noaudio)) { media(Engine.MediaOpts(removeAudio = true), "mute") }
                l += RingItem(getString(R.string.t_clean), getString(R.string.s_meta)) { media(Engine.MediaOpts(), "clean") }
            }
            Kind.AUDIO -> {
                l += RingItem(getString(R.string.t_trim), getString(R.string.s_part)) { askTrim { s, e -> media(Engine.MediaOpts(clipStartMs = s, clipEndMs = e), "cut") } }
                l += RingItem(getString(R.string.t_clean), getString(R.string.s_meta)) { media(Engine.MediaOpts(), "clean") }
            }
            Kind.OTHER -> {}
        }
        l += RingItem("ZIP", getString(R.string.s_archive)) { single { Engine.zip(this, srcs) } }
        return l
    }

    // ------------------------------------------------------------ running jobs
    private fun each(tag: String, op: suspend (Src) -> Saved) = run(tag) { onP -> srcs.mapIndexed { i, s -> onP(100 * i / srcs.size); op(s) } }
    private fun single(op: suspend () -> Saved) = run("single") { listOf(op()) }
    private fun media(opts: Engine.MediaOpts, tag: String?) = run(tag ?: "media") { onP ->
        srcs.mapIndexed { i, s -> Engine.media(this, s, opts, tag) { p -> onP((100 * i + p) / srcs.size) } }
    }

    private fun run(label: String, work: suspend ((Int) -> Unit) -> List<Saved>) {
        ring.isVisible = false
        busy.isVisible = true
        val prog = findViewById<CircularProgressIndicator>(R.id.progress)
        val pct = findViewById<TextView>(R.id.percent)
        prog.isIndeterminate = true
        lifecycleScope.launch {
            try {
                val out = work { p -> runOnUiThread { prog.isIndeterminate = false; prog.setProgressCompat(p, true); pct.text = "$p%" } }
                debugLog("OK $label ${out.joinToString { it.uri.toString() }}")
                val msg = if (out.size == 1) getString(R.string.saved_to, out[0].folder)
                          else getString(R.string.files_count, out.size) + "\n" + getString(R.string.saved_to, out[0].folder)
                showResult(true, msg, out)
            } catch (e: Throwable) {
                Log.e("Sahb", "job failed", e)
                debugLog("FAIL $label ${e.javaClass.simpleName}: ${e.message}")
                showResult(false, e.message ?: e.javaClass.simpleName, null)
            }
        }
    }

    private fun showResult(ok: Boolean, msg: String, out: List<Saved>?) {
        ring.isVisible = false
        busy.isVisible = false
        result.isVisible = true
        findViewById<TextView>(R.id.result_title).setText(if (ok) R.string.done else R.string.failed)
        findViewById<TextView>(R.id.result_msg).text = msg
        val open = findViewById<MaterialButton>(R.id.btn_open)
        val share = findViewById<MaterialButton>(R.id.btn_share)
        open.isVisible = ok && out?.size == 1
        share.isVisible = ok && !out.isNullOrEmpty()
        open.setOnClickListener {
            val s = out!![0]
            runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            finish()
        }
        share.setOnClickListener {
            val list = out!!
            val i = if (list.size == 1) Intent(Intent.ACTION_SEND).setType(list[0].mime).putExtra(Intent.EXTRA_STREAM, list[0].uri)
                    else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { it.uri }))
            i.clipData = ClipData.newRawUri("", list[0].uri).apply { list.drop(1).forEach { addItem(ClipData.Item(it.uri)) } }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, null))
            finish()
        }
        findViewById<MaterialButton>(R.id.btn_close).setOnClickListener { finish() }
    }

    private fun debugLog(line: String) {
        if (!BuildConfig.DEBUG) return
        Log.i("SahbJob", line)
        runCatching { File(getExternalFilesDir(null), "jobs.log").appendText(line + "\n") }
    }

    // ------------------------------------------------------------ parameter dialogs
    private fun askScale(then: (Double) -> Unit) {
        val opts = arrayOf("25%", "50%", "75%", "150%", "200%")
        val vals = doubleArrayOf(0.25, 0.5, 0.75, 1.5, 2.0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.resize_title)
            .setItems(opts) { _, w -> then(vals[w]) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun askAspect(then: (Double, String) -> Unit) {
        val opts = arrayOf("1:1", "4:5", "16:9", "9:16", "4:3")
        val vals = doubleArrayOf(1.0, 0.8, 16.0 / 9, 9.0 / 16, 4.0 / 3)
        val tags = arrayOf("1x1", "4x5", "16x9", "9x16", "4x3")
        MaterialAlertDialogBuilder(this).setTitle(R.string.crop_title)
            .setItems(opts) { _, w -> then(vals[w], tags[w]) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun askTrim(then: (Long, Long) -> Unit) {
        try { askTrimImpl(then) } catch (e: Throwable) {
            Log.e("Sahb", "trim dialog failed", e)
            debugLog("FAIL trim-dialog ${e.javaClass.simpleName}: ${e.message}")
            showResult(false, e.message ?: e.javaClass.simpleName, null)
        }
    }

    private fun askTrimImpl(then: (Long, Long) -> Unit) {
        debugLog("DIALOG trim")
        val durMs = Engine.info(this, srcs[0]).first
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(TextView(this).apply { text = getString(R.string.duration, fmt(durMs / 1000.0)) })
        val from = EditText(this).apply { hint = getString(R.string.trim_from); setText("0"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val to = EditText(this).apply {
            hint = getString(R.string.trim_to)
            setText(String.format(Locale.US, "%.0f", if (durMs > 0) durMs / 1000.0 else 10.0))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        box.addView(TextView(this).apply { text = getString(R.string.trim_from) }); box.addView(from)
        box.addView(TextView(this).apply { text = getString(R.string.trim_to) }); box.addView(to)
        MaterialAlertDialogBuilder(this).setTitle(R.string.trim_title).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val s = from.text.toString().toDoubleOrNull()
                val e = to.text.toString().toDoubleOrNull()
                if (s == null || e == null || e <= s || s < 0) {
                    MaterialAlertDialogBuilder(this).setMessage(R.string.bad_time).setPositiveButton(R.string.close, null).show()
                } else then((s * 1000).toLong(), (e * 1000).toLong())
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun fmt(sec: Double): String {
        val t = sec.toLong()
        return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    // debug-only hook so the emulator test can pick a slice by index without touch coordinates
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent.hasExtra("pick")) {
            if (intent.getBooleanExtra("tools", false) != tools) { tools = !tools; refresh() }
            currentItems.getOrNull(intent.getIntExtra("pick", -1))?.run?.invoke()
        }
    }

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}

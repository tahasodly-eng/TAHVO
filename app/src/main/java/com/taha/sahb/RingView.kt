package com.taha.sahb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class RingItem(val label: String, val sub: String?, val run: () -> Unit)

/**
 * The format ring. Tap a slice, or press in the centre and slide onto a slice and lift.
 * A tap on the centre switches between convert and tools.
 */
class RingView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var items: List<RingItem> = emptyList()
        set(v) { field = v; hover = -1; invalidate(); logSlices() }
    var centerTitle = ""
        set(v) { field = v; invalidate() }
    var centerHint = ""
        set(v) { field = v; invalidate() }

    var onPick: ((Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    var onOutsideTap: (() -> Unit)? = null

    private var hover = -1
    private var downInCenter = false
    private var moved = false
    private val d = resources.displayMetrics.density

    private val violet = Color.parseColor("#7C4DFF")
    private val cyan = Color.parseColor("#00C2FF")
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 24, 24, 32) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Color.argb(60, 255, 255, 255) }
    private val hotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val centerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val subLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textAlign = Paint.Align.CENTER }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 40); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 110, 125); textAlign = Paint.Align.CENTER }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val rOut get() = min(width, height) / 2f * 0.86f
    private val rIn get() = rOut * 0.36f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val g = LinearGradient(cx - rOut, cy - rOut, cx + rOut, cy + rOut, violet, cyan, Shader.TileMode.CLAMP)
        hotPaint.shader = g
        centerRim.shader = LinearGradient(cx - rIn, cy - rIn, cx + rIn, cy + rIn, violet, cyan, Shader.TileMode.CLAMP)
        logSlices()
    }

    override fun onDraw(c: Canvas) {
        val n = items.size.coerceAtLeast(1)
        val slice = 360f / n
        val gap = if (items.size > 1) 2.2f else 0f
        label.textSize = (if (items.size > 6) 15f else 17f) * d
        subLabel.textSize = 10.5f * d
        for (i in items.indices) {
            val start = -90f - slice / 2 + i * slice + gap / 2
            val sweep = slice - gap
            val hot = i == hover
            val ro = if (hot) rOut + 6 * d else rOut
            val path = Path().apply {
                arcTo(RectF(cx - ro, cy - ro, cx + ro, cy + ro), start, sweep)
                arcTo(RectF(cx - rIn, cy - rIn, cx + rIn, cy + rIn), start + sweep, -sweep)
                close()
            }
            c.drawPath(path, if (hot) hotPaint else slicePaint)
            c.drawPath(path, edgePaint)
            val mid = Math.toRadians((start + sweep / 2).toDouble())
            val r = (rIn + rOut) / 2 + 4 * d
            val x = cx + (cos(mid) * r).toFloat()
            val y = cy + (sin(mid) * r).toFloat()
            val sub = items[i].sub
            c.drawText(items[i].label, x, y + (if (sub == null) 6 * d else 0f), label)
            if (sub != null) c.drawText(sub, x, y + 16 * d, subLabel)
        }
        c.drawCircle(cx, cy, rIn - 6 * d, centerPaint)
        c.drawCircle(cx, cy, rIn - 6 * d, centerRim)
        titlePaint.textSize = 17f * d
        hintPaint.textSize = 10.5f * d
        c.drawText(centerTitle, cx, cy - 1 * d, titlePaint)
        c.drawText(centerHint, cx, cy + 17 * d, hintPaint)
    }

    private fun hit(x: Float, y: Float): Int {
        if (items.isEmpty()) return -1
        val dx = x - cx; val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < rIn || dist > rOut + 10 * d) return -1
        val ang = Math.toDegrees(atan2(dy, dx).toDouble())
        val a = (ang + 90 + 360) % 360
        val slice = 360.0 / items.size
        return (((a + slice / 2) % 360) / slice).toInt() % items.size
    }

    private fun inCenter(x: Float, y: Float): Boolean {
        val dx = x - cx; val dy = y - cy
        return sqrt(dx * dx + dy * dy) < rIn
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downInCenter = inCenter(e.x, e.y); moved = false
                setHover(hit(e.x, e.y))
            }
            MotionEvent.ACTION_MOVE -> {
                val h = hit(e.x, e.y)
                if (h != hover) { moved = true; setHover(h) }
            }
            MotionEvent.ACTION_UP -> {
                val h = hit(e.x, e.y)
                when {
                    h >= 0 -> { performClick(); onPick?.invoke(h) }
                    downInCenter && inCenter(e.x, e.y) && !moved -> { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onCenterTap?.invoke() }
                    !downInCenter && !inCenter(e.x, e.y) -> onOutsideTap?.invoke()
                }
                setHover(-1)
            }
            MotionEvent.ACTION_CANCEL -> setHover(-1)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun setHover(h: Int) {
        if (h == hover) return
        hover = h
        if (h >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    /** Debug builds log each slice's screen position so the emulator test can tap it. */
    private fun logSlices() {
        if (!BuildConfig.DEBUG || width == 0 || items.isEmpty()) return
        post {
            val loc = IntArray(2); getLocationOnScreen(loc)
            val slice = 360.0 / items.size
            items.forEachIndexed { i, it ->
                val mid = Math.toRadians(-90.0 + i * slice)
                val r = (rIn + rOut) / 2
                Log.i("SahbRing", "slice ${it.label} ${(loc[0] + cx + cos(mid) * r).toInt()} ${(loc[1] + cy + sin(mid) * r).toInt()}")
            }
            Log.i("SahbRing", "center ${(loc[0] + cx).toInt()} ${(loc[1] + cy).toInt()}")
        }
    }
}

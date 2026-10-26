package com.taha.sahb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One slice of the ring. Text comes from string resources; [icon] is a drawable id. */
class RingItem(val label: String, val sub: String?, val icon: Int, val run: () -> Unit)

/**
 * The format ring. Every position (slice outlines, icons, labels, touch areas and the
 * accessibility bounds) is computed from the same geometry in [sliceStart] / [hit].
 * Tap a slice, or press in the centre and slide onto a slice and lift.
 * Tapping the centre switches between conversion and tools.
 */
class RingView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var items: List<RingItem> = emptyList()
        set(v) { field = v; hover = -1; icons = v.map { ContextCompat.getDrawable(context, it.icon)?.mutate() }; invalidate(); a11y.invalidateRoot(); logSlices() }
    var centerTitle = ""
        set(v) { field = v; invalidate(); a11y.invalidateRoot() }
    var centerHint = ""
        set(v) { field = v; invalidate() }
    var centerA11y = ""

    var onPick: ((Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null

    private var icons: List<Drawable?> = emptyList()
    private var hover = -1
    private var pressedCenter = false
    private var downInCenter = false
    private var moved = false
    private val d = resources.displayMetrics.density

    private val cBg = ContextCompat.getColor(ctx, R.color.card)
    private val cLine = ContextCompat.getColor(ctx, R.color.line)
    private val cAccent = ContextCompat.getColor(ctx, R.color.accent)
    private val cOnAccent = ContextCompat.getColor(ctx, R.color.on_accent)
    private val cText = ContextCompat.getColor(ctx, R.color.text)
    private val cText2 = ContextCompat.getColor(ctx, R.color.text2)
    private val cSel = ContextCompat.getColor(ctx, R.color.selected)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val subLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    // ---------------- geometry (single source of truth)
    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val rOut get() = min(width, height) / 2f - 6 * d
    private val rIn get() = rOut * 0.37f
    private val slice get() = 360f / items.size.coerceAtLeast(1)
    private fun sliceStart(i: Int) = -90f - slice / 2 + i * slice
    private fun midAngle(i: Int) = Math.toRadians((sliceStart(i) + slice / 2).toDouble())

    private val a11y = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val h = hit(x, y)
            return when { h >= 0 -> h; inCenter(x, y) -> CENTER_ID; else -> INVALID_ID }
        }
        override fun getVisibleVirtualViews(ids: MutableList<Int>) { items.indices.forEach { ids += it }; ids += CENTER_ID }
        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            if (id == CENTER_ID) {
                node.contentDescription = centerA11y.ifEmpty { centerTitle }
                node.setBoundsInParent(Rect((cx - rIn).toInt(), (cy - rIn).toInt(), (cx + rIn).toInt(), (cy + rIn).toInt()))
            } else {
                val it = items[id]
                node.contentDescription = listOfNotNull(it.label, it.sub).joinToString(", ")
                val r = (rIn + rOut) / 2; val half = (rOut - rIn) / 2
                val x = cx + (cos(midAngle(id)) * r).toFloat(); val y = cy + (sin(midAngle(id)) * r).toFloat()
                node.setBoundsInParent(Rect((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt()))
            }
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
            node.className = "android.widget.Button"
        }
        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            if (id == CENTER_ID) onCenterTap?.invoke() else onPick?.invoke(id)
            return true
        }
    }

    init { ViewCompat.setAccessibilityDelegate(this, a11y) }

    override fun dispatchHoverEvent(e: MotionEvent): Boolean = a11y.dispatchHoverEvent(e) || super.dispatchHoverEvent(e)

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        val gap = if (items.size > 1) 1.6f else 0f
        val big = items.size > 6
        label.textSize = (if (big) 15f else 18f) * d
        subLabel.textSize = 11.5f * d
        val iconSize = ((if (big) 26 else 32) * d).toInt()

        // full disc behind the slices so nothing shows through the gaps
        fill.color = cLine
        c.drawCircle(cx, cy, rOut, fill)

        for (i in items.indices) {
            val start = sliceStart(i) + gap / 2
            val sweep = slice - gap
            val hot = i == hover
            val path = Path().apply {
                arcTo(RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut), start, sweep)
                arcTo(RectF(cx - rIn, cy - rIn, cx + rIn, cy + rIn), start + sweep, -sweep)
                close()
            }
            fill.color = if (hot) cSel else cBg
            c.drawPath(path, fill)
            if (hot) { // selection is shown by outline + fill, not by colour alone
                stroke.color = cAccent; stroke.strokeWidth = 3 * d
                c.drawPath(path, stroke)
            }

            val r = (rIn + rOut) / 2 + 2 * d
            val x = cx + (cos(midAngle(i)) * r).toFloat()
            val y = cy + (sin(midAngle(i)) * r).toFloat()
            val it = items[i]
            val hasSub = it.sub != null
            val iconTop = (y - iconSize - (if (hasSub) 8 else 2) * d).toInt()
            icons.getOrNull(i)?.let { ic ->
                ic.setTint(if (hot) cAccent else cText)
                ic.setBounds((x - iconSize / 2).toInt(), iconTop, (x + iconSize / 2).toInt(), iconTop + iconSize)
                ic.draw(c)
            }
            label.color = cText
            c.drawText(it.label, x, iconTop + iconSize + label.textSize + 2 * d, label)
            if (hasSub) {
                subLabel.color = if (hot) cText else cText2
                c.drawText(it.sub!!, x, iconTop + iconSize + label.textSize + subLabel.textSize + 7 * d, subLabel)
            }
        }

        // centre switch
        fill.color = if (pressedCenter) cSel else cAccent
        c.drawCircle(cx, cy, rIn - 5 * d, fill)
        label.color = if (pressedCenter) cText else cOnAccent
        label.textSize = 21f * d
        c.drawText(centerTitle, cx, cy + 2 * d, label)
        subLabel.color = if (pressedCenter) cText else cOnAccent
        subLabel.textSize = 11.5f * d
        c.drawText(centerHint, cx, cy + 20 * d, subLabel)
    }

    private fun hit(x: Float, y: Float): Int {
        if (items.isEmpty()) return -1
        val dx = x - cx; val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < rIn || dist > rOut) return -1
        val a = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90 + 360 + slice / 2) % 360
        return (a / slice).toInt().coerceIn(0, items.size - 1)
    }

    private fun inCenter(x: Float, y: Float): Boolean {
        val dx = x - cx; val dy = y - cy
        return sqrt(dx * dx + dy * dy) < rIn
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downInCenter = inCenter(e.x, e.y); moved = false
                pressedCenter = downInCenter
                setHover(hit(e.x, e.y)); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val h = hit(e.x, e.y)
                if (h != hover) { moved = true; pressedCenter = false; setHover(h) }
            }
            MotionEvent.ACTION_UP -> {
                val h = hit(e.x, e.y)
                pressedCenter = false
                when {
                    h >= 0 -> { performClick(); onPick?.invoke(h) }
                    downInCenter && inCenter(e.x, e.y) && !moved -> {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onCenterTap?.invoke()
                    }
                }
                setHover(-1); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedCenter = false; setHover(-1); invalidate() }
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

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); logSlices() }

    /** Debug builds log each slice's screen position for the emulator test. */
    private fun logSlices() {
        if (!BuildConfig.DEBUG || width == 0 || items.isEmpty()) return
        post {
            val loc = IntArray(2); getLocationOnScreen(loc)
            val r = (rIn + rOut) / 2
            items.forEachIndexed { i, it ->
                Log.i("SahbRing", "slice $i ${it.label} ${(loc[0] + cx + cos(midAngle(i)) * r).toInt()} ${(loc[1] + cy + sin(midAngle(i)) * r).toInt()}")
            }
            Log.i("SahbRing", "center ${(loc[0] + cx).toInt()} ${(loc[1] + cy).toInt()}")
        }
    }

    companion object { private const val CENTER_ID = 999 }
}

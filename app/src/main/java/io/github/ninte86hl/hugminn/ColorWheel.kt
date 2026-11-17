package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Colour wheel made of dots (like Retroid's LED window): hue around the circle, saturation from the
 * white centre outwards. Works with touch and with the D-pad ([move] jumps to the neighbouring dot).
 */
class ColorWheel(ctx: Context, private val ringColor: Int) : View(ctx) {
    private class Dot(val x: Float, val y: Float, val color: Int)

    private val rings = 7
    private var dots: List<Dot> = emptyList()
    private var spacing = 0f
    var selected = 0
        private set
    /** Called whenever the selection changes (touch or D-pad). */
    var onChange: ((Int) -> Unit)? = null
    /** Called when a dot is tapped (touch confirms right away). */
    var onPick: ((Int) -> Unit)? = null
    /** Highlights the cursor more strongly while the wheel is being steered with the D-pad. */
    var active = false
        set(v) { field = v; invalidate() }
    private var pending: Int? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    val color: Int get() = dots.getOrNull(selected)?.color ?: Color.WHITE

    /** Selects the dot closest to [c] (by hue and saturation). */
    fun select(c: Int) {
        if (dots.isEmpty()) { pending = c; return }
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        val r = hsv[1] * (rings - 1)
        val a = hsv[0] / 180.0 * PI - PI / 2
        val cx = width / 2f + (r * spacing * cos(a)).toFloat()
        val cy = height / 2f + (r * spacing * sin(a)).toFloat()
        selected = dots.indices.minByOrNull { hypot(dots[it].x - cx, dots[it].y - cy) } ?: 0
        invalidate()
    }

    /** D-pad: to the nearest dot in that direction. */
    fun move(dx: Int, dy: Int) {
        val cur = dots.getOrNull(selected) ?: return
        var best = -1
        var bestScore = Float.MAX_VALUE
        dots.forEachIndexed { i, d ->
            if (i == selected) return@forEachIndexed
            val vx = d.x - cur.x
            val vy = d.y - cur.y
            val along = vx * dx + vy * dy
            if (along < spacing * 0.4f) return@forEachIndexed
            val across = kotlin.math.abs(vx * dy - vy * dx)
            val score = along + across * 2.2f
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) {
            selected = best
            invalidate()
            onChange?.invoke(color)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val size = minOf(w, h).toFloat()
        spacing = size / (2f * rings)
        val cx = w / 2f
        val cy = h / 2f
        val list = mutableListOf(Dot(cx, cy, Color.WHITE))
        for (k in 1 until rings) {
            val n = 6 * k
            for (i in 0 until n) {
                val hue = i * 360f / n
                val a = hue / 180.0 * PI - PI / 2
                val c = Color.HSVToColor(floatArrayOf(hue, k / (rings - 1f), 1f))
                list += Dot(cx + (k * spacing * cos(a)).toFloat(), cy + (k * spacing * sin(a)).toFloat(), c)
            }
        }
        dots = list
        pending?.let { pending = null; select(it) }
    }

    override fun onDraw(c: Canvas) {
        val r = spacing * 0.36f
        dots.forEachIndexed { i, d ->
            fill.color = d.color
            c.drawCircle(d.x, d.y, if (i == selected) r * 1.25f else r, fill)
        }
        dots.getOrNull(selected)?.let { d ->
            ring.color = ringColor
            ring.strokeWidth = spacing * if (active) 0.16f else 0.1f
            c.drawCircle(d.x, d.y, r * 1.25f + spacing * 0.16f, ring)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = dots.indices.minByOrNull { hypot(dots[it].x - e.x, dots[it].y - e.y) } ?: return true
        if (hypot(dots[i].x - e.x, dots[i].y - e.y) > spacing) return true
        if (i != selected) {
            selected = i
            invalidate()
            onChange?.invoke(color)
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) onPick?.invoke(color)
        return true
    }

    @Suppress("unused")
    private fun angleOf(x: Float, y: Float) = atan2(y - height / 2f, x - width / 2f)
}

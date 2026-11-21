package io.github.ninte86hl.hugminn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Slide switch with [count] stops side by side (LED window: Aus · Farbe · Kombi · Effekt). The knob
 * sits on the active stop; its centre shows what lights there ([centers], one colour per stop).
 * A tap on a stop picks it directly.
 */
class TriRing(ctx: Context, private val line: Int, private val track: Int, private val count: Int = 3) : View(ctx) {
    var state = 0
        set(v) { field = v; invalidate() }

    /** Colour in the knob's centre for each stop. */
    var centers = IntArray(count) { 0xFF2A2A2A.toInt() }
        set(v) { field = v; invalidate() }

    var onPick: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    private fun slotX(i: Int): Float {
        val r = height / 2f
        return r + i * (width - 2 * r) / (count - 1).coerceAtLeast(1)
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val cy = h / 2f
        // the track with a small mark per stop
        p.style = Paint.Style.FILL
        p.color = track
        val tr = h * 0.30f
        c.drawRoundRect(slotX(0) - tr, cy - tr, slotX(count - 1) + tr, cy + tr, tr, tr, p)
        p.color = line
        for (i in 0 until count) c.drawCircle(slotX(i), cy, h * 0.09f, p)
        // the knob: grey ring, coloured centre for the stop it stands on
        val x = slotX(state.coerceIn(0, count - 1))
        p.color = line
        c.drawCircle(x, cy, h / 2f - 1f * dp, p)
        p.color = 0xFF000000.toInt()
        c.drawCircle(x, cy, h * 0.30f, p)
        p.color = centers.getOrElse(state) { line }
        c.drawCircle(x, cy, h * 0.24f, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val step = (slotX(count - 1) - slotX(0)) / (count - 1).coerceAtLeast(1)
            val k = Math.round((e.x - slotX(0)) / step).coerceIn(0, count - 1)
            onPick?.invoke(k)
        }
        return true
    }
}

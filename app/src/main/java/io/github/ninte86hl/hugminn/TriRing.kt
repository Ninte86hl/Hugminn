package io.github.ninte86hl.hugminn

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Switch with three positions side by side: left = off, middle = on (keeps its look),
 * right = custom (gets the changes). The knob sits on the active position and shows it by its
 * centre: dark for off, the element's colour for on, the accent for custom. A tap on a position
 * picks it directly; the knob slides there.
 */
class TriRing(ctx: Context, private val accent: Int, private val line: Int, private val track: Int) : View(ctx) {
    var state = 0
        set(v) {
            if (v == field && pos >= 0f) return
            val from = if (pos < 0f) v.toFloat() else pos
            field = v
            anim?.cancel()
            if (from == v.toFloat() || !isAttachedToWindow) { pos = v.toFloat(); invalidate(); return }
            anim = ValueAnimator.ofFloat(from, v.toFloat()).apply {
                duration = 160
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    /** Colour of the element (the knob's centre when on). */
    var color = accent
        set(v) { field = v; invalidate() }

    var onPick: ((Int) -> Unit)? = null
    private var pos = -1f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    private fun slotX(i: Float): Float {
        val r = height / 2f
        return r + i * (width - 2 * r) / 2f
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val cy = h / 2f
        // the track with three small marks
        p.style = Paint.Style.FILL
        p.color = track
        val tr = h * 0.30f
        c.drawRoundRect(slotX(0f) - tr, cy - tr, slotX(2f) + tr, cy + tr, tr, tr, p)
        p.color = line
        for (i in 0..2) c.drawCircle(slotX(i.toFloat()), cy, h * 0.09f, p)
        // the knob: grey ring, coloured centre for the state it stands on
        val x = slotX(if (pos < 0f) state.toFloat() else pos)
        p.color = line
        c.drawCircle(x, cy, h / 2f - 1f * dp, p)
        p.color = 0xFF000000.toInt()
        c.drawCircle(x, cy, h * 0.30f, p)
        p.color = when (state) { 0 -> 0xFF2A2A2A.toInt(); 1 -> color; else -> accent }
        c.drawCircle(x, cy, h * 0.24f, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val k = ((e.x - slotX(0f)) / ((slotX(2f) - slotX(0f)) / 2f)).let { Math.round(it) }.coerceIn(0, 2)
            onPick?.invoke(k)
        }
        return true
    }
}

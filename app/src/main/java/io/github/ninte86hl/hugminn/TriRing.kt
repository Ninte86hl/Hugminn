package io.github.ninte86hl.hugminn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * One switch with three states as three rings inside each other: outer = off, middle = on
 * (keeps its look), inner = custom (gets the changes). Only the active one is filled, the others
 * are thin lines. A tap on a ring picks that state directly.
 */
class TriRing(ctx: Context, private val accent: Int, private val line: Int) : View(ctx) {
    var state = 0
        set(v) { field = v; invalidate() }
    var onPick: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 1.5f * dp
        // bands: outer 0.74–1, middle 0.44–0.66, inner disc up to 0.34
        fun ring(inner: Float, outer: Float, on: Boolean) {
            p.style = Paint.Style.STROKE
            if (on) {
                p.color = accent
                p.strokeWidth = (outer - inner) * r
                c.drawCircle(cx, cy, (inner + outer) / 2f * r, p)
            } else {
                p.color = line
                p.strokeWidth = 1.2f * dp
                c.drawCircle(cx, cy, (inner + outer) / 2f * r, p)
            }
        }
        ring(0.76f, 1f, state == 0)
        ring(0.46f, 0.66f, state == 1)
        if (state == 2) {
            p.style = Paint.Style.FILL
            p.color = accent
            c.drawCircle(cx, cy, 0.34f * r, p)
        } else {
            p.style = Paint.Style.STROKE
            p.color = line
            p.strokeWidth = 1.2f * dp
            c.drawCircle(cx, cy, 0.22f * r, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val r = min(width, height) / 2f
            val dist = hypot(e.x - width / 2f, e.y - height / 2f) / r
            onPick?.invoke(if (dist > 0.71f) 0 else if (dist > 0.40f) 1 else 2)
        }
        return true
    }
}

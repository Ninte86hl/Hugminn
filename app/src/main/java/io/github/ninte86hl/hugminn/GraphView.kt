package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/** Small rolling line chart (last [capacity] values between 0 and [max]). */
class GraphView(ctx: Context, private val color: Int, private val max: Float = 100f, private val min: Float = 0f) : View(ctx) {
    private val capacity = 60
    private val values = ArrayDeque<Float>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    /** Colours from the bottom (0) to the top ([max]) of the graph, or null for the single [color]. */
    private var stops: IntArray? = null
    /** 0 smooth line (modern, cocoon), 1 steps (pixel), 2 dots (dot matrix). */
    var mode = 0
        set(v) { field = v; invalidate() }

    /** Takes the look of the chosen style. */
    fun styled(pal: Palette, pixel: Boolean) = apply { mode = if (pal.dots) 2 else if (pixel) 1 else 0 }

    /** Colours the line by height, like the gauges (e.g. green low, red high). */
    fun setColors(c: IntArray?) {
        stops = c?.takeIf { it.size >= 2 }
        invalidate()
    }

    fun push(v: Float) {
        if (values.isEmpty()) repeat(capacity - 1) { values.addLast(v.coerceIn(min, max)) }
        values.addLast(v.coerceIn(min, max))
        while (values.size > capacity) values.removeFirst()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (values.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / (capacity - 1)
        val start = w - step * (values.size - 1)
        path.reset()
        var lastY = 0f
        values.forEachIndexed { i, v ->
            val x = start + i * step
            val y = h - (v - min) / (max - min) * (h - line.strokeWidth) - line.strokeWidth / 2
            if (i == 0) path.moveTo(x, y)
            else if (mode == 1) { path.lineTo(x, lastY); path.lineTo(x, y) } // pixel: steps
            else path.lineTo(x, y)
            lastY = y
        }
        val area = Path(path).apply {
            lineTo(w, h)
            lineTo(start, h)
            close()
        }
        val st = stops
        if (st != null) {
            // line and area take the colour of their height
            line.shader = LinearGradient(0f, h, 0f, 0f, st, null, Shader.TileMode.CLAMP)
            fill.shader = LinearGradient(0f, h, 0f, 0f, st.map { (it and 0x00FFFFFF) or 0x40000000 }.toIntArray(), null, Shader.TileMode.CLAMP)
        } else {
            line.shader = null
            fill.shader = LinearGradient(0f, 0f, 0f, h, (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        if (mode == 2) {
            // dot matrix: one dot per value, no area
            line.style = Paint.Style.FILL
            val r = line.strokeWidth * 0.9f
            values.forEachIndexed { i, v ->
                if (i % 2 != 0) return@forEachIndexed
                val x = start + i * step
                val y = h - (v - min) / (max - min) * (h - r * 2) - r
                c.drawCircle(x, y, r, line)
            }
            line.style = Paint.Style.STROKE
            return
        }
        if (mode == 1) { line.strokeJoin = Paint.Join.MITER; line.strokeCap = Paint.Cap.SQUARE }
        c.drawPath(area, fill)
        c.drawPath(path, line)
    }
}

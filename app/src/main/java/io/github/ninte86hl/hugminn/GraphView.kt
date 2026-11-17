package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/** Small rolling line chart (last [capacity] values between 0 and [max]). */
class GraphView(ctx: Context, private val color: Int, private val max: Float = 100f) : View(ctx) {
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

    fun push(v: Float) {
        values.addLast(v.coerceIn(0f, max))
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
        values.forEachIndexed { i, v ->
            val x = start + i * step
            val y = h - v / max * (h - line.strokeWidth) - line.strokeWidth / 2
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path(path).apply {
            lineTo(w, h)
            lineTo(start, h)
            close()
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h, (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        c.drawPath(path, line)
    }
}

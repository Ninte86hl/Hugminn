package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Round gauge like on the AYN Thor: arc for the load, value and unit in the middle, label on top. */
class RingView(ctx: Context, private val pal: Palette, private val label: String, private val color: Int, private val pixel: Boolean = false) : View(ctx) {
    private var fraction = 0f
    private var value = "–"
    private var unit = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        this.color = pal.surfaceHighest
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        this.color = pal.onSurface
    }

    fun setColor(c: Int) {
        arc.color = c
    }

    fun set(fraction: Float, value: String, unit: String) {
        this.fraction = fraction.coerceIn(0f, 1f)
        this.value = value
        this.unit = unit
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val stroke = size * 0.07f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - stroke
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        if (pixel) {
            // 18 square segments instead of a smooth arc
            track.strokeCap = Paint.Cap.BUTT
            arc.strokeCap = Paint.Cap.BUTT
            val n = 18
            val seg = 270f / n
            val lit = Math.round(fraction * n)
            for (i in 0 until n) c.drawArc(box, 135f + i * seg, seg * 0.62f, false, if (i < lit) arc else track)
        } else {
            c.drawArc(box, 135f, 270f, false, track)
            if (fraction > 0f) c.drawArc(box, 135f, 270f * fraction, false, arc)
        }
        val regular = Look.font(context, pixel, false)
        val bold = Look.font(context, pixel, true)
        text.typeface = regular
        text.color = pal.onSurfaceVariant
        text.textSize = size * 0.12f
        c.drawText(label, cx, cy - size * 0.17f, text)
        text.typeface = bold
        text.color = pal.onSurface
        text.textSize = size * 0.21f
        c.drawText(value, cx, cy + size * 0.08f, text)
        text.typeface = regular
        text.color = pal.onSurfaceVariant
        text.textSize = size * 0.11f
        c.drawText(unit, cx, cy + size * 0.25f, text)
    }
}

/** Thick slider: rounded track, filled part with an icon, label and percent inside. Touch drags it. */
class BigSlider(
    ctx: Context,
    private val pal: Palette,
    private val icon: IconView.Kind,
    private val label: String,
    private val max: Int,
    initial: Int,
    private val pixel: Boolean = false,
    /** Vertical: fills from the bottom (for tall tiles). */
    private val vertical: Boolean = false,
    private val onChange: (Int) -> Unit,
) : View(ctx) {
    var value = initial.coerceIn(0, max)
        private set
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surfaceHighest }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.primary }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val iconView = IconView(ctx, icon, 22)

    /** Changes the value by [percent] of the full range (controller: 5 % per press). */
    fun step(percent: Int) {
        // Snap to the 5 % grid, then one step further (also when the range has few steps, e.g. volume 0-15).
        val dir = Integer.signum(percent)
        if (dir == 0 || max <= 0) return
        val cur = value * 100f / max
        val target = (Math.round(cur / 5f) * 5 + 5 * dir).coerceIn(0, 100)
        var v = Math.round(target * max / 100f)
        if (v == value) v += dir
        setValue(v)
    }

    fun setValue(v: Int) {
        val n = v.coerceIn(0, max)
        if (n == value) return
        value = n
        onChange(n)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val f = if (vertical) 1f - e.y / height else e.x / width
            setValue(Math.round(f.coerceIn(0f, 1f) * max))
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (vertical) {
            drawVertical(c)
            return
        }
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        val fw = (h + (w - h) * value / max.coerceAtLeast(1).toFloat())
        if (pixel) {
            Look.shape(context, true, pal.surfaceHighest, 0f).apply { setBounds(0, 0, w.toInt(), h.toInt()) }.draw(c)
            // filled part as square blocks
            val n = 20
            val gap = h * 0.08f
            val bw = (w - h * 0.2f) / n
            val lit = Math.round(value.toFloat() / max.coerceAtLeast(1) * n)
            for (i in 0 until lit) {
                val x = h * 0.1f + i * bw
                c.drawRect(x + gap / 2, h * 0.14f, x + bw - gap / 2, h * 0.86f, fill)
            }
        } else {
            c.drawRoundRect(0f, 0f, w, h, r, r, track)
            c.drawRoundRect(0f, 0f, fw, h, r, r, fill)
        }
        // icon inside the filled part
        if (pixel) {
            drawLabel(c, w, h, fw)
            return
        }
        val isz = h * 0.5f
        c.save()
        c.translate(h * 0.25f + (h - isz) / 4f, (h - isz) / 2f)
        val scale = isz / context.dp(22)
        c.scale(scale, scale)
        iconView.setColor(pal.onPrimary)
        iconView.layout(0, 0, context.dp(22), context.dp(22))
        iconView.draw(c)
        c.restore()
        drawLabel(c, w, h, fw)
    }

    private fun drawVertical(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) * 0.28f
        val f = value / max.coerceAtLeast(1).toFloat()
        if (pixel) {
            Look.shape(context, true, pal.surfaceHighest, 0f).apply { setBounds(0, 0, w.toInt(), h.toInt()) }.draw(c)
            val n = 14
            val gap = w * 0.06f
            val bh = (h - w * 0.2f) / n
            val lit = Math.round(f * n)
            for (i in 0 until lit) {
                val y = h - w * 0.1f - (i + 1) * bh
                c.drawRect(w * 0.14f, y + gap / 2, w * 0.86f, y + bh - gap / 2, fill)
            }
        } else {
            c.drawRoundRect(0f, 0f, w, h, r, r, track)
            val top = h - (w * 0.6f + (h - w * 0.6f) * f)
            c.save()
            c.clipRect(0f, top, w, h)
            c.drawRoundRect(0f, 0f, w, h, r, r, fill)
            c.restore()
        }
        // icon at the bottom, percent at the top
        val isz = context.dp(22)
        val on = !pixel && f > 0.12f
        iconView.setColor(if (on) pal.onPrimary else pal.onSurface)
        iconView.layout(0, 0, isz, isz)
        c.save()
        c.translate((w - isz) / 2f, h - isz - w * 0.22f)
        iconView.draw(c)
        c.restore()
        text.textSize = minOf(w * 0.24f, context.dp(16).toFloat())
        text.typeface = Look.font(context, pixel, true)
        text.textAlign = Paint.Align.CENTER
        text.color = if (!pixel && f > 0.85f) pal.onPrimary else pal.onSurface
        c.drawText("${Math.round(f * 100)} %", w / 2f, w * 0.32f + text.textSize * 0.4f, text)
    }

    private fun drawLabel(c: Canvas, w: Float, h: Float, fw: Float) {
        // label + percent on the right
        text.textSize = h * 0.36f
        text.typeface = Look.font(context, pixel, true)
        text.textAlign = Paint.Align.RIGHT
        val pct = Math.round(value * 100f / max.coerceAtLeast(1))
        val fm = text.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        text.color = if (!pixel && fw > w - h * 0.4f) pal.onPrimary else pal.onSurface
        c.drawText("$label  $pct %", w - h * 0.45f, baseline, text)
    }
}

/** Colour for a load between 0 and 1 in the chosen gauge palette. */
object GaugeColors {
    private val eval = android.animation.ArgbEvaluator()
    private fun blend(stops: List<Pair<Float, Int>>, f: Float): Int {
        val x = f.coerceIn(0f, 1f)
        for (i in 1 until stops.size) {
            val (p1, c1) = stops[i]
            val (p0, c0) = stops[i - 1]
            if (x <= p1) return eval.evaluate((x - p0) / (p1 - p0), c0, c1) as Int
        }
        return stops.last().second
    }

    fun of(pal: Palette, palette: GaugePalette, fraction: Float): Int = when (palette) {
        GaugePalette.TRAFFIC -> blend(listOf(0f to 0xFF4CAF50.toInt(), 0.5f to 0xFF8BC34A.toInt(), 0.7f to 0xFFFFC107.toInt(), 0.85f to 0xFFFF9800.toInt(), 1f to 0xFFF44336.toInt()), fraction)
        GaugePalette.COOL_WARM -> blend(listOf(0f to 0xFF42A5F5.toInt(), 0.5f to 0xFF7E57C2.toInt(), 0.8f to 0xFFEC407A.toInt(), 1f to 0xFFEF5350.toInt()), fraction)
        GaugePalette.ACCENT -> pal.primary
        GaugePalette.MONO -> pal.onSurfaceVariant
    }
}

/** Thin bar for a load between 0 and 1 (modern: rounded, pixel: blocks). */
class GaugeBar(ctx: Context, private val pal: Palette, private val pixel: Boolean) : View(ctx) {
    private var fraction = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surfaceHighest }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.primary }

    fun set(f: Float, color: Int) {
        fraction = f.coerceIn(0f, 1f)
        fill.color = color
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (pixel) {
            val n = 12
            val bw = w / n
            val lit = Math.round(fraction * n)
            for (i in 0 until n) c.drawRect(i * bw + 1.5f, 0f, (i + 1) * bw - 1.5f, h, if (i < lit) fill else track)
        } else {
            c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, track)
            if (fraction > 0f) c.drawRoundRect(0f, 0f, h + (w - h) * fraction, h, h / 2, h / 2, fill)
        }
    }
}

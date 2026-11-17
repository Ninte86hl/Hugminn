package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Round gauge like on the AYN Thor: arc for the load, value and unit in the middle, label on top. */
class RingView(ctx: Context, private val pal: Palette, private val label: String, private val color: Int) : View(ctx) {
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
        c.drawArc(box, 135f, 270f, false, track)
        if (fraction > 0f) c.drawArc(box, 135f, 270f * fraction, false, arc)
        text.typeface = Typeface.DEFAULT
        text.color = pal.onSurfaceVariant
        text.textSize = size * 0.12f
        c.drawText(label, cx, cy - size * 0.17f, text)
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = pal.onSurface
        text.textSize = size * 0.21f
        c.drawText(value, cx, cy + size * 0.08f, text)
        text.typeface = Typeface.DEFAULT
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
    private val onChange: (Int) -> Unit,
) : View(ctx) {
    var value = initial.coerceIn(0, max)
        private set
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surfaceHighest }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.primary }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val iconView = IconView(ctx, icon, 22)

    /** Changes the value by [percent] of the full range (controller: 5 % per press). */
    fun step(percent: Int) = setValue(value + Math.round(max * percent / 100f).coerceAtLeast(1) * Integer.signum(percent))

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
            setValue(Math.round((e.x / width).coerceIn(0f, 1f) * max))
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        c.drawRoundRect(0f, 0f, w, h, r, r, track)
        val fw = (h + (w - h) * value / max.coerceAtLeast(1).toFloat())
        c.drawRoundRect(0f, 0f, fw, h, r, r, fill)
        // icon inside the filled part
        val isz = h * 0.5f
        c.save()
        c.translate(h * 0.25f + (h - isz) / 4f, (h - isz) / 2f)
        val scale = isz / context.dp(22)
        c.scale(scale, scale)
        iconView.setColor(pal.onPrimary)
        iconView.layout(0, 0, context.dp(22), context.dp(22))
        iconView.draw(c)
        c.restore()
        // label + percent on the right
        text.textSize = h * 0.36f
        text.textAlign = Paint.Align.RIGHT
        val pct = value * 100 / max.coerceAtLeast(1)
        val fm = text.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        text.color = if (fw > w - h * 0.4f) pal.onPrimary else pal.onSurface
        c.drawText("$label  $pct %", w - h * 0.45f, baseline, text)
    }
}

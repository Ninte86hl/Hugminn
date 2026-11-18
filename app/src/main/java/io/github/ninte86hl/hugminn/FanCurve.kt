package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.io.File

/**
 * Hugminn's own fan curve for the firmware's "custom" fan mode (fan_mode 6): the vendor fan service
 * then stops regulating, and Hugminn sets the fan's PWM duty itself from the CPU temperature.
 * Safety: never below [MIN], full speed from [HOT] °C, and back to Smart when Hugminn stops.
 */
object FanCurve {
    const val MIN = 20
    const val HOT = 90
    private const val DUTY = "/sys/class/gpio5_pwm2/duty"
    private const val PERIOD = "/sys/class/gpio5_pwm2/period"
    /** Temperatures of the four curve points (°C). */
    val temps = listOf(40, 55, 70, 85)

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var currentPct = -1
        private set
    @Volatile var currentTemp = 0f
        private set

    /** Whether this device has the fan's PWM node (Pocket 6, AYN devices). */
    fun available(): Boolean = Root.exec("cat $DUTY 2>/dev/null")?.trim()?.toIntOrNull() != null

    fun percentFor(points: List<Int>, temp: Float): Int {
        if (temp >= HOT) return 100
        val t = temps
        val p = if (temp <= t.first()) points.first().toFloat()
        else if (temp >= t.last()) points.last().toFloat()
        else {
            val i = (0 until t.lastIndex).first { temp < t[it + 1] }
            val f = (temp - t[i]) / (t[i + 1] - t[i])
            points[i] + (points[i + 1] - points[i]) * f
        }
        return p.toInt().coerceIn(MIN, 100)
    }

    /** Starts the curve: custom mode on, then a loop that follows the temperature. */
    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        thread = Thread {
            try {
                val period = Root.exec("cat $PERIOD 2>/dev/null")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 50_000
                val first = percentFor(Prefs(app).fanCurve, cpuTemp())
                // custom mode and the first duty in one go (the mode switch alone may spin the fan up)
                Root.exec("settings put system fan_mode 6; echo ${period * first / 100} > $DUTY")
                var pct = first
                while (running) {
                    val temp = cpuTemp()
                    currentTemp = temp
                    val want = percentFor(Prefs(app).fanCurve, temp)
                    // change gently (max 5 % per step) so the fan doesn't jump audibly; faster when it gets hot
                    pct = if (want > pct) minOf(want, pct + if (temp >= 80) 20 else 5) else maxOf(want, pct - 3)
                    currentPct = pct
                    // re-assert the mode too: the firmware's own fan tile could have changed it
                    Root.exec("[ \"\$(settings get system fan_mode)\" = 6 ] || settings put system fan_mode 6; echo ${period * pct / 100} > $DUTY")
                    Thread.sleep(2_000)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Crash.note(app, t)
            }
        }.apply { isDaemon = true; name = "hugminn-fan"; start() }
    }

    /** Stops the curve; [restoreSmart] hands the fan back to the firmware (Smart). */
    fun stop(restoreSmart: Boolean) {
        if (!running) return
        running = false
        thread?.interrupt()
        thread = null
        currentPct = -1
        if (restoreSmart) Thread { Root.exec("settings put system fan_mode 4") }.start()
    }

    fun isRunning() = running

    private fun cpuTemp(): Float {
        var max = 0f
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { z ->
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: return@forEach
            if (!type.startsWith("cpu-") && !type.startsWith("cpuss-")) return@forEach
            val v = runCatching { File(z, "temp").readText().trim().toFloat() }.getOrNull() ?: return@forEach
            val c = if (v > 1000) v / 1000f else v
            if (c in 1f..150f && c > max) max = c
        }
        return max
    }
}

/** Draws the fan curve (temperature → speed) in the chosen style; the selected point is highlighted. */
class FanCurveView(ctx: Context, private val pal: Palette) : View(ctx) {
    var points: List<Int> = emptyList()
        set(v) { field = v; invalidate() }
    var selected = 0
        set(v) { field = v; invalidate() }
    var liveTemp = 0f
        set(v) { field = v; invalidate() }

    private val style = Prefs(ctx).uiStyle
    private val dots = style == UiStyle.DOTS
    private val pixel = style == UiStyle.PIXEL
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3).toFloat()
        color = pal.primary
        if (dots) { strokeCap = Paint.Cap.ROUND; pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, ctx.dp(7).toFloat()), 0f); strokeWidth = ctx.dp(4).toFloat() }
        if (pixel) isAntiAlias = false
    }
    private val grid = Paint().apply { color = pal.surfaceHighest; strokeWidth = ctx.dp(1).toFloat() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.onSurfaceVariant
        textSize = ctx.resources.displayMetrics.scaledDensity * 11f
        typeface = Look.font(ctx, pixel, false)
        textAlign = Paint.Align.CENTER
    }

    private val tMin = 30f
    private val tMax = 95f

    override fun onDraw(c: Canvas) {
        if (points.size != FanCurve.temps.size) return
        val pad = context.dp(28).toFloat()
        val w = width - pad * 2
        val h = height - pad * 2
        fun x(t: Float) = pad + (t - tMin) / (tMax - tMin) * w
        fun y(p: Float) = pad + h - p / 100f * h
        // grid: 0/50/100 % and the temperatures
        for (p in listOf(0, 50, 100)) {
            c.drawLine(pad, y(p.toFloat()), pad + w, y(p.toFloat()), grid)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("$p %", pad - context.dp(4), y(p.toFloat()) + text.textSize / 3, text)
        }
        text.textAlign = Paint.Align.CENTER
        for (t in FanCurve.temps + FanCurve.HOT) c.drawText("$t°", x(t.toFloat()), pad + h + text.textSize * 1.5f, text)
        // the curve (as the fan follows it)
        val path = Path()
        var first = true
        var t = tMin
        while (t <= tMax) {
            val px = x(t)
            val py = y(FanCurve.percentFor(points, t).toFloat())
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            t += 0.5f
        }
        c.drawPath(path, line)
        // the four points
        FanCurve.temps.forEachIndexed { i, temp ->
            val px = x(temp.toFloat())
            val py = y(points[i].toFloat().coerceAtLeast(FanCurve.MIN.toFloat()))
            val r = context.dp(if (i == selected) 9 else 6).toFloat()
            dot.color = if (i == selected) pal.onSurface else pal.primary
            if (pixel) c.drawRect(px - r, py - r, px + r, py + r, dot) else c.drawCircle(px, py, r, dot)
            if (i == selected) c.drawText("${points[i]} %", px, py - r - context.dp(6), text.apply { color = pal.onSurface })
            text.color = pal.onSurfaceVariant
        }
        // where the temperature is right now
        if (liveTemp > 0) {
            val px = x(liveTemp.coerceIn(tMin, tMax))
            dot.color = pal.onSurfaceVariant
            c.drawLine(px, pad, px, pad + h, dot.apply { strokeWidth = context.dp(1).toFloat() })
            c.drawText(t("jetzt ${liveTemp.toInt()}°", "now ${liveTemp.toInt()}°"), px, pad - context.dp(8), text)
        }
    }
}

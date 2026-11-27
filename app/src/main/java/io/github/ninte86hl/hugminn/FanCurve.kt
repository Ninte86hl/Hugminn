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
    /** Range of the temperature limit (from there the fan runs at full speed). */
    const val HOT_MIN = 70
    const val HOT_MAX = 95
    /** The temperature limit chosen in the curve editor. */
    @Volatile var hot = 90
    private const val DUTY = "/sys/class/gpio5_pwm2/duty"
    private const val PERIOD = "/sys/class/gpio5_pwm2/period"
    /** Temperatures of the ten curve points (°C): every 5 degrees. */
    val temps = listOf(40, 45, 50, 55, 60, 65, 70, 75, 80, 85)
    /**
     * Hugminn's own default: quiet while the handheld is cool, rising evenly from 50 °C where games
     * start to heat it up, full speed from 90 °C.
     */
    val DEFAULT = listOf(20, 20, 25, 30, 40, 50, 60, 75, 90, 100)
    const val DEFAULT_HOT = 90

    /** The points of earlier versions (for taking over an old curve): four, then eight. */
    private val oldTemps = mapOf(4 to listOf(40, 55, 70, 85), 8 to listOf(35, 42, 49, 56, 63, 70, 77, 84))

    /** An old curve as ten points (same shape); null if its size is unknown. */
    fun widen(old: List<Int>): List<Int>? {
        val f = oldTemps[old.size] ?: return null
        return temps.map { t ->
            when {
                t <= f.first() -> old.first()
                t >= f.last() -> old.last()
                else -> { val i = (0 until f.lastIndex).first { t < f[it + 1] }; (old[i] + (old[i + 1] - old[i]) * (t - f[i]) / (f[i + 1] - f[i])) }
            }.let { (Math.round(it / 5f) * 5).coerceIn(MIN, 100) }
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var currentPct = -1
        private set
    @Volatile var currentTemp = 0f
        private set

    @Volatile private var periodCache = 0

    /** How fast the fan turns right now, in percent of full speed (whoever drives it), or null. */
    fun speedPct(): Int? {
        if (running && currentPct >= 0) return currentPct
        if (periodCache <= 0) periodCache = Root.exec("cat $PERIOD 2>/dev/null")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val duty = Root.exec("cat $DUTY 2>/dev/null")?.trim()?.toIntOrNull() ?: return null
        return (duty * 100 / periodCache).coerceIn(0, 100)
    }

    /** Whether this device has the fan's PWM node (Pocket 6, AYN devices). */
    fun available(): Boolean = Root.exec("cat $DUTY 2>/dev/null")?.trim()?.toIntOrNull() != null

    fun percentFor(points: List<Int>, temp: Float, limit: Int = hot): Int {
        if (temp >= limit) return 100
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
                hot = Prefs(app).fanHot
                val first = percentFor(Prefs(app).fanCurve, cpuTemp())
                // custom mode and the first duty in one go (the mode switch alone may spin the fan up)
                Root.exec("settings put system fan_mode 6; echo ${period * first / 100} > $DUTY")
                var pct = first
                while (running) {
                    val temp = cpuTemp()
                    currentTemp = temp
                    // the curve test holds the fan where it wants it meanwhile
                    if (forced != null) { Thread.sleep(500); continue }
                    val want = percentFor(Prefs(app).fanCurve, temp, Prefs(app).fanHot)
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

    /** Set while the curve test runs: the loop leaves the fan alone. */
    @Volatile private var forced: Int? = null
    @Volatile var testing = false
        private set

    /**
     * Curve test: the fan runs each point's speed for a moment, one after the other, so you hear the
     * curve. [step] is told which point (main thread not guaranteed); the fan then goes back to what it did.
     */
    fun test(points: List<Int>, step: (Int) -> Unit, done: () -> Unit) {
        if (testing) return
        testing = true
        Thread {
            try {
                val period = Root.exec("cat $PERIOD 2>/dev/null")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 50_000
                val mode = Root.exec("settings get system fan_mode")?.trim()
                points.forEachIndexed { i, p ->
                    if (!testing) return@forEachIndexed
                    forced = p
                    step(i)
                    Root.exec("settings put system fan_mode 6; echo ${period * p.coerceIn(MIN, 100) / 100} > $DUTY")
                    Thread.sleep(1_800)
                }
                forced = null
                // back to what ran before: our curve (its loop takes over again) or the firmware's mode
                if (!running && mode != null && mode != "6" && mode != "null") Root.exec("settings put system fan_mode $mode")
            } catch (_: Throwable) {
            } finally {
                forced = null
                testing = false
                done()
            }
        }.apply { isDaemon = true }.start()
    }

    fun stopTest() { testing = false }

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
    /** Full speed from here (°C); selected when [selected] is past the last point. */
    var limit = FanCurve.hot
        set(v) { field = v; invalidate() }
    /** Touch: a point was dragged to a new speed (index, percent) or the limit moved (index = points.size, °C). */
    var onTouchChange: ((Int, Int) -> Unit)? = null

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

    private val tMin = 35f
    private val tMax = 95f

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (points.size != FanCurve.temps.size) return false
        val pad = context.dp(28).toFloat()
        // room on the left for "100 %", on the right for the limit's label
        val padL = context.dp(52).toFloat()
        val padR = context.dp(40).toFloat()
        val w = width - padL - padR
        val h = height - pad * 2
        fun x(t: Float) = padL + (t - tMin) / (tMax - tMin) * w
        if (e.action == android.view.MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            // nearest point, or the limit line
            val xs = FanCurve.temps.map { x(it.toFloat()) } + x(limit.toFloat())
            selected = xs.indices.minByOrNull { Math.abs(xs[it] - e.x) } ?: 0
        }
        if (e.action == android.view.MotionEvent.ACTION_DOWN || e.action == android.view.MotionEvent.ACTION_MOVE) {
            if (selected >= points.size) {
                val t = tMin + (e.x - padL) / w * (tMax - tMin)
                onTouchChange?.invoke(selected, (Math.round(t / 5f) * 5).coerceIn(FanCurve.HOT_MIN, FanCurve.HOT_MAX))
            } else {
                val p = ((pad + h - e.y) / h * 100f).coerceIn(0f, 100f)
                onTouchChange?.invoke(selected, (Math.round(p / 5f) * 5).coerceIn(FanCurve.MIN, 100))
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (points.size != FanCurve.temps.size) return
        val pad = context.dp(28).toFloat()
        // room on the left for "100 %", on the right for the limit's label
        val padL = context.dp(52).toFloat()
        val padR = context.dp(40).toFloat()
        val w = width - padL - padR
        val h = height - pad * 2
        fun x(t: Float) = padL + (t - tMin) / (tMax - tMin) * w
        fun y(p: Float) = pad + h - p / 100f * h
        // grid: 0/50/100 % and the temperatures
        for (p in listOf(0, 50, 100)) {
            c.drawLine(padL, y(p.toFloat()), padL + w, y(p.toFloat()), grid)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("$p %", padL - context.dp(6), y(p.toFloat()) + text.textSize / 3, text)
        }
        text.textAlign = Paint.Align.CENTER
        for (t in FanCurve.temps.filterIndexed { i, _ -> i % 2 == 0 }) c.drawText("$t°", x(t.toFloat()), pad + h + text.textSize * 1.5f, text)
        // the limit: full speed from here
        run {
            val lx = x(limit.toFloat())
            val sel = selected >= points.size
            dot.color = if (sel) pal.onSurface else pal.primary
            dot.strokeWidth = context.dp(if (sel) 3 else 2).toFloat()
            c.drawLine(lx, pad, lx, pad + h, dot)
            text.color = if (sel) pal.onSurface else pal.onSurfaceVariant
            text.textAlign = Paint.Align.RIGHT
            c.drawText(t("max ab $limit°", "max from $limit°"), minOf(lx + text.measureText("max ab 95°") / 2, width.toFloat() - 2), pad + h + text.textSize * 1.5f, text)
            text.textAlign = Paint.Align.CENTER
            text.color = pal.onSurfaceVariant
        }
        // the curve (as the fan follows it)
        val path = Path()
        var first = true
        var t = tMin
        while (t <= tMax) {
            val px = x(t)
            val py = y(FanCurve.percentFor(points, t, limit).toFloat())
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            t += 0.5f
        }
        c.drawPath(path, line)
        // the points (beyond the limit they no longer count)
        FanCurve.temps.forEachIndexed { i, temp ->
            if (temp >= limit) return@forEachIndexed
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

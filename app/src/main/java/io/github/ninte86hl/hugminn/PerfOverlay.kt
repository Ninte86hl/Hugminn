package io.github.ninte86hl.hugminn

import android.graphics.PixelFormat
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Values the performance overlay can show (like Steam's performance overlay, but chosen freely). */
enum class OverlayItem(val id: String, private val labelDe: String) {
    FPS("fps", "Bildrate (FPS)"),
    FRAMETIME("frametime", "Frametime-Graph"),
    CPU("cpu", "CPU"),
    GPU("gpu", "GPU"),
    RAM("ram", "RAM"),
    TEMP("temp", "Temperatur"),
    POWER("power", "Leistungsaufnahme"),
    BATTERY("battery", "Akku"),
    CLOCK("clock", "Uhrzeit");

    val label get() = tr(labelDe)


    companion object {
        val defaults = listOf("fps", "frametime", "cpu", "gpu", "temp", "battery")
        fun of(id: String) = values().firstOrNull { it.id == id }
    }
}

enum class OverlayPos(private val labelDe: String, val gravity: Int) {
    TOP_LEFT("Oben links", Gravity.TOP or Gravity.START),
    TOP_CENTER("Oben Mitte", Gravity.TOP or Gravity.CENTER_HORIZONTAL),
    TOP_RIGHT("Oben rechts", Gravity.TOP or Gravity.END),
    BOTTOM_LEFT("Unten links", Gravity.BOTTOM or Gravity.START),
    BOTTOM_RIGHT("Unten rechts", Gravity.BOTTOM or Gravity.END);

    val label get() = tr(labelDe)
}

/**
 * Small always-on-top display over the game (does not take touches or input): frame rate, frame time
 * graph and the chosen system values, updated every second.
 */
class PerfOverlay(private val svc: HugminnService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var metrics: Metrics? = null
    private val updates = mutableListOf<(Metrics.Sample) -> Unit>()

    val isShown get() = view != null

    @Volatile private var busy = false

    private val tick = object : Runnable {
        override fun run() {
            val m = metrics ?: return
            if (!busy) {
                busy = true
                Thread {
                    val s = try { m.sample() } catch (t: Throwable) { null }
                    busy = false
                    if (s != null) svc.ui.post { if (view != null) updates.forEach { it(s) } }
                }.start()
            }
            svc.ui.postDelayed(this, 600)
        }
    }

    fun show() {
        hide()
        val p = svc.prefs
        val pixel = p.uiStyle == UiStyle.PIXEL
        val row = p.overlayRow
        val k = p.overlayScale
        val d = svc.dp(1)
        // all sizes follow the chosen overlay size
        fun sz(v: Int) = (v * d * k).toInt()
        updates.clear()
        // colours, font and frame follow the chosen style and light/dark, like the Control Center
        val pal = Palette(svc)
        val style = p.uiStyle
        val dots = style == UiStyle.DOTS
        val cocoon = style == UiStyle.COCOON
        val bg = (pal.surfaceHigh and 0x00FFFFFF) or (if (pal.dark) 0xD8000000.toInt() else 0xE6000000.toInt())
        val text = pal.onSurface
        val accent = pal.primary
        val box = LinearLayout(svc).apply {
            orientation = if (row) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            // rounded (modern), stepped corners (pixel), ring of dots (dot matrix), softer and rounder (cocoon)
            background = when {
                dots -> Look.shape(svc, false, bg, 10f * d * k, (2 * d * k).toInt().coerceAtLeast(2), pal.outline)
                cocoon -> Look.shape(svc, false, bg, 10f * d * k, (1 * d * k).toInt().coerceAtLeast(1), (pal.outline and 0x00FFFFFF) or 0x80000000.toInt())
                pixel -> Look.shape(svc, true, bg, 10f * d * k)
                else -> Look.shape(svc, false, bg, 10f * d * k)
            }
            setPadding(sz(10), sz(5), sz(10), sz(5))
        }
        fun label(color: Int = text, bold: Boolean = true) = TextView(svc).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * k)
            setTextColor(color)
            typeface = Look.font(svc, pixel, bold)
            // a soft shadow helps light text on a busy game picture; dark text needs none
            if (pal.dark) setShadowLayer(2f, 0f, 1f, 0xFF000000.toInt())
        }
        fun addItem(v: View) {
            box.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (box.childCount > 0) { if (row) marginStart = sz(12) else topMargin = sz(2) }
            })
        }
        val gp = p.gaugePalette
        for (id in p.overlayItems) {
            when (OverlayItem.of(id)) {
                OverlayItem.FPS -> label(accent).also { t -> addItem(t); updates += { s -> t.text = if (s.fps >= 0) "${s.fps} FPS" else "– FPS" } }
                OverlayItem.FRAMETIME -> {
                    val g = GraphView(svc, accent, 50f).apply { setColors(GaugeColors.graph(pal, gp, 50f) { GaugeColors.frameTime(it) }) }
                    box.addView(g, LinearLayout.LayoutParams(sz(90), sz(22)).apply { if (box.childCount > 0) { if (row) marginStart = sz(12) else topMargin = sz(2) } })
                    updates += { s -> s.frameTimesMs.takeLast(20).forEach { g.push(it) } }
                }
                OverlayItem.CPU -> label().also { t -> addItem(t); updates += { s -> t.text = "CPU ${s.cpuPct.toInt()} %"; t.setTextColor(GaugeColors.of(pal, gp, s.cpuPct / 100f)) } }
                OverlayItem.GPU -> label().also { t -> addItem(t); updates += { s -> t.text = "GPU ${s.gpuPct.toInt()} %"; t.setTextColor(GaugeColors.of(pal, gp, s.gpuPct / 100f)) } }
                OverlayItem.RAM -> label().also { t -> addItem(t); updates += { s -> t.text = "RAM %.1f GB".format(s.ramUsedMb / 1024f) } }
                OverlayItem.TEMP -> label().also { t -> addItem(t); updates += { s -> t.text = "${s.cpuTemp.toInt()} °C"; t.setTextColor(GaugeColors.of(pal, gp, (s.cpuTemp - 40f) / 45f)) } }
                OverlayItem.POWER -> label().also { t -> addItem(t); updates += { s -> t.text = "%.1f W".format(s.watts) } }
                OverlayItem.BATTERY -> label().also { t -> addItem(t); updates += { s -> t.text = "${s.batPct} %" + if (s.charging) " ⚡" else "" } }
                OverlayItem.CLOCK -> addItem(android.widget.TextClock(svc).apply {
                    format24Hour = "HH:mm"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * k)
                    setTextColor(text)
                    typeface = Look.font(svc, pixel, true)
                    if (pal.dark) setShadowLayer(2f, 0f, 1f, 0xFF000000.toInt())
                })
                null -> {}
            }
        }
        if (box.childCount == 0) return
        // while developing: a small ⓘ that sends a report (screenshot + measurements) right away
        if (!p.githubToken.isNullOrBlank()) {
            val info = object : View(svc) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: android.graphics.Canvas) {
                    val r = width / 2f
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = 0xCCFFFFFF.toInt()
                    c.drawCircle(r, r, r, paint)
                    paint.color = 0xFF000000.toInt()
                    c.drawCircle(r, r * 0.55f, r * 0.13f, paint)
                    c.drawRoundRect(r - r * 0.12f, r * 0.82f, r + r * 0.12f, r * 1.55f, r * 0.08f, r * 0.08f, paint)
                }
            }.apply {
                setOnClickListener {
                    android.widget.Toast.makeText(svc, "Bericht wird gesendet …", android.widget.Toast.LENGTH_SHORT).show()
                    Feedback.capture(svc, "overlay", quick = true)
                }
            }
            box.addView(info, LinearLayout.LayoutParams(sz(20), sz(20)).apply { if (row) marginStart = sz(12) else topMargin = sz(4) })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // touchable only while the ⓘ is shown (touches outside the small box still reach the game)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or (if (p.githubToken.isNullOrBlank()) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = p.overlayPos.gravity
            x = 12 * d
            y = 8 * d
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(box, lp)
        view = box
        metrics = Metrics(svc) { svc.foregroundPkg }
        svc.ui.post(tick)
    }

    /** Redraws the overlay when the look changes (style, light/dark, tile colour, gauge colours). */
    private val lookKeys = setOf("uiStyle", "theme", "tileColor", "gaugePalette")
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in lookKeys && view != null) svc.ui.post { if (view != null) show() }
    }

    init {
        svc.getSharedPreferences("hugminn", android.content.Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
    }

    /** The system switched between light and dark: follow it when the theme is "system". */
    fun onNightModeChanged() {
        if (view != null && svc.prefs.theme == Theme.SYSTEM) show()
    }

    fun hide() {
        svc.ui.removeCallbacks(tick)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        metrics = null
    }

    /** Applies changed settings. */
    fun refresh() = if (svc.prefs.overlayOn) show() else hide()
}

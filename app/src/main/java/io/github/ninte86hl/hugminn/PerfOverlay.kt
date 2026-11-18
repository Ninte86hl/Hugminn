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
        val box = LinearLayout(svc).apply {
            orientation = if (row) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            // in the chosen style: rounded (modern), stepped corners (pixel), ring of dots (dot matrix)
            background = if (p.uiStyle == UiStyle.DOTS) Look.shape(svc, false, 0xD0000000.toInt(), 10f * d * k, (2 * d * k).toInt().coerceAtLeast(2), 0xFF5A5A5A.toInt())
                else Look.shape(svc, pixel, 0xB0000000.toInt(), 10f * d * k)
            setPadding(sz(10), sz(5), sz(10), sz(5))
        }
        fun label(color: Int = 0xFFFFFFFF.toInt(), bold: Boolean = true) = TextView(svc).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * k)
            setTextColor(color)
            typeface = Look.font(svc, pixel, bold)
            setShadowLayer(2f, 0f, 1f, 0xFF000000.toInt())
        }
        fun addItem(v: View) {
            box.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (box.childCount > 0) { if (row) marginStart = sz(12) else topMargin = sz(2) }
            })
        }
        val pal = Palette(svc)
        val gp = p.gaugePalette.takeIf { it != GaugePalette.ACCENT && it != GaugePalette.MONO } ?: GaugePalette.TRAFFIC
        for (id in p.overlayItems) {
            when (OverlayItem.of(id)) {
                OverlayItem.FPS -> label(if (p.uiStyle == UiStyle.DOTS) pal.primary else 0xFF7CFC9A.toInt()).also { t -> addItem(t); updates += { s -> t.text = if (s.fps >= 0) "${s.fps} FPS" else "– FPS" } }
                OverlayItem.FRAMETIME -> {
                    val g = GraphView(svc, 0xFF7CFC9A.toInt(), 50f)
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
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = Look.font(svc, pixel, true)
                })
                null -> {}
            }
        }
        if (box.childCount == 0) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // never touchable: every touch reaches the game
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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

    fun hide() {
        svc.ui.removeCallbacks(tick)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        metrics = null
    }

    /** Applies changed settings. */
    fun refresh() = if (svc.prefs.overlayOn) show() else hide()
}

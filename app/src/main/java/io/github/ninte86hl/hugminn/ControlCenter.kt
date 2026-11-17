package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import android.content.res.ColorStateList
import kotlin.math.abs

/**
 * Huginn, the Control Center: a full window over the running game with quick toggles, brightness and
 * volume, and live CPU/GPU/RAM/battery graphs. Fully usable with the D-pad (A = choose, B = close).
 */
class ControlCenter(private val svc: HugminnService) {
    private class Item(val view: View, val onA: (() -> Unit)?, val onAdjust: ((Int) -> Unit)? = null)

    var isOpen = false
        private set
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val audio = svc.getSystemService(AudioManager::class.java)
    private lateinit var pal: Palette
    private var root: FrameLayout? = null
    private val items = mutableListOf<Item>()
    private var focus = 0
    private var metrics: Metrics? = null
    private val refreshers = mutableListOf<() -> Unit>()
    private val graphUpdates = mutableListOf<(Metrics.Sample) -> Unit>()

    private val tick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            val m = metrics ?: return
            Thread {
                val s = m.sample()
                svc.ui.post { if (isOpen) graphUpdates.forEach { it(s) } }
            }.start()
            svc.ui.postDelayed(this, 1000)
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        pal = Palette(svc)
        items.clear()
        refreshers.clear()
        graphUpdates.clear()
        focus = 0
        metrics = Metrics(svc)
        val bounds = wm.currentWindowMetrics.bounds
        val r = FrameLayout(svc).apply {
            setBackgroundColor(pal.scrim)
            setOnClickListener { close() }
        }
        val panel = build()
        val pw = (bounds.width() * 0.94f).toInt()
        val ph = (bounds.height() * 0.90f).toInt()
        r.addView(panel, FrameLayout.LayoutParams(pw, ph, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(r, lp)
        root = r
        showFocus()
        svc.ui.post(tick)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        svc.ui.removeCallbacks(tick)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    // ---------- layout ----------

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun build(): View {
        val d = svc.dp(1)
        val panel = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.surface, 28f * d)
            setPadding(20 * d, 14 * d, 20 * d, 12 * d)
            isClickable = true // keeps taps inside from closing
        }

        // Header
        val header = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Huginn", 20f, pal.primary, bold = true))
        header.addView(text("  ·  Control Center", 16f, pal.onSurfaceVariant), lp(0, WRAP, 1f))
        val battery = text("", 14f, pal.onSurfaceVariant).apply { setPadding(0, 0, 14 * d, 0) }
        header.addView(battery)
        header.addView(TextClock(svc).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(pal.onSurface)
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(header)
        graphUpdates += { s -> battery.text = "${s.batPct} %${if (s.charging) " ⚡" else ""}" }

        // Body: controls left, graphs right
        val body = LinearLayout(svc).apply { setPadding(0, 12 * d, 0, 8 * d) }
        body.addView(controls(), lp(0, MATCH, 1f))
        body.addView(View(svc), lp(16 * d, MATCH))
        body.addView(graphs(), lp(0, MATCH, 1.1f))
        panel.addView(body, lp(MATCH, 0, 1f))

        // Footer with button hints
        val footer = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        fun hint(k: Int, s: String) {
            footer.addView(Glyphs.chip(svc, svc.prefs, pal, k))
            footer.addView(text(s, 13f, pal.onSurfaceVariant).apply { setPadding(6 * d, 0, 16 * d, 0) })
        }
        hint(KeyEvent.KEYCODE_BUTTON_A, "Auswählen")
        hint(KeyEvent.KEYCODE_BUTTON_B, "Schließen")
        panel.addView(footer)
        return panel
    }

    private fun controls(): View {
        val d = svc.dp(1)
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val rootOk = Root.available()

        data class Tile(val title: String, val state: () -> String, val on: () -> Boolean, val act: () -> Unit)
        val tiles = mutableListOf<Tile>()
        fun after() = svc.ui.postDelayed({ refreshers.forEach { it() } }, 250)

        tiles += Tile("WLAN", { if (Device.wifiOn(svc)) "An" else "Aus" }, { Device.wifiOn(svc) }) {
            if (rootOk) Device.setWifi(!Device.wifiOn(svc)) { after() } else openPanel(android.provider.Settings.Panel.ACTION_WIFI)
        }
        tiles += Tile("Bluetooth", { if (Device.btOn(svc)) "An" else "Aus" }, { Device.btOn(svc) }) {
            if (rootOk) Device.setBt(!Device.btOn(svc)) { after() } else openPanel(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        if (rootOk && Device.sys(svc, "performance_mode") != null) {
            tiles += Tile("Leistung", { if (Device.performance(svc)) "Hoch" else "Normal" }, { Device.performance(svc) }) {
                Device.setPerformance(!Device.performance(svc)) { after() }
            }
        }
        if (rootOk && Device.sys(svc, "fan_mode") != null) {
            tiles += Tile("Lüfter", { Device.fanLabel(svc) }, { Device.sys(svc, "fan_mode") == "4" }) { Device.nextFan(svc) { after() } }
        }
        val has120 = svc.display?.supportedModes?.any { it.refreshRate > 100f } == true
        if (rootOk && has120) {
            tiles += Tile("120 Hz", { if (Device.highRefresh(svc)) "An" else "Aus" }, { Device.highRefresh(svc) }) {
                Device.setHighRefresh(!Device.highRefresh(svc)) { after() }
            }
        }
        if (rootOk && Device.sys(svc, "joystick_light_enabled") != null) {
            tiles += Tile("Stick-LEDs", { if (Device.stickLeds(svc)) "An" else "Aus" }, { Device.stickLeds(svc) }) {
                Device.setStickLeds(!Device.stickLeds(svc)) { after() }
            }
        }
        tiles += Tile("Screenshot", { "Aufnehmen" }, { false }) {
            close()
            svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 300)
        }
        tiles += Tile("Hugminn", { "Einstellungen" }, { false }) {
            close()
            svc.startApp(Intent(svc, MainActivity::class.java))
        }

        val perRow = 4
        tiles.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(svc)
            chunk.forEach { t ->
                val title = text(t.title, 14f, pal.onSurface, bold = true)
                val state = text("", 12f, pal.onSurfaceVariant)
                val tile = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12 * d, 8 * d, 8 * d, 8 * d)
                    addView(title)
                    addView(state)
                    setOnClickListener { t.act() }
                }
                val refresh = {
                    val on = t.on()
                    tile.background = rounded(if (on) pal.primaryContainer else pal.surfaceHighest, 18f * d)
                    title.setTextColor(if (on) pal.onPrimaryContainer else pal.onSurface)
                    state.setTextColor(if (on) pal.onPrimaryContainer else pal.onSurfaceVariant)
                    state.text = t.state()
                }
                refresh()
                refreshers += refresh
                items += Item(tile, t.act)
                row.addView(tile, lp(0, 58 * d, 1f).apply { setMargins(0, 0, 8 * d, 8 * d) })
            }
            repeat(perRow - chunk.size) { row.addView(View(svc), lp(0, 1, 1f).apply { marginEnd = 8 * d }) }
            col.addView(row)
        }

        // Sliders
        col.addView(slider("Helligkeit", 255, Device.brightness(svc), 12) { Device.setBrightness(svc, it) })
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        col.addView(slider("Lautstärke", maxVol, audio.getStreamVolume(AudioManager.STREAM_MUSIC), 1) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        })
        return col
    }

    private fun slider(label: String, max: Int, value: Int, step: Int, set: (Int) -> Unit): View {
        val d = svc.dp(1)
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12 * d, 6 * d, 12 * d, 4 * d)
            background = rounded(pal.surfaceHigh, 18f * d)
        }
        val value0 = value.coerceIn(0, max)
        val title = text("$label  ${value0 * 100 / max.coerceAtLeast(1)} %", 13f, pal.onSurfaceVariant)
        val bar = SeekBar(svc).apply {
            this.max = max
            progress = value0
            progressTintList = ColorStateList.valueOf(pal.primary)
            thumbTintList = ColorStateList.valueOf(pal.primary)
            progressBackgroundTintList = ColorStateList.valueOf(pal.outline)
            isFocusable = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    title.text = "$label  ${p * 100 / max.coerceAtLeast(1)} %"
                    if (fromUser) set(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(title)
        box.addView(bar)
        items += Item(box, null) { dir ->
            bar.progress = (bar.progress + dir * step).coerceIn(0, max)
            set(bar.progress)
        }
        return box.also { it.layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 8 * d, 8 * d) } }
    }

    private fun graphs(): View {
        val d = svc.dp(1)
        val grid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        fun card(title: String, color: Int, max: Float, update: (Metrics.Sample) -> Triple<Float, String, String>): View {
            val value = text("–", 20f, pal.onSurface, bold = true)
            val sub = text("", 12f, pal.onSurfaceVariant)
            val graph = GraphView(svc, color, max)
            val c = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12 * d, 8 * d, 12 * d, 8 * d)
                background = rounded(pal.surfaceHigh, 18f * d)
                addView(text(title, 12f, pal.onSurfaceVariant))
                addView(value)
                addView(sub)
                addView(graph, lp(MATCH, 0, 1f).apply { topMargin = 4 * d })
            }
            graphUpdates += { s ->
                val (v, big, small) = update(s)
                graph.push(v)
                value.text = big
                sub.text = small
            }
            return c
        }
        fun temp(t: Float) = if (t > 0) " · ${t.toInt()} °C" else ""
        val rows = listOf(
            card("CPU", pal.primary, 100f) { s -> Triple(s.cpuPct, "${s.cpuPct.toInt()} %", "%.2f GHz".format(s.cpuMhz / 1000f) + temp(s.cpuTemp)) } to
                card("GPU", 0xFF4FC3F7.toInt(), 100f) { s -> Triple(s.gpuPct, "${s.gpuPct.toInt()} %", "${s.gpuMhz} MHz" + temp(s.gpuTemp)) },
            card("RAM", 0xFF81C784.toInt(), 100f) { s ->
                val pct = if (s.ramTotalMb > 0) s.ramUsedMb * 100f / s.ramTotalMb else 0f
                Triple(pct, "%.1f / %.0f GB".format(s.ramUsedMb / 1024f, s.ramTotalMb / 1024f), "${pct.toInt()} % belegt")
            } to card("Akku", 0xFFFFB74D.toInt(), 15f) { s ->
                Triple(s.watts, "%.1f W".format(s.watts), "${s.batPct} %" + (if (s.charging) " · lädt" else "") + temp(s.batTemp))
            },
        )
        rows.forEachIndexed { i, (a, b) ->
            val row = LinearLayout(svc)
            row.addView(a, lp(0, MATCH, 1f).apply { marginEnd = 8 * d })
            row.addView(b, lp(0, MATCH, 1f))
            grid.addView(row, lp(MATCH, 0, 1f).apply { if (i == 0) bottomMargin = 8 * d })
        }
        return grid
    }

    private fun openPanel(action: String) {
        close()
        svc.startApp(Intent(action))
    }

    // ---------- controller focus ----------

    private fun showFocus() {
        items.forEachIndexed { i, it ->
            it.view.foreground = if (i == focus) rounded(0, svc.dp(18).toFloat(), svc.dp(3), pal.primary) else null
        }
    }

    private fun move(dx: Int, dy: Int) {
        val cur = items.getOrNull(focus)?.view ?: return
        val a = IntArray(2).also(cur::getLocationOnScreen)
        val ax = a[0] + cur.width / 2
        val ay = a[1] + cur.height / 2
        var best = -1
        var bestScore = Float.MAX_VALUE
        items.forEachIndexed { i, it ->
            if (i == focus) return@forEachIndexed
            val b = IntArray(2).also(it.view::getLocationOnScreen)
            val vx = b[0] + it.view.width / 2 - ax
            val vy = b[1] + it.view.height / 2 - ay
            val along = vx * dx + vy * dy
            if (along <= 0) return@forEachIndexed
            val across = abs(vx * dy) + abs(vy * dx)
            val score = along + across * 2f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best >= 0) {
            focus = best
            showFocus()
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val item = items.getOrNull(focus)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> item?.onAdjust?.invoke(-1) ?: move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> item?.onAdjust?.invoke(1) ?: move(1, 0)
            KeyEvent.KEYCODE_BUTTON_L1 -> item?.onAdjust?.invoke(-3)
            KeyEvent.KEYCODE_BUTTON_R1 -> item?.onAdjust?.invoke(3)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> item?.onA?.invoke()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> close()
            else -> return true
        }
        return true
    }
}

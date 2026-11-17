package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.provider.Settings
import android.text.TextUtils
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
import kotlin.math.abs

/**
 * Huginn, the Control Center: a full window over the running game with quick tiles (most open a
 * small menu), brightness and volume, and live CPU/GPU/RAM/battery graphs.
 * Controller: D-pad moves, A chooses, left/right change sliders, B goes back/closes.
 */
class ControlCenter(private val svc: HugminnService) {
    private class Item(val view: View, val onA: (() -> Unit)?, val onAdjust: ((Int) -> Unit)? = null)
    private class MenuEntry(val label: String, val checked: Boolean = false, val hint: String? = null, val run: (() -> Unit)?)

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

    // Open sub menu (null = none)
    private var menuView: View? = null
    private var menuEntries: List<MenuEntry> = emptyList()
    private val menuRows = mutableListOf<View>()
    private var menuSel = 0

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

    private val d get() = svc.dp(1)

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
            setOnClickListener { if (menuView != null) closeMenu() else close() }
        }
        val pw = (bounds.width() * 0.94f).toInt()
        val ph = (bounds.height() * 0.90f).toInt()
        r.addView(build(), FrameLayout.LayoutParams(pw, ph, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        r.alpha = 0f
        wm.addView(r, lp)
        r.animate().alpha(1f).setDuration(120).start()
        root = r
        showFocus()
        svc.ui.post(tick)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        menuView = null
        svc.ui.removeCallbacks(tick)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    // ---------- layout helpers ----------

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun refreshAll() = svc.ui.postDelayed({ refreshers.forEach { it() } }, 300)

    private fun build(): View {
        val panel = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.surface, 28f * d)
            setPadding(22 * d, 16 * d, 22 * d, 12 * d)
            isClickable = true
        }

        // Header
        val header = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Huginn", 20f, pal.primary, bold = true))
        header.addView(text("  ·  Control Center", 15f, pal.onSurfaceVariant), lp(0, WRAP, 1f))
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

        val body = LinearLayout(svc).apply { setPadding(0, 14 * d, 0, 10 * d) }
        body.addView(controls(), lp(0, MATCH, 1f))
        body.addView(View(svc), lp(16 * d, MATCH))
        body.addView(graphs(), lp(0, MATCH, 1f))
        panel.addView(body, lp(MATCH, 0, 1f))

        val footer = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        fun hint(k: Int, s: String) {
            footer.addView(Glyphs.chip(svc, svc.prefs, pal, k, 22))
            footer.addView(text(s, 13f, pal.onSurfaceVariant).apply { setPadding(6 * d, 0, 16 * d, 0) })
        }
        if (!svc.prefs.githubToken.isNullOrBlank()) hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Feedback")
        hint(KeyEvent.KEYCODE_BUTTON_A, "Auswählen")
        hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück")
        panel.addView(footer)
        return panel
    }

    // ---------- tiles ----------

    private data class Tile(val title: String, val icon: IconView.Kind, val state: () -> String, val on: () -> Boolean, val act: () -> Unit)

    private fun controls(): View {
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val rootOk = Root.available()
        val tiles = mutableListOf<Tile>()

        tiles += Tile("WLAN", IconView.Kind.WIFI, { if (Device.wifiOn(svc)) Device.wifiName(svc) ?: "An" else "Aus" }, { Device.wifiOn(svc) }) { wifiMenu(rootOk) }
        tiles += Tile("Bluetooth", IconView.Kind.BLUETOOTH, {
            if (!Device.btOn(svc)) "Aus" else Device.pairedDevices(svc).firstOrNull { it.second }?.first ?: "An"
        }, { Device.btOn(svc) }) { btMenu(rootOk) }
        if (rootOk && Device.sys(svc, "performance_mode") != null) {
            tiles += Tile("Leistung", IconView.Kind.BOLT, { Device.perfLabel(svc) }, { Device.performance(svc) }) {
                profileMenu("Leistung", "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } }
            }
        }
        if (rootOk && Device.sys(svc, "fan_mode") != null) {
            tiles += Tile("Lüfter", IconView.Kind.FAN, { Device.fanLabel(svc) }, { true }) {
                profileMenu("Lüfter", "fan_mode", Device.fanProfiles) { v -> Device.setFan(v) { refreshAll() } }
            }
        }
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val has120 = display?.supportedModes?.any { it.refreshRate > 100f } == true
        if (rootOk && has120) {
            tiles += Tile("Bildrate", IconView.Kind.REFRESH, { if (Device.highRefresh(svc)) "120 Hz" else "60 Hz" }, { Device.highRefresh(svc) }) {
                Device.setHighRefresh(!Device.highRefresh(svc)) { refreshAll() }
            }
        }
        if (rootOk && Device.sys(svc, "joystick_light_enabled") != null) {
            tiles += Tile("Stick-LEDs", IconView.Kind.LED, { if (Device.stickLeds(svc)) "An" else "Aus" }, { Device.stickLeds(svc) }) {
                Device.setStickLeds(!Device.stickLeds(svc)) { refreshAll() }
            }
        }
        tiles += Tile("Screenshot", IconView.Kind.CAMERA, { "Aufnehmen" }, { false }) {
            close()
            svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 300)
        }
        tiles += Tile("Hugminn", IconView.Kind.GEAR, { "Einstellungen" }, { false }) {
            close()
            svc.startApp(Intent(svc, MainActivity::class.java))
        }

        val perRow = 4
        tiles.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(svc)
            chunk.forEach { t -> row.addView(tileView(t), lp(0, 72 * d, 1f).apply { setMargins(0, 0, 8 * d, 8 * d) }) }
            repeat(perRow - chunk.size) { row.addView(View(svc), lp(0, 1, 1f).apply { marginEnd = 8 * d }) }
            col.addView(row)
        }
        col.addView(View(svc), lp(MATCH, 0, 1f))
        col.addView(slider("Helligkeit", 255, Device.brightness(svc), 12) { Device.setBrightness(svc, it) })
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        col.addView(slider("Lautstärke", maxVol, audio.getStreamVolume(AudioManager.STREAM_MUSIC), 1) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        })
        return col
    }

    private fun tileView(t: Tile): View {
        val icon = IconView(svc, t.icon, 22)
        val title = text(t.title, 14f, pal.onSurface, bold = true)
        val state = text("", 12f, pal.onSurfaceVariant)
        val texts = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(state)
        }
        val tile = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12 * d, 0, 8 * d, 0)
            addView(icon)
            addView(texts, lp(0, WRAP, 1f).apply { marginStart = 10 * d })
            setOnClickListener { t.act() }
        }
        val refresh = {
            val on = t.on()
            tile.background = rounded(if (on) pal.primaryContainer else pal.surfaceHighest, 20f * d)
            val fg = if (on) pal.onPrimaryContainer else pal.onSurface
            title.setTextColor(fg)
            icon.setColor(fg)
            state.setTextColor(if (on) pal.onPrimaryContainer else pal.onSurfaceVariant)
            state.text = t.state()
        }
        refresh()
        refreshers += refresh
        items += Item(tile, t.act)
        return tile
    }

    private fun slider(label: String, max: Int, value: Int, step: Int, set: (Int) -> Unit): View {
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14 * d, 8 * d, 14 * d, 6 * d)
            background = rounded(pal.surfaceHigh, 20f * d)
        }
        val value0 = value.coerceIn(0, max)
        fun pct(p: Int) = p * 100 / max.coerceAtLeast(1)
        val head = LinearLayout(svc)
        head.addView(text(label, 13f, pal.onSurface, bold = true), lp(0, WRAP, 1f))
        val pctText = text("${pct(value0)} %", 13f, pal.onSurfaceVariant)
        head.addView(pctText)
        val bar = SeekBar(svc).apply {
            this.max = max
            progress = value0
            progressTintList = ColorStateList.valueOf(pal.primary)
            thumbTintList = ColorStateList.valueOf(pal.primary)
            progressBackgroundTintList = ColorStateList.valueOf(pal.outline)
            isFocusable = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    pctText.text = "${pct(p)} %"
                    if (fromUser) set(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(head)
        box.addView(bar)
        items += Item(box, null) { dir ->
            bar.progress = (bar.progress + dir * step).coerceIn(0, max)
            set(bar.progress)
        }
        return box.also { it.layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 8 * d, 8 * d) } }
    }

    // ---------- graphs ----------

    private fun graphs(): View {
        val grid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        fun card(title: String, color: Int, max: Float, update: (Metrics.Sample) -> Triple<Float, String, String>): View {
            val value = text("–", 22f, pal.onSurface, bold = true)
            val sub = text("", 12f, pal.onSurfaceVariant)
            val graph = GraphView(svc, color, max)
            val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(View(svc).apply { background = rounded(color, 4f * d) }, lp(8 * d, 8 * d).apply { marginEnd = 6 * d })
            head.addView(text(title, 12f, pal.onSurfaceVariant, bold = true))
            val c = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14 * d, 10 * d, 14 * d, 10 * d)
                background = rounded(pal.surfaceHigh, 20f * d)
                addView(head)
                addView(value)
                addView(sub)
                addView(graph, lp(MATCH, 0, 1f).apply { topMargin = 6 * d })
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
        val cpuColor = pal.primary
        val rows = listOf(
            card("CPU", cpuColor, 100f) { s -> Triple(s.cpuPct, "${s.cpuPct.toInt()} %", "%.2f GHz".format(s.cpuMhz / 1000f) + temp(s.cpuTemp)) } to
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

    // ---------- sub menus ----------

    private fun profileMenu(title: String, key: String, table: Map<String, String>, set: (String) -> Unit) {
        val cur = Device.sys(svc, key)
        val entries = table.map { (v, name) -> MenuEntry(name, checked = v == cur) { set(v) } }.toMutableList()
        if (cur != null && cur !in table) entries += MenuEntry("Profil $cur (unbekannt)", checked = true, run = null)
        showMenu(title, entries)
    }

    private fun wifiMenu(rootOk: Boolean) {
        val on = Device.wifiOn(svc)
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(if (on) "WLAN ausschalten" else "WLAN einschalten") {
            if (rootOk) Device.setWifi(!on) { refreshAll() } else openSystem(Settings.Panel.ACTION_WIFI)
        }
        if (on) {
            Device.wifiName(svc)?.let { entries += MenuEntry(it, checked = true, hint = "Verbunden", run = null) }
            entries += MenuEntry("Netzwerke & neue Verbindung …") { openSystem(Settings.Panel.ACTION_WIFI) }
        }
        showMenu("WLAN", entries)
    }

    private fun btMenu(rootOk: Boolean) {
        val on = Device.btOn(svc)
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(if (on) "Bluetooth ausschalten" else "Bluetooth einschalten") {
            if (rootOk) Device.setBt(!on) { refreshAll() } else openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        if (on) {
            Device.pairedDevices(svc).forEach { (name, connected) ->
                entries += MenuEntry(name, checked = connected, hint = if (connected) "Verbunden" else "Gekoppelt") {
                    openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)
                }
            }
            entries += MenuEntry("Neues Gerät koppeln …") { openSystem("android.settings.BLUETOOTH_PAIRING_SETTINGS") }
        }
        showMenu("Bluetooth", entries)
    }

    private fun openSystem(action: String) {
        close()
        svc.startApp(Intent(action))
    }

    private fun showMenu(title: String, entries: List<MenuEntry>) {
        val r = root ?: return
        closeMenu()
        menuEntries = entries
        menuRows.clear()
        menuSel = entries.indexOfFirst { it.run != null }.coerceAtLeast(0)
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(10 * d, 16 * d, 10 * d, 12 * d)
            elevation = 12f * d
            isClickable = true
        }
        box.addView(text(title, 18f, pal.onSurface, bold = true).apply { setPadding(14 * d, 0, 14 * d, 10 * d) })
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        entries.forEachIndexed { i, e ->
            val row = LinearLayout(svc).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14 * d, 0, 14 * d, 0)
                minimumHeight = 46 * d
                setOnClickListener { runMenu(i) }
            }
            row.addView(text(if (e.checked) "●" else "○", 14f, if (e.checked) pal.primary else pal.outline).apply {
                setPadding(0, 0, 12 * d, 0)
                visibility = if (e.run == null && !e.checked) View.INVISIBLE else View.VISIBLE
            })
            val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(e.label, 15f, if (e.run == null) pal.onSurfaceVariant else pal.onSurface, bold = e.checked))
            e.hint?.let { col.addView(text(it, 12f, pal.onSurfaceVariant)) }
            row.addView(col, lp(0, WRAP, 1f))
            list.addView(row, lp(MATCH, WRAP))
            menuRows += row
        }
        box.addView(android.widget.ScrollView(svc).apply { addView(list) }, lp(MATCH, WRAP))
        val w = (r.width * 0.42f).toInt().coerceAtLeast(320 * d)
        r.addView(box, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        menuView = box
        box.scaleX = 0.96f
        box.scaleY = 0.96f
        box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
        showMenuSel()
    }

    private fun showMenuSel() {
        menuRows.forEachIndexed { i, v ->
            v.background = if (i == menuSel) rounded(pal.secondaryContainer, 16f * d) else null
        }
    }

    private fun closeMenu() {
        menuView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        menuView = null
    }

    private fun runMenu(i: Int) {
        val e = menuEntries.getOrNull(i) ?: return
        val run = e.run ?: return
        closeMenu()
        run()
    }

    private fun moveMenu(dir: Int) {
        var i = menuSel
        repeat(menuEntries.size) {
            i = (i + dir).coerceIn(0, menuEntries.lastIndex)
            if (menuEntries[i].run != null) {
                menuSel = i
                showMenuSel()
                return
            }
        }
    }

    // ---------- controller focus ----------

    private fun showFocus() {
        items.forEachIndexed { i, it ->
            it.view.foreground = if (i == focus) rounded(0, 20f * d, 3 * d, pal.primary) else null
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
            val score = along + (abs(vx * dy) + abs(vy * dx)) * 2f
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
        if (menuView != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveMenu(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveMenu(1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> runMenu(menuSel)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> closeMenu()
            }
            return true
        }
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
        }
        return true
    }
}

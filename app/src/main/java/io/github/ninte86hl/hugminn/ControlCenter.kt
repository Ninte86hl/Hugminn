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
        // Full window over everything; the status bar is covered unless the user wants to keep it.
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val statusH = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        // The window takes input focus: the D-pad of these handhelds is a joystick hat, which Android
        // only turns into D-pad keys inside the focused window (an accessibility service never sees it).
        val r = object : FrameLayout(svc) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = try {
                onKey(event)
            } catch (t: Throwable) {
                Crash.note(svc, t)
                close()
                true
            }
        }.apply {
            setBackgroundColor(pal.surface)
            setOnClickListener { if (menuView != null) closeMenu() }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val panel = build()
        if (!svc.prefs.ccHideStatus) panel.setPadding(panel.paddingLeft, panel.paddingTop + statusH, panel.paddingRight, panel.paddingBottom)
        r.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        r.alpha = 0f
        wm.addView(r, lp)
        r.animate().alpha(1f).setDuration(120).start()
        root = r
        r.requestFocus()
        r.post { showFocus() }
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
            setBackgroundColor(pal.surface)
            setPadding(28 * d, 16 * d, 28 * d, 12 * d)
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

        val body = LinearLayout(svc).apply { setPadding(0, 12 * d, 0, 8 * d) }
        body.addView(stats(), lp(0, MATCH, 1f))
        body.addView(View(svc), lp(14 * d, MATCH))
        body.addView(controls(), lp(0, MATCH, 1.15f))
        panel.addView(body, lp(MATCH, 0, 1f))

        val footer = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        fun hint(k: Int, s: String) {
            footer.addView(Glyphs.chip(svc, svc.prefs, pal, k, 22))
            footer.addView(text(s, 13f, pal.onSurfaceVariant).apply { setPadding(6 * d, 0, 16 * d, 0) })
        }
        if (!svc.prefs.githubToken.isNullOrBlank()) hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Feedback")
        if (pageCount() > 1) hint(KeyEvent.KEYCODE_BUTTON_R1, "Seite")
        hint(KeyEvent.KEYCODE_BUTTON_A, "Auswählen")
        hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück")
        panel.addView(footer)
        return panel
    }

    private fun card() = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(pal.surfaceHigh, 22f * d)
        setPadding(16 * d, 12 * d, 16 * d, 12 * d)
    }

    // ---------- left: frame rate, temperature, fan, gauges (AYN Thor style) ----------

    private fun stats(): View {
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }

        // Frame rate
        val fps = card().apply { gravity = Gravity.CENTER }
        val modeChip = text("", 13f, pal.onPrimaryContainer, bold = true).apply {
            background = rounded(pal.primaryContainer, 14f * d)
            setPadding(14 * d, 4 * d, 14 * d, 4 * d)
        }
        val fpsRow = LinearLayout(svc).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        val fpsValue = text("–", 54f, pal.onSurface, bold = true)
        fpsRow.addView(fpsValue)
        fpsRow.addView(text(" FPS", 18f, pal.onSurfaceVariant, bold = true).apply { setPadding(0, 0, 0, 12 * d) })
        fps.addView(modeChip)
        fps.addView(fpsRow)
        fps.addView(text("Aktuelle Bildrate", 12f, pal.onSurfaceVariant))
        col.addView(fps, lp(MATCH, 0, 1.2f).apply { bottomMargin = 10 * d })
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        graphUpdates += { s ->
            fpsValue.text = if (s.fps >= 0) "${s.fps}" else "–"
            modeChip.text = "${Math.round(display?.refreshRate ?: 60f)} Hz Modus"
        }

        // Temperature and fan
        val small = LinearLayout(svc)
        fun smallCard(icon: IconView.Kind, title: String): Pair<View, Pair<TextView, TextView>> {
            val c = card()
            val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(IconView(svc, icon, 18).apply { setColor(pal.onSurfaceVariant) })
            head.addView(text(title, 12f, pal.onSurfaceVariant, bold = true).apply { setPadding(8 * d, 0, 0, 0) })
            val big = text("–", 24f, pal.onSurface, bold = true)
            val sub = text("", 11f, pal.onSurfaceVariant)
            c.addView(head)
            c.addView(big)
            c.addView(sub)
            return c to (big to sub)
        }
        val (tempCard, temp) = smallCard(IconView.Kind.THERMO, "TEMPERATUR")
        val (fanCard, fan) = smallCard(IconView.Kind.FAN, "LÜFTER")
        small.addView(tempCard, lp(0, MATCH, 1f).apply { marginEnd = 10 * d })
        small.addView(fanCard, lp(0, MATCH, 1f))
        col.addView(small, lp(MATCH, 0, 0.75f).apply { bottomMargin = 10 * d })
        graphUpdates += { s ->
            temp.first.text = if (s.cpuTemp > 0) "${s.cpuTemp.toInt()} °C" else "–"
            temp.second.text = listOfNotNull(
                s.gpuTemp.takeIf { it > 0 }?.let { "GPU ${it.toInt()} °C" },
                s.batTemp.takeIf { it > 0 }?.let { "Akku ${it.toInt()} °C" },
            ).joinToString(" · ")
            fan.first.text = Device.fanLabel(svc)
            fan.second.text = Device.fanSpeed(svc)?.let { "Drehzahl $it" } ?: ""
        }

        // Gauges
        val rings = card().apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8 * d, 8 * d, 8 * d, 8 * d)
        }
        val cpu = RingView(svc, pal, "CPU", pal.primary)
        val gpu = RingView(svc, pal, "GPU", 0xFF4FC3F7.toInt())
        val pwr = RingView(svc, pal, "LEISTUNG", 0xFFFFB74D.toInt())
        val ram = RingView(svc, pal, "RAM", 0xFF81C784.toInt())
        listOf(cpu, gpu, pwr, ram).forEach { rings.addView(it, lp(0, MATCH, 1f)) }
        col.addView(rings, lp(MATCH, 0, 1f))
        graphUpdates += { s ->
            cpu.set(s.cpuPct / 100f, "%.2f".format(s.cpuMhz / 1000f), "GHz · ${s.cpuPct.toInt()} %")
            gpu.set(s.gpuPct / 100f, "${s.gpuMhz}", "MHz · ${s.gpuPct.toInt()} %")
            pwr.set(s.watts / 15f, "%.1f".format(s.watts), if (s.charging) "W · lädt" else "W")
            val pct = if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f
            ram.set(pct, "%.1f".format(s.ramUsedMb / 1024f), "GB · ${(pct * 100).toInt()} %")
        }
        return col
    }

    // ---------- right: quick tiles (chosen in the settings) and sliders ----------

    private data class Tile(val title: String, val icon: IconView.Kind, val state: () -> String?, val on: () -> Boolean, val act: () -> Unit)

    private val tiles = mutableListOf<Tile>()
    private var page = 0
    private val perPage = 8
    private var tileGrid: LinearLayout? = null
    private var dots: LinearLayout? = null
    private val sliderItems = mutableListOf<Item>()

    private fun pageCount() = ((tiles.size + perPage - 1) / perPage).coerceAtLeast(1)

    private fun tileFor(id: String, rootOk: Boolean): Tile? = when (id) {
        "wifi" -> Tile("WLAN", IconView.Kind.WIFI, { if (Device.wifiOn(svc)) Device.wifiName(svc) ?: "An" else "Aus" }, { Device.wifiOn(svc) }) { wifiMenu(rootOk) }
        "bt" -> Tile("Bluetooth", IconView.Kind.BLUETOOTH, {
            if (!Device.btOn(svc)) "Aus" else Device.pairedDevices(svc).firstOrNull { it.second }?.first ?: "An"
        }, { Device.btOn(svc) }) { btMenu(rootOk) }
        "perf" -> if (rootOk && Device.sys(svc, "performance_mode") != null) Tile("Leistung", IconView.Kind.BOLT, { Device.perfLabel(svc) }, { Device.performance(svc) }) {
            profileMenu("Leistung", "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } }
        } else null
        "fan" -> if (rootOk && Device.sys(svc, "fan_mode") != null) Tile("Lüfter", IconView.Kind.FAN, { Device.fanLabel(svc) }, { true }) {
            profileMenu("Lüfter", "fan_mode", Device.fanProfiles) { v -> Device.setFan(v) { refreshAll() } }
        } else null
        "refresh" -> {
            val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (rootOk && display?.supportedModes?.any { it.refreshRate > 100f } == true)
                Tile("Bildrate", IconView.Kind.REFRESH, { if (Device.highRefresh(svc)) "120 Hz" else "60 Hz" }, { Device.highRefresh(svc) }) {
                    Device.setHighRefresh(!Device.highRefresh(svc)) { refreshAll() }
                } else null
        }
        "leds" -> if (rootOk && Device.sys(svc, "joystick_light_enabled") != null) Tile("Stick-LEDs", IconView.Kind.LED, { if (Device.stickLeds(svc)) "An" else "Aus" }, { Device.stickLeds(svc) }) {
            Device.setStickLeds(!Device.stickLeds(svc)) { refreshAll() }
        } else null
        "dnd" -> if (rootOk) Tile("Nicht stören", IconView.Kind.MOON, { if (Device.dnd(svc)) "An" else "Aus" }, { Device.dnd(svc) }) {
            Device.setDnd(!Device.dnd(svc)) { refreshAll() }
        } else null
        "airplane" -> if (rootOk) Tile("Flugmodus", IconView.Kind.AIRPLANE, { if (Device.airplane(svc)) "An" else "Aus" }, { Device.airplane(svc) }) {
            Device.setAirplane(!Device.airplane(svc)) { refreshAll() }
        } else null
        "rotation" -> Tile("Drehung sperren", IconView.Kind.ROTATE, { if (Device.rotationLocked(svc)) "Gesperrt" else "Automatisch" }, { Device.rotationLocked(svc) }) {
            Device.setRotationLocked(svc, !Device.rotationLocked(svc)) { refreshAll() }
        }
        "screen" -> if (rootOk && Device.sys(svc, "keep_screen_on") != null) Tile("Bildschirm an", IconView.Kind.SCREEN, { if (Device.keepScreenOn(svc)) "An" else "Aus" }, { Device.keepScreenOn(svc) }) {
            Device.setKeepScreenOn(!Device.keepScreenOn(svc)) { refreshAll() }
        } else null
        "clean" -> if (rootOk) Tile("Speicher leeren", IconView.Kind.BROOM, { null }, { false }) {
            Thread {
                val mb = Device.cleanMemory(svc)
                svc.ui.post { android.widget.Toast.makeText(svc, "$mb MB freigegeben", android.widget.Toast.LENGTH_SHORT).show() }
            }.start()
        } else null
        "screenshot" -> Tile("Screenshot", IconView.Kind.CAMERA, { null }, { false }) {
            close()
            svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 300)
        }
        "rpsettings" -> svc.packageManager.getLaunchIntentForPackage("com.rp.settings")?.let { i ->
            Tile("Retroid-Einstellungen", IconView.Kind.GAMEPAD, { null }, { false }) { close(); svc.startApp(i) }
        }
        "settings" -> Tile("Android-Einstellungen", IconView.Kind.GEAR, { null }, { false }) { openSystem(Settings.ACTION_SETTINGS) }
        "hugminn" -> Tile("Hugminn", IconView.Kind.GEAR, { null }, { false }) {
            close()
            svc.startApp(Intent(svc, MainActivity::class.java))
        }
        else -> null
    }

    private fun controls(): View {
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val rootOk = Root.available()
        tiles.clear()
        svc.prefs.ccTiles.forEach { id -> tileFor(id, rootOk)?.let { tiles += it } }
        page = 0

        val tilesCard = card().apply { setPadding(8 * d, 12 * d, 8 * d, 8 * d) }
        val grid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        tileGrid = grid
        tilesCard.addView(grid, lp(MATCH, 0, 1f))
        val dotRow = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        dots = dotRow
        tilesCard.addView(dotRow, lp(MATCH, 10 * d))
        col.addView(tilesCard, lp(MATCH, 0, 1f).apply { bottomMargin = 10 * d })

        sliderItems.clear()
        val bright = BigSlider(svc, pal, IconView.Kind.SUN, "Helligkeit", 255, Device.brightness(svc)) { Device.setBrightness(svc, it) }
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = BigSlider(svc, pal, IconView.Kind.SPEAKER, "Lautstärke", maxVol, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        }
        col.addView(bright, lp(MATCH, 50 * d).apply { bottomMargin = 10 * d })
        col.addView(vol, lp(MATCH, 50 * d))
        sliderItems += Item(bright, null) { dir -> bright.step(5 * dir) }
        sliderItems += Item(vol, null) { dir -> vol.step(5 * dir) }
        fillPage()
        return col
    }

    /** Builds the tiles of the current page (4 per row, 2 rows) and the item list for the D-pad. */
    private fun fillPage() {
        val grid = tileGrid ?: return
        grid.removeAllViews()
        refreshers.clear()
        val tileItems = mutableListOf<Item>()
        val shown = tiles.drop(page * perPage).take(perPage)
        shown.chunked(4).forEach { chunk ->
            val row = LinearLayout(svc)
            chunk.forEach { t ->
                val (view, focusView) = tileView(t)
                row.addView(view, lp(0, MATCH, 1f))
                tileItems += Item(focusView, t.act)
            }
            repeat(4 - chunk.size) { row.addView(View(svc), lp(0, 1, 1f)) }
            grid.addView(row, lp(MATCH, 0, 1f))
        }
        if (shown.size <= 4) grid.addView(View(svc), lp(MATCH, 0, 1f))
        dots?.let { dr ->
            dr.removeAllViews()
            if (pageCount() > 1) repeat(pageCount()) { i ->
                dr.addView(View(svc).apply { background = rounded(if (i == page) pal.primary else pal.outline, 4f * d) },
                    lp(if (i == page) 16 * d else 7 * d, 7 * d).apply { marginStart = 3 * d; marginEnd = 3 * d })
            }
        }
        items.clear()
        items += tileItems
        items += sliderItems
        focus = 0
        showFocus()
    }

    /** One tile: round (or rounded square) icon button with the full name below (two lines, shrinks to fit). */
    private fun tileView(t: Tile): Pair<View, View> {
        val size = 54 * d
        val round = svc.prefs.tileShape == TileShape.ROUND
        val icon = IconView(svc, t.icon, 24)
        val button = FrameLayout(svc).apply {
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { t.act() }
        }
        val title = TextView(svc).apply {
            text = t.title
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(pal.onSurface)
            setTypeface(typeface, Typeface.BOLD)
            setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val state = text("", 11f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER }
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(size, size))
            addView(title, lp(MATCH, 30 * d).apply { topMargin = 4 * d })
            addView(state, lp(MATCH, WRAP))
            setPadding(2 * d, 0, 2 * d, 0)
        }
        val refresh = {
            val on = t.on()
            button.background = rounded(if (on) pal.primary else pal.surfaceHighest, if (round) size / 2f else 16f * d)
            icon.setColor(if (on) pal.onPrimary else pal.onSurface)
            val st = t.state()
            state.text = st ?: ""
            state.visibility = if (st == null) View.GONE else View.VISIBLE
        }
        refresh()
        refreshers += refresh
        return box to button
    }

    private fun changePage(dir: Int): Boolean {
        val n = pageCount()
        if (n < 2) return false
        page = (page + dir + n) % n
        fillPage()
        return true
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
        val round = svc.prefs.tileShape == TileShape.ROUND
        items.forEachIndexed { i, it ->
            val r = if (it.onAdjust != null) it.view.height / 2f else if (round) it.view.width / 2f else 16f * d
            it.view.foreground = if (i == focus) rounded(0, r.coerceAtLeast(12f * d), 3 * d, pal.onSurface) else null
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
        } else if (dx != 0 && items.getOrNull(focus)?.onAdjust == null) {
            // Past the last tile of a page: next/previous page.
            changePage(dx)
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
            KeyEvent.KEYCODE_BUTTON_L1 -> changePage(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> changePage(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> item?.onA?.invoke()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> close()
        }
        return true
    }
}

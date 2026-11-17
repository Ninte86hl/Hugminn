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
    private class MenuEntry(val label: String, val checked: Boolean = false, val hint: String? = null, val swatch: Int = 0, val run: (() -> Unit)?)

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
        topInset = if (svc.prefs.ccHideStatus) 0 else statusH
        val panel = build()
        panel.setPadding(panel.paddingLeft, panel.paddingTop + topInset, panel.paddingRight, panel.paddingBottom)
        r.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panelView = panel
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
        editView = null
        gridEdit = null
        svc.ui.removeCallbacks(tick)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    // ---------- layout helpers ----------

    private var pixel = false

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Look.font(svc, pixel, bold)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun shape(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = Look.shape(svc, pixel, color, radius, stroke, strokeColor)

    private fun refreshAll() = svc.ui.postDelayed({ refreshers.forEach { it() } }, 300)

    /** Colour of active tiles: chosen in the editor, or the Material You accent. */
    private fun tileOn() = svc.prefs.tileColor.takeIf { it != 0 } ?: pal.primary
    private fun tileOnFg() = if (svc.prefs.tileColor == 0) pal.onPrimary else onColor(svc.prefs.tileColor)

    private var gearItem: Item? = null
    private var panelView: View? = null
    private var topInset = 0

    /** Rebuilds the Control Center in place (live preview while editing). */
    private fun rebuildPanel() {
        val r = root ?: return
        graphUpdates.clear()
        refreshers.clear()
        items.clear()
        val p = build()
        p.setPadding(p.paddingLeft, p.paddingTop + topInset, p.paddingRight, p.paddingBottom)
        panelView?.let { r.removeView(it) }
        r.addView(p, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panelView = p
        r.post { showFocus() }
    }

    private fun size(id: String) = svc.prefs.ccSizes[id] ?: 1f

    private fun build(): View {
        pixel = svc.prefs.uiStyle == UiStyle.PIXEL
        val panel = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            setPadding(28 * d, 14 * d, 28 * d, 12 * d)
            isClickable = true
        }

        // Header with a permanent button for Android's own settings
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
            typeface = Look.font(svc, pixel, true)
        })
        val gear = FrameLayout(svc).apply {
            background = shape(pal.surfaceHighest, 20f * d)
            addView(IconView(svc, IconView.Kind.GEAR, 22).apply { setColor(pal.onSurface) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { openSystem(Settings.ACTION_SETTINGS) }
        }
        header.addView(gear, lp(40 * d, 40 * d).apply { marginStart = 16 * d })
        gearItem = Item(gear, { openSystem(Settings.ACTION_SETTINGS) })
        panel.addView(header)
        graphUpdates += { s -> battery.text = "${s.batPct} %${if (s.charging) " ⚡" else ""}" }

        // Body: the user's own layout in two columns
        val (left, right) = svc.prefs.ccLayout
        val body = LinearLayout(svc).apply { setPadding(0, 12 * d, 0, 8 * d) }
        tiles.clear()
        tileGrid = null
        sliderItems.clear()
        val cols = listOf(left, right).filter { it.isNotEmpty() }
        cols.forEachIndexed { i, ids ->
            if (i > 0) body.addView(View(svc), lp(14 * d, MATCH))
            body.addView(column(ids), lp(0, MATCH, if (ids.contains("tiles")) 1.15f else 1f))
        }
        if (cols.isEmpty()) body.addView(text("Alles ausgeblendet – mit Y bearbeiten", 15f, pal.onSurfaceVariant), lp(MATCH, WRAP))
        panel.addView(body, lp(MATCH, 0, 1f))

        val footer = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        fun hint(k: Int, s: String) {
            footer.addView(Glyphs.chip(svc, svc.prefs, pal, k, 22))
            footer.addView(text(s, 13f, pal.onSurfaceVariant).apply { setPadding(6 * d, 0, 16 * d, 0) })
        }
        if (!svc.prefs.githubToken.isNullOrBlank()) hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Feedback")
        if (pageCount() > 1) hint(KeyEvent.KEYCODE_BUTTON_R1, "Seite")
        hint(KeyEvent.KEYCODE_BUTTON_Y, "Bearbeiten")
        hint(KeyEvent.KEYCODE_BUTTON_A, "Auswählen")
        hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück")
        panel.addView(footer)
        fillPage()
        return panel
    }

    /** One column: blocks top to bottom; neighbouring small blocks share a row. */
    private fun column(ids: List<String>): View {
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val blocks = ids.mapNotNull { CcBlock.of(it) }
        var i = 0
        var first = true
        fun add(v: View, params: LinearLayout.LayoutParams) {
            if (!first) params.topMargin = 10 * d
            first = false
            col.addView(v, params)
        }
        while (i < blocks.size) {
            val b = blocks[i]
            if (b.small) {
                val row = LinearLayout(svc)
                var n = 0
                while (i < blocks.size && blocks[i].small && n < 4) {
                    row.addView(smallBlock(blocks[i]), lp(0, MATCH, 1f).apply { if (n > 0) marginStart = 10 * d })
                    i++
                    n++
                }
                val ids2 = blocks.subList(i - n, i).map { it.id }
                add(row, lp(MATCH, 0, ids2.maxOf { size(it) }))
                continue
            }
            when (b) {
                CcBlock.TILES -> add(tilesBlock(), lp(MATCH, 0, 2.2f * size("tiles")))
                CcBlock.FPS -> add(fpsBlock(), lp(MATCH, 0, 1.3f * size("fps")))
                CcBlock.BRIGHTNESS -> add(slider(IconView.Kind.SUN, "Helligkeit", 255, Device.brightness(svc)) { Device.setBrightness(svc, it) }, lp(MATCH, (50 * d * size("brightness")).toInt()))
                CcBlock.VOLUME -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    add(slider(IconView.Kind.SPEAKER, "Lautstärke", max, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                    }, lp(MATCH, (50 * d * size("volume")).toInt()))
                }
                else -> {}
            }
            i++
        }
        return col
    }

    private fun slider(icon: IconView.Kind, label: String, max: Int, value: Int, set: (Int) -> Unit): View {
        val v = BigSlider(svc, pal, icon, label, max, value, pixel, set)
        sliderItems += Item(v, null) { dir -> v.step(5 * dir) }
        return v
    }

    private fun card() = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(pal.surfaceHigh, 22f * d)
        setPadding(14 * d, 10 * d, 14 * d, 10 * d)
    }

    private fun fpsBlock(): View {
        val fps = card().apply { gravity = Gravity.CENTER }
        val modeChip = text("", 13f, pal.onPrimaryContainer, bold = true).apply {
            background = shape(pal.primaryContainer, 14f * d)
            setPadding(14 * d, 4 * d, 14 * d, 4 * d)
        }
        val row = LinearLayout(svc).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        val value = text("–", 50f, pal.onSurface, bold = true)
        row.addView(value)
        row.addView(text(" FPS", 18f, pal.onSurfaceVariant, bold = true).apply { setPadding(0, 0, 0, 10 * d) })
        fps.addView(modeChip)
        fps.addView(row)
        fps.addView(text("Aktuelle Bildrate", 12f, pal.onSurfaceVariant))
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        graphUpdates += { s ->
            value.text = if (s.fps >= 0) "${s.fps}" else "–"
            modeChip.text = "${Math.round(display?.refreshRate ?: 60f)} Hz Modus"
        }
        return fps
    }

    private fun smallBlock(b: CcBlock): View {
        val gp = svc.prefs.gaugePalette
        fun ring(label: String, color: Int, update: RingView.(Metrics.Sample) -> Unit): View {
            val c = card().apply { setPadding(4 * d, 4 * d, 4 * d, 4 * d) }
            val r = RingView(svc, pal, label, color, pixel)
            c.addView(r, lp(MATCH, MATCH))
            graphUpdates += { s -> r.update(s) }
            return c
        }
        fun info(icon: IconView.Kind, title: String, update: (TextView, TextView, Metrics.Sample) -> Unit): View {
            val c = card()
            val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(IconView(svc, icon, 18).apply { setColor(pal.onSurfaceVariant) })
            head.addView(text(title, 12f, pal.onSurfaceVariant, bold = true).apply { setPadding(8 * d, 0, 0, 0) })
            val big = text("–", 24f, pal.onSurface, bold = true)
            val sub = text("", 11f, pal.onSurfaceVariant)
            c.addView(head)
            c.addView(View(svc), lp(1, 0, 1f))
            c.addView(big)
            c.addView(sub)
            graphUpdates += { s -> update(big, sub, s) }
            return c
        }
        return when (b) {
            CcBlock.TEMP -> info(IconView.Kind.THERMO, "TEMPERATUR") { big, sub, s ->
                big.text = if (s.cpuTemp > 0) "${s.cpuTemp.toInt()} °C" else "–"
                big.setTextColor(if (s.cpuTemp > 0 && gp != GaugePalette.MONO) GaugeColors.of(pal, gp, (s.cpuTemp - 40f) / 45f) else pal.onSurface)
                sub.text = listOfNotNull(
                    s.gpuTemp.takeIf { it > 0 }?.let { "GPU ${it.toInt()} °C" },
                    s.batTemp.takeIf { it > 0 }?.let { "Akku ${it.toInt()} °C" },
                ).joinToString(" · ")
            }
            CcBlock.FAN -> info(IconView.Kind.FAN, "LÜFTER") { big, sub, _ ->
                big.text = Device.fanLabel(svc)
                sub.text = Device.fanSpeed(svc)?.let { "Drehzahl $it" } ?: ""
            }
            CcBlock.BATTERY -> info(IconView.Kind.PLUG, "AKKU") { big, sub, s ->
                big.text = "${s.batPct} %"
                big.setTextColor(if (gp != GaugePalette.MONO) GaugeColors.of(pal, gp, 1f - s.batPct / 100f) else pal.onSurface)
                sub.text = "%.1f W".format(s.watts) + if (s.charging) " · lädt" else ""
            }
            CcBlock.CPU -> ring("CPU", pal.primary) { s ->
                setColor(GaugeColors.of(pal, gp, s.cpuPct / 100f))
                set(s.cpuPct / 100f, "%.2f".format(s.cpuMhz / 1000f), "GHz · ${s.cpuPct.toInt()} %")
            }
            CcBlock.GPU -> ring("GPU", pal.primary) { s ->
                setColor(GaugeColors.of(pal, gp, s.gpuPct / 100f))
                set(s.gpuPct / 100f, "${s.gpuMhz}", "MHz · ${s.gpuPct.toInt()} %")
            }
            CcBlock.POWER -> ring("LEISTUNG", pal.primary) { s ->
                setColor(GaugeColors.of(pal, gp, s.watts / 15f))
                set(s.watts / 15f, "%.1f".format(s.watts), if (s.charging) "W · lädt" else "W")
            }
            CcBlock.RAM -> ring("RAM", pal.primary) { s ->
                val pct = if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f
                setColor(GaugeColors.of(pal, gp, pct))
                set(pct, "%.1f".format(s.ramUsedMb / 1024f), "GB · ${(pct * 100).toInt()} %")
            }
            else -> View(svc)
        }
    }

    // ---------- quick tiles ----------

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
        "landscape" -> sysTile(rootOk, "Querformat erzwingen", IconView.Kind.SCREEN, "force_landscape")
        "vibration" -> sysTile(rootOk, "Vibration", IconView.Kind.VIBRATE, "haptic_feedback_enabled")
        "keymapper" -> sysTile(rootOk, "Tastenzuordnung", IconView.Kind.GAMEPAD, "key_adapter_enable")
        "mouse" -> sysTile(rootOk, "Mausmodus", IconView.Kind.MOUSE, "global_gamepad_to_mouse_mode")
        "homelock" -> sysTile(rootOk, "Home-Sperre", IconView.Kind.LOCK, "prevent_press_home_accidentally")
        "chargesep" -> sysTile(rootOk, "Direkte Stromversorgung", IconView.Kind.PLUG, "is_charging_separation")
        "trigger" -> if (rootOk && Device.sys(svc, "trigger_input_mode") != null) Tile("Trigger-Modus", IconView.Kind.GAMEPAD, {
            Device.profileLabel(svc, "trigger_input_mode", Device.triggerModes)
        }, { false }) {
            profileMenu("Trigger-Modus", "trigger_input_mode", Device.triggerModes) { v -> Device.setSys("trigger_input_mode", v) { refreshAll() } }
        } else null
        "controller" -> if (rootOk && Device.sys(svc, "temp_abxy_layout_mode") != null) Tile("Tastenlayout", IconView.Kind.GAMEPAD, {
            if (Glyphs.nintendoLayout()) "Retroid" else "Xbox"
        }, { false }) {
            showMenu("Tastenlayout", listOf(
                MenuEntry("Xbox (A unten)", checked = !Glyphs.nintendoLayout()) { Device.setControllerLayout(false) { refreshAll() } },
                MenuEntry("Retroid / Nintendo (A rechts)", checked = Glyphs.nintendoLayout()) { Device.setControllerLayout(true) { refreshAll() } },
            ))
        } else null
        "theme" -> Tile("Farbschema", IconView.Kind.PALETTE, { svc.prefs.theme.label }, { false }) {
            showMenu("Farbschema", Theme.values().map { t ->
                MenuEntry(t.label, checked = svc.prefs.theme == t) {
                    svc.prefs.theme = t
                    close()
                    svc.ui.postDelayed({ open() }, 60)
                }
            })
        }
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

    /** On/off tile for a firmware system setting; hidden if this device does not have the setting. */
    private fun sysTile(rootOk: Boolean, title: String, icon: IconView.Kind, key: String): Tile? =
        if (!rootOk || Device.sys(svc, key) == null) null
        else Tile(title, icon, { if (Device.sysOn(svc, key)) "An" else "Aus" }, { Device.sysOn(svc, key) }) {
            Device.setSys(key, if (Device.sysOn(svc, key)) "0" else "1") { refreshAll() }
        }

    private fun loadTiles() {
        val rootOk = Root.available()
        tiles.clear()
        svc.prefs.ccTiles.forEach { id -> tileFor(id, rootOk)?.let { tiles += it } }
        page = 0
    }

    private fun tilesBlock(): View {
        loadTiles()
        val tilesCard = card().apply { setPadding(8 * d, 12 * d, 8 * d, 8 * d) }
        val grid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        tileGrid = grid
        tilesCard.addView(grid, lp(MATCH, 0, 1f))
        val dotRow = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        dots = dotRow
        tilesCard.addView(dotRow, lp(MATCH, 10 * d))
        return tilesCard
    }

    /** Builds the tiles of the current page (4 per row, 2 rows) and the item list for the D-pad. */
    private fun fillPage() {
        refreshers.clear()
        val tileItems = mutableListOf<Item>()
        tileGrid?.let { grid ->
            grid.removeAllViews()
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
            if (shown.isEmpty()) grid.addView(text("Keine Kacheln – mit Y auswählen", 14f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        }
        dots?.let { dr ->
            dr.removeAllViews()
            if (pageCount() > 1) repeat(pageCount()) { i ->
                dr.addView(View(svc).apply { background = shape(if (i == page) pal.primary else pal.outline, 4f * d) },
                    lp(if (i == page) 16 * d else 7 * d, 7 * d).apply { marginStart = 3 * d; marginEnd = 3 * d })
            }
        }
        items.clear()
        items += tileItems
        items += sliderItems
        gearItem?.let { items += it }
        focus = 0
        showFocus()
    }

    /** One tile: round (or square) icon button with the full name below (two lines, shrinks to fit). */
    private fun tileView(t: Tile): Pair<View, View> {
        val size = 54 * d
        val round = svc.prefs.tileShape == TileShape.ROUND && !pixel
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
            typeface = Look.font(svc, pixel, true)
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
            button.background = if (round) rounded(if (on) tileOn() else pal.surfaceHighest, size / 2f)
            else shape(if (on) tileOn() else pal.surfaceHighest, 16f * d)
            icon.setColor(if (on) tileOnFg() else pal.onSurface)
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
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
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
            if (e.swatch != 0) row.addView(View(svc).apply { background = shape(e.swatch, 12f * d, 1 * d, pal.outline) },
                lp(24 * d, 24 * d).apply { marginEnd = 12 * d })
            val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(e.label, 15f, if (e.run == null) pal.onSurfaceVariant else pal.onSurface, bold = e.checked))
            e.hint?.let { col.addView(text(it, 12f, pal.onSurfaceVariant)) }
            row.addView(col, lp(0, WRAP, 1f))
            list.addView(row, lp(MATCH, WRAP))
            menuRows += row
        }
        val tall = entries.size > 6
        val sc = android.widget.ScrollView(svc).apply { addView(list) }
        menuScroll = sc
        box.addView(sc, if (tall) lp(MATCH, 0, 1f) else lp(MATCH, WRAP))
        val w = (r.width * 0.42f).toInt().coerceAtLeast(320 * d)
        r.addView(box, FrameLayout.LayoutParams(w, if (tall) (r.height * 0.86f).toInt() else FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        menuView = box
        box.scaleX = 0.96f
        box.scaleY = 0.96f
        box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
        showMenuSel()
    }

    private var menuScroll: android.widget.ScrollView? = null

    private fun showMenuSel() {
        menuRows.forEachIndexed { i, v ->
            v.background = if (i == menuSel) shape(pal.secondaryContainer, 16f * d) else null
        }
        val v = menuRows.getOrNull(menuSel) ?: return
        menuScroll?.post {
            val sc = menuScroll ?: return@post
            if (v.top < sc.scrollY) sc.scrollTo(0, v.top) else if (v.bottom > sc.scrollY + sc.height) sc.scrollTo(0, v.bottom - sc.height)
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
        val round = svc.prefs.tileShape == TileShape.ROUND && !pixel
        items.forEachIndexed { i, it ->
            val r = if (it.onAdjust != null) it.view.height / 2f else if (round) it.view.width / 2f else 16f * d
            it.view.foreground = if (i == focus) shape(0, r.coerceAtLeast(12f * d), 3 * d, pal.onSurface) else null
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

    // ---------- editing (Y): layout, tiles, colours, style ----------

    private fun reopen() {
        close()
        svc.ui.postDelayed({ open() }, 60)
    }

    private fun editMenu() {
        showMenu("Bearbeiten", listOf(
            MenuEntry("Layout anpassen", hint = "Bereiche ein-/ausblenden, Reihenfolge, Spalte") { layoutEditor() },
            MenuEntry("Kacheln auswählen", hint = "Wie bei Android: Kacheln hinzufügen, verschieben, entfernen") { tileEditor() },
            MenuEntry("Kachelfarbe", hint = TILE_COLORS[svc.prefs.tileColor] ?: "") { colorMenu() },
            MenuEntry("Form der Kacheln", hint = svc.prefs.tileShape.label) {
                showMenu("Form der Kacheln", TileShape.values().map { t ->
                    MenuEntry(t.label, checked = svc.prefs.tileShape == t) { svc.prefs.tileShape = t; reopen() }
                })
            },
            MenuEntry("Stil", hint = svc.prefs.uiStyle.label) {
                showMenu("Stil", UiStyle.values().map { st ->
                    MenuEntry(if (st == UiStyle.PIXEL) "Pixel (Retroid-Pocket-Classic-Look)" else "Modern (Material You)", checked = svc.prefs.uiStyle == st) {
                        svc.prefs.uiStyle = st
                        reopen()
                    }
                })
            },
            MenuEntry("Farben der Anzeigen", hint = svc.prefs.gaugePalette.label) {
                showMenu("Farben der Anzeigen", GaugePalette.values().map { g ->
                    MenuEntry(g.label, checked = svc.prefs.gaugePalette == g) { svc.prefs.gaugePalette = g; reopen() }
                })
            },
            MenuEntry("Farbschema", hint = svc.prefs.theme.label) {
                showMenu("Farbschema", Theme.values().map { t ->
                    MenuEntry(t.label, checked = svc.prefs.theme == t) { svc.prefs.theme = t; reopen() }
                })
            },
            MenuEntry("Standard wiederherstellen", hint = "Layout, Kacheln, Größen und Farben zurücksetzen") {
                showMenu("Wirklich zurücksetzen?", listOf(
                    MenuEntry("Ja, Standard wiederherstellen") { svc.prefs.resetControlCenter(); reopen() },
                    MenuEntry("Abbrechen") {},
                ))
            },
        ))
    }

    private fun colorMenu() {
        showMenu("Kachelfarbe", TILE_COLORS.map { (c, name) ->
            MenuEntry(name, checked = svc.prefs.tileColor == c, swatch = if (c == 0) pal.primary else c) {
                svc.prefs.tileColor = c
                reopen()
            }
        })
    }

    /** A list split into sections (e.g. left column / right column / hidden); items move across sections. */
    private class EditSection(val title: String, val ids: MutableList<String>)

    private var editView: View? = null
    private var editScroll: android.widget.ScrollView? = null
    private var editListView: LinearLayout? = null
    private var editSections: List<EditSection> = emptyList()
    private var editLabel: (String) -> String = { it }
    private var editNote: (String) -> String = { "" }
    private var editEnableTo = 0
    private var editDone: () -> Unit = {}
    private var editAdjust: ((String, Int) -> Unit)? = null
    private var editLive: (() -> Unit)? = null
    private var editSel = 0

    private fun flat() = editSections.flatMapIndexed { si, sec -> sec.ids.indices.map { si to it } }

    private fun openEditor(
        title: String, sections: List<EditSection>, enableTo: Int, label: (String) -> String, note: (String) -> String,
        adjust: ((String, Int) -> Unit)? = null, live: (() -> Unit)? = null, done: () -> Unit,
    ) {
        val r = root ?: return
        closeMenu()
        editAdjust = adjust
        editLive = live
        editSections = sections
        editLabel = label
        editNote = note
        editEnableTo = enableTo
        editDone = done
        editSel = 0
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(12 * d, 16 * d, 12 * d, 12 * d)
            elevation = 12f * d
            isClickable = true
        }
        box.addView(text(title, 18f, pal.onSurface, bold = true).apply { setPadding(12 * d, 0, 12 * d, 4 * d) })
        val help = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12 * d, 0, 12 * d, 10 * d)
        }
        fun h(k: Int, t: String) {
            help.addView(Glyphs.chip(svc, svc.prefs, pal, k, 20))
            help.addView(text(t, 12f, pal.onSurfaceVariant).apply { setPadding(5 * d, 0, 12 * d, 0) })
        }
        h(KeyEvent.KEYCODE_BUTTON_A, "ein/aus")
        if (adjust != null) h(KeyEvent.KEYCODE_DPAD_LEFT, "Größe −/+")
        h(KeyEvent.KEYCODE_BUTTON_L1, "nach oben")
        h(KeyEvent.KEYCODE_BUTTON_R1, "nach unten")
        h(KeyEvent.KEYCODE_BUTTON_B, "fertig")
        box.addView(help)
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        editListView = list
        val sc = android.widget.ScrollView(svc).apply { addView(list) }
        editScroll = sc
        box.addView(sc, lp(MATCH, 0, 1f))
        // With live preview the editor sits on the right so the Control Center stays visible.
        val w = (r.width * (if (live != null) 0.38f else 0.5f)).toInt().coerceAtLeast(340 * d)
        r.addView(box, FrameLayout.LayoutParams(w, (r.height * 0.9f).toInt(), if (live != null) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER).apply {
            if (live != null) marginEnd = 12 * d
        })
        editView = box
        renderEditor()
    }

    private fun renderEditor() {
        val list = editListView ?: return
        list.removeAllViews()
        var flatIndex = 0
        var selView: View? = null
        editSections.forEachIndexed { si, sec ->
            list.addView(text(sec.title, 13f, pal.primary, bold = true).apply { setPadding(12 * d, if (si == 0) 0 else 12 * d, 0, 4 * d) })
            if (sec.ids.isEmpty()) list.addView(text("—", 13f, pal.outline).apply { setPadding(36 * d, 4 * d, 0, 4 * d) })
            sec.ids.forEachIndexed { i, id ->
                val hidden = si == editSections.lastIndex
                val idx = flatIndex++
                val row = LinearLayout(svc).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12 * d, 0, 12 * d, 0)
                    minimumHeight = 42 * d
                    background = if (idx == editSel) shape(pal.secondaryContainer, 16f * d) else null
                    setOnClickListener { editSel = idx; toggleEdit() }
                }
                row.addView(text(if (hidden) "○" else "●", 15f, if (hidden) pal.outline else pal.primary).apply { setPadding(0, 0, 12 * d, 0) })
                row.addView(text(editLabel(id), 15f, pal.onSurface, bold = !hidden), lp(0, WRAP, 1f))
                row.addView(text(editNote(id).ifEmpty { if (hidden) "" else "${i + 1}" }, 12f, pal.onSurfaceVariant))
                list.addView(row, lp(MATCH, WRAP))
                if (idx == editSel) selView = row
            }
        }
        list.post {
            val v = selView ?: return@post
            editScroll?.let { sc ->
                if (v.top < sc.scrollY) sc.scrollTo(0, v.top - 30 * d)
                else if (v.bottom > sc.scrollY + sc.height) sc.scrollTo(0, v.bottom - sc.height)
            }
        }
    }

    private fun toggleEdit() {
        val (si, i) = flat().getOrNull(editSel) ?: return
        val id = editSections[si].ids.removeAt(i)
        val last = editSections.lastIndex
        if (si == last) editSections[editEnableTo].ids.add(id) else editSections[last].ids.add(0, id)
        editSel = flat().indexOfFirst { (s2, j) -> editSections[s2].ids[j] == id }.coerceAtLeast(0)
        renderEditor()
        editLive?.invoke()
    }

    /** Moves the selected item up/down; past the end of a section it moves into the next one. */
    private fun moveEdit(dir: Int) {
        val (si, i) = flat().getOrNull(editSel) ?: return
        val sec = editSections[si].ids
        val id = sec[i]
        if (dir < 0) {
            if (i > 0) { sec[i] = sec[i - 1]; sec[i - 1] = id }
            else if (si > 0) { sec.removeAt(i); editSections[si - 1].ids.add(id) } else return
        } else {
            if (i < sec.lastIndex) { sec[i] = sec[i + 1]; sec[i + 1] = id }
            else if (si < editSections.lastIndex) { sec.removeAt(i); editSections[si + 1].ids.add(0, id) } else return
        }
        editSel = flat().indexOfFirst { (s2, j) -> editSections[s2].ids[j] == id }.coerceAtLeast(0)
        renderEditor()
        editLive?.invoke()
    }

    private fun adjustEdit(dir: Int) {
        val (si, i) = flat().getOrNull(editSel) ?: return
        editAdjust?.invoke(editSections[si].ids[i], dir) ?: return
        renderEditor()
        editLive?.invoke()
    }

    private fun closeEditor() {
        editView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        editView = null
        editDone()
    }

    private fun layoutEditor() {
        val (left, right) = svc.prefs.ccLayout
        val hidden = CcBlock.values().map { it.id }.filter { it !in left && it !in right }
        openEditor("Layout anpassen", listOf(
            EditSection("Linke Spalte", left.toMutableList()),
            EditSection("Rechte Spalte", right.toMutableList()),
            EditSection("Ausgeblendet", hidden.toMutableList()),
        ), enableTo = 1, label = { CcBlock.of(it)?.label ?: it },
            note = { id -> "Größe ${Math.round(size(id) * 100)} %" },
            adjust = { id, dir ->
                val m = svc.prefs.ccSizes.toMutableMap()
                m[id] = ((m[id] ?: 1f) + 0.2f * dir).coerceIn(0.6f, 2.0f)
                svc.prefs.ccSizes = m
            },
            live = {
                svc.prefs.ccLayout = editSections[0].ids.toList() to editSections[1].ids.toList()
                rebuildPanel()
                editView?.bringToFront()
            },
        ) {
            svc.prefs.ccLayout = editSections[0].ids.toList() to editSections[1].ids.toList()
            rebuildPanel()
        }
    }

    // ---------- tile editor like stock Android: grid of shown tiles, grid of tiles to add ----------

    private var gridEdit: View? = null
    private val geActive = mutableListOf<String>()
    private val geAvail = mutableListOf<String>()
    private var geSel = 0
    private var geMoving = false
    private var geActiveGrid: LinearLayout? = null
    private var geAvailGrid: LinearLayout? = null
    private var geScroll: android.widget.ScrollView? = null
    private var geTitle: TextView? = null
    private val geCols = 6

    private fun tileEditor() {
        val r = root ?: return
        closeMenu()
        val rootOk = Root.available()
        geActive.clear()
        geActive += svc.prefs.ccTiles.filter { tileFor(it, rootOk) != null }
        geAvail.clear()
        geAvail += TileCatalog.all.keys.filter { it !in geActive && tileFor(it, rootOk) != null }
        geSel = 0
        geMoving = false
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(18 * d, 16 * d, 18 * d, 12 * d)
            elevation = 12f * d
            isClickable = true
        }
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Kacheln bearbeiten", 18f, pal.onSurface, bold = true), lp(0, WRAP, 1f))
        fun h(k: Int, t: String) {
            head.addView(Glyphs.chip(svc, svc.prefs, pal, k, 20))
            head.addView(text(t, 12f, pal.onSurfaceVariant).apply { setPadding(5 * d, 0, 12 * d, 0) })
        }
        h(KeyEvent.KEYCODE_BUTTON_A, "Hinzufügen / Verschieben")
        h(KeyEvent.KEYCODE_BUTTON_X, "Entfernen")
        h(KeyEvent.KEYCODE_BUTTON_B, "Fertig")
        box.addView(head)
        val content = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val t1 = text("", 13f, pal.primary, bold = true).apply { setPadding(4 * d, 12 * d, 0, 6 * d) }
        geTitle = t1
        content.addView(t1)
        val g1 = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        geActiveGrid = g1
        content.addView(g1)
        content.addView(View(svc).apply { setBackgroundColor(pal.outline) }, lp(MATCH, 1 * d).apply { topMargin = 12 * d; bottomMargin = 4 * d })
        content.addView(text("Zum Hinzufügen auswählen", 13f, pal.primary, bold = true).apply { setPadding(4 * d, 8 * d, 0, 6 * d) })
        val g2 = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        geAvailGrid = g2
        content.addView(g2)
        val sc = android.widget.ScrollView(svc).apply { addView(content) }
        geScroll = sc
        box.addView(sc, lp(MATCH, 0, 1f))
        r.addView(box, FrameLayout.LayoutParams((r.width * 0.9f).toInt(), (r.height * 0.9f).toInt(), Gravity.CENTER))
        gridEdit = box
        renderGrid()
    }

    private fun renderGrid() {
        val rootOk = Root.available()
        geTitle?.text = "Angezeigt (${geActive.size}) · Reihenfolge wie im Control Center"
        var selView: View? = null
        fun fill(grid: LinearLayout?, ids: List<String>, offset: Int) {
            grid ?: return
            grid.removeAllViews()
            if (ids.isEmpty()) grid.addView(text("—", 14f, pal.outline).apply { setPadding(8 * d, 8 * d, 0, 8 * d) })
            ids.chunked(geCols).forEachIndexed { ri, chunk ->
                val row = LinearLayout(svc)
                chunk.forEachIndexed { ci, id ->
                    val idx = offset + ri * geCols + ci
                    val t = tileFor(id, rootOk) ?: return@forEachIndexed
                    val selected = idx == geSel
                    val moving = selected && geMoving
                    val icon = IconView(svc, t.icon, 24).apply { setColor(if (moving) tileOnFg() else pal.onSurface) }
                    val circle = FrameLayout(svc).apply {
                        background = if (svc.prefs.tileShape == TileShape.ROUND && !pixel) rounded(if (moving) tileOn() else pal.surfaceHighest, 26f * d)
                        else shape(if (moving) tileOn() else pal.surfaceHighest, 14f * d)
                        foreground = if (selected) shape(0, if (svc.prefs.tileShape == TileShape.ROUND && !pixel) 26f * d else 14f * d, 3 * d, pal.onSurface) else null
                        addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    }
                    val cell = LinearLayout(svc).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(2 * d, 4 * d, 2 * d, 4 * d)
                        addView(circle, LinearLayout.LayoutParams(52 * d, 52 * d))
                        addView(TextView(svc).apply {
                            text = t.title
                            gravity = Gravity.CENTER
                            maxLines = 2
                            setTextColor(pal.onSurface)
                            typeface = Look.font(svc, pixel, selected)
                            setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                        }, lp(MATCH, 30 * d).apply { topMargin = 4 * d })
                        setOnClickListener { geSel = idx; gridA() }
                    }
                    row.addView(cell, lp(0, WRAP, 1f))
                    if (selected) selView = cell
                }
                repeat(geCols - chunk.size) { row.addView(View(svc), lp(0, 1, 1f)) }
                grid.addView(row, lp(MATCH, WRAP))
            }
        }
        fill(geActiveGrid, geActive, 0)
        fill(geAvailGrid, geAvail, geActive.size)
        geScroll?.post {
            val v = selView ?: return@post
            val sc = geScroll ?: return@post
            val loc = IntArray(2).also(v::getLocationInWindow)
            val sloc = IntArray(2).also(sc::getLocationInWindow)
            val top = loc[1] - sloc[1]
            if (top < 0) sc.smoothScrollBy(0, top - 10 * d) else if (top + v.height > sc.height) sc.smoothScrollBy(0, top + v.height - sc.height + 10 * d)
        }
    }

    private fun gridMove(dx: Int, dy: Int) {
        val total = geActive.size + geAvail.size
        if (total == 0) return
        if (geMoving && geSel < geActive.size) {
            // Move the held tile within the shown tiles
            val to = (geSel + dx + dy * geCols).coerceIn(0, geActive.lastIndex)
            if (to != geSel) {
                val id = geActive.removeAt(geSel)
                geActive.add(to, id)
                geSel = to
            }
        } else {
            val inActive = geSel < geActive.size
            var to = geSel + dx + dy * geCols
            // Crossing between the two grids keeps the column where possible
            if (dy > 0 && inActive && to >= geActive.size) to = geActive.size + (geSel % geCols).coerceAtMost((geAvail.size - 1).coerceAtLeast(0))
            if (dy < 0 && !inActive && to < geActive.size) {
                val col = (geSel - geActive.size) % geCols
                val lastRowStart = (geActive.size - 1).coerceAtLeast(0) / geCols * geCols
                to = (lastRowStart + col).coerceAtMost(geActive.size - 1)
            }
            geSel = to.coerceIn(0, total - 1)
        }
        renderGrid()
    }

    private fun gridA() {
        if (geSel < geActive.size) {
            geMoving = !geMoving
        } else {
            val i = geSel - geActive.size
            val id = geAvail.removeAt(i)
            geActive += id
            // stay on the next tile to add
            geSel = (geActive.size + i).coerceAtMost(geActive.size + geAvail.size - 1)
        }
        renderGrid()
    }

    private fun gridRemove() {
        if (geSel >= geActive.size) return
        val id = geActive.removeAt(geSel)
        geAvail.add(0, id)
        geMoving = false
        geSel = geSel.coerceAtMost((geActive.size + geAvail.size - 1).coerceAtLeast(0))
        renderGrid()
    }

    private fun gridDone() {
        if (geMoving) {
            geMoving = false
            renderGrid()
            return
        }
        svc.prefs.ccTiles = geActive.toList()
        gridEdit?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        gridEdit = null
        rebuildPanel()
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (gridEdit != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> gridMove(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> gridMove(1, 0)
                KeyEvent.KEYCODE_DPAD_UP -> gridMove(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> gridMove(0, 1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> gridA()
                KeyEvent.KEYCODE_BUTTON_X -> gridRemove()
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_Y -> gridDone()
            }
            return true
        }
        if (editView != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { editSel = (editSel - 1).coerceAtLeast(0); renderEditor() }
                KeyEvent.KEYCODE_DPAD_DOWN -> { editSel = (editSel + 1).coerceAtMost((flat().size - 1).coerceAtLeast(0)); renderEditor() }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> toggleEdit()
                KeyEvent.KEYCODE_BUTTON_L1 -> moveEdit(-1)
                KeyEvent.KEYCODE_BUTTON_R1 -> moveEdit(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (editAdjust != null) adjustEdit(-1) else moveEdit(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (editAdjust != null) adjustEdit(1) else moveEdit(1)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_Y -> closeEditor()
            }
            return true
        }
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
            KeyEvent.KEYCODE_BUTTON_Y -> editMenu()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> close()
        }
        return true
    }
}

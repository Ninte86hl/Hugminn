package io.github.ninte86hl.hugminn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Device test: checks everything Hugminn uses on this handheld, then a short hands-on part (buttons,
 * LEDs) and a summary that can be sent as a GitHub issue. No Wi-Fi names or other private data.
 */
class DeviceTestActivity : Activity() {

    private val checks = mutableListOf<DeviceCheck>()
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var buttons: LinearLayout
    private var keyTest: ((Int) -> Boolean)? = null

    private fun d(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private val bg = 0xFF121214.toInt()
    private val card = 0xFF1E1E22.toInt()
    private val fg = 0xFFECECEC.toInt()
    private val dim = 0xFF9A9AA2.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(d(24), d(20), d(24), d(16)) }
        root.addView(text(t("Gerätetest", "Device test"), 24f, fg, true))
        root.addView(text(t("Prüft, was Hugminn auf diesem Gerät nutzen kann. Es werden keine privaten Daten gesammelt.",
            "Checks what Hugminn can use on this device. No private data is collected."), 13f, dim).apply { setPadding(0, d(4), 0, d(12)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list); isFocusable = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        buttons = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, d(12), 0, 0) }
        root.addView(buttons)
        setContentView(root)
        status(t("Prüfe … (etwa 10 Sekunden)", "Checking … (about 10 seconds)"))
        Thread { runAuto(); runOnUiThread { startKeyTest() } }.start()
    }

    // ---- UI helpers ----

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun status(s: String) = runOnUiThread {
        list.removeAllViews()
        list.addView(text(s, 16f, fg).apply { setPadding(0, d(12), 0, d(12)) })
    }

    private fun button(label: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setTextColor(if (primary) Color.WHITE else fg)
        background = GradientDrawable().apply { setColor(if (primary) 0xFFE0303C.toInt() else card); cornerRadius = d(20).toFloat() }
        setPadding(d(20), 0, d(20), 0)
        setOnClickListener { click() }
        isFocusable = true
        setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.75f; v.scaleX = if (f) 1.05f else 1f; v.scaleY = v.scaleX }
        alpha = 0.75f
    }

    private fun setButtons(vararg b: Button) {
        buttons.removeAllViews()
        b.forEach { buttons.addView(it, LinearLayout.LayoutParams(-2, d(44)).apply { marginStart = d(10) }) }
        b.lastOrNull()?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keyTest?.let { if (event.action == KeyEvent.ACTION_DOWN && it(event.keyCode)) return true; if (keyTest != null) return true }
        if (event.action == KeyEvent.ACTION_UP) when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> { currentFocus?.performClick(); return true }
            KeyEvent.KEYCODE_BUTTON_B -> { finish(); return true }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_BUTTON_B) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- automatic part ----

    private fun add(area: String, name: String, state: CheckState, note: String = "") { synchronized(checks) { checks += DeviceCheck(area, name, state, note) } }
    private fun ok(b: Boolean) = if (b) CheckState.OK else CheckState.FAIL

    private fun runAuto() = DeviceReport.auto(this) { area, name, state, note -> add(area, name, state, note) }

    // ---- hands-on part ----

    private val keys = linkedMapOf(
        KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B", KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1", KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_VOLUME_UP to "Vol+", KeyEvent.KEYCODE_VOLUME_DOWN to "Vol−",
        KeyEvent.KEYCODE_HOME to "Home", KeyEvent.KEYCODE_BACK to t("Zurück", "Back"),
    )

    private fun startKeyTest() {
        val pressed = mutableSetOf<Int>()
        val labels = mutableMapOf<Int, TextView>()
        list.removeAllViews()
        list.addView(text(t("Tasten: drücke jede Taste einmal", "Buttons: press each button once"), 18f, fg, true))
        list.addView(text(t("Endet automatisch, wenn alle gedrückt sind, oder nach 30 Sekunden.", "Ends by itself once all are pressed, or after 30 seconds."), 13f, dim).apply { setPadding(0, d(4), 0, d(12)) })
        var grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.entries.forEachIndexed { i, (code, name) ->
            if (i == 8) { list.addView(grid); grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, d(6), 0, 0) } }
            val v = text(name, 16f, dim, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(card); cornerRadius = d(10).toFloat() }
            }
            labels[code] = v
            grid.addView(v, LinearLayout.LayoutParams(0, d(48), 1f).apply { marginEnd = d(6) })
        }
        list.addView(grid)
        // Home is taken by Hugminn itself: it tells the test instead of opening the Control Center
        HugminnService.keyProbe = { code -> keyTest?.invoke(code) }
        setButtons()
        var done = false
        fun finishKeys() {
            if (done) return
            done = true
            keyTest = null
            HugminnService.keyProbe = null
            val missing = keys.filterKeys { it !in pressed }.values
            add(t("Tasten", "Buttons"), t("Tasten", "Buttons"), if (missing.isEmpty()) CheckState.OK else CheckState.WARN,
                if (missing.isEmpty()) t("Alle ${keys.size} erkannt", "All ${keys.size} detected") else t("Nicht erkannt: ", "Not detected: ") + missing.joinToString())
            startLedTest()
        }
        keyTest = { code ->
            if (code in keys && pressed.add(code)) {
                labels[code]?.apply { setTextColor(Color.WHITE); (background as GradientDrawable).setColor(0xFF2E9E5B.toInt()) }
                if (pressed.size == keys.size) list.postDelayed({ finishKeys() }, 400)
            }
            true
        }
        list.postDelayed({ finishKeys() }, 30_000)
    }

    private fun startLedTest() {
        if (!Root.available() || !Device.hasStickLeds(this)) { showSummary(); return }
        list.removeAllViews()
        list.addView(text(t("Stick-LEDs", "Stick LEDs"), 18f, fg, true))
        list.addView(text(t("Die Sticks leuchten jetzt rot. Siehst du es?", "The sticks light up red now. Can you see it?"), 15f, fg).apply { setPadding(0, d(6), 0, 0) })
        LedFx.previewZone = 2  // Odin 2: zone 2 = sticks
        LedFx.preview(Color.RED, Color.RED, 1f)
        fun answer(seen: Boolean) {
            add(if (Device.isAyn) t("AYN-Funktionen", "AYN features") else t("Retroid-Funktionen", "Retroid features"), t("Stick-LEDs leuchten", "Stick LEDs light up"), if (seen) CheckState.OK else CheckState.FAIL,
                if (seen) "" else t("Farben werden nicht übernommen", "Colours are not applied"))
            runCatching { if (Device.stickLeds(this)) { if (Prefs(this).ledEffect != LedEffect.NONE) LedFx.resume(this) else LedFx.restoreStatic(this) } else LedFx.preview(0, 0, 1f) }
            startLedProbe()
        }
        setButtons(button(t("Nein", "No")) { answer(false) }, button(t("Ja", "Yes"), primary = true) { answer(true) })
    }

    // ---- LED tester (AYN Odin 2: sticks and side lights) ----

    /** One step: switch something on, ask what lights up, put it back. */
    private class Probe(val name: String, val on: List<String>, val off: List<String>)

    /** What can light up: the device's elements, and anything else. */
    private val lightNames get() = Device.ledElNames(this) + t("Etwas anderes", "Something else")

    /**
     * Lights one setting or driver zone after the other in red and asks what lit up – so it is clear
     * which switch drives which light (Odin 2, Thor, Retroid). The answers go into the result (and the
     * feedback report).
     */
    private fun startLedProbe() {
        status(t("LED-Tester wird vorbereitet …", "Preparing the LED tester …"))
        Thread {
            val probes = mutableListOf<Probe>()
            val restore = mutableListOf<String>()
            val allOn: List<String>
            if (Device.isAyn) {
                val keys = listOf("left_joystick_light_enabled", "right_joystick_light_enabled", "left_handle_light_enabled", "right_handle_light_enabled")
                    .filter { Device.sys(this, it) != null }
                val before = keys.associateWith { Device.sys(this, it) ?: "1" }
                val colorBefore = Device.sys(this, "led_light_picker_color") ?: "-1"
                fun only(k: String?) = keys.map { Root.putSetting("system", it, if (it == k) "1" else "0") } +
                    Root.putSetting("system", "led_light_picker_color", "${0xFFFF0000.toInt()}")
                keys.forEach { k -> probes += Probe("AYN: $k", only(k), emptyList()) }
                allOn = keys.map { Root.putSetting("system", it, "1") }
                restore += keys.map { Root.putSetting("system", it, before[it] ?: "1") } + Root.putSetting("system", "led_light_picker_color", colorBefore)
            } else {
                // Retroid: one setting "left,right" and the colours "#left,#right"
                val en = Device.sys(this, "joystick_light_enabled") ?: "1,1"
                val col = Device.sys(this, "joystick_led_light_picker_color")
                val red = Root.putSetting("system", "joystick_led_light_picker_color", "#ffff0000,#ffff0000")
                for (v in listOf("1,0", "0,1")) probes += Probe("Retroid: joystick_light_enabled=$v", listOf(Root.putSetting("system", "joystick_light_enabled", v), red), emptyList())
                allOn = listOf(Root.putSetting("system", "joystick_light_enabled", "1,1"))
                restore += Root.putSetting("system", "joystick_light_enabled", en)
                if (col != null) restore += Root.putSetting("system", "joystick_led_light_picker_color", col)
            }
            // the LED drivers: each one, each zone on its own (everything else dark)
            val drivers = Root.execLong("ls -d /sys/class/sn3112* 2>/dev/null", 3_000)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val dark = drivers.flatMap { dv -> (1..4).map { z -> "echo $z-0:0:0:255 > $dv/led/brightness" } }
            for (dv in drivers) for (z in 1..4)
                probes += Probe("${dv.substringAfterLast('/')} Zone $z", allOn + listOf("sleep 0.4") + dark + "echo $z-255:0:0:255 > $dv/led/brightness", dark)
            // other light drivers of the device (not the screen, flash or vibration)
            val leds = Root.execLong("ls /sys/class/leds/ 2>/dev/null", 3_000)?.lines()?.map { it.trim() }?.filter {
                it.isNotEmpty() && !Regex("lcd|backlight|wled|flash|torch|vibrator|haptic|mmc|charging|battery", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }.orEmpty().take(8)
            leds.forEach { l ->
                val max = Root.exec("cat /sys/class/leds/$l/max_brightness")?.trim()?.toIntOrNull() ?: 255
                val was = Root.exec("cat /sys/class/leds/$l/brightness")?.trim()?.toIntOrNull() ?: 0
                probes += Probe("leds/$l", listOf("echo $max > /sys/class/leds/$l/brightness"), listOf("echo $was > /sys/class/leds/$l/brightness"))
            }
            Displays.ledProbe += "Treiber: ${drivers.joinToString().ifEmpty { "keine" }} · leds: ${leds.joinToString().ifEmpty { "keine" }}\n"
            runOnUiThread { probeStep(probes, 0, restore) }
        }.start()
    }

    private fun probeStep(probes: List<Probe>, i: Int, restore: List<String>) {
        if (i >= probes.size) {
            status(t("Beleuchtung wird zurückgestellt …", "Putting the lights back …"))
            Thread {
                Root.run(*restore.toTypedArray()) {
                    runCatching { if (Prefs(this).ledEffect != LedEffect.NONE) LedFx.resume(this) else LedFx.restoreStatic(this, delayMs = 400) }
                    runOnUiThread { showSummary() }
                }
            }.start()
            return
        }
        val p = probes[i]
        list.removeAllViews()
        list.addView(text(t("LED-Tester · ${i + 1}/${probes.size}", "LED tester · ${i + 1}/${probes.size}"), 18f, fg, true))
        list.addView(text(p.name, 13f, dim).apply { setPadding(0, d(4), 0, 0) })
        list.addView(text(t("Was leuchtet jetzt rot? Alles antippen, was leuchtet, dann Weiter.", "What lights up red now? Tap everything that lights, then Next."), 15f, fg).apply { setPadding(0, d(8), 0, d(10)) })
        val chosen = mutableSetOf<String>()
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lightNames.forEach { n ->
            lateinit var b: Button
            b = button(n) {
                if (!chosen.add(n)) chosen.remove(n)
                (b.background as GradientDrawable).setColor(if (n in chosen) 0xFF2E9E5B.toInt() else card)
            }
            row.addView(b, LinearLayout.LayoutParams(-1, d(44)).apply { bottomMargin = d(6) })
        }
        list.addView(row)
        Thread { Root.run(*p.on.toTypedArray()) }.start()
        fun next(skip: Boolean) {
            val seen = if (chosen.isEmpty()) t("nichts", "nothing") else lightNames.filter { it in chosen }.joinToString(", ")
            if (!skip) add(t("LED-Tester", "LED tester"), p.name, if (chosen.isEmpty()) CheckState.WARN else CheckState.OK, "→ $seen")
            Displays.ledProbe += "${p.name} → $seen\n"
            Thread { if (p.off.isNotEmpty()) Root.run(*p.off.toTypedArray()) }.start()
            probeStep(probes, i + 1, restore)
        }
        setButtons(
            button(t("Abbrechen", "Cancel")) { probeStep(probes, probes.size, restore) },
            button(t("Weiter", "Next"), primary = true) { next(false) },
        )
    }

    // ---- summary ----

    private fun showSummary() {
        list.removeAllViews()
        val all = synchronized(checks) { checks.toList() }
        val okN = all.count { it.state == CheckState.OK }
        val warnN = all.count { it.state == CheckState.WARN }
        val failN = all.count { it.state == CheckState.FAIL }
        list.addView(text(t("Ergebnis", "Result"), 20f, fg, true))
        list.addView(text("✓ $okN   ⚠ $warnN   ✗ $failN", 16f, dim).apply { setPadding(0, d(4), 0, d(10)) })
        all.groupBy { it.area }.forEach { (area, items) ->
            list.addView(text(area, 14f, 0xFFE0303C.toInt(), true).apply { setPadding(0, d(12), 0, d(4)) })
            items.forEach { c ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { setColor(card); cornerRadius = d(12).toFloat() }
                    setPadding(d(14), d(8), d(14), d(8))
                }
                val icon = when (c.state) { CheckState.OK -> "✓"; CheckState.WARN -> "⚠"; CheckState.FAIL -> "✗" }
                val col = when (c.state) { CheckState.OK -> 0xFF4CC38A.toInt(); CheckState.WARN -> 0xFFF0B429.toInt(); CheckState.FAIL -> 0xFFFF5C5C.toInt() }
                row.addView(text("$icon  ${c.name}", 15f, col, true))
                if (c.note.isNotEmpty()) row.addView(text(c.note, 12f, dim))
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6) })
            }
        }
        scroll.scrollTo(0, 0)
        setButtons(
            button(t("Schließen", "Close")) { finish() },
            button(t("Kopieren", "Copy")) {
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Hugminn", report(all)))
                android.widget.Toast.makeText(this, t("Kopiert", "Copied"), android.widget.Toast.LENGTH_SHORT).show()
            },
            button(t("Auf GitHub melden", "Report on GitHub"), primary = true) { share(all) },
        )
    }

    private fun report(all: List<DeviceCheck>) = DeviceReport.text(this, all)

    private fun share(all: List<DeviceCheck>) = DeviceReport.share(this, all)

    override fun onDestroy() {
        keyTest = null
        HugminnService.keyProbe = null
        super.onDestroy()
    }
}

enum class CheckState { OK, WARN, FAIL }
class DeviceCheck(val area: String, val name: String, val state: CheckState, val note: String)

/** The automatic checks and the report, shared by the device test and the setup wizard. */
object DeviceReport {
    fun auto(ctx: Context, sink: (String, String, CheckState, String) -> Unit): Unit = with(ctx) {
        fun add(area: String, name: String, state: CheckState, note: String = "") = sink(area, name, state, note)
        fun ok(b: Boolean) = if (b) CheckState.OK else CheckState.FAIL
        val windowManager = getSystemService(android.view.WindowManager::class.java)
        val dev = t("Gerät", "Device")
        val m = resources.displayMetrics
        val real = android.graphics.Point().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it) }
        val w = maxOf(real.x, real.y); val h = minOf(real.x, real.y)
        val rates = runCatching { @Suppress("DEPRECATION") windowManager.defaultDisplay.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted() }.getOrDefault(emptyList())
        add(dev, "${Build.MANUFACTURER} ${Build.MODEL}", CheckState.OK, "Android ${Build.VERSION.RELEASE}")
        add(dev, t("Bildschirm", "Screen"), CheckState.OK, "${w}×$h · " + "%.2f".format(w.toFloat() / h) + ":1 · ${rates.joinToString("/")} Hz · " + "%.2f".format(m.density))
        // dual-screen devices: the second built-in screen (for the coming dual-screen support)
        Displays.list(this).filter { !it.isDefault }.forEach { d ->
            add(dev, t("Weiterer Bildschirm", "Further screen") + " #${d.id}", if (d.isPrivate) CheckState.WARN else CheckState.OK,
                "\"${d.name}\" ${maxOf(d.width, d.height)}×${minOf(d.width, d.height)} · ${d.rates.joinToString("/")} Hz · ${d.density} dpi" + if (d.isPrivate) t(" · privat", " · private") else "")
        }

        // permissions
        val perm = t("Berechtigungen", "Permissions")
        val root = Root.available()
        add(perm, t("Bedienungshilfe", "Accessibility service"), ok(HugminnService.instance != null), if (HugminnService.instance == null) t("Hugminn ist nicht eingeschaltet", "Hugminn is not switched on") else "")
        add(perm, "Root", ok(root), if (!root) t("Ohne Root fehlen Leistung, Lüfter, LEDs und mehr", "Without root, performance, fan, LEDs and more are missing") else "")
        add(perm, t("Hintergrund-Erlaubnis", "Background whitelist"), if (Device.whitelisted(this)) CheckState.OK else CheckState.WARN, if (!Device.whitelisted(this)) t("Das System könnte Hugminn beenden", "The system might end Hugminn") else "")
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        add(perm, t("Musik-Zugriff", "Music access"), if (listeners.contains(packageName)) CheckState.OK else CheckState.WARN, if (!listeners.contains(packageName)) t("Musikkachel zeigt evtl. keine Titel", "Music tile may show no titles") else "")
        add(perm, t("Mikrofon (für Spielton)", "Microphone (for game sound)"), if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0) CheckState.OK else CheckState.WARN,
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) t("Wird bei der ersten Aufnahme erteilt", "Granted on the first recording") else "")

        // the handheld's own settings
        val fw = if (Device.isAyn) t("AYN-Funktionen", "AYN features") else t("Retroid-Funktionen", "Retroid features")
        listOf(
            "performance_mode" to t("Leistungsprofile", "Performance profiles"),
            "fan_mode" to t("Lüfterprofile", "Fan profiles"),
            Device.controllerKey(this) to t("Tastenlayout / eingebaute Steuerung aus", "Button layout / built-in controls off"),
            "trigger_input_mode" to t("Trigger-Modus", "Trigger mode"),
            Device.stickLedKey(this) to t("Stick-LEDs", "Stick LEDs"),
            "is_charging_separation" to t("Direkte Stromversorgung", "Charging separation"),
        ).forEach { (key, name) ->
            val v = Device.sys(this, key)
            when {
                v != null -> add(fw, name, CheckState.OK, "$key = $v")
                // AYN writes the trigger mode only after it was changed once: the tile still works
                Device.lazySetting(key) -> add(fw, name, CheckState.OK, t("noch nie umgestellt – Standard", "never changed yet – default"))
                else -> add(fw, name, CheckState.FAIL, t("Einstellung fehlt – Kachel wird ausgeblendet", "Setting missing – the tile is hidden"))
            }
        }
        val ledDrv = root && Root.exec("test -e /sys/class/sn3112l/led/brightness && test -e /sys/class/sn3112r/led/brightness && echo yes")?.contains("yes") == true
        add(fw, t("LED-Treiber (Effekte)", "LED driver (effects)"), if (ledDrv) CheckState.OK else CheckState.WARN, if (!ledDrv) t("Effekte und Aufnahme-Pulsieren nicht möglich", "Effects and recording pulse not possible") else "sn3112")

        // live values
        val live = t("Messwerte", "Live values")
        val metrics = Metrics(this)
        runCatching { metrics.sample(); Thread.sleep(600) }
        val s = runCatching { metrics.sample() }.getOrNull()
        if (s != null) {
            add(live, "CPU", if (s.cpuMhz > 0) CheckState.OK else CheckState.WARN, if (s.cpuMhz > 0) "${s.cpuMhz} MHz · ${s.cpuPct.toInt()} %" else t("Takt nicht lesbar", "Clock not readable"))
            add(live, "GPU", if (File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").canRead() || s.gpuMhz > 0) CheckState.OK else CheckState.WARN, "${s.gpuPct.toInt()} % · ${s.gpuMhz} MHz")
            add(live, t("Temperaturen", "Temperatures"), if (s.cpuTemp > 0) CheckState.OK else CheckState.WARN, "CPU ${s.cpuTemp.toInt()} °C · GPU ${s.gpuTemp.toInt()} °C · " + t("Akku", "Battery") + " ${s.batTemp.toInt()} °C")
            add(live, t("Akku", "Battery"), if (s.batPct > 0) CheckState.OK else CheckState.WARN, "${s.batPct} % · " + "%.1f W".format(s.watts))
        } else add(live, t("Systemwerte", "System values"), CheckState.FAIL, t("Nicht lesbar", "Not readable"))
        val fan = runCatching { FanCurve.speedPct() }.getOrNull()
        add(live, t("Lüfterdrehzahl", "Fan speed"), if (fan != null) CheckState.OK else CheckState.WARN, fan?.let { "$it %" } ?: t("Nicht lesbar – Kachel zeigt nur das Profil", "Not readable – the tile only shows the profile"))
        val fps = root && Root.exec("service call SurfaceFlinger 1013", 1500)?.contains("Parcel") == true
        add(live, "FPS", ok(fps), if (!fps) t("Braucht Root", "Needs root") else "SurfaceFlinger")

        // recording
        val rec = t("Aufnahme", "Capture")
        val enc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        val caps = enc.mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        val p1080 = caps.any { runCatching { it.areSizeAndRateSupported(1920, 1080, 60.0) }.getOrDefault(false) }
        val p720 = caps.any { runCatching { it.areSizeAndRateSupported(1280, 720, 60.0) }.getOrDefault(false) }
        add(rec, "Video-Encoder", when { p1080 -> CheckState.OK; p720 -> CheckState.WARN; else -> CheckState.FAIL },
            when { p1080 -> "1080p · 60 FPS"; p720 -> t("Nur 720p · 60 FPS", "720p · 60 FPS only"); else -> t("Kein passender Encoder", "No suitable encoder") })
        add(rec, t("Spielton", "Game sound"), ok(Build.VERSION.SDK_INT >= 29), t("Manche Apps verbieten die Aufnahme", "Some apps don't allow recording"))

        // controllers
        val pad = "Controller"
        val devices = android.view.InputDevice.getDeviceIds().toList().mapNotNull { android.view.InputDevice.getDevice(it) }
        val gamepads = devices.filter { it.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD }
        val external = gamepads.filter { Device.isExternalPad(it.id) }
        val builtin = gamepads.filter { !Device.isExternalPad(it.id) }
        add(pad, t("Eingebaute Steuerung", "Built-in controls"), if (builtin.isNotEmpty()) CheckState.OK else CheckState.WARN,
            builtin.joinToString { "${it.name} (${it.vendorId}/${it.productId})" }.ifEmpty { t("Nicht erkannt (oder gerade aus)", "Not found (or switched off)") })
        if (external.isNotEmpty()) {
            val bats = runCatching { Bt.batteries(this) }.getOrDefault(emptyList())
            external.forEach { e ->
                val b = bats.firstOrNull { it.first.contains(e.name, true) || e.name.contains(it.first, true) }
                add(pad, e.name, if (b != null) CheckState.OK else CheckState.WARN, if (b != null) t("Akku", "Battery") + " ${b.second} %" else t("Meldet keinen Akkustand", "Reports no battery level"))
            }
        } else add(pad, t("Externer Controller", "External controller"), CheckState.OK, t("Keiner verbunden", "None connected"))
    }


    fun icon(s: CheckState) = when (s) { CheckState.OK -> "✓"; CheckState.WARN -> "⚠"; CheckState.FAIL -> "✗" }

    fun text(ctx: Context, all: List<DeviceCheck>) = buildString {
        appendLine("Hugminn ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?")} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        all.groupBy { it.area }.forEach { (area, items) ->
            appendLine(); appendLine("$area:")
            items.forEach { c -> appendLine("  ${icon(c.state)} ${c.name}${if (c.note.isNotEmpty()) " – ${c.note}" else ""}") }
        }
    }

    /** Opens a prefilled "Device report" issue on GitHub in the browser (the person sends it themselves). */
    fun share(act: android.app.Activity, all: List<DeviceCheck>) {
        val version = runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrDefault("")
        val url = "https://github.com/Ninte86hl/Hugminn/issues/new?template=device-report.yml" +
            "&title=" + Uri.encode("[Device] ${Build.MODEL}") +
            "&device=" + Uri.encode("${Build.MANUFACTURER} ${Build.MODEL}") +
            "&version=" + Uri.encode(version) +
            "&root=" + Uri.encode(if (Root.available()) "Rooted" else "No root") +
            "&results=" + Uri.encode("```\n" + text(act, all).take(5500) + "\n```")
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { android.widget.Toast.makeText(act, t("Kein Browser gefunden", "No browser found"), android.widget.Toast.LENGTH_LONG).show() }
    }
}

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
        if (intent.getBooleanExtra("double", false)) {
            add(t("Gerät", "Device"), "${Build.MANUFACTURER} ${Build.MODEL}", CheckState.OK, "Android ${Build.VERSION.RELEASE} · Firmware ${Build.DISPLAY}")
            startDoubleTest()
            return
        }
        status(t("Prüfe … (etwa 10 Sekunden)", "Checking … (about 10 seconds)"))
        Thread { runAuto(); runOnUiThread { if (Device.isAyn) thorStart() else startKeyTest() } }.start()
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
        if (keyTest != null && event.keyCode == KeyEvent.KEYCODE_BUTTON_START && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount > 0 && event.eventTime - event.downTime >= 1000) { keyTestDone?.invoke(); return true }
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

    private val keys = linkedMapOf<Int, String>(
        KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B", KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1", KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_VOLUME_UP to "Vol+", KeyEvent.KEYCODE_VOLUME_DOWN to "Vol−",
        KeyEvent.KEYCODE_HOME to "Home", KeyEvent.KEYCODE_BACK to t("Zurück", "Back"),
    ).apply { if (Device.noSticks) { remove(KeyEvent.KEYCODE_BUTTON_THUMBL); remove(KeyEvent.KEYCODE_BUTTON_THUMBR) } }

    /** Start held for a second ends a step early and goes on with the test (A and B are under test). */
    private var keyTestDone: (() -> Unit)? = null

    /**
     * Guided button test: asks for one button after the other and notes what really arrives
     * (code, scan code, device). A button that sends something else – swapped A/B, B as Back
     * from a maker's key mapper – shows up in the report. Start held for a second ends it early,
     * a button not pressed within 8 seconds is skipped.
     */
    private fun startKeyTest() {
        val order = keys.entries.toList()
        val rows = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var idx = 0
        var done = false
        var askedAt = 0L
        list.removeAllViews()
        list.addView(text(t("Tasten: drücke die angezeigte Taste", "Buttons: press the button shown"), 18f, fg, true))
        list.addView(text(t("Eine Taste nach der anderen. Nicht vorhanden: 8 Sekunden warten, dann geht es weiter. Start 1 Sekunde halten beendet den Test.",
            "One button after the other. Not there: wait 8 seconds and it moves on. Holding Start for a second ends the test."), 13f, dim).apply { setPadding(0, d(4), 0, d(12)) })
        val prompt = text("", 34f, fg, true).apply { gravity = Gravity.CENTER; setPadding(0, d(10), 0, d(4)) }
        val progress = text("", 13f, dim).apply { gravity = Gravity.CENTER }
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(12), 0, 0) }
        list.addView(prompt); list.addView(progress); list.addView(lines)
        val timeout = Runnable { }
        var skipTask: Runnable = timeout
        fun finishKeys() {
            if (done) return
            done = true
            list.removeCallbacks(skipTask)
            keyTest = null; keyTestDone = null
            HugminnService.keyProbe = null; HugminnService.keyTap = null
            val state = if (wrong.isEmpty() && skipped.isEmpty()) CheckState.OK else CheckState.WARN
            val head = when {
                wrong.isEmpty() && skipped.isEmpty() -> t("Alle ${order.size} richtig", "All ${order.size} correct")
                else -> listOfNotNull(wrong.takeIf { it.isNotEmpty() }?.let { t("Anders: ", "Different: ") + it.joinToString() },
                    skipped.takeIf { it.isNotEmpty() }?.let { t("Nicht gedrückt: ", "Not pressed: ") + it.joinToString() }).joinToString(" · ")
            }
            add(t("Tasten", "Buttons"), t("Tasten", "Buttons"), state, head)
            add(t("Tasten", "Buttons"), t("Tastencodes", "Button codes"), CheckState.OK, rows.joinToString(" · "))
            startExtraKeys()
        }
        fun show() {
            if (idx >= order.size) { finishKeys(); return }
            askedAt = android.os.SystemClock.uptimeMillis()
            prompt.text = order[idx].value
            progress.text = "${idx + 1} / ${order.size}"
            list.removeCallbacks(skipTask)
            skipTask = Runnable {
                if (!done) { skipped += order[idx].value; rows += "${order[idx].value}→–"; idx++; show() }
            }
            list.postDelayed(skipTask, 8_000)
        }
        // a stick click (L3/R3) nudges the stick too: only the first key of a press counts, and the
        // next button is asked for once everything is released again
        val held = mutableSetOf<Int>()
        var waitRelease = false
        fun got(code: Int, scan: Int, dev: String?) {
            if (done || idx >= order.size || waitRelease) return
            val (want, name) = order[idx]
            // tilting a stick while clicking it sends D-pad keys: they don't answer for L3/R3 or any other button
            val dpad = code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
            val wantDpad = want in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            if (dpad && !wantDpad) return
            // a moment after the last button: leftovers of it don't count for the next one
            if (android.os.SystemClock.uptimeMillis() - askedAt < 500) return
            val got = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            // Home: whatever the maker sends for it (Home, F10 on Anbernic, Back on the Pocket Classic) is fine
            val ok = code == want || (want == KeyEvent.KEYCODE_HOME && code in setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_BACK))
            rows += "$name→$got($code/$scan)"
            if (!ok) wrong += "$name→$got"
            lines.addView(text("$name  →  $got ($code, scan $scan${dev?.let { " · $it" } ?: ""})", 14f, if (ok) fg else 0xFFE0303C.toInt()).apply { setPadding(0, d(2), 0, d(2)) }, 0)
            idx++
            if (held.isEmpty()) list.postDelayed({ show() }, 350) else { waitRelease = true; list.removeCallbacks(skipTask) }
        }
        fun released(code: Int) {
            held.remove(code)
            if (waitRelease && held.isEmpty()) { waitRelease = false; list.postDelayed({ if (!done) show() }, 350) }
        }
        if (HugminnService.instance != null) {
            // raw, before anything of Hugminn: the service hands over every key
            val startDown = longArrayOf(0)
            HugminnService.keyTap = { e ->
                if (e.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) startDown[0] = e.eventTime
                    if (e.action == KeyEvent.ACTION_UP) {
                        if (e.eventTime - startDown[0] >= 1000) finishKeys() else got(e.keyCode, e.scanCode, e.device?.name)
                    }
                } else if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    val first = held.isEmpty()
                    held += e.keyCode
                    if (first) got(e.keyCode, e.scanCode, e.device?.name)
                } else if (e.action == KeyEvent.ACTION_UP) released(e.keyCode)
            }
        } else {
            HugminnService.keyProbe = { code -> got(code, 0, null) }
            keyTest = { code -> got(code, 0, null); true }
            keyTestDone = { finishKeys() }
        }
        setButtons(button(t("Überspringen", "Skip"), primary = false) { list.removeCallbacks(skipTask); skipTask.run() },
            button(t("Weiter", "Next"), primary = true) { finishKeys() })
        show()
    }

    /**
     * Extra buttons (M1, M2, back paddles, a Home that is also Back): every key that arrives is listed
     * with its code, scan code, device and how long it was held, so the report shows what the
     * firmware makes of them. Hugminn itself ignores all keys meanwhile (power aside).
     */
    private fun startExtraKeys() {
        list.removeAllViews()
        list.addView(text(t("Zusatztasten", "Extra buttons"), 18f, fg, true))
        list.addView(text(t("Drück jetzt jede zusätzliche Taste (M1, M2, Paddles …) einmal kurz und einmal lang, dazu Home kurz und lang. " +
            "Jede Taste erscheint hier mit ihrem Code. Weiter mit Start 1 Sekunde halten, dem Knopf unten oder nach 40 Sekunden.",
            "Now press every extra button (M1, M2, paddles …) once briefly and once long, and Home briefly and long. " +
                "Each shows up here with its code. Move on by holding Start for a second, with the button below or after 40 seconds."), 13f, dim).apply { setPadding(0, d(4), 0, d(10)) })
        val log = mutableListOf<String>()
        val downAt = HashMap<Int, Long>()
        var lastUp = 0L
        var gap = -1L
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(lines)
        var done = false
        fun finish() {
            if (done) return
            done = true
            HugminnService.keyTap = null
            add(t("Tasten", "Buttons"), t("Zusatztasten", "Extra buttons"), CheckState.OK,
                if (log.isEmpty()) t("keine gedrückt", "none pressed") else log.joinToString(" | "))
            startLedTest()
        }
        HugminnService.keyTap = { e ->
            if (!done) {
                val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
                when (e.action) {
                    KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                        downAt[e.keyCode] = e.eventTime
                        // pause since the last release: shows how fast a double click really is
                        gap = if (lastUp > 0) e.eventTime - lastUp else -1L
                    }
                    KeyEvent.ACTION_UP -> {
                        val ms = downAt.remove(e.keyCode)?.let { e.eventTime - it } ?: 0L
                        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_START && ms >= 1000) finish() else {
                            lastUp = e.eventTime
                            val entry = "$name (${e.keyCode}) scan ${e.scanCode} · ${e.device?.name ?: "?"} · $ms ms" +
                                (if (gap in 0..2000) " · Pause $gap ms" else "")
                            log += entry
                            lines.addView(text(entry, 14f, fg).apply { setPadding(0, d(3), 0, d(3)) }, 0)
                        }
                    }
                }
            }
        }
        setButtons(button(t("Weiter", "Next"), primary = true) { finish() })
        list.postDelayed({ finish() }, 40_000)
    }

    /**
     * Double-click test (Pocket Classic): every key with the time since the test began and since the
     * key before, down and up, so the report shows what the firmware really sends on a double click
     * and when. Hugminn itself does nothing meanwhile.
     */
    private fun startDoubleTest() {
        list.removeAllViews()
        list.addView(text(t("Doppelklick-Test", "Double-click test"), 18f, fg, true))
        list.addView(text(t("Drück die Taste unter dem Bildschirm etwa fünfmal doppelt – so, wie du den Game Switcher öffnen würdest – mit ein paar Sekunden Pause dazwischen. " +
            "Danach einmal einzeln und einmal lang. Weiter mit Start 1 Sekunde halten, dem Knopf unten oder nach 60 Sekunden.",
            "Double-click the button below the screen about five times – the way you'd open the Game Switcher – with a few seconds in between. " +
                "Then once single and once long. Move on by holding Start for a second, with the button below or after 60 seconds."), 13f, dim).apply { setPadding(0, d(4), 0, d(10)) })
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(lines)
        val log = mutableListOf<String>()
        val start = android.os.SystemClock.uptimeMillis()
        var last = 0L
        var lastUp = 0L
        val startDown = HashMap<Int, Long>()
        var done = false
        fun finish() {
            if (done) return
            done = true
            HugminnService.keyTap = null
            add(t("Tasten", "Buttons"), t("Doppelklick", "Double click"), CheckState.OK,
                if (log.isEmpty()) t("keine gedrückt", "none pressed") else log.joinToString(" | "))
            showSummary()
        }
        HugminnService.keyTap = { e ->
            if (!done) {
                if (e.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) startDown[0] = e.eventTime
                    if (e.action == KeyEvent.ACTION_UP && e.eventTime - (startDown[0] ?: e.eventTime) >= 1000) finish()
                } else if (e.repeatCount == 0) {
                    val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
                    val down = e.action == KeyEvent.ACTION_DOWN
                    val since = if (last > 0) e.eventTime - last else 0L
                    var entry = "+${e.eventTime - start} ms $name ${if (down) "↓" else "↑"} (Δ $since)"
                    if (down && lastUp > 0) {
                        val pause = e.eventTime - lastUp
                        if (pause < prefs.doubleMs) entry += t(" ⇒ Doppelklick", " ⇒ double click")
                    }
                    if (!down) lastUp = e.eventTime
                    last = e.eventTime
                    log += entry
                    lines.addView(text(entry, 14f, if (entry.contains("⇒")) 0xFF2E9E5B.toInt() else fg).apply { setPadding(0, d(2), 0, d(2)) }, 0)
                }
            }
        }
        setButtons(button(t("Weiter", "Next"), primary = true) { finish() })
        list.postDelayed({ finish() }, 60_000)
    }

    private val prefs by lazy { Prefs(this) }

    private fun startLedTest() {
        // AYN Thor (two screens): first learn how its lower panel is driven
        if (Device.isAnbernic && !Root.available() && AnbernicHelper.alive(this)) { anbernicLed(); return }
        if (!Root.available() || !Device.hasStickLeds(this)) { showSummary(); return }
        list.removeAllViews()
        list.addView(text(t("Stick-LEDs", "Stick LEDs"), 18f, fg, true))
        list.addView(text(t("Die Sticks leuchten jetzt rot. Siehst du es?", "The sticks light up red now. Can you see it?"), 15f, fg).apply { setPadding(0, d(6), 0, 0) })
        LedFx.previewZone = if (Device.hasSideLights(this)) 2 else 1  // Odin 2: zone 2 = sticks
        // the LEDs only show anything while their power switch is on: on for the test, back afterwards
        val before = Device.elStates(this)
        val stickOn = if (Device.hasSideLights(this)) Device.stickLedCmds(this, true).toList()
            else listOf(Root.putSetting("system", "joystick_light_enabled", "1,1"))
        Root.run(*stickOn.toTypedArray()) { runOnUiThread { LedFx.preview(Color.RED, Color.RED, 1f) } }
        fun answer(seen: Boolean) {
            add(if (Device.isAyn) t("AYN-Funktionen", "AYN features") else t("Retroid-Funktionen", "Retroid features"), t("Stick-LEDs leuchten", "Stick LEDs light up"), if (seen) CheckState.OK else CheckState.FAIL,
                if (seen) "" else t("Farben werden nicht übernommen", "Colours are not applied"))
            // the lights as they were: power switches and the elements' own modes
            runCatching { Device.setElStates(this, before) {} }
            startLedProbe()
        }
        setButtons(button(t("Nein", "No")) { answer(false) }, button(t("Ja", "Yes"), primary = true) { answer(true) })
    }

    // ---- AYN Thor: the lower screen's panel ----

    /** AYN: the lower-screen round comes first; it says why when it cannot run. */
    private fun thorStart() {
        val second = Displays.second(this)
        val root = Root.available()
        if (second != null && root) { thorLower(); return }
        val why = if (second == null) t("Kein zweiter Bildschirm erkannt (ist er an?)", "No second screen found (is it on?)")
            else t("Kein Root (Root-Dienst der Firmware fehlt)", "No root (the firmware's root service is missing)")
        add(t("Thor unten", "Thor lower"), t("Test", "Test"), CheckState.FAIL, why)
        list.removeAllViews()
        list.addView(text(t("Unterer Bildschirm", "Lower screen"), 18f, fg, true))
        list.addView(text(why, 15f, fg).apply { setPadding(0, d(6), 0, 0) })
        setButtons(button(t("Weiter", "Next"), true) { startKeyTest() })
    }

    /**
     * Finds the lower screen's backlight: lists the panels (and the input devices and settings that may
     * belong to it), then dims each candidate for a moment and switches it off for two seconds, asking
     * what happened. Everything goes back as it was.
     */
    private fun thorLower() {
        list.removeAllViews()
        list.addView(text(t("Unterer Bildschirm", "Lower screen"), 18f, fg, true))
        list.addView(text(t("Einen Moment, Hugminn sucht die Steuerung …", "One moment, Hugminn looks for its controls …"), 15f, fg).apply { setPadding(0, d(6), 0, 0) })
        setButtons()
        Thread {
            val area = t("Thor unten", "Thor lower")
            // long pipelines only work as a script
            val body = "for b in /sys/class/backlight/* /sys/class/leds/*backlight* /sys/class/leds/*lcd* /sys/class/leds/*panel*; do " +
                "[ -e \"\$b/brightness\" ] && echo \"\$b max=\$(cat \$b/max_brightness) cur=\$(cat \$b/brightness) power=\$(cat \$b/bl_power 2>/dev/null)\"; done\n" +
                "echo leds: \$(ls /sys/class/leds | tr '\\n' ' ')\n" +
                "echo backlight: \$(ls /sys/class/backlight 2>&1 | tr '\\n' ' ')\n" +
                "echo input: \$(grep -E '^N: Name' /proc/bus/input/devices | cut -d= -f2 | tr '\\n' ',')\n" +
                "settings list system | grep -iE 'second|sub_|dual|lower|bottom|screen2|display2|bright'\n" +
                "settings list global | grep -iE 'second|dual|lower|bottom|screen2'\n" +
                "dumpsys display | grep -iE 'mBrightness=|mScreenBrightness|brightness=' | head -n 12\n" +
                "getprop | grep -iE 'second|dual|sub_|screen2|lcd2|panel' | head -n 15\n"
            val info = Root.script("thorlower", body)?.let { Root.execLong(it) }.orEmpty()
            add(area, t("Gefunden", "Found"), CheckState.OK, info.lines().filter { it.isNotBlank() }.joinToString(" | ").take(2500))
            val panels = Regex("(/sys/class/(?:backlight|leds)/[^ ]+) max=(\\d+) cur=(\\d+)").findAll(info).map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.distinctBy { it.first }.toList()
            runOnUiThread { thorPanel(panels, 0, area) }
        }.start()
    }

    private fun thorPanel(panels: List<Triple<String, Int, Int>>, i: Int, area: String) {
        if (panels.isEmpty()) add(area, t("Panels", "Panels"), CheckState.FAIL, t("keine Hintergrundbeleuchtung gefunden", "no backlight found"))
        if (i >= panels.size) { startKeyTest(); return }
        val (path, max, cur) = panels[i]
        val name = path.substringAfterLast('/')
        // dim to a tenth for the question, then back
        Root.run("echo ${(max / 10).coerceAtLeast(1)} > $path/brightness")
        list.removeAllViews()
        list.addView(text(t("Panel ${i + 1}/${panels.size}: $name", "Panel ${i + 1}/${panels.size}: $name"), 18f, fg, true))
        list.addView(text(t("Welcher Bildschirm ist gerade dunkler geworden?", "Which screen just got darker?"), 15f, fg).apply { setPadding(0, d(8), 0, d(10)) })
        fun answer(a: String) {
            Root.run("echo $cur > $path/brightness")
            add(area, t("Helligkeit $name", "Brightness $name"), if (a == "lower") CheckState.OK else CheckState.WARN, "→ $a (max $max)")
            if (a == "lower") thorOff(panels, i, area) else thorPanel(panels, i + 1, area)
        }
        listOf(t("Der untere", "The lower one") to "lower", t("Der obere", "The top one") to "top", t("Keiner", "Neither") to "none").forEach { (label, v) ->
            list.addView(button(label) { answer(v) }, LinearLayout.LayoutParams(-1, d(44)).apply { bottomMargin = d(6) })
        }
        setButtons()
    }

    /** The lower panel switched off for two seconds (bl_power 4), then on again. */
    private fun thorOff(panels: List<Triple<String, Int, Int>>, i: Int, area: String) {
        val path = panels[i].first
        list.removeAllViews()
        list.addView(text(t("Unterer Bildschirm aus", "Lower screen off"), 18f, fg, true))
        list.addView(text(t("Der untere Bildschirm geht gleich für 2 Sekunden aus. Achte darauf.", "The lower screen goes off for 2 seconds now. Watch it."), 15f, fg).apply { setPadding(0, d(8), 0, d(10)) })
        setButtons()
        val cur = panels[i].third
        // backlight class: bl_power 4 = off; leds class has no bl_power, brightness 0 instead
        if (path.contains("/leds/")) Root.run("echo 0 > $path/brightness; sleep 2; echo $cur > $path/brightness")
        else Root.run("echo 4 > $path/bl_power; sleep 2; echo 0 > $path/bl_power")
        list.postDelayed({
            list.removeAllViews()
            list.addView(text(t("Ging der untere Bildschirm aus und wieder an?", "Did the lower screen go off and on again?"), 15f, fg).apply { setPadding(0, d(8), 0, d(10)) })
            fun answer(a: String, st: CheckState) { add(area, t("Ausschalten (bl_power)", "Switching off (bl_power)"), st, "→ $a"); thorVolume(area) }
            listOf(t("Ja, aus und wieder an", "Yes, off and on again") to CheckState.OK, t("Ging aus, blieb aber aus", "Went off but stayed off") to CheckState.WARN,
                t("Nur dunkel, nicht aus", "Only dark, not off") to CheckState.WARN, t("Nichts passiert", "Nothing happened") to CheckState.FAIL).forEach { (label, st) ->
                list.addView(button(label) {
                    if (st == CheckState.WARN) Root.run(if (path.contains("/leds/")) "echo $cur > $path/brightness" else "echo 0 > $path/bl_power")
                    answer(label, st)
                }, LinearLayout.LayoutParams(-1, d(44)).apply { bottomMargin = d(6) })
            }
        }, 3_000)
    }

    /** Thor: which settings change when the lower screen's volume is set in AYN's own menu (before/after). */
    private fun thorVolume(area: String) {
        val snap = "for t in system global secure; do settings list \$t | sed \"s/^/\$t:/\"; done; getprop | grep -iE 'vol|audio|second|dual' | sed 's/^/prop:/'\n"
        list.removeAllViews()
        list.addView(text(t("Lautstärke unten", "Volume below"), 18f, fg, true))
        list.addView(text(t("Hugminn merkt sich die Einstellungen. Tippe auf „Los“, stelle dann in AYNs eigenem Menü die Lautstärke für den unteren Bildschirm deutlich anders ein, komm zurück und tippe auf „Fertig“.",
            "Hugminn notes the settings. Tap \"Go\", then set the lower screen's volume clearly differently in AYN's own menu, come back and tap \"Done\"."), 15f, fg).apply { setPadding(0, d(8), 0, d(10)) })
        var before: Set<String> = emptySet()
        fun done() {
            setButtons()
            Thread {
                val after = Root.script("thorsnap", snap)?.let { Root.execLong(it) }.orEmpty().lines().filter { it.isNotBlank() }.toSet()
                val diff = (after - before).filter { !it.contains("_time") && !it.contains("timestamp") }
                add(area, t("Lautstärke unten", "Volume below"), if (diff.isEmpty()) CheckState.WARN else CheckState.OK,
                    if (diff.isEmpty()) t("keine Einstellung geändert", "no setting changed")
                    else diff.joinToString(" | ") { l -> val k = l.substringBefore('='); (before.firstOrNull { it.startsWith("$k=") }?.substringAfter('=') ?: "neu") + " → " + l }.take(1500))
                runOnUiThread { startKeyTest() }
            }.start()
        }
        setButtons(button(t("Überspringen", "Skip")) { add(area, t("Lautstärke unten", "Volume below"), CheckState.WARN, t("übersprungen", "skipped")); startKeyTest() },
            button(t("Los", "Go"), true) {
                setButtons()
                Thread {
                    before = Root.script("thorsnap", snap)?.let { Root.execLong(it) }.orEmpty().lines().filter { it.isNotBlank() }.toSet()
                    runOnUiThread { setButtons(button(t("Fertig", "Done"), true) { done() }) }
                }.start()
            })
    }

    // ---- LED tester for Anbernic (through its helper: the controller's own light settings) ----

    private class AStep(val name: String, val writes: List<Pair<String, Int>>, val ask: String, val answers: List<String>)

    /**
     * Anbernic: the stick lights hang on the controller driver (singleadc-joypad) – colour per stick,
     * mode, speed, brightness. Each step writes some of them and asks what changed, so Hugminn learns
     * how to drive them itself (own colours per stick instead of Anbernic's six effects).
     */
    private fun anbernicLed() {
        val before = AnbernicHelper.joypadNow()
        val colour = t("Was zeigen die Sticks?", "What do the sticks show?")
        val colours = listOf(t("Links rot, rechts blau", "Left red, right blue"), t("Beide dieselbe Farbe", "Both the same colour"),
            t("Keine Änderung", "No change"), t("Etwas anderes", "Something else"))
        val look = t("Was macht das Licht?", "What does the light do?")
        val looks = listOf(t("Leuchtet ruhig", "Steady"), t("Atmet / pulsiert", "Breathes / pulses"), t("Regenbogen / Farbwechsel", "Rainbow / colour change"),
            t("Läuft im Kreis", "Runs round"), t("Aus", "Off"), t("Etwas anderes", "Something else"))
        // Anbernic's own light service steers the LEDs all the time (led_level reads back changing values):
        // with its lighting switched off in the settings it should leave them to us
        val off = listOf("set:led_enable" to 0)
        // round 5: Anbernic's own "Steady" is led_mode 1 with speed 0, its colour in custum_rgb and in both
        // sticks' Led_rgb. So: steady with a different colour per stick, then breathing through the speed
        val what = t("Was siehst du?", "What do you see?")
        val answers = listOf(t("Links rot, rechts blau – ruhig", "Left red, right blue – steady"), t("Links rot, rechts blau – atmet", "Left red, right blue – breathes"),
            t("Beide rot", "Both red"), t("Beide blau", "Both blue"), t("Beide grün", "Both green"), t("Andere Farbe", "Another colour"), t("Aus", "Off"))
        val split = listOf("led_sync_colour" to 0, "custum_rgb_r" to 255, "custum_rgb_g" to 0, "custum_rgb_b" to 0,
            "Led_rgb_r1" to 255, "Led_rgb_g1" to 0, "Led_rgb_b1" to 0, "Led_rgb_r2" to 0, "Led_rgb_g2" to 0, "Led_rgb_b2" to 255)
        // round 6: every mode of the controller itself, so Hugminn can run Anbernic's effects without its service.
        // Two colours (red / blue) and a middle speed, so colour changes and movement both show.
        val modeLooks = listOf(t("Leuchtet ruhig", "Steady"), t("Atmet / pulsiert", "Breathes / pulses"),
            t("Wechselt zwischen Rot und Blau", "Changes between red and blue"), t("Regenbogen – alle Farben", "Rainbow – all colours"),
            t("Läuft im Kreis – Rot/Blau", "Runs round – red/blue"), t("Läuft im Kreis – Regenbogen", "Runs round – rainbow"),
            t("Blinkt", "Flashes"), t("Aus", "Off"), t("Etwas anderes", "Something else"))
        val steps = mutableListOf<AStep>()
        for (m in 0..10) {
            val w = (if (m == 0) off + listOf("led_switch" to 1, "led_level" to 80) + split else emptyList()) +
                listOf("led_mode" to m, "led_speed" to 3, "led_set" to 1)
            steps += AStep(t("Modus $m", "Mode $m"), w, look, modeLooks)
        }
        steps += AStep(t("Modus 1, Tempo 8", "Mode 1, speed 8"), listOf("led_mode" to 1, "led_speed" to 8, "led_set" to 1),
            t("Schneller als bei Modus 1 vorhin?", "Faster than mode 1 before?"), listOf(t("Schneller", "Faster"), t("Langsamer", "Slower"), t("Gleich", "The same")))
        aStep(steps, 0, before)
    }

    private fun aStep(steps: List<AStep>, i: Int, before: Map<String, Int>) {
        if (i >= steps.size) {
            // back as it was (the order matters little: everything is written once more)
            before.forEach { (k, v) -> AnbernicHelper.joypad(k, v) }
            // Anbernic's own lighting back on: it takes over again
            AnbernicHelper.put(this, "led_enable", "1")
            showSummary()
            return
        }
        val s = steps[i]
        s.writes.forEach { (k, v) -> if (k.startsWith("set:")) AnbernicHelper.put(this, k.removePrefix("set:"), "$v") else AnbernicHelper.joypad(k, v) }
        list.removeAllViews()
        list.addView(text(t("LED-Tester · ${i + 1}/${steps.size}", "LED tester · ${i + 1}/${steps.size}"), 18f, fg, true))
        list.addView(text(s.name + " – " + s.writes.joinToString { "${it.first}=${it.second}" }, 13f, dim).apply { setPadding(0, d(4), 0, 0) })
        list.addView(text(s.ask, 15f, fg).apply { setPadding(0, d(8), 0, d(10)) })
        s.answers.forEach { a ->
            list.addView(button(a) {
                add(t("LED-Tester", "LED tester"), s.name, if (a.startsWith(t("Keine", "No"))) CheckState.WARN else CheckState.OK, "→ $a")
                aStep(steps, i + 1, before)
            }, LinearLayout.LayoutParams(-1, d(44)).apply { bottomMargin = d(6) })
        }
        setButtons(button(t("Abbrechen", "Cancel")) { aStep(steps, steps.size, before) })
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
        HugminnService.keyTap = null
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
        add(dev, "${Build.MANUFACTURER} ${Build.MODEL}", CheckState.OK, "Android ${Build.VERSION.RELEASE} · Firmware ${Build.DISPLAY} (${Build.TIME.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(it)) }})")
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
        if (!root) add(perm, t("Systemeinstellungen schreiben", "Write system settings"), if (Device.canWrite(this)) CheckState.OK else CheckState.WARN,
            if (Device.canWrite(this)) t("Helfer läuft – Leistung, Lüfter, Beleuchtung ohne Root", "Helper running – performance, fan, lighting without root")
            else t("Helfer läuft nicht (Anbernic: Skript von der Startseite, nach Neustart ▶)", "Helper not running (Anbernic: script from the start page, ▶ after a restart)"))
        if (!root && Device.isAnbernic) add(perm, t("Helfer-Protokoll", "Helper log"), CheckState.OK, AnbernicHelper.report(this))
        add(perm, t("Hintergrund-Erlaubnis", "Background whitelist"), if (Device.whitelisted(this)) CheckState.OK else CheckState.WARN, if (!Device.whitelisted(this)) t("Das System könnte Hugminn beenden", "The system might end Hugminn") else "")
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        add(perm, t("Musik-Zugriff", "Music access"), if (listeners.contains(packageName)) CheckState.OK else CheckState.WARN, if (!listeners.contains(packageName)) t("Musikkachel zeigt evtl. keine Titel", "Music tile may show no titles") else "")
        add(perm, t("Mikrofon (für Spielton)", "Microphone (for game sound)"), if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0) CheckState.OK else CheckState.WARN,
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) t("Wird bei der ersten Aufnahme erteilt", "Granted on the first recording") else "")

        // the handheld's own settings
        val fw = if (Device.isAyn) t("AYN-Funktionen", "AYN features") else t("Retroid-Funktionen", "Retroid features")
        listOf(
            Device.perfKey to t("Leistungsprofile", "Performance profiles"),
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

        // other makers (Anbernic …): which of their own settings exist – the way to their performance, fan and lights
        if (!Device.isRetroid && !android.os.Build.MANUFACTURER.contains("AYN", true)) {
            val words = Regex("perf|fan|cpu|gpu|gov|freq|light|led|rgb|joy|stick|key|button|home|charg|power|mode|anbernic|rg_", RegexOption.IGNORE_CASE)
            // only "system": that is where the makers keep theirs (global/secure made the report too long for GitHub's link)
            listOf("system" to android.provider.Settings.System.CONTENT_URI).forEach { (name, uri) ->
                val found = runCatching {
                    contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                        buildList { while (c.moveToNext()) { val k = c.getString(0) ?: continue; if (words.containsMatchIn(k)) add("$k=${c.getString(1)}") } }
                    }
                }.getOrNull()
                add(t("Systemeinstellungen", "System settings"), name, if (found.isNullOrEmpty()) CheckState.WARN else CheckState.OK,
                    found?.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(" · ") ?: t("nicht lesbar oder leer", "not readable or empty"))
            }
        }
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

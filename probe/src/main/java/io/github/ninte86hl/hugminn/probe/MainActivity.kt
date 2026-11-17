package io.github.ninte86hl.hugminn.probe

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Guided, controller-friendly test. Every step explains what to do; results go into one report
 * that is saved to Download/Hugminn and can be shared. Expert tools keep the manual functions.
 */
class MainActivity : Activity() {

    private val bgColor = 0xFF141218.toInt()
    private val surface = 0xFF211F26.toInt()
    private val surface2 = 0xFF36343B.toInt()
    private val onColor = 0xFFE6E0E9.toInt()
    private val onVar = 0xFFCAC4D0.toInt()
    private val accent = 0xFFE0262F.toInt()

    private lateinit var statusLine: TextView
    private lateinit var stepList: TextView
    private lateinit var titleView: TextView
    private lateinit var body: TextView
    private lateinit var actions: GridLayout
    private lateinit var detail: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val report = LinkedHashMap<String, String>()
    @Volatile private var busy = false
    private var lastUri: Uri? = null
    private var lastName: String? = null

    private enum class Step(val label: String) {
        INTRO("Start"), A11Y("Bedienungshilfe"), READ("Gerät einlesen"), KEYS("Tasten"),
        HOME("Home-Taste"), COMPARE("Einstellungen"), RECENTS("Android-Übersicht"), DONE("Bericht")
    }

    private var step = Step.INTRO
    private var expert = false

    // Key prompts
    private val prompts = listOf(
        "Untere Taste (Xbox A · PlayStation ✕ · Nintendo B)",
        "Rechte Taste (Xbox B · PlayStation ○ · Nintendo A)",
        "Linke Taste (Xbox X · PlayStation □ · Nintendo Y)",
        "Obere Taste (Xbox Y · PlayStation △ · Nintendo X)",
        "L1", "R1", "L2", "R2", "Start", "Select",
        "Linken Stick drücken (L3)", "Rechten Stick drücken (R3)", "Steuerkreuz nach oben",
        "Linken Stick nach rechts", "Rechten Stick nach rechts"
    )
    private var promptIndex = 0
    private var keyRound = 1
    private val keyResults = StringBuilder()
    private var ignoreUntil = 0L
    private val promptTimeout = Runnable { recordInput("nicht erkannt") }
    private val keyAxes = intArrayOf(
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS
    )

    // Home test
    private val homeTasks = listOf(
        "Home einmal kurz drücken",
        "Home lange gedrückt halten (ca. 1 Sekunde)",
        "Home zweimal schnell hintereinander drücken",
        "Abfang-Test: Home einmal kurz drücken. Die App versucht jetzt, die Taste abzufangen – bleibt sie offen?"
    )
    private val consumeTask = 3
    private var homeIndex = 0
    private var homeLeft = false
    private var homeRecorded = -1
    private val homeResults = StringBuilder()
    private var rawFile: String? = null
    private var round1Pad: String? = null

    // Setting comparisons
    private val compareTasks = listOf(
        "LED-Beleuchtung der Sticks ein- oder ausschalten (z. B. in den Schnelleinstellungen)",
        "Leistungsmodus ändern (z. B. Standard → Performance)",
        "Lüftermodus ändern",
        "Controller-Stil wechseln (Xbox ↔ Nintendo/Retroid)",
        "Eine App zur Hintergrund-Whitelist hinzufügen",
        "Option „Home zweimal drücken“ (Schutz vor versehentlichem Drücken) umschalten",
        "Bildwiederholrate ändern (z. B. 60 ↔ 120 Hz)"
    )
    private val controllerTask = 3
    private var compareIndex = 0
    private var snapA: Map<String, String>? = null
    private var controllerSwitched = false

    // Expert key test
    private var expertKeyTest = false
    private val expertKeys = ArrayDeque<String>()
    private val lastAxis = HashMap<Int, Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(bgColor)
        val pad = dp(20)
        val screenW = resources.displayMetrics.widthPixels

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgColor)
            setPadding(pad, pad, pad, pad)
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = "Hugminn Probe"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(onColor)
        })
        statusLine = TextView(this).apply { textSize = 13f; setTextColor(onVar); setPadding(0, dp(6), 0, dp(18)) }
        left.addView(statusLine)
        stepList = TextView(this).apply { textSize = 16f; setTextColor(onColor); setLineSpacing(dp(6).toFloat(), 1f) }
        left.addView(stepList)
        root.addView(left, LinearLayout.LayoutParams(maxOf(dp(220), (screenW * 0.28f).toInt()), MATCH_PARENT))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, dp(28))
            setPadding(dp(28), dp(24), dp(28), dp(20))
        }
        titleView = TextView(this).apply { textSize = 26f; setTextColor(onColor); setTypeface(typeface, Typeface.BOLD) }
        body = TextView(this).apply { textSize = 17f; setTextColor(onColor); setPadding(0, dp(10), 0, dp(14)); setLineSpacing(dp(3).toFloat(), 1f) }
        actions = GridLayout(this).apply { columnCount = 3 }
        detail = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(onVar) }
        val detailScroll = ScrollView(this).apply {
            isFocusable = false
            descendantFocusability = ScrollView.FOCUS_BLOCK_DESCENDANTS
            addView(detail)
        }
        card.addView(titleView)
        card.addView(body)
        card.addView(actions)
        card.addView(detailScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        root.addView(card, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = pad })

        setContentView(root)
        goTo(Step.INTRO)
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit): TextView {
        val fill = if (primary) accent else surface2
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(fill, dp(26), Color.WHITE, dp(3)))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(fill, dp(26), Color.WHITE, dp(3)))
                addState(intArrayOf(), rounded(fill, dp(26)))
            }
            setOnClickListener {
                if (!busy) action()
            }
        }
    }

    private fun setActions(vararg items: Pair<String, () -> Unit>) {
        actions.removeAllViews()
        items.forEachIndexed { i, item ->
            val b = button(item.first, i == 0, item.second)
            val lp = GridLayout.LayoutParams()
            lp.setMargins(0, dp(6), dp(12), dp(6))
            lp.width = WRAP_CONTENT
            lp.height = WRAP_CONTENT
            actions.addView(b, lp)
        }
        if (actions.childCount > 0) actions.getChildAt(0).requestFocus()
    }

    private fun currentPad(): String =
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD }
            .map { it.name }.distinct().joinToString().ifEmpty { "keiner erkannt" }

    private fun render() {
        val rootOk = Root.available()
        val a11y = ProbeAccessibilityService.instance != null
        statusLine.text = "Root-Dienst: ${if (rootOk) "gefunden" else "nicht gefunden"}\n" +
            "Bedienungshilfe: ${if (a11y) "aktiv" else "aus"}\n" +
            "Controller: ${currentPad()}"
        stepList.text = if (expert) "Expertenmodus" else Step.values().filter { it != Step.INTRO }.joinToString("\n") {
            when {
                it.ordinal < step.ordinal -> "✓  ${it.label}"
                it == step -> "▶  ${it.label}"
                else -> "·   ${it.label}"
            }
        }
    }

    private fun page(t: String, b: String, d: String = "") {
        titleView.text = t
        body.text = b
        detail.text = d
        render()
    }

    private fun background(work: () -> String, done: (String) -> Unit) {
        busy = true
        Thread {
            val r = Probe.safe(work)
            ui.post {
                busy = false
                done(r)
            }
        }.start()
    }

    // ---------- Wizard ----------

    private fun goTo(s: Step) {
        expert = false
        step = s
        actions.removeAllViews()
        when (s) {
            Step.INTRO -> intro()
            Step.A11Y -> a11yStep()
            Step.READ -> readStep()
            Step.KEYS -> keysStep()
            Step.HOME -> homeStep()
            Step.COMPARE -> compareStep()
            Step.RECENTS -> recentsStep()
            Step.DONE -> doneStep()
        }
    }

    private fun intro() {
        page(
            "Willkommen",
            "Dieser Test sammelt die Infos, die Hugminn für dein Gerät braucht. Er dauert etwa 5 Minuten " +
                "und führt dich Schritt für Schritt. Alles lässt sich mit dem Controller bedienen.\n\n" +
                "Die App liest nur und ändert nichts. Einzige Ausnahme: Sie schaltet ihre eigene Bedienungshilfe ein. " +
                "Persönliche Daten wie Seriennummer, WLAN oder Konten werden aus dem Bericht entfernt."
        )
        setActions("Test starten" to { goTo(Step.A11Y) }, "Expertenwerkzeuge" to { showExpert() })
    }

    private fun a11yStep() {
        if (ProbeAccessibilityService.instance != null) {
            goTo(Step.READ)
            return
        }
        if (Root.available()) {
            page("Bedienungshilfe", "Schalte die Bedienungshilfe ein …")
            val comp = "$packageName/${ProbeAccessibilityService::class.java.name}"
            background({ Root.runAsset(this, "enable_a11y.sh", Root.quote(comp)) ?: "" }) {
                ui.postDelayed({ afterA11yAttempt() }, 800)
            }
        } else {
            a11yManual()
        }
    }

    private fun afterA11yAttempt() {
        if (step != Step.A11Y) return
        if (ProbeAccessibilityService.instance != null) goTo(Step.READ) else a11yManual()
    }

    private fun a11yManual() {
        page(
            "Bedienungshilfe einschalten",
            "Damit der Test auch die Home-Taste sieht, bitte in den Bedienungshilfen „Hugminn Probe“ einschalten.\n\n" +
                "Ist der Eintrag ausgegraut: Einstellungen › Apps › Hugminn Probe › ⋮ oben rechts › " +
                "„Eingeschränkte Einstellungen zulassen“, danach erneut versuchen.\n\n" +
                "Wenn du zurückkommst, geht es automatisch weiter."
        )
        setActions(
            "Bedienungshilfen öffnen" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            "Ohne weiter" to { goTo(Step.READ) }
        )
    }

    private fun readStep() {
        page("Gerät einlesen", "Lese alle Infos ein … das kann bis zu einer Minute dauern.")
        busy = true
        Thread {
            val sections = Probe.all(this)
            ui.post { onReadDone(sections) }
        }.start()
    }

    private fun onReadDone(sections: LinkedHashMap<String, String>) {
        busy = false
        report.putAll(sections)
        if (step != Step.READ) return
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD }
            .map { it.name }.distinct()
        val m = resources.displayMetrics
        page(
            "Gerät eingelesen ✓",
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                "Root-Dienst: ${if (Root.available()) "gefunden ✓" else "nicht gefunden"}\n" +
                "Controller: ${pads.joinToString().ifEmpty { "keiner erkannt" }}\n" +
                "Display: ${m.widthPixels} × ${m.heightPixels}",
            sections.entries.joinToString("\n\n") { "### ${it.key}\n${it.value}" }.take(40_000)
        )
        setActions("Weiter zu den Tasten" to { goTo(Step.KEYS) })
    }

    // Keys

    private fun keysStep() {
        promptIndex = 0
        keyResults.setLength(0)
        ignoreUntil = System.currentTimeMillis() + 300
        startPrompt()
    }

    private fun startPrompt() {
        ui.removeCallbacks(promptTimeout)
        if (promptIndex >= prompts.size) {
            finishKeys()
            return
        }
        val round = if (keyRound > 1) " (2. Durchgang)" else ""
        page(
            "Drücke: ${prompts[promptIndex]}",
            "Gemeint ist die Position der Taste, nicht ihr Aufdruck.\n" +
                "Taste ${promptIndex + 1} von ${prompts.size}$round · Controller: ${currentPad()}\n" +
                "Wird nach 6 Sekunden übersprungen.",
            keyResults.toString()
        )
        ui.postDelayed(promptTimeout, 6_000)
    }

    private fun recordInput(desc: String) {
        if (step != Step.KEYS || promptIndex >= prompts.size) return
        ui.removeCallbacks(promptTimeout)
        keyResults.append(prompts[promptIndex]).append("  →  ").append(desc).append('\n')
        promptIndex++
        ignoreUntil = System.currentTimeMillis() + 250
        detail.text = keyResults.toString()
        ui.postDelayed({ if (step == Step.KEYS) startPrompt() }, 200)
    }

    private fun finishKeys() {
        val pad = currentPad()
        if (keyRound == 1) round1Pad = pad
        report["Tastenbelegung (Durchgang $keyRound, Controller: $pad)"] = keyResults.toString()
        if (keyRound == 1) goTo(Step.HOME) else goTo(Step.RECENTS)
    }

    // Home

    private fun homeStep() {
        homeIndex = 0
        homeRecorded = -1
        homeResults.setLength(0)
        rawFile = null
        page("Home-Taste", "Bereite den Home-Test vor …")
        if (Root.available()) {
            background({ Root.startBackground("timeout 150 getevent -lt") ?: "" }) {
                rawFile = it.ifEmpty { null }
                startHomeTask()
            }
        } else {
            startHomeTask()
        }
    }

    private fun startHomeTask() {
        if (homeIndex >= homeTasks.size) {
            finishHome()
            return
        }
        homeLeft = false
        ProbeAccessibilityService.keyLog.clear()
        ProbeAccessibilityService.consumeHome = homeIndex == consumeTask
        if (homeIndex == consumeTask) ui.postDelayed({ ProbeAccessibilityService.consumeHome = false }, 20_000)
        page(
            "Home-Taste ${homeIndex + 1} von ${homeTasks.size}",
            "${homeTasks[homeIndex]}.\n\n" +
                "Die App kommt danach von selbst zurück (falls nicht: über die Übersicht wieder öffnen).\n" +
                "Passiert beim Drücken gar nichts, wähle hier „Weiter“.",
            homeResults.toString()
        )
        setActions("Weiter" to { recordHome() })
    }

    private fun recordHome() {
        if (step != Step.HOME || homeRecorded == homeIndex || homeIndex >= homeTasks.size) return
        homeRecorded = homeIndex
        val lines = synchronized(ProbeAccessibilityService.keyLog) { ProbeAccessibilityService.keyLog.toList() }
        homeResults.append("— ").append(homeTasks[homeIndex]).append(" —\n")
        homeResults.append("App verlassen: ").append(if (homeLeft) "ja" else "nein").append('\n')
        if (homeIndex == consumeTask) {
            homeResults.append(if (homeLeft) "→ Abfangen hat NICHT gereicht, das System ging trotzdem zum Startbildschirm\n"
            else "→ Abfangen hat funktioniert, die App blieb offen\n")
        }
        if (lines.isEmpty()) homeResults.append("Bedienungshilfe hat keine Taste gesehen\n")
        else lines.forEach { homeResults.append(it).append('\n') }
        homeResults.append('\n')
        homeIndex++
        startHomeTask()
    }

    private fun finishHome() {
        ProbeAccessibilityService.consumeHome = false
        report["Home-Taste"] = homeResults.toString()
        val f = rawFile
        if (f == null) {
            goTo(Step.COMPARE)
            return
        }
        page("Home-Taste", "Werte die Rohdaten aus …")
        background({
            Root.kill("getevent")
            Root.collect(f, 10_000)
        }) { raw ->
            report["Tasten-Rohdaten (Home-Test)"] = raw.ifEmpty { "keine Rohdaten" }
            rawFile = null
            goTo(Step.COMPARE)
        }
    }

    // Compare

    private fun compareStep() {
        compareIndex = 0
        startCompare()
    }

    private fun startCompare() {
        if (compareIndex >= compareTasks.size) {
            finishCompare()
            return
        }
        page(
            "Einstellung ${compareIndex + 1} von ${compareTasks.size}",
            "${compareTasks[compareIndex]}\n\n" +
                "1. „Los“ drücken – die App merkt sich den jetzigen Zustand.\n" +
                "2. Die Einstellung ändern.\n" +
                "3. Zurückkommen und „Fertig“ drücken.\n\n" +
                "Gibt es die Einstellung auf deinem Gerät nicht: „Überspringen“."
        )
        setActions(
            "Los" to { snapshotThenWait() },
            "Überspringen" to { skipCompare() },
            "Alle überspringen" to { finishCompare() }
        )
    }

    private fun skipCompare() {
        compareIndex++
        startCompare()
    }

    private fun snapshotThenWait() {
        val heading = titleView.text.toString()
        page(heading, "Merke mir den Zustand …")
        actions.removeAllViews()
        background({
            val s = Probe.snapshot(this)
            snapA = s
            s.size.toString()
        }) {
            page(heading, "Jetzt ändern: ${compareTasks[compareIndex]}\n\nDanach hierher zurück und „Fertig“ drücken.")
            setActions("Fertig – vergleichen" to { compareNow() }, "Abbrechen" to { skipCompare() })
        }
    }

    private fun compareNow() {
        val a = snapA ?: return
        val task = compareTasks[compareIndex]
        val heading = titleView.text.toString()
        page(heading, "Vergleiche …")
        actions.removeAllViews()
        background({ Probe.diff(a, Probe.snapshot(this)) }) { diff ->
            report["Vergleich: $task"] = diff
            val changed = !diff.startsWith("Keine")
            if (compareIndex == controllerTask && changed) controllerSwitched = true
            val msg = if (changed) "Gespeichert ✓  Die Änderungen stehen unten."
            else "Es wurde keine Änderung gefunden. Auch das wird gespeichert."
            page(heading, msg, diff)
            compareIndex++
            setActions("Weiter" to { startCompare() })
        }
    }

    private fun finishCompare() {
        if (controllerSwitched && keyRound == 1) {
            keyRound = 2
            checkSecondRound()
        } else {
            goTo(Step.RECENTS)
        }
    }

    private fun checkSecondRound() {
        val now = currentPad()
        if (now == round1Pad) {
            page(
                "Bitte Controller-Stil wechseln",
                "Der Controller meldet sich gerade als „$now“ – genau wie im ersten Durchgang.\n\n" +
                    "Stell den Controller-Stil bitte auf den anderen Modus um (z. B. in den Schnelleinstellungen) " +
                    "und komm dann zurück."
            )
            setActions(
                "Erneut prüfen" to { checkSecondRound() },
                "Trotzdem starten" to { goTo(Step.KEYS) },
                "Überspringen" to { goTo(Step.RECENTS) }
            )
        } else {
            page(
                "Tasten im anderen Modus",
                "Controller jetzt: „$now“ (vorher: „${round1Pad ?: "?"}“).\n\n" +
                    "Bitte die Tasten noch einmal drücken – wieder nach Position."
            )
            setActions("Starten" to { goTo(Step.KEYS) }, "Überspringen" to { goTo(Step.RECENTS) })
        }
    }

    // Recents

    private fun recentsStep() {
        if (ProbeAccessibilityService.instance == null) {
            page("Android-Übersicht", "Übersprungen, weil die Bedienungshilfe aus ist.")
            setActions("Weiter" to { goTo(Step.DONE) })
            return
        }
        page(
            "Android-Übersicht",
            "Öffne vorher bitte 2–3 andere Apps (z. B. RetroArch, Einstellungen) und komm über die Übersicht hierher zurück, " +
                "damit dort Karten zu sehen sind.\n\nDann „Übersicht auslesen“ und währenddessen nichts drücken."
        )
        setActions("Übersicht auslesen" to { dumpRecents { goTo(Step.DONE) } }, "Überspringen" to { goTo(Step.DONE) })
    }

    private fun dumpRecents(after: () -> Unit) {
        val svc = ProbeAccessibilityService.instance ?: return
        busy = true
        svc.openRecents()
        ui.postDelayed({
            Thread {
                val dump = svc.dumpWindows()
                ui.post {
                    busy = false
                    report["Android-Übersicht ${Probe.now()}"] = dump
                    svc.backToProbe()
                    after()
                    detail.text = dump
                }
            }.start()
        }, 900)
    }

    // Done

    private fun doneStep() {
        page("Fertig!", "Speichere den Bericht …")
        background({ save() }) { msg ->
            page("Fertig! ✓", "$msg\n\nBitte die Datei an den Entwickler schicken, z. B. über „Teilen“.")
            setActions(
                "Teilen" to { share() },
                "Neu beginnen" to { restart() },
                "Expertenwerkzeuge" to { showExpert() }
            )
        }
    }

    private fun restart() {
        report.clear()
        keyRound = 1
        controllerSwitched = false
        goTo(Step.INTRO)
    }

    // ---------- Expert tools ----------

    private fun showExpert() {
        expert = true
        page("Expertenwerkzeuge", "Einzelne Funktionen für gezielte Tests. Ergebnisse landen ebenfalls im Bericht.")
        setActions(
            "Alles einlesen" to { expertReadAll() },
            "Snapshot A" to { expertSnapA() },
            "Snapshot B + Vergleich" to { expertSnapB() },
            "Tastentest an/aus" to { expertToggleKeys() },
            "Tasten-Rohdaten (8 s)" to { expertRawKeys() },
            "Übersicht auslesen" to { expertRecents() },
            "Bericht speichern" to { background({ save() }) { body.text = it } },
            "Teilen" to { share() },
            "Zum Assistenten" to { goTo(Step.INTRO) }
        )
    }

    private fun expertReadAll() {
        body.text = "Lese alles ein …"
        background({
            val s = Probe.all(this)
            report.putAll(s)
            s.entries.joinToString("\n\n") { "### ${it.key}\n${it.value}" }
        }) {
            body.text = "Eingelesen."
            detail.text = it.take(80_000)
        }
    }

    private fun expertSnapA() {
        body.text = "Snapshot A …"
        background({
            val s = Probe.snapshot(this)
            snapA = s
            s.size.toString()
        }) { body.text = "Snapshot A gespeichert ($it Werte). Jetzt etwas ändern, dann „Snapshot B“." }
    }

    private fun expertSnapB() {
        val a = snapA
        if (a == null) {
            body.text = "Erst Snapshot A."
            return
        }
        background({ Probe.diff(a, Probe.snapshot(this)) }) {
            report["Vergleich ${Probe.now()}"] = it
            detail.text = it
            body.text = "Vergleich gespeichert."
        }
    }

    private fun expertToggleKeys() {
        expertKeyTest = !expertKeyTest
        if (expertKeyTest) {
            expertKeys.clear()
            ProbeAccessibilityService.keyLog.clear()
            body.text = "Tastentest läuft – alle Tasten drücken."
        } else {
            report["Freier Tastentest ${Probe.now()}"] = expertKeys.joinToString("\n")
            body.text = "Tastentest beendet und gespeichert."
        }
        showExpertKeys()
    }

    private fun expertRawKeys() {
        if (!Root.available()) {
            body.text = "Braucht den Root-Dienst."
            return
        }
        body.text = "Jetzt 8 Sekunden lang Tasten drücken …"
        background({ Root.exec(this, "timeout 8 getevent -lt", 15_000) ?: "keine Antwort" }) {
            report["Tasten-Rohdaten ${Probe.now()}"] = it
            detail.text = it
            body.text = "Rohdaten gespeichert."
        }
    }

    private fun expertRecents() {
        if (ProbeAccessibilityService.instance == null) {
            body.text = "Bedienungshilfe ist aus."
            return
        }
        dumpRecents { showExpert() }
    }

    private fun showExpertKeys() {
        if (!expert) return
        val a11y = synchronized(ProbeAccessibilityService.keyLog) { ProbeAccessibilityService.keyLog.takeLast(20) }
        val a11yText = if (ProbeAccessibilityService.instance == null) "(aus)" else a11y.joinToString("\n")
        detail.text = "— App sieht —\n" + expertKeys.toList().takeLast(25).joinToString("\n") +
            "\n\n— Bedienungshilfe sieht —\n" + a11yText
    }

    // ---------- Input ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (step == Step.KEYS && !expert) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && System.currentTimeMillis() > ignoreUntil) {
                recordInput("${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}), Scancode ${event.scanCode}, Gerät \"${event.device?.name}\"")
            }
            return true
        }
        if (expert && expertKeyTest) {
            expertKeys.addLast(Probe.describeKey("APP ", event))
            while (expertKeys.size > 500) expertKeys.removeFirst()
            showExpertKeys()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (step == Step.KEYS && !expert) {
            if (System.currentTimeMillis() > ignoreUntil) {
                for (axis in keyAxes) {
                    val v = event.getAxisValue(axis)
                    if (abs(v) > 0.5f) {
                        recordInput("Achse ${MotionEvent.axisToString(axis)} = ${"%.2f".format(Locale.US, v)}, Gerät \"${event.device?.name}\"")
                        break
                    }
                }
            }
            return true
        }
        if (expert && expertKeyTest) {
            for (axis in keyAxes) {
                val v = event.getAxisValue(axis)
                val last = lastAxis[axis] ?: 0f
                if (abs(v - last) >= 0.5f || (v == 0f && last != 0f)) {
                    lastAxis[axis] = v
                    expertKeys.addLast("${Probe.now()} APP  ACHSE ${MotionEvent.axisToString(axis)}=${"%.2f".format(Locale.US, v)} dev=\"${event.device?.name}\"")
                }
            }
            showExpertKeys()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (expert) {
            goTo(Step.INTRO)
        } else if (step == Step.INTRO || step == Step.DONE) {
            super.onBackPressed()
        } else {
            val base = body.text.toString().substringBefore("\n\n(Zurück")
            body.text = "$base\n\n(Zurück ist während des Tests gesperrt.)"
        }
    }

    // ---------- Lifecycle ----------

    override fun onDestroy() {
        ProbeAccessibilityService.consumeHome = false
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (step == Step.HOME && !expert && homeRecorded != homeIndex) {
            homeLeft = true
            val svc = ProbeAccessibilityService.instance
            if (svc != null) ui.postDelayed({ svc.backToProbe() }, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (expert) {
            showExpertKeys()
        } else if (step == Step.A11Y && ProbeAccessibilityService.instance != null) {
            goTo(Step.READ)
        } else if (step == Step.HOME && homeLeft) {
            ui.postDelayed({ recordHome() }, 300)
        }
    }

    // ---------- Report ----------

    private fun fullReport(): String = buildString {
        appendLine("Hugminn Probe 0.4.1 – Bericht vom ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}")
        appendLine("Persönliche Werte (Seriennummer, Konten, WLAN, MAC usw.) wurden entfernt.")
        appendLine()
        for ((k, v) in report) {
            appendLine("################ $k")
            appendLine(v)
            appendLine()
        }
        synchronized(ProbeAccessibilityService.windowLog) {
            if (ProbeAccessibilityService.windowLog.isNotEmpty()) {
                appendLine("################ Fensterwechsel")
                ProbeAccessibilityService.windowLog.forEach { appendLine(it) }
            }
        }
    }

    private fun save(): String {
        val text = fullReport()
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-")
        val name = "hugminn-probe-$model-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hugminn")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return "Speichern fehlgeschlagen."
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        lastUri = uri
        lastName = name
        return "Gespeichert unter Download/Hugminn/$name (${text.length / 1024} KB)."
    }

    private fun share() {
        val uri = lastUri
        if (uri == null) {
            body.text = "Erst den Bericht speichern."
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, lastName ?: "Hugminn Probe")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Bericht teilen")) }
    }
}

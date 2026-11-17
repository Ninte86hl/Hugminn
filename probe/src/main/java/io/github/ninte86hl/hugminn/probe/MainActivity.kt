package io.github.ninte86hl.hugminn.probe

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val report = LinkedHashMap<String, String>()
    private val appKeys = ArrayDeque<String>()
    private val lastAxis = HashMap<Int, Float>()
    private var snapshotA: Map<String, String>? = null
    private var keyTest = false
    @Volatile private var busy = false

    private val axes = intArrayOf(
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(left) }, LinearLayout.LayoutParams(dp(300), MATCH_PARENT))

        left.addView(TextView(this).apply { text = "Hugminn Probe"; textSize = 22f })
        status = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(8)) }
        left.addView(status)

        fun button(label: String, action: () -> Unit) {
            left.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        button("1 · Alles einlesen") { collectAll() }
        button("2 · Snapshot A (vorher)") { snapA() }
        button("3 · Snapshot B + Vergleich") { snapB() }
        button("4 · Tastentest an/aus") { toggleKeyTest() }
        button("5 · Tasten-Rohdaten (8 s, Root)") { rawKeys() }
        button("6 · Bedienungshilfe aktivieren") { enableA11y() }
        button("7 · Android-Übersicht auslesen") { dumpRecents() }
        button("8 · Bericht speichern & teilen") { saveAndShare() }

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = INTRO
        }
        val outScroll = ScrollView(this).apply { isFocusable = false; addView(out) }
        root.addView(outScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = pad })
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (keyTest) showKeys()
    }

    private fun refreshStatus() {
        val rootOk = Root.available()
        val a11y = ProbeAccessibilityService.instance != null
        status.text = "Root-Dienst: ${if (rootOk) "ja" else "nein"}\n" +
            "Bedienungshilfe: ${if (a11y) "aktiv" else "aus"}\n" +
            "Tastentest: ${if (keyTest) "läuft" else "aus"}" +
            (if (snapshotA != null) "\nSnapshot A: vorhanden" else "") +
            (if (busy) "\n… arbeitet" else "")
    }

    private fun show(title: String, text: String) {
        out.text = "== $title ==\n\n" + if (text.length > 80_000) text.take(80_000) + "\n[… gekürzt, vollständig im Bericht]" else text
    }

    private fun runBg(title: String, store: Boolean = true, block: () -> String) {
        if (busy) return
        busy = true
        refreshStatus()
        out.text = "$title …"
        Thread {
            val result = Probe.safe(block)
            ui.post {
                busy = false
                if (store) report[title] = result
                show(title, result)
                refreshStatus()
            }
        }.start()
    }

    private fun collectAll() {
        if (busy) return
        busy = true
        refreshStatus()
        out.text = "Lese alles ein … (bis zu einer Minute)"
        Thread {
            val sections = Probe.all(this)
            ui.post {
                busy = false
                report.putAll(sections)
                show("Alles eingelesen", sections.entries.joinToString("\n\n") { "### ${it.key}\n${it.value}" })
                refreshStatus()
            }
        }.start()
    }

    private fun snapA() {
        if (busy) return
        busy = true
        refreshStatus()
        out.text = "Snapshot A …"
        Thread {
            val snap = Probe.snapshot(this)
            ui.post {
                busy = false
                snapshotA = snap
                out.text = "Snapshot A gespeichert (${snap.size} Werte).\n\n" +
                    "Jetzt genau EINE Sache ändern, z. B.:\n" +
                    "• LED-Kachel in den Schnelleinstellungen\n" +
                    "• Leistungsmodus oder Lüfter in den Retroid/AYN-Einstellungen\n" +
                    "• Controller-Stil Xbox ↔ Nintendo\n" +
                    "• eine App zur Hintergrund-Whitelist hinzufügen\n\n" +
                    "Dann zurück hierher und „Snapshot B + Vergleich“ drücken."
                refreshStatus()
            }
        }.start()
    }

    private fun snapB() {
        val a = snapshotA
        if (a == null) {
            out.text = "Erst „Snapshot A“ machen."
            return
        }
        runBg("Vergleich ${Probe.now()}") { Probe.diff(a, Probe.snapshot(this)) }
    }

    private fun toggleKeyTest() {
        keyTest = !keyTest
        if (keyTest) {
            appKeys.clear()
            ProbeAccessibilityService.keyLog.clear()
            showKeys()
        } else {
            report["Tastentest (App) ${Probe.now()}"] = appKeys.joinToString("\n")
            out.text = "Tastentest beendet, ${appKeys.size} Einträge im Bericht."
        }
        refreshStatus()
    }

    private fun logKey(line: String) {
        appKeys.addLast(line)
        while (appKeys.size > 600) appKeys.removeFirst()
        showKeys()
    }

    private fun showKeys() {
        val a11y = synchronized(ProbeAccessibilityService.keyLog) { ProbeAccessibilityService.keyLog.takeLast(25) }
        out.text = "Tastentest läuft. Alle Tasten nacheinander drücken, auch Home kurz, lang und doppelt.\n" +
            "Home verlässt die App: einfach zurückkommen, die Einträge bleiben erhalten.\n\n" +
            "— App sieht —\n" + appKeys.toList().takeLast(30).joinToString("\n") +
            "\n\n— Bedienungshilfe sieht —\n" +
            (if (ProbeAccessibilityService.instance == null) "(Bedienungshilfe aus)" else a11y.joinToString("\n"))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyTest) logKey(Probe.describeKey("APP ", event))
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (keyTest) {
            for (axis in axes) {
                val v = event.getAxisValue(axis)
                val last = lastAxis[axis] ?: 0f
                if (abs(v - last) >= 0.5f || (v == 0f && last != 0f)) {
                    lastAxis[axis] = v
                    logKey("${Probe.now()} APP  ACHSE ${MotionEvent.axisToString(axis)}=${"%.2f".format(Locale.US, v)} dev=\"${event.device?.name}\"")
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun rawKeys() {
        if (!Root.available()) {
            out.text = "Tasten-Rohdaten brauchen den Root-Dienst, der hier nicht gefunden wurde."
            return
        }
        runBg("Tasten-Rohdaten ${Probe.now()}") { Root.exec(this, "timeout 8 getevent -lt", 15_000) ?: "keine Antwort" }
        out.text = "Jetzt 8 Sekunden lang Tasten drücken (Home kurz, lang, doppelt; danach A, B, X, Y) …"
    }

    private fun enableA11y() {
        val comp = "$packageName/${ProbeAccessibilityService::class.java.name}"
        if (Root.available()) {
            runBg("Bedienungshilfe aktivieren", store = false) {
                (Root.runAsset(this, "enable_a11y.sh", Root.quote(comp)) ?: "keine Antwort") +
                    "\n\nFalls weiter „aus“: in den Bedienungshilfen „Hugminn Probe“ selbst einschalten."
            }
        } else {
            out.text = "Kein Root-Dienst: Bitte in den Bedienungshilfen „Hugminn Probe“ einschalten.\n" +
                "Falls ausgegraut: Einstellungen › Apps › Hugminn Probe › ⋮ › „Eingeschränkte Einstellungen zulassen“."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun dumpRecents() {
        val svc = ProbeAccessibilityService.instance
        if (svc == null) {
            out.text = "Dafür muss die Bedienungshilfe aktiv sein (Knopf 6)."
            return
        }
        out.text = "Öffne die Übersicht … bitte nichts drücken."
        svc.openRecents()
        ui.postDelayed({
            Thread {
                val dump = svc.dumpWindows()
                ui.post {
                    report["Android-Übersicht ${Probe.now()}"] = dump
                    svc.backToProbe()
                    show("Android-Übersicht", dump)
                }
            }.start()
        }, 1500)
    }

    private fun fullReport(): String = buildString {
        appendLine("Hugminn Probe 0.1.0 – Bericht vom ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}")
        appendLine("Persönliche Werte (Seriennummer, Kontonamen, WLAN, MAC usw.) wurden entfernt.")
        appendLine()
        for ((k, v) in report) {
            appendLine("################ $k")
            appendLine(v)
            appendLine()
        }
        if (appKeys.isNotEmpty()) {
            appendLine("################ Tastentest (App, zuletzt)")
            appKeys.forEach { appendLine(it) }
            appendLine()
        }
        synchronized(ProbeAccessibilityService.keyLog) {
            if (ProbeAccessibilityService.keyLog.isNotEmpty()) {
                appendLine("################ Tastentest (Bedienungshilfe)")
                ProbeAccessibilityService.keyLog.forEach { appendLine(it) }
                appendLine()
            }
        }
        synchronized(ProbeAccessibilityService.windowLog) {
            if (ProbeAccessibilityService.windowLog.isNotEmpty()) {
                appendLine("################ Fensterwechsel")
                ProbeAccessibilityService.windowLog.forEach { appendLine(it) }
            }
        }
    }

    private fun saveAndShare() {
        if (report.isEmpty() && appKeys.isEmpty()) {
            out.text = "Noch nichts eingelesen – zuerst „Alles einlesen“."
            return
        }
        val text = fullReport()
        runBg("Bericht", store = false) {
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-")
            val name = "hugminn-probe-$model-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hugminn")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return@runBg "Speichern fehlgeschlagen."
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ui.post {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(Intent.createChooser(send, "Bericht teilen")) }
            }
            "Gespeichert unter Download/Hugminn/$name (${text.length / 1024} KB).\nZum Teilen öffnet sich gleich die Auswahl."
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val INTRO = "So geht's:\n\n" +
            "1. „Alles einlesen“ drücken.\n" +
            "2. Für jede Einstellung, die uns interessiert: Snapshot A, Einstellung ändern, Snapshot B.\n" +
            "3. „Bedienungshilfe aktivieren“, dann „Tastentest“ und alle Tasten drücken.\n" +
            "4. Mit Root zusätzlich „Tasten-Rohdaten“ (8 s lang Tasten drücken).\n" +
            "5. „Android-Übersicht auslesen“ (vorher ein paar Apps öffnen).\n" +
            "6. „Bericht speichern & teilen“ und die Datei schicken.\n\n" +
            "Die App liest nur. Einzige Änderung: Knopf 6 schaltet ihre eigene Bedienungshilfe ein."
    }
}

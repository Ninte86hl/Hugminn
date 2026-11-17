package io.github.ninte86hl.hugminn

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Setup and settings: enable the service, choose what the Home gestures do, pick button glyphs. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var pal: Palette
    private lateinit var content: LinearLayout
    private val buttons = mutableListOf<Button>()
    private val afterChange = mutableListOf<() -> Unit>()
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        prefs = Prefs(this)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && !Root.available()) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        build()
        // Android switches the service off after a crash: bring it back automatically (root only).
        if (HugminnService.instance == null && Root.available() && Device.whitelisted(this)) {
            thread {
                if (Device.restartService(this)) runOnUiThread {
                    Toast.makeText(this, "Hugminn wurde neu gestartet", Toast.LENGTH_SHORT).show()
                    build()
                }
            }
        }
    }

    private fun d(v: Int) = dp(v)

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(title: String): LinearLayout {
        content.addView(text(title, 14f, pal.primary, bold = true).apply { setPadding(d(4), d(18), 0, d(6)) })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.surfaceHigh, d(20).toFloat())
            setPadding(d(16), d(8), d(16), d(8))
            content.addView(this)
        }
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(pal.onPrimaryContainer)
        background = rounded(pal.primaryContainer, d(20).toFloat())
        setPadding(d(16), 0, d(16), 0)
        stateListAnimator = null
        setOnClickListener { click() }
        setOnFocusChangeListener { v, f -> v.foreground = if (f) rounded(0, d(20).toFloat(), d(3), pal.primary) else null }
        buttons += this
    }

    /** One settings row: label on the left, a button showing the value on the right (A cycles it). */
    private fun row(parent: LinearLayout, label: String, sub: String?, value: () -> String, click: () -> Unit) {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(6), 0, d(6))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 16f, pal.onSurface))
        sub?.let { col.addView(text(it, 12f, pal.onSurfaceVariant)) }
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Changes apply in place: no rebuild, focus and scroll position stay where they are.
        var self: Button? = null
        self = button(value()) {
            click()
            self?.text = value()
            afterChange.forEach { it() }
        }
        r.addView(self)
        parent.addView(r)
    }

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

    private fun build() {
        // Structural changes rebuild the page; keep the scroll position and the focused button.
        val oldScroll = scroll?.scrollY ?: 0
        val oldFocus = buttons.indexOfFirst { it.isFocused }
        afterChange.clear()
        pal = Palette(this)
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        buttons.clear()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(28), d(16), d(28), d(28))
        }

        // Header
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(d(56), d(56)))
        val hc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), 0, 0, 0)
        }
        hc.addView(text("Hugminn", 26f, pal.onSurface, bold = true))
        hc.addView(text("Huginn denkt mit (Control Center) · Muninn erinnert sich (Game Switcher)", 13f, pal.onSurfaceVariant))
        head.addView(hc)
        content.addView(head)

        // Status
        val running = HugminnService.instance != null
        val rootOk = Root.available()
        val st = section("Status")
        st.addView(text(if (running) "✓ Hugminn ist aktiv" else "✕ Hugminn ist noch nicht aktiv", 16f, pal.onSurface, bold = true).apply {
            setPadding(0, d(6), 0, d(2))
        })
        st.addView(text(
            if (rootOk) "Root-Dienst des Handhelds gefunden: Schalter für Leistung, Lüfter, LEDs usw. funktionieren."
            else "Kein Root-Dienst gefunden: Game Switcher und Graphen gehen, Geräteschalter öffnen die Android-Einstellungen.",
            12f, pal.onSurfaceVariant,
        ))
        if (Device.hasWhitelist(this)) {
            val wl = Device.whitelisted(this)
            st.addView(text(
                if (wl) "✓ Auf der Hintergrund-Whitelist: Das System beendet Hugminn nicht."
                else "✕ Nicht auf der Hintergrund-Whitelist: Das System kann Hugminn im Hintergrund beenden.",
                13f, pal.onSurface,
            ).apply { setPadding(0, d(8), 0, 0) })
            if (!wl && rootOk) {
                val b = LinearLayout(this).apply { setPadding(0, d(8), 0, d(4)) }
                b.addView(button("Zur Whitelist hinzufügen") {
                    thread {
                        Device.ensureWhitelist(this)
                        runOnUiThread { build() }
                    }
                })
                st.addView(b)
            }
        }
        Crash.last(this)?.let { crash ->
            st.addView(text("Letzter Fehler (wird mit dem Feedback gesendet):", 13f, pal.onSurface, bold = true).apply { setPadding(0, d(10), 0, d(2)) })
            st.addView(text(crash.lines().take(6).joinToString("\n"), 11f, pal.onSurfaceVariant).apply {
                maxLines = 6
                typeface = android.graphics.Typeface.MONOSPACE
            })
            val cb = LinearLayout(this).apply { setPadding(0, d(6), 0, d(4)) }
            cb.addView(button("Fehler löschen") { Crash.clear(this); build() })
            st.addView(cb)
        }
        if (!running) {
            val b = LinearLayout(this).apply { setPadding(0, d(8), 0, d(4)) }
            b.addView(button("Aktivieren") { enable(rootOk) })
            st.addView(b)
        }

        // Gestures
        val g = section("Home-Taste")
        val actions = Action.values().toList()
        row(g, "Einmal drücken", null, { prefs.single.label }) { prefs.single = cycle(actions, prefs.single) }
        row(g, "Zweimal drücken", null, { prefs.double.label }) { prefs.double = cycle(actions, prefs.double) }
        row(g, "Lange drücken", null, { prefs.long.label }) { prefs.long = cycle(actions, prefs.long) }
        row(g, "Zeit für Doppeldruck", "Kürzer = einfacher Druck reagiert schneller", { "${prefs.doubleMs} ms" }) {
            prefs.doubleMs = cycle(listOf(220, 260, 300, 350, 400), prefs.doubleMs)
        }
        row(g, "Zeit für langen Druck", null, { "${prefs.longMs} ms" }) {
            prefs.longMs = cycle(listOf(350, 450, 600, 800), prefs.longMs)
        }

        // Switcher look
        val sw = section("Game Switcher")
        row(sw, "Auch bei Wischgeste", "Hugminn legt sich automatisch über die Android-Übersicht, egal wie sie geöffnet wird", { if (prefs.autoSwitcher) "An" else "Aus" }) {
            prefs.autoSwitcher = !prefs.autoSwitcher
        }
        row(sw, "Statusleiste", "Was der Rahmen oben zeigt: Uhrzeit und Akku im Hugminn-Stil, nichts, oder die normale Android-Leiste", { prefs.statusBar.label }) {
            prefs.statusBar = cycle(StatusBar.values().toList(), prefs.statusBar)
        }

        val ccs = section("Control Center")
        row(ccs, "Statusleiste ausblenden", "Das Control Center füllt den ganzen Bildschirm; aus = Android-Statusleiste bleibt sichtbar", { if (prefs.ccHideStatus) "An" else "Aus" }) {
            prefs.ccHideStatus = !prefs.ccHideStatus
        }

        // Glyphs
        val gl = section("Tastensymbole")
        row(gl, "Stil", "Automatisch folgt dem Layout in den Retroid-Einstellungen", { prefs.glyphStyle.label }) {
            prefs.glyphStyle = cycle(GlyphStyle.values().toList(), prefs.glyphStyle)
        }
        row(gl, "Farbig", null, { if (prefs.glyphColored) "An" else "Aus" }) { prefs.glyphColored = !prefs.glyphColored }
        val preview = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(6), 0, d(8))
        }
        val fillPreview = {
            preview.removeAllViews()
            preview.addView(text("Vorschau:  ", 13f, pal.onSurfaceVariant))
            listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y).forEach { k ->
                preview.addView(Glyphs.chip(this, prefs, pal, k), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = d(8) })
            }
            preview.addView(text(if (Glyphs.nintendoLayout()) "  (Gerät im Retroid-/Nintendo-Layout)" else "  (Gerät im Xbox-Layout)", 12f, pal.onSurfaceVariant))
        }
        fillPreview()
        afterChange += fillPreview
        gl.addView(preview)

        // Try it
        val t = section("Ausprobieren")
        val tr = LinearLayout(this).apply { setPadding(0, d(6), 0, d(6)) }
        tr.addView(button("Game Switcher öffnen") { HugminnService.instance?.perform(Action.SWITCHER) ?: notRunning() })
        tr.addView(View(this), LinearLayout.LayoutParams(d(12), 1))
        tr.addView(button("Control Center öffnen") { HugminnService.instance?.perform(Action.CONTROL_CENTER) ?: notRunning() })
        t.addView(tr)
        t.addView(text(
            "RetroArch: Für „Speichern & zurück“ im Game Switcher in RetroArch unter Einstellungen → Netzwerk die Netzwerkbefehle einschalten (Port 55355).",
            12f, pal.onSurfaceVariant,
        ).apply { setPadding(0, 0, 0, d(6)) })

        // Profile learning
        val pr = section("Geräteprofile erkennen")
        pr.addView(text(
            "Öffne die Retroid-Schnelleinstellungen und schalte nacheinander durch alle Leistungs- und Lüfterprofile. " +
                "Hugminn schreibt hier mit, welche Werte sich ändern. Schick davon einfach ein Foto.",
            12f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(6), 0, d(6)) })
        val logView = text("", 13f, pal.onSurface).apply {
            maxLines = 20
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, d(6))
        }
        val fillLog = {
            val log = synchronized(SettingsWatch.log) { SettingsWatch.log.toList() }
            logView.text = if (log.isEmpty()) "Noch keine Änderungen erkannt." else log.take(20).joinToString("\n")
        }
        fillLog()
        pr.addView(logView)
        val prb = LinearLayout(this).apply { setPadding(0, 0, 0, d(6)) }
        prb.addView(button("Aktualisieren") { fillLog() })
        prb.addView(View(this), LinearLayout.LayoutParams(d(12), 1))
        prb.addView(button("Liste leeren") { SettingsWatch.log.clear(); fillLog() })
        pr.addView(prb)

        // Feedback upload
        val fb = section("Feedback an Claude")
        fb.addView(text(
            "Drücke Select, während der Game Switcher oder das Control Center offen ist: Hugminn lädt einen Screenshot " +
                "und eine Beschreibung des Bildschirms auf GitHub hoch (Zweig „feedback“). Dafür braucht Hugminn einen eigenen " +
                "GitHub-Token mit Schreibrecht: Repository Hugminn, Berechtigung „Contents: Read and write“.",
            12f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(6), 0, d(6)) })
        val hasToken = !prefs.githubToken.isNullOrBlank()
        fb.addView(text(if (hasToken) "✓ Token eingetragen" else "✕ Kein Token eingetragen", 15f, pal.onSurface, bold = true))
        val fbb = LinearLayout(this).apply { setPadding(0, d(8), 0, d(6)) }
        fbb.addView(button("Token aus Zwischenablage einfügen") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (t != null && (t.startsWith("github_pat_") || t.startsWith("ghp_"))) {
                prefs.githubToken = t
                Toast.makeText(this, "Token gespeichert", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "In der Zwischenablage ist kein GitHub-Token", Toast.LENGTH_LONG).show()
            build()
        })
        if (hasToken) {
            fbb.addView(View(this), LinearLayout.LayoutParams(d(12), 1))
            fbb.addView(button("Token entfernen") { prefs.githubToken = null; build() })
        }
        fb.addView(fbb)
        if (hasToken) {
            fb.addView(text(
                "Fehler werden automatisch mit dem Systemprotokoll hochgeladen, nach einem Absturz beim nächsten Start.",
                12f, pal.onSurfaceVariant,
            ))
            val lb = LinearLayout(this).apply { setPadding(0, d(8), 0, d(6)) }
            lb.addView(button("Protokoll jetzt hochladen") {
                Feedback.sendLog(this, "protokoll")
                Toast.makeText(this, "Wird hochgeladen – siehe Benachrichtigung", Toast.LENGTH_SHORT).show()
            })
            fb.addView(lb)
        }

        // About
        val a = section("Über")
        a.addView(text(
            "In der nordischen Sage hat Odin zwei Raben: Huginn (Gedanke) und Muninn (Erinnerung). " +
                "Hugminn vereint beide: das Control Center denkt für dich mit, der Game Switcher merkt sich deine Spiele.",
            13f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(6), 0, d(4)) })
        a.addView(text("Version ${packageManager.getPackageInfo(packageName, 0).versionName} · ${android.os.Build.MODEL}", 12f, pal.outline).apply {
            setPadding(0, 0, 0, d(6))
        })

        val sv = ScrollView(this).apply {
            setBackgroundColor(pal.surface)
            addView(content)
        }
        scroll = sv
        setContentView(sv)
        sv.post {
            sv.scrollTo(0, oldScroll)
            (buttons.getOrNull(oldFocus) ?: buttons.firstOrNull())?.requestFocus()
            sv.scrollTo(0, oldScroll)
        }
    }

    private fun notRunning() = Toast.makeText(this, "Bitte zuerst Hugminn aktivieren", Toast.LENGTH_SHORT).show()

    private fun enable(rootOk: Boolean) {
        if (!rootOk) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "„Hugminn“ in der Liste einschalten", Toast.LENGTH_LONG).show()
            return
        }
        thread {
            val ok = Device.enableService(this)
            Device.grantExtras(this)
            Device.ensureWhitelist(this)
            runOnUiThread {
                if (ok) {
                    window.decorView.postDelayed({ build() }, 600)
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this, "„Hugminn“ in der Liste einschalten", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

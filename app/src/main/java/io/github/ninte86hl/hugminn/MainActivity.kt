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
        Lang.init(this)
    }

    override fun onResume() {
        super.onResume()
        build()
        // Android switches the service off after a crash: bring it back automatically (root only).
        if (HugminnService.instance == null && Root.available() && Device.whitelisted(this)) {
            thread {
                if (Device.restartService(this)) runOnUiThread {
                    Toast.makeText(this, tr("Hugminn wurde neu gestartet"), Toast.LENGTH_SHORT).show()
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
        typeface = Look.font(this@MainActivity, pixel, bold)
    }

    private fun section(title: String): LinearLayout {
        content.addView(text(title, 14f, pal.primary, bold = true).apply { setPadding(d(4), d(18), 0, d(6)) })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.shape(this@MainActivity, pixel, pal.surfaceHigh, d(20).toFloat())
            setPadding(d(16), d(8), d(16), d(8))
            content.addView(this)
        }
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(pal.onPrimaryContainer)
        background = Look.shape(this@MainActivity, pixel, pal.primaryContainer, d(20).toFloat())
        typeface = Look.font(this@MainActivity, pixel, true)
        setPadding(d(16), 0, d(16), 0)
        stateListAnimator = null
        setOnClickListener { click() }
        setOnFocusChangeListener { v, f -> v.foreground = if (f) Look.shape(this@MainActivity, pixel, 0, d(20).toFloat(), d(3), pal.primary) else null }
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

    private val pixel get() = prefs.uiStyle == UiStyle.PIXEL

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
        hc.addView(text(tr("Huginn denkt mit (Control Center) · Muninn erinnert sich (Game Switcher)"), 13f, pal.onSurfaceVariant))
        head.addView(hc)
        content.addView(head)

        // Status
        val running = HugminnService.instance != null
        val rootOk = Root.available()
        val st = section("Status")
        st.addView(text(if (running) tr("✓ Hugminn ist aktiv") else tr("✕ Hugminn ist noch nicht aktiv"), 16f, pal.onSurface, bold = true).apply {
            setPadding(0, d(6), 0, d(2))
        })
        st.addView(text(
            if (rootOk) tr("Root-Dienst des Handhelds gefunden: Schalter für Leistung, Lüfter, LEDs usw. funktionieren.")
            else tr("Kein Root-Dienst gefunden: Game Switcher und Graphen gehen, Geräteschalter öffnen die Android-Einstellungen."),
            12f, pal.onSurfaceVariant,
        ))
        if (Device.hasWhitelist(this)) {
            val wl = Device.whitelisted(this)
            st.addView(text(
                if (wl) tr("✓ Auf der Hintergrund-Whitelist: Das System beendet Hugminn nicht.")
                else tr("✕ Nicht auf der Hintergrund-Whitelist: Das System kann Hugminn im Hintergrund beenden."),
                13f, pal.onSurface,
            ).apply { setPadding(0, d(8), 0, 0) })
            if (!wl && rootOk) {
                val b = LinearLayout(this).apply { setPadding(0, d(8), 0, d(4)) }
                b.addView(button(tr("Zur Whitelist hinzufügen")) {
                    thread {
                        Device.ensureWhitelist(this)
                        runOnUiThread { build() }
                    }
                })
                st.addView(b)
            }
        }
        Crash.last(this)?.let { crash ->
            st.addView(text(t("Letzter Fehler:", "Last error:"), 13f, pal.onSurface, bold = true).apply { setPadding(0, d(10), 0, d(2)) })
            st.addView(text(crash.lines().take(6).joinToString("\n"), 11f, pal.onSurfaceVariant).apply {
                maxLines = 6
                typeface = android.graphics.Typeface.MONOSPACE
            })
            val cb = LinearLayout(this).apply { setPadding(0, d(6), 0, d(4)) }
            cb.addView(button(tr("Fehler löschen")) { Crash.clear(this); build() })
            st.addView(cb)
        }
        if (!running) {
            val b = LinearLayout(this).apply { setPadding(0, d(8), 0, d(4)) }
            b.addView(button(tr("Aktivieren")) { enable(rootOk) })
            st.addView(b)
        }

        // Gestures
        val g = section(tr("Home-Taste"))
        val actions = Action.values().toList()
        row(g, tr("Einmal drücken"), null, { prefs.single.label }) { prefs.single = cycle(actions, prefs.single) }
        row(g, tr("Zweimal drücken"), null, { prefs.double.label }) { prefs.double = cycle(actions, prefs.double) }
        row(g, tr("Lange drücken"), null, { prefs.long.label }) { prefs.long = cycle(actions, prefs.long) }
        row(g, tr("Zeit für Doppeldruck"), tr("Kürzer = einfacher Druck reagiert schneller"), { "${prefs.doubleMs} ms" }) {
            prefs.doubleMs = cycle(listOf(220, 260, 300, 350, 400), prefs.doubleMs)
        }
        row(g, tr("Zeit für langen Druck"), null, { "${prefs.longMs} ms" }) {
            prefs.longMs = cycle(listOf(350, 450, 600, 800), prefs.longMs)
        }

        val look = section(tr("Darstellung"))
        row(look, t("Sprache", "Language"), null, { prefs.language.label }) {
            prefs.language = cycle(Language.values().toList(), prefs.language)
            Lang.init(this)
            HugminnService.instance?.overlay?.refresh()
            content.post { build() }
        }
        row(look, tr("Stil"), tr("Gilt überall: Control Center, Game Switcher, Overlay und diese Einstellungen"), { prefs.uiStyle.label }) {
            prefs.uiStyle = cycle(UiStyle.values().toList(), prefs.uiStyle)
            content.post { build() }
        }
        row(look, tr("Kachelfarbe"), tr("Farbe aktiver Kacheln im Control Center"), { TILE_COLORS[prefs.tileColor] ?: "" }) {
            prefs.tileColor = cycle(TILE_COLORS.keys.toList(), prefs.tileColor)
        }
        row(look, tr("Farbschema"), tr("OLED-Schwarz: echtes Schwarz als Hintergrund, spart auf OLED-Bildschirmen Strom"), { prefs.theme.label }) {
            prefs.theme = cycle(Theme.values().toList(), prefs.theme)
            content.post { build() } // all colours change
        }

        // Switcher look
        val sw = section("Game Switcher")
        row(sw, tr("Eigene Übersicht"), tr("An: Hugminn zeigt die letzten Apps selbst (mit eigenen Vorschaubildern), Androids Übersicht bleibt zu. Aus: Rahmen über Androids Übersicht"), { if (prefs.ownSwitcher) tr("An") else tr("Aus") }) {
            prefs.ownSwitcher = !prefs.ownSwitcher
        }
        row(sw, tr("Spiel pausieren"), tr("Solange die eigene Übersicht offen ist, wird die App dahinter verdeckt und hält an (auch der Ton)"), { if (prefs.pauseInSwitcher) tr("An") else tr("Aus") }) {
            prefs.pauseInSwitcher = !prefs.pauseInSwitcher
        }
        row(sw, tr("Auch bei Wischgeste"), tr("Hugminn legt sich automatisch über die Android-Übersicht, egal wie sie geöffnet wird"), { if (prefs.autoSwitcher) tr("An") else tr("Aus") }) {
            prefs.autoSwitcher = !prefs.autoSwitcher
        }
        row(sw, tr("Statusleiste"), tr("Was der Rahmen oben zeigt: Uhrzeit und Akku im gewählten Stil, nichts, oder die normale Android-Leiste"), { prefs.statusBar.label }) {
            prefs.statusBar = cycle(StatusBar.values().toList(), prefs.statusBar)
        }

        val ccs = section("Control Center")
        row(ccs, tr("Statusleiste"), tr("Oben im Control Center: Uhrzeit und Akku im gewählten Stil, nichts, oder die normale Android-Leiste"), { prefs.ccStatusBar.label }) {
            prefs.ccStatusBar = cycle(StatusBar.values().toList(), prefs.ccStatusBar)
        }
        row(ccs, tr("LED-App"), tr("Wer die Farben der Stick-LEDs einstellt: Hugminn selbst (wie Retroids LED-Fenster) oder eine installierte LED-App"), {
            val cur = prefs.ledApp
            if (cur.isEmpty()) "Hugminn" else Apps.ledApps(this).firstOrNull { it.second == cur }?.first ?: tr("Nicht installiert")
        }) {
            val options = listOf("") + Apps.ledApps(this).map { it.second }
            prefs.ledApp = cycle(options, prefs.ledApp.takeIf { it in options } ?: "")
        }
        row(ccs, tr("Farben der Anzeigen"), tr("Färbt CPU, GPU, Leistung, RAM und Temperatur nach Auslastung"), { prefs.gaugePalette.label }) {
            prefs.gaugePalette = cycle(GaugePalette.values().toList(), prefs.gaugePalette)
        }
        ccs.addView(text(tr("Angezeigte Kacheln. Layout, Reihenfolge und Farben: im Control Center Y drücken. Mehr als 8 werden auf Seiten verteilt (LB/RB)."), 13f, pal.onSurfaceVariant).apply {
            setPadding(0, d(10), 0, d(2))
        })
        TileCatalog.all.forEach { (id, name) ->
            row(ccs, name, null, { if (id in prefs.ccTiles) tr("An") else tr("Aus") }) {
                val cur = prefs.ccTiles
                prefs.ccTiles = if (id in cur) cur - id else cur + id
            }
        }

        // Glyphs
        val gl = section(tr("Tastensymbole"))
        row(gl, tr("Stil"), tr("Automatisch folgt dem Layout in den Retroid-Einstellungen"), { prefs.glyphStyle.label }) {
            prefs.glyphStyle = cycle(GlyphStyle.values().toList(), prefs.glyphStyle)
        }
        row(gl, tr("Farbig"), null, { if (prefs.glyphColored) tr("An") else tr("Aus") }) { prefs.glyphColored = !prefs.glyphColored }
        val preview = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(6), 0, d(8))
        }
        val fillPreview = {
            preview.removeAllViews()
            preview.addView(text(tr("Vorschau:  "), 13f, pal.onSurfaceVariant))
            listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y).forEach { k ->
                preview.addView(Glyphs.chip(this, prefs, pal, k), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = d(8) })
            }
            preview.addView(text(if (Glyphs.nintendoLayout()) tr("  (Gerät im Retroid-/Nintendo-Layout)") else tr("  (Gerät im Xbox-Layout)"), 12f, pal.onSurfaceVariant))
        }
        fillPreview()
        afterChange += fillPreview
        gl.addView(preview)

        // Try it
        val t = section(tr("Ausprobieren"))
        val tr = LinearLayout(this).apply { setPadding(0, d(6), 0, d(6)) }
        tr.addView(button(tr("Game Switcher öffnen")) { HugminnService.instance?.perform(Action.SWITCHER) ?: notRunning() })
        tr.addView(View(this), LinearLayout.LayoutParams(d(12), 1))
        tr.addView(button(tr("Control Center öffnen")) { HugminnService.instance?.perform(Action.CONTROL_CENTER) ?: notRunning() })
        t.addView(tr)

        // Performance and fan per app
        HugminnService.instance?.let { svc ->
            val profiles = AppProfiles.all(svc)
            val ap = section(t("Profile pro App", "Profiles per app"))
            ap.addView(text(
                t("Im Control Center bei Leistung oder Lüfter „Für … merken“ wählen, während das Spiel läuft: " +
                    "die App startet dann immer mit diesem Profil, danach kommt die vorige Einstellung zurück.",
                    "In the Control Center, choose “Remember for …” under performance or fan while the game runs: " +
                        "the app then always starts with that profile, afterwards the previous setting comes back."),
                12f, pal.onSurfaceVariant,
            ).apply { setPadding(0, d(6), 0, d(6)) })
            if (profiles.isEmpty()) ap.addView(text(t("Noch keine gespeichert", "None saved yet"), 14f, pal.onSurface))
            profiles.forEach { (pkg, p) ->
                val parts = listOfNotNull(
                    p.perf?.let { Device.perfProfiles[it] ?: it },
                    p.fan?.let { t("Lüfter: ", "Fan: ") + (Device.fanProfiles[it] ?: it) },
                ).joinToString(" · ")
                row(ap, Apps.label(this, pkg), parts, { t("Entfernen", "Remove") }) {
                    AppProfiles.set(svc, pkg, "perf", null)
                    AppProfiles.set(svc, pkg, "fan", null)
                    content.post { build() }
                }
            }
        }

        // Backup
        val bk = section(t("Sichern", "Backup"))
        bk.addView(text(
            t("Alle Einstellungen (Layout, Kacheln, Farben, Profile pro App …) als Datei sichern, zum Beispiel vor einer Neuinstallation.",
                "Save all settings (layout, tiles, colours, profiles per app …) to a file, for example before reinstalling."),
            12f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(6), 0, d(6)) })
        val bkb = LinearLayout(this).apply { setPadding(0, 0, 0, d(6)) }
        bkb.addView(button(t("Sichern …", "Save …")) {
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "hugminn-settings.json"), REQ_EXPORT)
            }
        })
        bkb.addView(View(this), LinearLayout.LayoutParams(d(12), 1))
        bkb.addView(button(t("Wiederherstellen …", "Restore …")) {
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), REQ_IMPORT)
            }
        })
        bk.addView(bkb)

        // Report a problem: a prefilled GitHub issue (nothing is sent by Hugminn itself)
        val bug = section(tr("Fehler melden"))
        bug.addView(text(
            tr("Öffnet auf GitHub eine neue Fehlermeldung, schon ausgefüllt mit Version, Gerät und dem letzten Fehler. ") +
                tr("Beschreib kurz, was passiert ist, und schick sie ab (dafür brauchst du ein GitHub-Konto)."),
            12f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(6), 0, d(6)) })
        val lastError = Crash.last(this)
        if (lastError != null) bug.addView(text(tr("Letzter Fehler: ") + lastError.lineSequence().first(), 12f, pal.onSurface).apply { setPadding(0, 0, 0, d(6)) })
        val bb = LinearLayout(this).apply { setPadding(0, 0, 0, d(6)) }
        bb.addView(button(tr("Fehler auf GitHub melden")) { reportBug() })
        if (lastError != null) {
            bb.addView(View(this), LinearLayout.LayoutParams(d(12), 1))
            bb.addView(button(tr("Fehler vergessen")) { Crash.clear(this); build() })
        }
        bug.addView(bb)

        // Feedback upload
        val fb = section("Feedback an Claude")
        fb.addView(text(
            "Drücke Select, während der Game Switcher oder das Control Center offen ist: Hugminn lädt einen Screenshot " +
                "und eine Beschreibung des Bildschirms in das private Repository Hugminn-Feedback hoch. Dafür braucht Hugminn einen eigenen " +
                "GitHub-Token mit Schreibrecht: Repository Hugminn-Feedback, Berechtigung „Contents: Read and write“.",
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
        val a = section(tr("Über"))
        a.addView(text(
            tr("In der nordischen Sage hat Odin zwei Raben: Huginn (Gedanke) und Muninn (Erinnerung). ") +
                tr("Hugminn vereint beide: das Control Center denkt für dich mit, der Game Switcher merkt sich deine Spiele."),
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

    private fun notRunning() = Toast.makeText(this, tr("Bitte zuerst Hugminn aktivieren"), Toast.LENGTH_SHORT).show()

    private fun enable(rootOk: Boolean) {
        if (!rootOk) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, tr("„Hugminn“ in der Liste einschalten"), Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, tr("„Hugminn“ in der Liste einschalten"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun reportBug() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val error = Crash.last(this)
        val body = buildString {
            appendLine(t("**Was ist passiert?**", "**What happened?**"))
            appendLine()
            appendLine(t("<!-- Kurz beschreiben: was hast du gemacht, was ist passiert, was hättest du erwartet? -->",
                "<!-- Briefly: what did you do, what happened, what did you expect? -->"))
            appendLine()
            appendLine(t("**Gerät**", "**Device**"))
            appendLine("- Hugminn $version")
            appendLine("- ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("- Style: ${prefs.uiStyle.name.lowercase()} · Root service: ${if (Root.available()) "yes" else "no"} · Language: ${prefs.language.name.lowercase()}")
            if (error != null) {
                appendLine()
                appendLine(t("**Letzter Fehler**", "**Last error**"))
                appendLine("```")
                appendLine(error.lines().take(18).joinToString("\n"))
                appendLine("```")
            }
        }
        val uri = android.net.Uri.parse("https://github.com/Ninte86hl/Hugminn/issues/new").buildUpon()
            .appendQueryParameter("title", tr("Fehler: "))
            .appendQueryParameter("body", body.take(6000))
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Toast.makeText(this, tr("Kein Browser gefunden"), Toast.LENGTH_LONG).show()
        }
    }


    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> Toast.makeText(this,
                if (Backup.export(this, uri)) t("Einstellungen gesichert", "Settings saved") else t("Sichern fehlgeschlagen", "Saving failed"),
                Toast.LENGTH_LONG).show()
            REQ_IMPORT -> {
                val n = Backup.import(this, uri)
                if (n < 0) Toast.makeText(this, t("Keine Hugminn-Sicherung", "Not a Hugminn backup"), Toast.LENGTH_LONG).show()
                else {
                    prefs = Prefs(this)
                    Lang.init(this)
                    HugminnService.instance?.let { svc -> runCatching { svc.overlay.refresh() } }
                    Toast.makeText(this, t("$n Einstellungen wiederhergestellt", "$n settings restored"), Toast.LENGTH_LONG).show()
                    build()
                }
            }
        }
    }

    private companion object {
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
    }

}

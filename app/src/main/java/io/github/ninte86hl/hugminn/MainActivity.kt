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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        prefs = Prefs(this)
        prefs.keepOldStyle()
        Lang.init(this)
    }

    override fun onResume() {
        super.onResume()
        // first start: the introduction
        if (!prefs.setupDone) {
            prefs.setupDone = true
            startActivity(Intent(this, SetupActivity::class.java))
        }
        build()
        // opened from the Control Center's "Background & tiles" menu: straight to the picture picker
        if (intent?.getBooleanExtra(EXTRA_PICK_BACKDROP, false) == true) {
            intent.removeExtra(EXTRA_PICK_BACKDROP)
            page = "look"
            build()
            pickBackdrop()
        }
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

    /** Settings are split into pages like Android's own settings; null = the overview. */
    private var page: String? = null

    // ---- emulator shortcuts: L1/R1 switch the emulator, keys are recorded by pressing them ----
    private var hotkeyEmus: List<String> = emptyList()
    private var hotkeyEmu: String? = null

    private fun switchEmu(dir: Int) {
        val l = hotkeyEmus
        if (l.isEmpty()) return
        hotkeyEmu = l[((l.indexOf(hotkeyEmu).coerceAtLeast(0) + dir) % l.size + l.size) % l.size]
        afterChange.forEach { it() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (page == "hotkeys" && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> { switchEmu(-1); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { switchEmu(1); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Waits for the buttons of a shortcut: everything held together counts, once they are let go. */
    private fun recordKeys(pkg: String, act: Hotkeys.Act) {
        val held = linkedSetOf<Int>()
        val combo = linkedSetOf<Int>()
        val msg = text(t("Halte die Tasten für „${act.label}“ gleichzeitig gedrückt und lass sie los.", "Hold the buttons for “${act.label}” together, then let go."), 15f, pal.onSurface).apply { setPadding(d(24), d(16), d(24), d(8)) }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(Apps.label(this, pkg))
            .setView(msg)
            .setNeutralButton(t("Nicht belegt", "Not set")) { _, _ -> Hotkeys.setKeys(this, pkg, act, intArrayOf()); afterChange.forEach { it() } }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .create()
        dlg.setOnKeyListener { d, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && combo.isEmpty()) return@setOnKeyListener false
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                    held += code; combo += code
                    msg.text = combo.joinToString(" + ") { Hotkeys.name(it) }
                }
                KeyEvent.ACTION_UP -> {
                    held -= code
                    if (held.isEmpty() && combo.isNotEmpty()) {
                        Hotkeys.setKeys(this, pkg, act, combo.toIntArray())
                        afterChange.forEach { it() }
                        d.dismiss()
                    }
                }
            }
            true
        }
        dlg.show()
    }

    private var builtPage: String? = "-"
    private val pageButtons = HashMap<String, Button>()

    private class Page(val id: String, val icon: IconView.Kind, val de: String, val en: String, val subDe: String, val subEn: String)

    private val pages = listOf(
        Page("look", IconView.Kind.PALETTE, "Darstellung", "Appearance", "Stil, Farben, Sprache, Tastensymbole", "Style, colours, language, button symbols"),
        Page("cc", IconView.Kind.SCREEN, "Control Center", "Control Center", "Statusleiste, Kacheln, LED-App", "Status bar, tiles, LED app"),
        Page("switcher", IconView.Kind.REFRESH, "Game Switcher", "Game Switcher", "Eigene Übersicht, Pausieren, Wischgeste", "Own overview, pausing, swipe gesture"),
        Page("home", IconView.Kind.GAMEPAD, "Tasten", "Buttons", "Home-Taste und Zusatztasten", "Home button and extra buttons"),
        Page("hotkeys", IconView.Kind.GAMEPAD, "Tastenkürzel", "Emulator shortcuts", "Welche Tasten in welchem Emulator was tun", "Which buttons do what in each emulator"),
        Page("shortcuts", IconView.Kind.PLAY, "Verknüpfungen", "Shortcuts", "Control Center und Spielwechsler ohne Taste öffnen", "Open the Control Center and switcher without a button"),
        Page("profiles", IconView.Kind.FAN, "Profile pro App", "Profiles per app", "Leistung und Lüfter je Emulator", "Performance and fan per emulator"),
        Page("backup", IconView.Kind.LOCK, "Sichern", "Backup", "Einstellungen sichern und wiederherstellen", "Save and restore settings"),
        Page("about", IconView.Kind.GEAR, "Hilfe & Info", "Help & about", "Ausprobieren, Fehler melden, Version", "Try it, report a problem, version"),
    )

    /** Which page a section belongs to (by its title). */
    private fun pageOf(title: String): String = when (title) {
        "Status" -> "status"
        tr("Home-Taste"), t("Zusatztasten", "Extra buttons") -> "home"
        t("Verknüpfungen", "Shortcuts") -> "shortcuts"
        t("Tastenkürzel", "Emulator shortcuts") -> "hotkeys"
        tr("Darstellung"), tr("Tastensymbole") -> "look"
        "Game Switcher" -> "switcher"
        "Control Center" -> "cc"
        t("Profile pro App", "Profiles per app") -> "profiles"
        t("Sichern", "Backup") -> "backup"
        else -> "about"
    }

    private fun section(title: String): LinearLayout {
        val cat = pageOf(title)
        val shown = if (page == null) cat == "status" else cat == page
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.shape(this@MainActivity, pixel, pal.surfaceHigh, d(20).toFloat())
            setPadding(d(16), d(8), d(16), d(8))
        }
        // sections of other pages are built but never shown
        if (!shown) return box
        content.addView(text(title, 14f, pal.primary, bold = true).apply { setPadding(d(4), d(18), 0, d(6)) })
        content.addView(box)
        return box
    }

    /** Icon of a line-icon kind as a drawable (for the page list). */
    private fun iconDrawable(kind: IconView.Kind, color: Int, sizeDp: Int): android.graphics.drawable.Drawable {
        val px = d(sizeDp)
        val v = IconView(this, kind, sizeDp).apply { setColor(color) }
        v.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, px, px)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply { setBounds(0, 0, px, px) }
    }

    /** The overview: one row per page, like the main list of Android's settings. */
    private fun pageList() {
        content.addView(text(t("Einstellungen", "Settings"), 14f, pal.primary, bold = true).apply { setPadding(d(4), d(18), 0, d(6)) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.shape(this@MainActivity, pixel, pal.surfaceHigh, d(20).toFloat())
            setPadding(d(8), d(8), d(8), d(8))
        }
        pages.forEach { pg ->
            val title = t(pg.de, pg.en)
            val sub = t(pg.subDe, pg.subEn)
            val label = android.text.SpannableStringBuilder().apply {
                append(title, android.text.style.StyleSpan(Typeface.BOLD), 0)
                append("\n")
                val st = length
                append(sub)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), st, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(pal.onSurfaceVariant), st, length, 0)
            }
            val b = button("") { page = pg.id; build() }
            pageButtons[pg.id] = b
            b.text = label
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.setTextColor(pal.onSurface)
            b.background = Look.shape(this, pixel, pal.surfaceHigh, d(16).toFloat())
            b.setPadding(d(14), d(10), d(14), d(10))
            b.compoundDrawablePadding = d(16)
            b.setCompoundDrawablesRelative(iconDrawable(pg.icon, pal.primary, 26), null, null, null)
            box.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        content.addView(box)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // back from a page to the overview, from the overview out of the app
        if (page != null) { page = null; build() } else {
            @Suppress("DEPRECATION") super.onBackPressed()
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
        afterChange += { self?.text = value() }
    }

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

    private val pixel get() = prefs.uiStyle == UiStyle.PIXEL

    private fun build() {
        // Structural changes rebuild the page; keep the scroll position and the focused button.
        // a new page starts at the top; back on the overview the page's row gets the focus again
        val pageChanged = page != builtPage
        val returnTo = if (pageChanged && page == null) builtPage else null
        builtPage = page
        val oldScroll = if (pageChanged) 0 else scroll?.scrollY ?: 0
        val oldFocus = if (pageChanged) -1 else buttons.indexOfFirst { it.isFocused }
        pageButtons.clear()
        afterChange.clear()
        pal = Palette(this)
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        buttons.clear()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(28), d(16), d(28), d(28))
        }

        // On a page: a back button and the page's name instead of the big header
        val cur = pages.firstOrNull { it.id == page }
        if (cur != null) {
            val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            bar.addView(button("‹  " + t("Zurück", "Back")) { page = null; build() })
            bar.addView(text(t(cur.de, cur.en), 24f, pal.onSurface, bold = true).apply { setPadding(d(18), 0, 0, 0) })
            content.addView(bar)
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
        if (cur == null) content.addView(head)

        // Status
        val running = HugminnService.instance != null
        val rootOk = Root.available()
        // Anbernic: keep helper.sh in the folder up to date (an update brings a new one; ▶ in Root Scripts starts it)
        if (Device.isAnbernic && android.os.Environment.isExternalStorageManager()) runCatching { AnbernicHelper.install(packageName) }
        // Anbernic without root: a one-line script for its "Root Scripts" grants Hugminn the system settings
        if (Device.isAnbernic && !rootOk && !Device.canWrite(this)) {
            val an = section(t("Anbernic freischalten", "Unlock on Anbernic"))
            an.addView(text(t("Ohne Root darf Hugminn Leistung, Lüfter und Beleuchtung nur anzeigen. Ein kleiner Helfer schaltet das Umschalten frei: Skripte anlegen, " +
                "in Anbernics Einstellungen → Advanced → Root Scripts → Import Script „hugminn-anbernic.sh“ aus dem Ordner hugminn wählen und unter Script Management mit ▶ starten. " +
                "Nach jedem Neustart einmal ▶ drücken. Läuft der Helfer, verschwindet dieser Abschnitt.",
                "Without root Hugminn may only show performance, fan and lighting. A small helper unlocks switching them: create the scripts, in Anbernic's " +
                    "settings → Advanced → Root Scripts → Import Script pick “hugminn-anbernic.sh” from the hugminn folder and start it with ▶ under Script Management. " +
                    "After every restart press ▶ once. While the helper runs, this section disappears."), 13f, pal.onSurfaceVariant)
                .apply { setPadding(0, d(8), 0, d(4)) })
            if (!android.os.Environment.isExternalStorageManager())
                row(an, t("Eigener Ordner „hugminn“", "Own folder “hugminn”"), t("Zugriff auf alle Dateien erlauben – dann liegen die Skripte im internen Speicher unter hugminn statt in Download", "Allow all files access – the scripts then live in internal storage under hugminn instead of Download"), { "" }) {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                }
            row(an, t("Skripte anlegen", "Create the scripts"), t("hugminn-anbernic.sh und helper.sh in ", "hugminn-anbernic.sh and helper.sh in ") + AnbernicHelper.dir().path.substringAfter("/0/"), { "" }) {
                val ok = runCatching { AnbernicHelper.install(packageName) }.isSuccess
                android.widget.Toast.makeText(this, if (ok) t("Angelegt: ", "Created: ") + AnbernicHelper.dir().path.substringAfter("/0/")
                    else t("Anlegen ging nicht", "Could not create"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
        // without root the firmware list can't be changed: Android's own "run in the background" exemption helps
        if (!rootOk && !Device.batteryFree(this)) {
            st.addView(text(t("✕ Android darf Hugminn im Hintergrund beenden (Akku-Optimierung). Dann reagiert die Home-Taste zeitweise nicht.",
                "✕ Android may end Hugminn in the background (battery optimisation). The Home button then stops working at times."), 13f, pal.onSurface).apply { setPadding(0, d(8), 0, 0) })
            val b = LinearLayout(this).apply { setPadding(0, d(8), 0, d(4)) }
            b.addView(button(t("Im Hintergrund erlauben", "Allow in the background")) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) }
                    .onFailure { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            })
            st.addView(b)
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

        if (page == null) pageList()

        // Gestures
        val g = section(tr("Home-Taste"))
        // "switcher + Control Center" only where there are two screens
        val actions = Action.values().filter { (it != Action.BOTH || Displays.second(this) != null) && it != Action.RETROID }
        if (Device.noSticks) g.addView(text(t("Pocket Classic: Die Taste unter dem Bildschirm ist hier die Home-Taste. Zurück geht über die B-Taste oder per Touch. Den Doppelklick erkennt die Firmware selbst.",
            "Pocket Classic: the button below the screen is the Home button here. Back is on the B button or touch. The firmware detects the double click itself."), 13f, pal.onSurfaceVariant).apply { setPadding(0, d(8), 0, d(4)) })
        row(g, tr("Einmal drücken"), null, { prefs.single.label }) { prefs.single = cycle(actions, prefs.single) }
        if (Device.isDuo) g.addView(text(t("Pocket Duo: Zweimal drücken öffnet Retroids eigenes Kontrollcenter – das erkennt die Firmware selbst. Lange drücken öffnet Spielwechsler und Control Center zusammen, je einer pro Bildschirm.",
            "Pocket Duo: a double press opens Retroid's own control center – the firmware detects it itself. Holding opens switcher and Control Center together, one on each screen."), 13f, pal.onSurfaceVariant).apply { setPadding(0, d(8), 0, d(4)) })
        else row(g, tr("Zweimal drücken"), null, { prefs.double.label }) { prefs.double = cycle(actions, prefs.double) }
        row(g, tr("Lange drücken"), null, { prefs.long.label }) { prefs.long = cycle(actions, prefs.long) }
        if (Device.isRetroid && !Device.noSticks) row(g, t("Zurück lange drücken", "Back held"), t("Kurz gedrückt bleibt es Zurück", "A short press stays Back"), { prefs.backLong.label }) { prefs.backLong = cycle(actions, prefs.backLong) }
        if (!Device.noSticks && !Device.isDuo) row(g, tr("Zeit für Doppeldruck"), tr("Kürzer = einfacher Druck reagiert schneller"), { "${prefs.doubleMs} ms" }) {
            prefs.doubleMs = cycle(listOf(220, 260, 300, 350, 400), prefs.doubleMs)
        }
        row(g, tr("Zeit für langen Druck"), null, { "${prefs.longMs} ms" }) {
            prefs.longMs = cycle(listOf(350, 450, 600, 800), prefs.longMs)
        }
        if (Device.noSticks) row(g, t("Doppelklick testen", "Test the double click"),
            t("Zeigt, was die Taste beim Doppelklick wirklich sendet, und schickt es als Bericht", "Shows what the button really sends on a double click, and sends it as a report"),
            { t("Starten", "Start") }) { startActivity(Intent(this, DeviceTestActivity::class.java).putExtra("double", true)) }
        row(g, t("Home-Taste am Controller", "Home button on a controller"),
            t("Die Home-/Guide-Taste eines Bluetooth- oder USB-Controllers (z. B. 8BitDo) wirkt wie die Home-Taste des Geräts",
                "The Home/Guide button of a Bluetooth or USB controller (e.g. 8BitDo) works like the handheld's Home button"),
            { if (prefs.padHome) tr("An") else tr("Aus") }) { prefs.padHome = !prefs.padHome }

        // extra buttons: back paddles (Odin 2, Pocket 6) or M1/M2 (Nova) – both arrive as C and Z
        val nova = android.os.Build.MODEL.contains("Nova", true)
        val ex = section(t("Zusatztasten", "Extra buttons"))
        ex.addView(text(t("Rückseitentasten, M1/M2 bzw. C/Z (Pocket Classic mit sechs Tasten). Eine belegte Taste erreicht das Spiel nicht mehr – frei gelassen funktioniert sie im Spiel wie bisher.",
            "Back paddles, M1/M2 or C/Z (six-button Pocket Classic). A button with an action no longer reaches the game – left free it works in games as before."), 13f, pal.onSurfaceVariant).apply { setPadding(0, d(8), 0, d(4)) })
        for ((code, name) in listOf(KeyEvent.KEYCODE_BUTTON_C to (if (nova) "M1" else if (Device.noSticks) "C" else t("Paddel links", "Left paddle")),
                KeyEvent.KEYCODE_BUTTON_Z to (if (nova) "M2" else if (Device.noSticks) "Z" else t("Paddel rechts", "Right paddle"))))
            for (long in listOf(false, true))
                row(ex, "$name · " + (if (long) tr("Lange drücken") else tr("Einmal drücken")), null,
                    { prefs.extraKey(code, long).label }) { prefs.setExtraKey(code, long, cycle(actions, prefs.extraKey(code, long))) }

        // starting the overlays without a button: home screen shortcuts and extra app icons for frontends
        val sc = section(t("Verknüpfungen", "Shortcuts"))
        for (sw in listOf(false, true)) row(sc,
            if (sw) t("Spielwechsler auf den Startbildschirm", "Game switcher on the home screen") else t("Control Center auf den Startbildschirm", "Control Center on the home screen"),
            t("Öffnet es per Tippen, ohne Taste", "Opens it with a tap, no button needed"), { t("Hinzufügen", "Add") }) {
            if (!OpenFromOutside.pin(this, sw)) android.widget.Toast.makeText(this,
                t("Dein Launcher unterstützt das nicht – nutze „Als eigene Apps anzeigen“", "Your launcher doesn't support this – use “Show as separate apps”"), android.widget.Toast.LENGTH_LONG).show()
        }
        row(sc, t("Als eigene Apps anzeigen", "Show as separate apps"),
            t("„Hugminn Control Center“ und „Hugminn Spielwechsler“ erscheinen in der App-Liste, z. B. für Cocoon oder ES-DE",
                "“Hugminn Control Center” and “Hugminn Game switcher” appear in the app list, e.g. for Cocoon or ES-DE"),
            { if (OpenFromOutside.appIcons(this)) tr("An") else tr("Aus") }) { OpenFromOutside.setAppIcons(this, !OpenFromOutside.appIcons(this)) }

        // emulator shortcuts for the switcher's help card (Y → Shortcuts)
        val emus = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().filter { Apps.typeOf(it) != AppType.APP }
            .sortedBy { Apps.label(this, it).lowercase() }
        val hk = section(t("Tastenkürzel", "Emulator shortcuts"))
        hk.addView(text(t("Hugminn zeigt diese Belegung im Spielwechsler (Y → Tastenkürzel). Am Emulator selbst ändert Hugminn nichts – stelle sie dort genauso ein oder passe sie hier an.",
            "Hugminn shows these shortcuts in the game switcher (Y → Shortcuts). It doesn't change the emulator – set it up the same way there, or adjust it here."), 13f, pal.onSurfaceVariant).apply { setPadding(0, d(4), 0, d(8)) })
        if (emus.isEmpty()) hk.addView(text(t("Keine Emulatoren gefunden", "No emulators found"), 14f, pal.onSurfaceVariant))
        else {
            hotkeyEmus = emus
            val cur = hotkeyEmu?.takeIf { it in emus } ?: emus.first()
            hotkeyEmu = cur
            row(hk, t("Emulator", "Emulator"), t("L1 / R1 wechselt den Emulator", "L1 / R1 switches the emulator"), { "◀  " + Apps.label(this, hotkeyEmu ?: cur) + "  ▶" }) { switchEmu(1) }
            row(hk, t("Belegung", "Layout"), t("Gängig: Select als Hotkey, wie bei EmuDeck, ROCKNIX oder Batocera", "Common: Select as hotkey, as on EmuDeck, ROCKNIX or Batocera"),
                { Hotkeys.mode(this, hotkeyEmu ?: cur).label }) { val e = hotkeyEmu ?: cur; Hotkeys.setMode(this, e, cycle(Hotkeys.Mode.values().toList(), Hotkeys.mode(this, e))) }
            for (act in Hotkeys.Act.values()) row(hk, act.label, t("Antippen und die Tasten drücken", "Tap, then press the buttons"), { Hotkeys.text(Hotkeys.comboOf(this, hotkeyEmu ?: cur, act)) }) {
                recordKeys(hotkeyEmu ?: cur, act)
            }
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
            Backdrop.prepareGlass(this) { build() }
            content.post { build() }
        }
        if (Root.available()) row(look, t("Navigationsleiste ausblenden", "Hide the navigation bar"),
            t("Die Leiste bzw. der Gestenstrich unten verschwindet überall. Vom unteren Rand wischen holt sie kurz zurück",
                "The bar or gesture line at the bottom disappears everywhere. Swipe up from the bottom edge to bring it back for a moment"),
            { if (prefs.hideNav) tr("An") else tr("Aus") }) { NavBar.set(this, !prefs.hideNav) }
        row(look, tr("Kachelfarbe"), tr("Farbe aktiver Kacheln im Control Center"), { TILE_COLORS[prefs.tileColor] ?: "" }) {
            prefs.tileColor = cycle(TILE_COLORS.keys.toList(), prefs.tileColor)
        }
        row(look, tr("Farbschema"), tr("OLED-Schwarz: echtes Schwarz als Hintergrund, spart auf OLED-Bildschirmen Strom"), { prefs.theme.label }) {
            prefs.theme = cycle(Theme.values().toList(), prefs.theme)
            content.post { build() } // all colours change
        }

        // Background and tiles of the Control Center and the switcher
        val bgs = section(t("Hintergrund & Kacheln", "Background & tiles"))
        row(bgs, t("Hintergrund", "Background"),
            t("Was hinter Control Center und Game Switcher liegt. Android-Hintergrundbild braucht Root", "What lies behind the Control Center and the game switcher. The Android wallpaper needs root"),
            { prefs.backdrop.label }) {
            val options = BackdropSource.values().filter { it != BackdropSource.WALLPAPER || Root.available() }
            val next = cycle(options, prefs.backdrop)
            prefs.backdrop = next
            when (next) {
                BackdropSource.IMAGE -> if (!Backdrop.imageFile(this).exists()) pickBackdrop()
                BackdropSource.WALLPAPER -> Backdrop.fetchWallpaper(this) { ok ->
                    if (!ok) Toast.makeText(this, t("Hintergrundbild konnte nicht gelesen werden", "Couldn't read the wallpaper"), Toast.LENGTH_LONG).show()
                }
                BackdropSource.STYLE -> Unit
            }
            content.post { build() }
        }
        if (prefs.backdrop == BackdropSource.IMAGE) {
            row(bgs, t("Eigenes Bild", "Own picture"), t("Wird in Hugminn kopiert – das Original darfst du danach verschieben oder löschen",
                "Copied into Hugminn – you may move or delete the original afterwards"), { t("Wählen …", "Choose …") }) { pickBackdrop() }
        }
        if (prefs.backdrop == BackdropSource.WALLPAPER) {
            row(bgs, t("Hintergrundbild neu laden", "Reload wallpaper"), t("Nach einem Wechsel des Android-Hintergrundbilds", "After changing the Android wallpaper"),
                { t("Neu laden", "Reload") }) {
                Backdrop.fetchWallpaper(this) { ok ->
                    Toast.makeText(this, if (ok) t("Hintergrundbild übernommen", "Wallpaper taken over") else t("Hintergrundbild konnte nicht gelesen werden", "Couldn't read the wallpaper"), Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (Backdrop.source(this) != BackdropSource.STYLE) {
            val dark = Palette(this).dark
            row(bgs, if (dark) t("Abdunkeln", "Darken") else t("Aufhellen", "Lighten"),
                t("Damit Schrift und Werte auch auf hellen oder bunten Bildern gut lesbar bleiben", "So text and values stay readable on bright or colourful pictures"),
                { "${prefs.backdropDim} %" }) {
                prefs.backdropDim = cycle(listOf(0, 10, 20, 30, 40, 50, 60, 70, 80), prefs.backdropDim)
            }
            row(bgs, t("Weichzeichnen", "Blur"), t("Lässt das Bild verschwimmen, die Kacheln bleiben scharf", "Blurs the picture, the tiles stay sharp"),
                { listOf(tr("Aus"), t("Leicht", "Light"), t("Mittel", "Medium"), t("Stark", "Strong"))[prefs.backdropBlur.coerceIn(0, 3)] }) {
                prefs.backdropBlur = (prefs.backdropBlur + 1) % 4
                Backdrop.forget()
            }
        }
        row(bgs, t("Kacheln", "Tiles"),
            t("OLED-Schwarz: tiefschwarze Kacheln (nur im dunklen Farbschema). Glas: durchscheinend, am schönsten mit Hintergrundbild",
                "OLED black: pitch-black tiles (dark colour schemes only). Glass: see-through, nicest with a background picture"),
            { prefs.tileStyle.label }) {
            prefs.tileStyle = cycle(TileStyle.values().toList(), prefs.tileStyle)
        }
        row(bgs, t("Auswahl-Markierung", "Selection mark"),
            t("Wie das gewählte Element beim Steuern mit dem Steuerkreuz markiert wird. Automatisch: Ecken beim Stil Cocoon, sonst Rahmen",
                "How the selected element is marked when steering with the D-pad. Automatic: corners in the Cocoon style, otherwise a frame"),
            { prefs.focusStyle.label }) {
            prefs.focusStyle = cycle(FocusStyle.values().toList(), prefs.focusStyle)
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
        if (Displays.second(this) != null) row(sw, t("Spielwechsler + Control Center", "Switcher + Control Center"),
            t("Öffnen beide zusammen (Aktion für die Home-Taste): wo das Control Center hinkommt – der Spielwechsler nimmt den anderen Bildschirm. Steuerkreuz über den Rand wechselt zwischen beiden",
                "Open together (an action for the Home button): where the Control Center goes – the switcher takes the other screen. The D-pad past the edge moves between them"),
            { if (prefs.ccLower) t("Control Center unten", "Control Center below") else t("Control Center oben", "Control Center on top") }) { prefs.ccLower = !prefs.ccLower }
        if (Displays.second(this) != null && Prefs.dualReady(this)) {
            row(sw, t("Zweiter Bildschirm", "Second screen"),
                t("Name, Spiel, Tastenhinweise und Tastenkürzel des Emulators unten auf dem zweiten Bildschirm – oben bleibt mehr Platz für die Vorschaubilder",
                    "Name, game, button hints and the emulator's shortcuts on the second screen – more room for the previews up top"),
                { if (prefs.dualSwitcher) tr("An") else tr("Aus") }) { prefs.dualSwitcher = !prefs.dualSwitcher }
            row(sw, t("Player auf dem zweiten Bildschirm", "Player on the second screen"),
                t("Läuft oben Musik oder ein Video (Spotify, YouTube …), zeigt der untere Bildschirm Cover, Titel und Tasten – bei Videos nur den Titel. Den Balken antippen oder ziehen springt",
                    "While music or a video plays up top (Spotify, YouTube …), the lower screen shows cover, title and buttons – for videos only the title. Tap or drag the bar to jump"),
                { if (prefs.dualMedia) tr("An") else tr("Aus") }) { prefs.dualMedia = !prefs.dualMedia }
        }
        row(sw, t("Video-Apps immer quer", "Video apps always landscape"),
            t("YouTube, Netflix, Disney+, Prime Video und Crunchyroll werden komplett quer angezeigt, nicht nur Videos im Vollbild – auch Startseite, Suche und Menüs",
                "YouTube, Netflix, Disney+, Prime Video and Crunchyroll are shown fully in landscape, not only full-screen videos – also home page, search and menus"),
            { if (prefs.landscapeOn) tr("An") else tr("Aus") }) { prefs.landscapeOn = !prefs.landscapeOn }
        row(sw, tr("Statusleiste"), tr("Was der Rahmen oben zeigt: Uhrzeit und Akku im gewählten Stil, nichts, oder die normale Android-Leiste"), { prefs.statusBar.label }) {
            prefs.statusBar = cycle(StatusBar.values().toList(), prefs.statusBar)
        }

        val ccs = section("Control Center")
        row(ccs, t("Zeilen", "Rows"), t("Wie viele Reihen das Raster des Control Centers hat", "How many rows the Control Center grid has"), { "${prefs.gridRows}" }) {
            prefs.gridRows = cycle(listOf(3, 4, 5, 6), prefs.gridRows)
        }
        row(ccs, t("Spalten", "Columns"), t("Automatisch: so viele quadratische Felder, wie auf den Bildschirm passen", "Automatic: as many square cells as fit on the screen"),
            { if (prefs.gridCols == 0) t("Automatisch", "Automatic") else "${prefs.gridCols}" }) {
            prefs.gridCols = cycle(listOf(0, 4, 5, 6, 7, 8, 9, 10), prefs.gridCols)
        }
        row(ccs, t("Spiel pausieren", "Pause the game"), t("Solange das Control Center offen ist, hält das Spiel dahinter an (auch der Ton) und läuft danach weiter",
            "While the Control Center is open, the game behind it pauses (sound too) and goes on afterwards"), { if (prefs.pauseInCc) tr("An") else tr("Aus") }) {
            prefs.pauseInCc = !prefs.pauseInCc
        }
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
            preview.addView(text(if (Glyphs.nintendoLayout()) tr("  (Gerät im Retro-Layout)") else tr("  (Gerät im Modern-Layout)"), 12f, pal.onSurfaceVariant))
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
                t("Im Control Center bei Leistung oder Lüfter „Für … merken“ wählen, während das Spiel läuft – für den ganzen Emulator oder nur dieses Spiel: " +
                    "es startet dann immer mit diesem Profil, danach kommt die vorige Einstellung zurück. Ein Spielprofil geht vor dem des Emulators.",
                    "In the Control Center, choose “Remember for …” under performance or fan while the game runs – for the whole emulator or just this game: " +
                        "it then always starts with that profile, afterwards the previous setting comes back. A game's profile beats the emulator's."),
                12f, pal.onSurfaceVariant,
            ).apply { setPadding(0, d(6), 0, d(6)) })
            row(ap, t("Profil im Launcher", "Profile in the launcher"),
                t("Im Launcher laufen immer die Leistung und der Lüfter unten – spart Akku zwischen den Spielen. Beim Start einer App ohne Profil kommt die vorige Einstellung zurück.",
                    "In the launcher the performance and fan below always apply – saves battery between games. An app without a profile gets the previous setting back."),
                { if (svc.prefs.launcherProfile) tr("An") else tr("Aus") }) { svc.prefs.launcherProfile = !svc.prefs.launcherProfile; content.post { build() } }
            if (svc.prefs.launcherProfile) {
                val sysHome = runCatching {
                    packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo?.packageName?.takeIf { it != "android" }
                }.getOrNull()
                row(ap, t("Launcher", "Launcher"), t("Die App, die als Startbildschirm zählt", "The app that counts as the home screen"),
                    { svc.prefs.launcherPkg.ifBlank { null }?.let { Apps.label(this, it) }
                        ?: t("Automatisch", "Automatic") + (sysHome?.let { " (${Apps.label(this, it)})" } ?: "") }) {
                    val apps = Apps.labels(this).entries.map { it.key to it.value }.filter { it.second != packageName }
                        .distinctBy { it.second }.sortedBy { it.first.lowercase() }
                    val names = listOf(t("Automatisch", "Automatic") + (sysHome?.let { " (${Apps.label(this, it)})" } ?: "")) + apps.map { it.first }
                    android.app.AlertDialog.Builder(this).setTitle(t("Launcher wählen", "Choose launcher"))
                        .setItems(names.toTypedArray()) { _, i -> svc.prefs.launcherPkg = if (i == 0) "" else apps[i - 1].second; content.post { build() } }
                        .setNegativeButton(t("Abbrechen", "Cancel"), null).show()
                }
                if (Device.sys(svc, Device.perfKey) != null) row(ap, t("Leistung im Launcher", "Performance in the launcher"), null,
                    { Device.perfProfiles[svc.prefs.launcherPerf] ?: svc.prefs.launcherPerf }) {
                    svc.prefs.launcherPerf = cycle(Device.perfProfiles.keys.toList(), svc.prefs.launcherPerf)
                }
                if (Device.sys(svc, "fan_mode") != null) row(ap, t("Lüfter im Launcher", "Fan in the launcher"), null,
                    { Device.fanProfiles[svc.prefs.launcherFan] ?: svc.prefs.launcherFan }) {
                    svc.prefs.launcherFan = cycle(Device.fanProfiles.keys.toList(), svc.prefs.launcherFan)
                }
            }
            if (profiles.isEmpty()) ap.addView(text(t("Noch keine gespeichert", "None saved yet"), 14f, pal.onSurface))
            fun parts(p: AppProfiles.Profile) = listOfNotNull(
                p.perf?.let { Device.perfProfiles[it] ?: it },
                p.fan?.let { t("Lüfter: ", "Fan: ") + (Device.fanProfiles[it] ?: it) },
            ).joinToString(" · ")
            fun remove(key: String) {
                AppProfiles.set(svc, key, "perf", null)
                AppProfiles.set(svc, key, "fan", null)
                content.post { build() }
            }
            // grouped by emulator, so the same game on two systems cannot be mixed up:
            // "DuckStation · PlayStation" – "All games …" – "Crash Bandicoot 2 (only this game)"
            profiles.keys.groupBy { AppProfiles.pkgOf(it) }.toSortedMap(compareBy { Apps.label(this, it).lowercase() }).forEach { (pkg, keys) ->
                val name = Apps.label(this, pkg)
                val kind = Apps.platform(pkg) ?: Apps.typeOf(pkg).takeIf { it != AppType.APP }?.label
                ap.addView(text(listOfNotNull(name, kind).joinToString(" · "), 14f, pal.primary, bold = true).apply { setPadding(0, d(14), 0, d(2)) })
                profiles[pkg]?.let { p ->
                    row(ap, if (Apps.typeOf(pkg) == AppType.APP) t("Diese App", "This app") else t("Alle Spiele (Emulator-Profil)", "All games (emulator profile)"),
                        parts(p), { t("Entfernen", "Remove") }) { remove(pkg) }
                }
                keys.filter { it != pkg }.sortedBy { AppProfiles.titleOf(it)?.lowercase() }.forEach { key ->
                    val game = AppProfiles.titleOf(key) ?: return@forEach
                    row(ap, game, t("Nur dieses Spiel in $name · ", "Only this game in $name · ") + parts(profiles.getValue(key)), { t("Entfernen", "Remove") }) { remove(key) }
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
        val ib = LinearLayout(this).apply { setPadding(0, d(8), 0, d(4)) }
        ib.addView(button(t("Einführung erneut zeigen", "Show the introduction again")) { startActivity(Intent(this, SetupActivity::class.java)) })
        ib.addView(button(t("Gerätetest", "Device test")) { startActivity(Intent(this, DeviceTestActivity::class.java)) })
        a.addView(ib)
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
            (returnTo?.let { pageButtons[it] } ?: buttons.getOrNull(oldFocus)?.takeIf { it.isAttachedToWindow }
                ?: buttons.firstOrNull { it.isAttachedToWindow })?.requestFocus()
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
    /** Lets the user pick a picture for the background (copied in, see [Backdrop.importImage]). */
    private fun pickBackdrop() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_BACKDROP)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BACKDROP && resultCode != RESULT_OK && !Backdrop.imageFile(this).exists()) {
            // nothing picked and no picture yet: back to the style's own background
            prefs.backdrop = BackdropSource.STYLE
            build()
            return
        }
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_BACKDROP) {
            Thread {
                val ok = Backdrop.importImage(this, uri)
                runOnUiThread {
                    if (ok) prefs.backdrop = BackdropSource.IMAGE
                    Toast.makeText(this, if (ok) t("Bild übernommen", "Picture set") else t("Bild konnte nicht geladen werden", "Couldn't load the picture"), Toast.LENGTH_SHORT).show()
                    build()
                }
            }.start()
            return
        }
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

    companion object {
        internal const val REQ_EXPORT = 41
        internal const val REQ_IMPORT = 42
        internal const val REQ_BACKDROP = 43
        const val EXTRA_PICK_BACKDROP = "pickBackdrop"
    }

}

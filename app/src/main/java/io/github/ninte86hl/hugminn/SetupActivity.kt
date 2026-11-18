package io.github.ninte86hl.hugminn

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * First start: a few pages that explain Game Switcher and Control Center with a picture each,
 * and let the user switch off what they do not want. Can be started again from the settings.
 */
class SetupActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var pal: Palette
    private var step = 0
    private val steps = 6
    private val buttons = mutableListOf<Button>()

    private fun d(v: Int) = dp(v)
    private val pixel get() = prefs.uiStyle == UiStyle.PIXEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Lang.init(this)
        step = savedInstanceState?.getInt("step") ?: 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (step > 0) { step--; build() } else {
            @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------- small building blocks ----------

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Look.font(this@SetupActivity, pixel, bold)
    }

    private fun button(label: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary) pal.onPrimary else pal.onPrimaryContainer)
        background = Look.shape(this@SetupActivity, pixel, if (primary) pal.primary else pal.primaryContainer, d(20).toFloat())
        typeface = Look.font(this@SetupActivity, pixel, true)
        setPadding(d(18), 0, d(18), 0)
        stateListAnimator = null
        setOnClickListener { click() }
        setOnFocusChangeListener { v, f -> v.foreground = if (f) Look.shape(this@SetupActivity, pixel, 0, d(20).toFloat(), d(3), pal.onSurface) else null }
        buttons += this
    }

    private fun picture(name: String): View? = runCatching {
        val bmp = assets.open("wizard/$name.jpg").use { BitmapFactory.decodeStream(it) }
        ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            background = Look.shape(this@SetupActivity, pixel, pal.surfaceHigh, d(18).toFloat())
            contentDescription = name
        }
    }.getOrNull()

    /** A line "label ……… [value]" whose button changes the value in place. */
    private fun option(parent: LinearLayout, label: String, sub: String, value: () -> String, click: () -> Unit) {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(6), 0, d(6)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 16f, pal.onSurface, bold = true))
        col.addView(text(sub, 12f, pal.onSurfaceVariant))
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(12) })
        var self: Button? = null
        self = button(value()) { click(); self?.text = value() }
        r.addView(self)
        parent.addView(r)
    }

    private fun onOff(b: Boolean) = if (b) tr("An") else tr("Aus")

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

    /** Explanation of the buttons as a small table. */
    private fun keys(parent: LinearLayout, rows: List<Pair<String, String>>) {
        rows.forEach { (k, what) ->
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(3), 0, d(3)) }
            r.addView(text(k, 14f, pal.primary, bold = true), LinearLayout.LayoutParams(d(110), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(text(what, 14f, pal.onSurface))
            parent.addView(r)
        }
    }

    // ---------- pages ----------

    private fun build() {
        pal = Palette(this)
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        buttons.clear()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(40), d(22), d(40), d(16))
        }
        // progress: one dot per step
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in 0 until steps) {
            dots.addView(View(this).apply { background = rounded(if (i <= step) pal.primary else pal.surfaceHighest, 999f) },
                LinearLayout.LayoutParams(if (i == step) d(26) else d(10), d(10)).apply { marginEnd = d(8) })
        }
        dots.addView(text(t("Schritt ${step + 1} von $steps", "Step ${step + 1} of $steps"), 13f, pal.onSurfaceVariant).apply { setPadding(d(8), 0, 0, 0) })
        page.addView(dots)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(14), 0, d(8)) }
        when (step) {
            0 -> welcome(body)
            1 -> activate(body)
            2 -> switcherPage(body)
            3 -> ccPage(body)
            4 -> lookPage(body)
            else -> donePage(body)
        }
        page.addView(body)

        // navigation
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10), 0, 0) }
        if (step > 0) nav.addView(button("‹  " + t("Zurück", "Back")) { step--; build() })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (step < steps - 1) nav.addView(button(t("Überspringen", "Skip")) { finishSetup() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = d(12) })
        val next = button(if (step < steps - 1) t("Weiter  ›", "Next  ›") else t("Los geht's", "Let's go"), primary = true) {
            if (step < steps - 1) { step++; build() } else finishSetup()
        }
        nav.addView(next)
        page.addView(nav)

        val sv = ScrollView(this).apply {
            setBackgroundColor(pal.surface)
            isFillViewport = true
            addView(page)
        }
        setContentView(sv)
        sv.post { next.requestFocus() }
    }

    private fun twoColumns(parent: LinearLayout, pic: String?, fill: (LinearLayout) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.TOP }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fill(left)
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(28) })
        pic?.let { picture(it) }?.let { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        parent.addView(row)
    }

    private fun welcome(b: LinearLayout) = twoColumns(b, "cc") { c ->
        c.addView(text(t("Willkommen bei Hugminn", "Welcome to Hugminn"), 28f, pal.onSurface, bold = true))
        c.addView(text(
            t("Ein Control Center und ein Game Switcher für deinen Handheld, ganz mit den Tasten bedienbar – ohne deinen Launcher zu ersetzen.\n\n" +
                "Huginn (Gedanke) ist das Control Center, Muninn (Erinnerung) der Game Switcher. In ein paar Schritten zeige ich dir beide und du stellst ein, was du brauchst.",
                "A control center and a game switcher for your handheld, all controlled with the buttons – without replacing your launcher.\n\n" +
                    "Huginn (thought) is the Control Center, Muninn (memory) the Game Switcher. In a few steps you'll see both and choose what you need."),
            15f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(10), 0, d(14)) })
        option(c, t("Sprache", "Language"), t("Wie das System, Deutsch oder English", "Same as the system, German or English"), { prefs.language.label }) {
            prefs.language = cycle(Language.values().toList(), prefs.language)
            Lang.init(this)
            build()
        }
    }

    private fun activate(b: LinearLayout) {
        val running = HugminnService.instance != null
        val rootOk = Root.available()
        b.addView(text(t("Hugminn einschalten", "Turn Hugminn on"), 28f, pal.onSurface, bold = true))
        b.addView(text(
            t("Hugminn arbeitet als Bedienungshilfe: nur so kann es die Home-Taste und den Controller abfangen und sich über Spiele legen. " +
                "Hugminn liest nicht mit, was du tippst, und hat keine Internet-Berechtigung.",
                "Hugminn works as an accessibility service: only that way it can catch the Home button and the controller and appear over games. " +
                    "Hugminn does not read what you type and has no internet permission."),
            15f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(10), 0, d(14)) })
        b.addView(text(if (running) tr("✓ Hugminn ist aktiv") else tr("✕ Hugminn ist noch nicht aktiv"), 18f, pal.onSurface, bold = true))
        b.addView(text(
            if (rootOk) tr("Root-Dienst des Handhelds gefunden: Schalter für Leistung, Lüfter, LEDs usw. funktionieren.")
            else tr("Kein Root-Dienst gefunden: Game Switcher und Graphen gehen, Geräteschalter öffnen die Android-Einstellungen."),
            13f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(4), 0, d(10)) })
        if (!running) {
            val r = LinearLayout(this)
            r.addView(button(tr("Aktivieren"), primary = true) { enable(rootOk) })
            b.addView(r)
        }
    }

    private fun switcherPage(b: LinearLayout) = twoColumns(b, "switcher") { c ->
        c.addView(text("Muninn · Game Switcher", 26f, pal.onSurface, bold = true))
        c.addView(text(
            t("Deine zuletzt geöffneten Spiele und Apps als große Karten, mit dem Spieltitel darüber. Das Spiel dahinter pausiert, solange der Switcher offen ist.",
                "Your recently opened games and apps as large cards, with the game's title above. The game behind it pauses while the switcher is open."),
            14f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(8), 0, d(10)) })
        keys(c, listOf(
            t("Home 2×", "Home 2×") to t("Switcher öffnen", "Open the switcher"),
            "◀ ▶" to t("Spiel auswählen", "Choose a game"),
            "A" to t("Zum Spiel wechseln", "Switch to the game"),
            "X" to t("App schließen", "Close the app"),
            "Y" to t("Optionen", "Options"),
            "B" to t("Zurück ins Spiel", "Back to the game"),
        ))
        c.addView(View(this), LinearLayout.LayoutParams(1, d(10)))
        option(c, t("Game Switcher verwenden", "Use the game switcher"), t("Aus: Home 2× macht nichts", "Off: Home 2× does nothing"),
            { onOff(prefs.double == Action.SWITCHER) }) {
            prefs.double = if (prefs.double == Action.SWITCHER) Action.NONE else Action.SWITCHER
        }
        option(c, t("Spiel pausieren", "Pause the game"), t("Solange der Switcher offen ist", "While the switcher is open"), { onOff(prefs.pauseInSwitcher) }) {
            prefs.pauseInSwitcher = !prefs.pauseInSwitcher
        }
        option(c, t("Auch bei Wischgeste", "Also for the swipe gesture"), t("Statt Androids eigener Übersicht", "Instead of Android's own overview"), { onOff(prefs.autoSwitcher) }) {
            prefs.autoSwitcher = !prefs.autoSwitcher
        }
    }

    private fun ccPage(b: LinearLayout) = twoColumns(b, "edit") { c ->
        c.addView(text("Huginn · Control Center", 26f, pal.onSurface, bold = true))
        c.addView(text(
            t("Leistung, Lüfter, WLAN, Bluetooth, LEDs, Helligkeit, Lautstärke und Live-Werte auf einen Blick. Das Raster ordnest du selbst an: Y drücken und „Anordnen“ wählen.",
                "Performance, fan, Wi-Fi, Bluetooth, LEDs, brightness, volume and live values at a glance. You arrange the grid yourself: press Y and choose “Arrange”."),
            14f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(8), 0, d(10)) })
        keys(c, listOf(
            t("Home lang", "Home long") to t("Control Center öffnen", "Open the Control Center"),
            t("Steuerkreuz", "D-pad") to t("Feld auswählen", "Choose a tile"),
            "A" to t("Schalten / Regler bestätigen", "Toggle / confirm a slider"),
            "X" to t("Untermenü (WLAN, LEDs …)", "Submenu (Wi-Fi, LEDs …)"),
            "Y" to t("Bearbeiten", "Edit"),
            "Select" to "Screenshot",
        ))
        c.addView(View(this), LinearLayout.LayoutParams(1, d(10)))
        option(c, t("Control Center verwenden", "Use the Control Center"), t("Aus: Home lang macht nichts", "Off: Home long does nothing"),
            { onOff(prefs.long == Action.CONTROL_CENTER) }) {
            prefs.long = if (prefs.long == Action.CONTROL_CENTER) Action.NONE else Action.CONTROL_CENTER
        }
        option(c, t("Spiel pausieren", "Pause the game"), t("Solange das Control Center offen ist", "While the Control Center is open"), { onOff(prefs.pauseInCc) }) {
            prefs.pauseInCc = !prefs.pauseInCc
        }
        option(c, t("Leistungs-Overlay", "Performance overlay"), t("FPS, CPU, GPU und Temperatur über dem Spiel", "FPS, CPU, GPU and temperature over the game"), { onOff(prefs.overlayOn) }) {
            prefs.overlayOn = !prefs.overlayOn
            HugminnService.instance?.let { svc -> runCatching { svc.overlay.refresh() } }
        }
    }

    private fun lookPage(b: LinearLayout) {
        b.addView(text(t("Wie soll Hugminn aussehen?", "How should Hugminn look?"), 26f, pal.onSurface, bold = true))
        b.addView(text(t("Vier Stile, jeweils hell, dunkel oder OLED-Schwarz. Alles lässt sich später in den Einstellungen ändern.",
            "Four styles, each light, dark or OLED black. You can change everything later in the settings."), 14f, pal.onSurfaceVariant).apply { setPadding(0, d(8), 0, d(12)) })
        val pics = LinearLayout(this)
        listOf("cc" to "Modern", "pixel" to "Pixel", "dots" to tr("Punktmatrix")).forEachIndexed { i, (pic, name) ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            picture(pic)?.let { col.addView(it) }
            col.addView(text(name, 13f, pal.onSurfaceVariant).apply { setPadding(0, d(4), 0, 0) })
            pics.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = d(14) })
        }
        b.addView(pics)
        b.addView(View(this), LinearLayout.LayoutParams(1, d(10)))
        option(b, tr("Stil"), t("Modern, Pixel, Punktmatrix oder Cocoon", "Modern, Pixel, Dot matrix or Cocoon"), { prefs.uiStyle.label }) {
            prefs.uiStyle = cycle(UiStyle.values().toList(), prefs.uiStyle)
            build()
        }
        option(b, tr("Farbschema"), t("System, hell, dunkel oder OLED-Schwarz", "System, light, dark or OLED black"), { prefs.theme.label }) {
            prefs.theme = cycle(Theme.values().toList(), prefs.theme)
            build()
        }
    }

    private fun donePage(b: LinearLayout) = twoColumns(b, "overlay") { c ->
        c.addView(text(t("Fertig!", "All set!"), 30f, pal.onSurface, bold = true))
        c.addView(text(
            t("Probier es gleich aus: im Spiel Home zweimal drücken für den Game Switcher, Home lang drücken für das Control Center.\n\n" +
                "In den Einstellungen kannst du jederzeit alles anpassen, Profile pro Emulator anlegen und deine Einstellungen sichern. " +
                "Diese Einführung findest du dort unter „Hilfe & Info“.",
                "Try it right away: in a game press Home twice for the Game Switcher, hold Home for the Control Center.\n\n" +
                    "In the settings you can adjust everything at any time, create profiles per emulator and back up your settings. " +
                    "You'll find this introduction there under “Help & about”."),
            15f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(10), 0, 0) })
    }

    private fun finishSetup() {
        prefs.setupDone = true
        finish()
    }

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
                if (ok) window.decorView.postDelayed({ build() }, 600)
                else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this, tr("„Hugminn“ in der Liste einschalten"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

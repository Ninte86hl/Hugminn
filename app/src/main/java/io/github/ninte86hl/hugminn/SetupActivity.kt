package io.github.ninte86hl.hugminn

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
    private val steps = 7
    private val buttons = mutableListOf<View>()

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

    private fun picture(name: String, off: Boolean = false, heightDp: Int = 190): View? = runCatching {
        val bmp = assets.open("wizard/$name.jpg").use { BitmapFactory.decodeStream(it) }
        ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            clipToOutline = true
            background = Look.shape(this@SetupActivity, pixel, pal.surfaceHigh, d(18).toFloat())
            contentDescription = name
            // switched off: the picture fades
            alpha = if (off) 0.3f else 1f
        }
    }.getOrNull()

    /**
     * One setting as a large card (the whole card is the button): name, what the current value does,
     * and the value on the right. Changing it rebuilds the page, so picture and text follow the
     * choice; the focus stays on the card. Nothing on these pages scrolls.
     */
    private fun option(parent: LinearLayout, label: String, sub: () -> String, value: () -> String, click: () -> Unit) {
        val idx = buttons.size
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(6), d(12), d(6))
            background = Look.shape(this@SetupActivity, pixel, pal.surfaceHigh, d(16).toFloat())
            isFocusable = true
            isClickable = true
            setOnClickListener { click(); focusAfter = idx; build() }
            setOnFocusChangeListener { v, f -> v.foreground = if (f) Look.shape(this@SetupActivity, pixel, 0, d(16).toFloat(), d(3), pal.primary) else null }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 15f, pal.onSurface, bold = true))
        col.addView(text(sub(), 12f, pal.onSurfaceVariant).apply { maxLines = 3 })
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(10) })
        card.addView(text(value(), 14f, pal.onPrimaryContainer, bold = true).apply {
            background = Look.shape(this@SetupActivity, pixel, pal.primaryContainer, d(14).toFloat())
            setPadding(d(14), d(6), d(14), d(6))
        })
        buttons += card
        // side by side under picture and text
        parent.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (parent.childCount > 0) marginStart = d(12) })
    }

    /** Button to focus after a rebuild (-1: "next"). */
    private var focusAfter = -1

    /** Picture of the chosen style (and light/dark for Cocoon). */
    private fun stylePicture(): String {
        val base = when (prefs.uiStyle) {
            UiStyle.PIXEL -> "pixel"
            UiStyle.DOTS -> "dots"
            UiStyle.COCOON -> "cocoon"
            else -> "modern"
        }
        // light or dark picture of the style, whichever exists
        val want = "$base-${if (pal.dark) "dark" else "light"}"
        val names = runCatching { assets.list("wizard")?.toSet() }.getOrNull().orEmpty()
        return listOf(want, "$base-dark", "$base-light").firstOrNull { "$it.jpg" in names } ?: "modern-dark"
    }

    private fun onOff(b: Boolean) = if (b) tr("An") else tr("Aus")

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

    /** Explanation of the buttons as a small table. */
    /** Button → what it does; buttons given as key codes are drawn as the chosen symbols. */
    private fun keys(parent: LinearLayout, rows: List<Pair<Any, String>>) {
        rows.forEach { (k, what) ->
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(2), 0, d(2)) }
            val cell = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (k is Int) cell.addView(Glyphs.chip(this, prefs, pal, k, 22))
            else cell.addView(text(k.toString(), 13f, pal.primary, bold = true))
            r.addView(cell, LinearLayout.LayoutParams(d(100), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(text(what, 13f, pal.onSurface))
            parent.addView(r)
        }
    }

    // ---------- pages ----------

    private fun build() {
        pal = Palette(this)
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        buttons.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            setPadding(d(36), d(16), d(36), d(14))
        }
        // progress: one dot per step
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in 0 until steps) {
            dots.addView(View(this).apply { background = rounded(if (i <= step) pal.primary else pal.surfaceHighest, 999f) },
                LinearLayout.LayoutParams(if (i == step) d(26) else d(10), d(10)).apply { marginEnd = d(8) })
        }
        dots.addView(text(t("Schritt ${step + 1} von $steps", "Step ${step + 1} of $steps"), 13f, pal.onSurfaceVariant).apply { setPadding(d(8), 0, 0, 0) })
        root.addView(dots)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(12), 0, d(8)) }
        val opts = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        options = opts
        when (step) {
            0 -> welcome(body)
            1 -> activate(body)
            2 -> switcherPage(body)
            3 -> ccPage(body)
            4 -> lookPage(body)
            5 -> buttonsPage(body)
            else -> donePage(body)
        }
        // nothing scrolls (awkward with a controller): the page fits, the buttons stay at the bottom
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (opts.childCount > 0) root.addView(opts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(76)).apply { bottomMargin = d(6) })

        // navigation
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(8), 0, 0) }
        if (step > 0) nav.addView(button("‹  " + t("Zurück", "Back")) { step--; build() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)))
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (step < steps - 1) nav.addView(button(t("Überspringen", "Skip")) { finishSetup() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)).apply { marginEnd = d(12) })
        val next = button(if (step < steps - 1) t("Weiter  ›", "Next  ›") else t("Los geht's", "Let's go"), primary = true) {
            if (step < steps - 1) { step++; build() } else finishSetup()
        }
        nav.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)))
        root.addView(nav)

        setContentView(root)
        val f = focusAfter
        focusAfter = -1
        root.post { (buttons.getOrNull(f) ?: next).requestFocus() }
    }

    /** Row of setting cards under picture and text (filled by the page, added by build()). */
    private var options: LinearLayout? = null

    /** Picture on the left, explanation beside it; the setting cards go into one row below. */
    private fun twoColumns(parent: LinearLayout, pic: String?, off: Boolean = false, right: ((LinearLayout) -> Unit)? = null, fill: (LinearLayout) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.TOP }
        pic?.let { picture(it, off) }?.let {
            row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply { marginEnd = d(28) })
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fill(text)
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
        right?.let { r -> options?.let { r(it) } }
    }

    private fun welcome(b: LinearLayout) = twoColumns(b, stylePicture(), right = { c ->
        option(c, t("Sprache", "Language"), {
            when (prefs.language) {
                Language.AUTO -> t("Folgt der Sprache des Systems", "Follows the system language")
                Language.DE -> "Hugminn spricht immer Deutsch"
                Language.EN -> "Hugminn always speaks English"
            }
        }, { prefs.language.label }) {
            prefs.language = cycle(Language.values().toList(), prefs.language)
            Lang.init(this)
        }
    }) { c ->
        c.addView(text(t("Willkommen bei Hugminn", "Welcome to Hugminn"), 26f, pal.onSurface, bold = true))
        c.addView(text(
            t("Ein Control Center und ein Game Switcher für deinen Handheld, ganz mit den Tasten bedienbar – ohne deinen Launcher zu ersetzen.\n\n" +
                "Huginn (Gedanke) ist das Control Center, Muninn (Erinnerung) der Game Switcher. In ein paar Schritten zeige ich dir beide und du stellst ein, was du brauchst.",
                "A control center and a game switcher for your handheld, all controlled with the buttons – without replacing your launcher.\n\n" +
                    "Huginn (thought) is the Control Center, Muninn (memory) the Game Switcher. In a few steps you'll see both and choose what you need."),
            15f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(10), 0, 0) })
    }

    private fun activate(b: LinearLayout) {
        val running = HugminnService.instance != null
        val rootOk = Root.available()
        b.addView(text(t("Hugminn einschalten", "Turn Hugminn on"), 26f, pal.onSurface, bold = true))
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
            r.addView(button(tr("Aktivieren"), primary = true) { enable(rootOk) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)))
            b.addView(r)
        }
    }

    private fun switcherPage(b: LinearLayout) {
        val on = prefs.double == Action.SWITCHER
        twoColumns(b, "switcher", off = !on, right = { c ->
            option(c, t("Game Switcher", "Game Switcher"), {
                if (prefs.double == Action.SWITCHER) t("Home 2× öffnet den Switcher", "Home 2× opens the switcher")
                else t("Aus: Home 2× macht nichts", "Off: Home 2× does nothing")
            }, { onOff(prefs.double == Action.SWITCHER) }) {
                prefs.double = if (prefs.double == Action.SWITCHER) Action.NONE else Action.SWITCHER
            }
            if (on) {
                option(c, t("Spiel pausieren", "Pause the game"), {
                    if (prefs.pauseInSwitcher) t("Spiel und Ton halten an, solange der Switcher offen ist", "Game and sound stop while the switcher is open")
                    else t("Das Spiel läuft hinter dem Switcher weiter", "The game keeps running behind the switcher")
                }, { onOff(prefs.pauseInSwitcher) }) { prefs.pauseInSwitcher = !prefs.pauseInSwitcher }
                option(c, t("Wischgeste", "Swipe gesture"), {
                    if (prefs.autoSwitcher) t("Auch Wischen von unten öffnet Hugminns Switcher", "Swiping up also opens Hugminn's switcher")
                    else t("Wischen von unten öffnet Androids eigene Übersicht", "Swiping up opens Android's own overview")
                }, { onOff(prefs.autoSwitcher) }) { prefs.autoSwitcher = !prefs.autoSwitcher }
            }
        }) { c ->
            c.addView(text("Muninn · Game Switcher", 24f, pal.onSurface, bold = true))
            c.addView(text(
                t("Deine zuletzt geöffneten Spiele und Apps als große Karten, mit dem Spieltitel darüber.",
                    "Your recently opened games and apps as large cards, with the game's title above."),
                14f, pal.onSurfaceVariant,
            ).apply { setPadding(0, d(6), 0, d(10)) })
            keys(c, listOf(
                t("Home 2×", "Home 2×") to t("Switcher öffnen", "Open the switcher"),
                KeyEvent.KEYCODE_DPAD_LEFT to t("Spiel auswählen", "Choose a game"),
                KeyEvent.KEYCODE_BUTTON_A to t("Zum Spiel wechseln", "Switch to the game"),
                KeyEvent.KEYCODE_BUTTON_X to t("App schließen", "Close the app"),
                KeyEvent.KEYCODE_BUTTON_Y to t("Optionen", "Options"),
                KeyEvent.KEYCODE_BUTTON_B to t("Zurück ins Spiel", "Back to the game"),
            ))
        }
    }

    private fun ccPage(b: LinearLayout) {
        val on = prefs.long == Action.CONTROL_CENTER
        twoColumns(b, if (prefs.overlayOn && on) "overlay" else stylePicture(), off = !on, right = { c ->
            option(c, "Control Center", {
                if (prefs.long == Action.CONTROL_CENTER) t("Home lang drücken öffnet das Control Center", "Holding Home opens the Control Center")
                else t("Aus: Home lang macht nichts", "Off: holding Home does nothing")
            }, { onOff(prefs.long == Action.CONTROL_CENTER) }) {
                prefs.long = if (prefs.long == Action.CONTROL_CENTER) Action.NONE else Action.CONTROL_CENTER
            }
            if (on) {
                option(c, t("Spiel pausieren", "Pause the game"), {
                    if (prefs.pauseInCc) t("Spiel und Ton halten an, solange es offen ist", "Game and sound stop while it is open")
                    else t("Das Spiel läuft dahinter weiter", "The game keeps running behind it")
                }, { onOff(prefs.pauseInCc) }) { prefs.pauseInCc = !prefs.pauseInCc }
                option(c, t("Leistungs-Overlay", "Performance overlay"), {
                    if (prefs.overlayOn) t("FPS, CPU, GPU und Temperatur klein über dem Spiel (Bild)", "FPS, CPU, GPU and temperature small over the game (picture)")
                    else t("Kein Overlay über dem Spiel", "No overlay over the game")
                }, { onOff(prefs.overlayOn) }) {
                    prefs.overlayOn = !prefs.overlayOn
                    HugminnService.instance?.let { svc -> runCatching { svc.overlay.refresh() } }
                }
            }
        }) { c ->
            c.addView(text("Huginn · Control Center", 24f, pal.onSurface, bold = true))
            c.addView(text(
                t("Leistung, Lüfter, WLAN, Bluetooth, LEDs, Helligkeit, Lautstärke und Live-Werte auf einen Blick. Das Raster ordnest du selbst an.",
                    "Performance, fan, Wi-Fi, Bluetooth, LEDs, brightness, volume and live values at a glance. You arrange the grid yourself."),
                14f, pal.onSurfaceVariant,
            ).apply { setPadding(0, d(6), 0, d(10)) })
            keys(c, listOf(
                t("Home lang", "Home long") to t("Control Center öffnen", "Open the Control Center"),
                KeyEvent.KEYCODE_DPAD_LEFT to t("Feld auswählen", "Choose a tile"),
                KeyEvent.KEYCODE_BUTTON_A to t("Schalten / Regler bestätigen", "Toggle / confirm a slider"),
                KeyEvent.KEYCODE_BUTTON_X to t("Untermenü (WLAN, LEDs …)", "Submenu (Wi-Fi, LEDs …)"),
                KeyEvent.KEYCODE_BUTTON_Y to t("Bearbeiten und anordnen", "Edit and arrange"),
                "Select" to "Screenshot",
            ))
        }
    }

    private fun lookPage(b: LinearLayout) = twoColumns(b, stylePicture(), right = { c ->
        option(c, tr("Stil"), {
            when (prefs.uiStyle) {
                UiStyle.PIXEL -> t("Pixel-Schrift und stufige Ecken wie beim Retroid Pocket Classic", "Pixel font and stepped corners like the Retroid Pocket Classic")
                UiStyle.DOTS -> t("Schwarz-weiß aus Punkten mit einer Akzentfarbe, wie ein Punktmatrix-Display", "Black and white dots with one accent colour, like a dot-matrix display")
                UiStyle.COCOON -> t("Hell und verspielt mit bunten Symbolen, passend zum Cocoon-Launcher", "Light and playful with colourful icons, matching the Cocoon launcher")
                else -> t("Material You: Farben passend zu deinem Hintergrundbild, runde Formen", "Material You: colours matching your wallpaper, round shapes")
            }
        }, { prefs.uiStyle.label }) { prefs.uiStyle = cycle(UiStyle.values().toList(), prefs.uiStyle) }
        option(c, tr("Farbschema"), {
            when (prefs.theme) {
                Theme.LIGHT -> t("Immer hell", "Always light")
                Theme.DARK -> t("Immer dunkel", "Always dark")
                Theme.OLED -> t("Echtes Schwarz, spart Strom auf OLED-Bildschirmen", "True black, saves power on OLED screens")
                else -> t("Folgt dem Dunkelmodus von Android", "Follows Android's dark mode")
            }
        }, { prefs.theme.label }) { prefs.theme = cycle(Theme.values().toList(), prefs.theme) }
    }) { c ->
        c.addView(text(t("Wie soll Hugminn aussehen?", "How should Hugminn look?"), 26f, pal.onSurface, bold = true))
        c.addView(text(t("Das Bild zeigt das Control Center im gewählten Stil. Alles lässt sich später in den Einstellungen ändern, auch die Kachelfarbe.",
            "The picture shows the Control Center in the chosen style. You can change everything later in the settings, the tile colour too."),
            14f, pal.onSurfaceVariant).apply { setPadding(0, d(8), 0, 0) })
    }

    /** Button symbols: Xbox, Nintendo or PlayStation, coloured or plain, shown large. */
    private fun buttonsPage(b: LinearLayout) {
        val row = LinearLayout(this).apply { gravity = Gravity.TOP }
        // the four face buttons in their places (diamond), large, instead of a picture
        val diamond = android.widget.FrameLayout(this).apply {
            background = Look.shape(this@SetupActivity, pixel, pal.surfaceHigh, d(18).toFloat())
        }
        val g = 56
        fun put(k: Int, x: Int, y: Int) = diamond.addView(Glyphs.chip(this, prefs, pal, k, g),
            android.widget.FrameLayout.LayoutParams(d(g), d(g)).apply { leftMargin = d(x); topMargin = d(y) })
        // physical positions follow the device layout (A right on Retroid/Nintendo, A bottom on Xbox)
        val nin = Glyphs.nintendoLayout()
        val bottom = if (nin) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        val right = if (nin) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        val left = if (nin) KeyEvent.KEYCODE_BUTTON_Y else KeyEvent.KEYCODE_BUTTON_X
        val top = if (nin) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y
        put(top, 92, 16); put(left, 30, 76); put(right, 154, 76); put(bottom, 92, 136)
        row.addView(diamond, LinearLayout.LayoutParams(d(240), d(210)).apply { marginEnd = d(28) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(text(tr("Tastensymbole"), 26f, pal.onSurface, bold = true))
        c.addView(text(
            t("So zeigt Hugminn die Tasten in allen Hinweisen an. „Automatisch“ folgt dem Tastenlayout aus den Retroid-Einstellungen" +
                (if (nin) " – dein Gerät nutzt gerade das Retroid-/Nintendo-Layout (A rechts)." else " – dein Gerät nutzt gerade das Xbox-Layout (A unten)."),
                "This is how Hugminn shows the buttons in all hints. “Automatic” follows the button layout in the Retroid settings" +
                    (if (nin) " – your device currently uses the Retroid/Nintendo layout (A on the right)." else " – your device currently uses the Xbox layout (A at the bottom).")),
            14f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(8), 0, 0) })
        row.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(row)
        options?.let { o ->
            option(o, tr("Stil"), {
                when (prefs.glyphStyle) {
                    GlyphStyle.XBOX -> t("A, B, X, Y wie beim Xbox-Controller", "A, B, X, Y like the Xbox controller")
                    GlyphStyle.NINTENDO -> t("A, B, X, Y wie beim Nintendo-Controller", "A, B, X, Y like the Nintendo controller")
                    GlyphStyle.PLAYSTATION -> t("Kreuz, Kreis, Quadrat, Dreieck", "Cross, circle, square, triangle")
                    else -> t("Folgt dem Tastenlayout des Geräts", "Follows the device's button layout")
                }
            }, { prefs.glyphStyle.label }) { prefs.glyphStyle = cycle(GlyphStyle.values().toList(), prefs.glyphStyle) }
            option(o, tr("Farbig"), {
                if (prefs.glyphColored) t("Jede Taste in ihrer Farbe", "Each button in its colour") else t("Schlicht in der Textfarbe", "Plain in the text colour")
            }, { onOff(prefs.glyphColored) }) { prefs.glyphColored = !prefs.glyphColored }
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

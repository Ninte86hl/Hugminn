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
    private val steps = 10
    private val buttons = mutableListOf<View>()

    private fun d(v: Int) = dp(v)
    private val pixel get() = prefs.uiStyle == UiStyle.PIXEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Lang.init(this)
        step = savedInstanceState?.getInt("step") ?: 0
        // a new install: permissions only as the person allows them here
        if (!prefs.setupDone) prefs.askPermissions = true
    }

    override fun onDestroy() {
        stopTrying()
        HugminnService.keyProbe = null
        HugminnService.keyWatch = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
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

    /** Picture of the chosen style in light or dark (prefix "switcher-" for the switcher). */
    private fun stylePicture(prefix: String = ""): String {
        val base = when (prefs.uiStyle) {
            UiStyle.PIXEL -> "pixel"
            UiStyle.DOTS -> "dots"
            UiStyle.COCOON -> "cocoon"
            else -> "modern"
        }
        // light or dark picture of the style, whichever exists
        val want = "$prefix$base-${if (pal.dark) "dark" else "light"}"
        val names = runCatching { assets.list("wizard")?.toSet() }.getOrNull().orEmpty()
        return listOf(want, "$prefix$base-dark", "$prefix$base-light", "${prefix}modern-dark").firstOrNull { "$it.jpg" in names } ?: "modern-dark"
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
        // leaving the "try it" / button test pages ends their listening
        if (step != 3) stopTrying()
        if (step != 4) { keyTest = null; HugminnService.keyProbe = null; HugminnService.keyWatch = null }
        when (step) {
            0 -> welcome(body)
            1 -> activate(body)
            2 -> permissionsPage(body)
            3 -> tryPage(body)
            4 -> keyTestPage(body)
            5 -> switcherPage(body)
            6 -> ccPage(body)
            7 -> lookPage(body)
            8 -> buttonsPage(body)
            else -> donePage(body)
        }
        // nothing scrolls (awkward with a controller): the page fits, the buttons stay at the bottom
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (opts.childCount > 0) root.addView(opts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(76)).apply { bottomMargin = d(6) })

        // navigation
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(8), 0, 0) }
        if (step > 0) nav.addView(button("‹  " + t("Zurück", "Back")) { step--; build() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)))
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // trying out the switcher / Control Center and the button test have to be done (a way out
        // appears after 30 seconds, in case a button doesn't work – that is a result too)
        val gated = (step == 3 && !tryDone()) || (step == 4 && !keysDone)
        if (step < steps - 1 && !gated && step != 3 && step != 4) nav.addView(button(t("Überspringen", "Skip")) { finishSetup() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)).apply { marginEnd = d(12) })
        if (gated && System.currentTimeMillis() - gateSince > 30_000) nav.addView(button(t("Klappt nicht – weiter", "Doesn't work – continue")) {
            if (step == 3) { if (!sawSwitcher) failedSwitcher = true; if (!sawCc) failedCc = true }
            if (step == 4) finishKeys()
            step++; build()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)).apply { marginEnd = d(12) })
        val next = button(if (step < steps - 1) t("Weiter  ›", "Next  ›") else t("Los geht's", "Let's go"), primary = true) {
            if (gated) return@button
            if (step < steps - 1) { step++; build() } else finishSetup()
        }
        if (gated) next.alpha = 0.35f
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
                "Hugminn liest nicht mit, was du tippst, und hat keine Internet-Berechtigung. Alles Weitere fragt dich Hugminn gleich einzeln.",
                "Hugminn works as an accessibility service: only that way it can catch the Home button and the controller and appear over games. " +
                    "Hugminn does not read what you type and has no internet permission. Everything else Hugminn asks you one by one next."),
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
        twoColumns(b, stylePicture("switcher-"), off = !on, right = { c ->
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
                KeyEvent.KEYCODE_BUTTON_Y to t("Untermenü (WLAN, LEDs …)", "Submenu (Wi-Fi, LEDs …)"),
                KeyEvent.KEYCODE_BUTTON_X to t("Bearbeiten und anordnen", "Edit and arrange"),
            ))
        }
    }

    private fun lookPage(b: LinearLayout) = twoColumns(b, stylePicture(), right = { c ->
        option(c, tr("Stil"), {
            when (prefs.uiStyle) {
                UiStyle.PIXEL -> t("Pixel-Schrift und stufige Ecken wie beim Retroid Pocket Classic", "Pixel font and stepped corners like the Retroid Pocket Classic")
                UiStyle.DOTS -> t("Schwarz-weiß aus Punkten mit einer Akzentfarbe, wie ein Punktmatrix-Display", "Black and white dots with one accent colour, like a dot-matrix display")
                UiStyle.COCOON -> t("Hell und verspielt mit bunten Symbolen, passend zum Cocoon-Launcher", "Light and playful with colourful icons, matching the Cocoon launcher")
                UiStyle.GLASS -> t("Durchscheinende Glas-Kacheln über deinem weichgezeichneten Hintergrundbild", "See-through glass tiles over your blurred wallpaper")
                else -> t("Material You: Farben passend zu deinem Hintergrundbild, runde Formen", "Material You: colours matching your wallpaper, round shapes")
            }
        }, { prefs.uiStyle.label }) { prefs.uiStyle = cycle(UiStyle.values().toList(), prefs.uiStyle); Backdrop.prepareGlass(this) { } }
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

    /** Button symbols: Modern, Retro or Modern (alternative, shapes), coloured or plain, shown large. */
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
        // inside Hugminn X is always west and Y north (Glyphs.logical): the symbols show the real letter
        val left = KeyEvent.KEYCODE_BUTTON_X
        val top = KeyEvent.KEYCODE_BUTTON_Y
        put(top, 92, 16); put(left, 30, 76); put(right, 154, 76); put(bottom, 92, 136)
        row.addView(diamond, LinearLayout.LayoutParams(d(240), d(210)).apply { marginEnd = d(28) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(text(tr("Tastensymbole"), 26f, pal.onSurface, bold = true))
        c.addView(text(
            t("So zeigt Hugminn die Tasten in allen Hinweisen an. „Automatisch“ folgt dem Tastenlayout aus den Retroid-Einstellungen" +
                (if (nin) " – dein Gerät nutzt gerade das Retro-Layout (A rechts)." else " – dein Gerät nutzt gerade das Modern-Layout (A unten)."),
                "This is how Hugminn shows the buttons in all hints. “Automatic” follows the button layout in the Retroid settings" +
                    (if (nin) " – your device currently uses the Retro layout (A on the right)." else " – your device currently uses the Modern layout (A at the bottom).")),
            14f, pal.onSurfaceVariant,
        ).apply { setPadding(0, d(8), 0, 0) })
        row.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(row)
        options?.let { o ->
            option(o, tr("Stil"), {
                when (prefs.glyphStyle) {
                    GlyphStyle.XBOX -> t("A, B, X, Y im Modern-Layout (A unten)", "A, B, X, Y in the Modern layout (A at the bottom)")
                    GlyphStyle.NINTENDO -> t("A, B, X, Y im Retro-Layout (A rechts)", "A, B, X, Y in the Retro layout (A on the right)")
                    GlyphStyle.PLAYSTATION -> t("Modern mit Formen: Kreuz, Kreis, Quadrat, Dreieck", "Modern with shapes: cross, circle, square, triangle")
                    else -> t("Folgt dem Tastenlayout des Geräts", "Follows the device's button layout")
                }
            }, { prefs.glyphStyle.label }) { prefs.glyphStyle = cycle(GlyphStyle.values().toList(), prefs.glyphStyle) }
            option(o, tr("Farbig"), {
                if (prefs.glyphColored) t("Jede Taste in ihrer Farbe", "Each button in its colour") else t("Schlicht in der Textfarbe", "Plain in the text colour")
            }, { onOff(prefs.glyphColored) }) { prefs.glyphColored = !prefs.glyphColored }
        }
    }

    // ---------- permissions, asked one by one ----------

    private class Perm(val title: String, val why: String, val granted: () -> Boolean, val ask: () -> Unit)

    private fun runtime(vararg p: String) = p.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun perms(): List<Perm> {
        val rootOk = Root.available()
        val pkg = packageName
        fun rootThen(block: () -> Unit) = thread { block(); runOnUiThread { build() } }
        val list = mutableListOf(
            Perm(t("Bluetooth", "Bluetooth"),
                t("Damit du Kopfhörer und Controller im Control Center verbinden und ihren Akku sehen kannst.", "So you can connect headphones and controllers in the Control Center and see their battery."),
                { runtime(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT) },
                { requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT), 11) }),
            Perm(t("WLAN-Liste", "Wi-Fi list"),
                t("Android zeigt WLAN-Netze nur Apps mit Standortzugriff. Hugminn bestimmt nie, wo du bist.", "Android only shows Wi-Fi networks to apps with location access. Hugminn never finds out where you are."),
                { runtime(android.Manifest.permission.ACCESS_FINE_LOCATION) },
                { requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 12) }),
            Perm(t("Musik", "Music"),
                t("Damit die Musik-Kachel zeigt, was gerade läuft. Andere Mitteilungen liest Hugminn nicht.", "So the music tile shows what is playing. Hugminn doesn't read other notifications."),
                { Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().contains(pkg) },
                { if (rootOk) rootThen { runCatching { Media.enableListener(this) } } else startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
            Perm(t("Aufnahme", "Capture"),
                t("Für Videos mit Spielton und die Aufnahme-Mitteilung. Das Mikrofon wird nur bei einer Aufnahme benutzt.", "For videos with game sound and the recording notification. The microphone is only used while recording."),
                { runtime(android.Manifest.permission.RECORD_AUDIO) && (android.os.Build.VERSION.SDK_INT < 33 || runtime(android.Manifest.permission.POST_NOTIFICATIONS)) },
                {
                    if (rootOk) prefs.recNoAsk = true // no "Start recording?" each time
                    requestPermissions(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
                        else arrayOf(android.Manifest.permission.RECORD_AUDIO), 13)
                }),
            Perm(t("Im Hintergrund bleiben", "Stay in the background"),
                t("Damit das System Hugminn nicht beendet und die Home-Taste immer funktioniert.", "So the system doesn't end Hugminn and the Home button always works."),
                { if (rootOk) Device.whitelisted(this) || (Device.batteryFree(this) && !Device.hasWhitelist(this)) else Device.batteryFree(this) },
                { if (rootOk) rootThen { Device.ensureWhitelist(this) } else backgroundHelp() }),
        )
        // with root, brightness and similar values go through the root service, and the switcher reads the
        // task list itself: both cards are only needed without it
        if (!rootOk) list += Perm(t("Helligkeit & Systemwerte", "Brightness & system values"),
            t("Damit Helligkeit, Bildschirm-Aus-Zeit und ähnliche Regler im Control Center funktionieren.", "So brightness, screen timeout and similar sliders in the Control Center work."),
            { Settings.System.canWrite(this) },
            { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$pkg"))) })
        if (!rootOk) list += Perm(t("Zuletzt genutzte Apps", "Recent apps"),
            t("Ohne Root-Dienst erfährt der Spielwechsler nur so, welche Spiele zuletzt offen waren.", "Without the root service, this is how the switcher learns which games were open last."),
            { (getSystemService(android.app.AppOpsManager::class.java)?.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), pkg) == android.app.AppOpsManager.MODE_ALLOWED) },
            { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        return list
    }

    /** Without root: a short guide, then Android's own dialog (or the app's page to do it by hand). */
    private fun backgroundHelp() {
        val msg = t("Android und die Handheld-Firmware beenden Apps im Hintergrund, um Akku zu sparen. Dann reagiert die Home-Taste nicht mehr, bis Hugminn neu startet.\n\n" +
            "So geht's:\n1. Tippe auf „Jetzt erlauben“.\n2. Android fragt: „Darf Hugminn immer im Hintergrund laufen?“ – tippe auf „Zulassen“.\n\n" +
            "Kommt keine Frage: App-Info öffnen → Akku → „Nicht eingeschränkt“ wählen.",
            "Android and the handheld's firmware end apps in the background to save battery. Then the Home button stops working until Hugminn restarts.\n\n" +
                "How to:\n1. Tap “Allow now”.\n2. Android asks: “Let Hugminn always run in the background?” – tap “Allow”.\n\n" +
                "If nothing asks: open App info → Battery → choose “Unrestricted”.")
        styledDialog(t("Im Hintergrund bleiben", "Stay in the background"), null, text(msg, 14f, pal.onSurface), listOf(
            t("Später", "Later") to {},
            t("App-Info", "App info") to { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) },
            t("Jetzt erlauben", "Allow now") to {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) }
                    .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            },
        ))
    }

    private fun permissionsPage(b: LinearLayout) {
        b.addView(text(t("Was Hugminn darf", "What Hugminn may do"), 24f, pal.onSurface, bold = true))
        b.addView(text(t("Tippe auf „Erlauben“ bei allem, was du nutzen möchtest. Lehnst du etwas ab, fehlt nur die passende Funktion – du kannst es später in den Android-Einstellungen nachholen.",
            "Tap “Allow” for everything you want to use. If you decline something, only that feature is missing – you can change it later in Android's settings."),
            13f, pal.onSurfaceVariant).apply { setPadding(0, d(4), 0, d(8)) })
        val list = perms()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { perm ->
                val ok = perm.granted()
                val idx = buttons.size
                val card = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(d(14), d(6), d(10), d(6))
                    background = Look.shape(this@SetupActivity, pixel, pal.surfaceHigh, d(14).toFloat())
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { if (!perm.granted()) { focusAfter = idx; perm.ask() } }
                    setOnFocusChangeListener { v, f -> v.foreground = if (f) Look.shape(this@SetupActivity, pixel, 0, d(14).toFloat(), d(3), pal.primary) else null }
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text(perm.title, 14f, pal.onSurface, bold = true))
                col.addView(text(perm.why, 11f, pal.onSurfaceVariant).apply { maxLines = 3 })
                card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(8) })
                card.addView(text(if (ok) "✓" else t("Erlauben", "Allow"), 13f, if (ok) pal.primary else pal.onPrimaryContainer, bold = true).apply {
                    if (!ok) { background = Look.shape(this@SetupActivity, pixel, pal.primaryContainer, d(12).toFloat()); setPadding(d(12), d(5), d(12), d(5)) }
                })
                buttons += card
                row.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (row.childCount > 0) marginStart = d(10) })
            }
            // an odd last card: the empty half must not set the row's height (it squashed the card to nothing)
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = d(10) })
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = d(8) })
        }
        b.addView(android.widget.ScrollView(this).apply { addView(grid); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ---------- trying out switcher and Control Center ----------

    private var sawSwitcher = false
    private var sawCc = false
    private var failedSwitcher = false
    private var failedCc = false
    private var gateSince = 0L
    private var gateStep = -1

    private fun gestureFor(a: Action): String? = when (a) {
        prefs.double -> t("Drück zweimal kurz die Home-Taste", "Press the Home button twice quickly")
        prefs.long -> t("Halte die Home-Taste gedrückt", "Hold the Home button")
        prefs.single -> t("Drück einmal die Home-Taste", "Press the Home button once")
        else -> null
    }

    private fun needSwitcher() = gestureFor(Action.SWITCHER) != null
    private fun needCc() = gestureFor(Action.CONTROL_CENTER) != null
    private fun tryDone() = (sawSwitcher || !needSwitcher()) && (sawCc || !needCc())

    private fun startGate() {
        if (gateStep == step) return
        gateStep = step
        gateSince = System.currentTimeMillis()
        val s = step
        window.decorView.postDelayed({ if (step == s && !isFinishing) build() }, 30_500)
    }

    private fun stopTrying() { HugminnService.overlayOpened = null }

    private fun tryPage(b: LinearLayout) {
        startGate()
        val running = HugminnService.instance != null
        b.addView(text(t("Probier es aus", "Try it out"), 26f, pal.onSurface, bold = true))
        if (!running) {
            b.addView(text(t("Hugminn ist noch nicht aktiv – geh einen Schritt zurück und schalte es ein.", "Hugminn isn't active yet – go back one step and switch it on."), 15f, pal.onSurfaceVariant).apply { setPadding(0, d(10), 0, 0) })
            return
        }
        b.addView(text(t("Schau dir beide einmal an. Nach ein paar Sekunden kommst du automatisch hierher zurück.",
            "Have a look at both. After a few seconds you come back here by yourself."), 15f, pal.onSurfaceVariant).apply { setPadding(0, d(8), 0, d(16)) })
        fun line(done: Boolean, what: String, how: String?) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(6), 0, d(6)) }
            r.addView(text(if (done) "✓" else "○", 22f, if (done) pal.primary else pal.onSurfaceVariant, bold = true), LinearLayout.LayoutParams(d(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(what, 18f, pal.onSurface, bold = true))
            col.addView(text(if (done) t("Geschafft!", "Done!") else how ?: "", 14f, pal.onSurfaceVariant))
            r.addView(col)
            b.addView(r)
        }
        if (needSwitcher()) line(sawSwitcher, "Muninn · Game Switcher", gestureFor(Action.SWITCHER))
        if (needCc()) line(sawCc, "Huginn · Control Center", gestureFor(Action.CONTROL_CENTER))
        if (tryDone()) { stopTrying(); return }
        // the service tells when one of them opened; after 5 seconds it closes again and the wizard is back
        HugminnService.overlayOpened = { which ->
            if (which == "switcher") sawSwitcher = true else sawCc = true
            window.decorView.postDelayed({
                HugminnService.instance?.closeOverlays()
                window.decorView.postDelayed({ if (!isFinishing && step == 3) { if (tryDone()) { stopTrying(); step++ }; build() } }, 300)
            }, 5_000)
        }
    }

    // ---------- button test ----------

    private val testKeys = linkedMapOf(
        KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B", KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1", KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3", KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
        KeyEvent.KEYCODE_DPAD_UP to "▲", KeyEvent.KEYCODE_DPAD_DOWN to "▼", KeyEvent.KEYCODE_DPAD_LEFT to "◀", KeyEvent.KEYCODE_DPAD_RIGHT to "▶",
        KeyEvent.KEYCODE_VOLUME_UP to "Vol+", KeyEvent.KEYCODE_VOLUME_DOWN to "Vol−", KeyEvent.KEYCODE_HOME to "Home",
        KeyEvent.KEYCODE_BACK to "Back",
    )
    private val pressedKeys = mutableSetOf<Int>()
    private var keysDone = false
    private var keyTest: ((Int) -> Unit)? = null
    private var missingKeys: List<String> = emptyList()

    private fun finishKeys() {
        keysDone = true
        keyTest = null
        HugminnService.keyProbe = null
        HugminnService.keyWatch = null
        missingKeys = testKeys.filterKeys { it !in pressedKeys }.values.toList()
    }

    private fun keyTestPage(b: LinearLayout) {
        startGate()
        b.addView(text(t("Tastentest", "Button test"), 26f, pal.onSurface, bold = true))
        b.addView(text(t("Drück jede Taste einmal – so sehen wir, dass Hugminn alle erkennt. Die Felder werden grün.",
            "Press every button once – this shows that Hugminn recognises them all. The boxes turn green."), 14f, pal.onSurfaceVariant).apply { setPadding(0, d(6), 0, d(12)) })
        val labels = mutableMapOf<Int, TextView>()
        testKeys.entries.chunked(10).forEach { chunk ->
            val row = LinearLayout(this).apply { setPadding(0, 0, 0, d(8)) }
            chunk.forEach { (code, name) ->
                val on = code in pressedKeys
                val v = text(name, 15f, if (on) pal.onPrimary else pal.onSurfaceVariant, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Look.shape(this@SetupActivity, pixel, if (on) pal.primary else pal.surfaceHigh, d(10).toFloat())
                }
                labels[code] = v
                row.addView(v, LinearLayout.LayoutParams(0, d(46), 1f).apply { marginEnd = d(6) })
            }
            repeat(10 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = d(6) }) }
            b.addView(row)
        }
        if (keysDone) {
            b.addView(text(if (missingKeys.isEmpty()) t("✓ Alle Tasten erkannt", "✓ All buttons recognised") else t("Nicht erkannt: ", "Not recognised: ") + missingKeys.joinToString(),
                16f, pal.onSurface, bold = true).apply { setPadding(0, d(8), 0, 0) })
            return
        }
        val mark: (Int) -> Unit = { code ->
            if (code in testKeys && pressedKeys.add(code)) {
                labels[code]?.apply { setTextColor(pal.onPrimary); background = Look.shape(this@SetupActivity, pixel, pal.primary, d(10).toFloat()) }
                if (pressedKeys.size == testKeys.size) window.decorView.postDelayed({ finishKeys(); build() }, 500)
            }
        }
        keyTest = mark
        // Home belongs to Hugminn itself: it tells the wizard instead of opening anything
        HugminnService.keyProbe = { code -> runOnUiThread { mark(code) } }
        // the keys also come through the service: right after the switcher / Control Center closed, this
        // window may not have the focus yet, and the first presses would otherwise be lost
        HugminnService.keyWatch = { code -> if (step == 4 && keyTest != null) mark(code) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = keyTest ?: return super.dispatchKeyEvent(event)
        // after 30 seconds, buttons already tested may move the focus again (to reach "Doesn't work")
        if (event.keyCode in pressedKeys && System.currentTimeMillis() - gateSince > 30_000) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) k(event.keyCode)
        return true
    }

    // ---------- the end: summary to send ----------

    private val setupChecks = mutableListOf<DeviceCheck>()
    @Volatile private var reportReady = false
    private var reportStarted = false

    private fun setupResults(): List<DeviceCheck> {
        val area = t("Einrichtung", "Setup")
        val list = mutableListOf<DeviceCheck>()
        if (needSwitcher()) list += DeviceCheck(area, t("Spielwechsler geöffnet", "Game switcher opened"), if (sawSwitcher) CheckState.OK else CheckState.FAIL, if (failedSwitcher) t("ging nicht auf", "didn't open") else "")
        if (needCc()) list += DeviceCheck(area, t("Control Center geöffnet", "Control Center opened"), if (sawCc) CheckState.OK else CheckState.FAIL, if (failedCc) t("ging nicht auf", "didn't open") else "")
        list += DeviceCheck(area, t("Tastentest", "Button test"), if (keysDone && missingKeys.isEmpty()) CheckState.OK else CheckState.WARN,
            if (!keysDone) t("nicht gemacht", "not done") else if (missingKeys.isEmpty()) t("alle ${testKeys.size} erkannt", "all ${testKeys.size} recognised") else t("nicht erkannt: ", "not recognised: ") + missingKeys.joinToString())
        val granted = perms().map { (if (it.granted()) "✓ " else "✗ ") + it.title }
        list += DeviceCheck(area, t("Berechtigungen", "Permissions"), CheckState.OK, granted.joinToString(", "))
        return list
    }

    private fun showReport() {
        val all = setupResults() + synchronized(setupChecks) { setupChecks.toList() }
        val report = DeviceReport.text(this, all)
        styledDialog(t("Dein Bericht", "Your report"),
            t("Das wird gesendet – sonst nichts:", "This is what gets sent – nothing else:"),
            text(report, 12f, pal.onSurface),
            listOf(t("Nicht senden", "Don't send") to {}, t("Auf GitHub senden", "Send on GitHub") to { DeviceReport.share(this, all) }))
    }

    /** A dialog in the chosen style (font, corners, colours), with the last action as the main one. */
    private fun styledDialog(title: String, intro: String?, content: View, actions: List<Pair<String, () -> Unit>>) {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24), d(20), d(24), d(16))
            background = Look.shape(this@SetupActivity, pixel, pal.surfaceHigh, d(24).toFloat())
        }
        box.addView(text(title, 22f, pal.onSurface, bold = true))
        intro?.let { box.addView(text(it, 13f, pal.onSurfaceVariant).apply { setPadding(0, d(4), 0, d(8)) }) }
        box.addView(android.widget.ScrollView(this).apply {
            addView(content)
            background = Look.shape(this@SetupActivity, pixel, pal.surface, d(14).toFloat())
            setPadding(d(14), d(10), d(14), d(10))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, d(14), 0, 0) }
        var main: View? = null
        actions.forEachIndexed { i, (label, run) ->
            val primary = i == actions.lastIndex
            val bt = button(label, primary) { dlg.dismiss(); run() }
            buttons.remove(bt)
            if (primary) main = bt
            row.addView(bt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, d(46)).apply { if (i > 0) marginStart = d(10) })
        }
        box.addView(row)
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val m = resources.displayMetrics
            setLayout((m.widthPixels * 0.62f).toInt(), (m.heightPixels * 0.82f).toInt())
            setDimAmount(0.6f)
        }
        dlg.show()
        main?.requestFocus()
    }

    private fun donePage(b: LinearLayout) = twoColumns(b, "overlay", right = { c ->
        option(c, t("Bericht ansehen und senden", "View and send the report"), {
            if (reportReady) t("Öffnet ein ausgefülltes GitHub-Issue – du schickst es selbst ab", "Opens a filled-in GitHub issue – you send it yourself")
            else t("Wird vorbereitet …", "Being prepared …")
        }, { if (reportReady) "›" else "…" }) { if (reportReady) showReport() }
    }) { c ->
        if (!reportStarted) { reportStarted = true; thread {
            DeviceReport.auto(this) { area, name, state, note -> synchronized(setupChecks) { setupChecks += DeviceCheck(area, name, state, note) } }
            reportReady = true
            runOnUiThread { if (step == steps - 1) build() }
        } }
        c.addView(text(t("Fertig!", "All set!"), 30f, pal.onSurface, bold = true))
        c.addView(text(
            t("In den Einstellungen kannst du jederzeit alles anpassen. Diese Einführung findest du dort unter „Hilfe & Info“.\n\n" +
                "Möchtest du helfen, Hugminn auf mehr Geräten gut zu machen? Schick uns eine kurze Zusammenfassung: Gerät, Android-Version, welche Funktionen es unterstützt, " +
                "das Ergebnis des Tastentests und welche Berechtigungen du erlaubt hast. Keine Namen, keine WLAN-Namen, keine Spiele, kein Standort. Du siehst alles vorher.",
                "You can adjust everything in the settings at any time. You'll find this introduction there under “Help & about”.\n\n" +
                    "Want to help make Hugminn good on more devices? Send us a short summary: device, Android version, which features it supports, " +
                    "the button test result and which permissions you allowed. No names, no Wi-Fi names, no games, no location. You see everything first."),
            14f, pal.onSurfaceVariant,
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
            if (Device.autoGrant(this)) { Device.grantExtras(this); Device.ensureWhitelist(this) }
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

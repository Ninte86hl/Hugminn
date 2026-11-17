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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
    }

    override fun onResume() {
        super.onResume()
        build()
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
    private fun row(parent: LinearLayout, label: String, sub: String?, value: String, click: () -> Unit) {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(6), 0, d(6))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 16f, pal.onSurface))
        sub?.let { col.addView(text(it, 12f, pal.onSurfaceVariant)) }
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        var self: Button? = null
        self = button(value) {
            val idx = buttons.indexOf(self)
            click()
            build()
            buttons.getOrNull(idx)?.requestFocus()
        }
        r.addView(self)
        parent.addView(r)
    }

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

    private fun build() {
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
        if (!running) {
            val b = LinearLayout(this).apply { setPadding(0, d(8), 0, d(4)) }
            b.addView(button("Aktivieren") { enable(rootOk) })
            st.addView(b)
        }

        // Gestures
        val g = section("Home-Taste")
        val actions = Action.values().toList()
        row(g, "Einmal drücken", null, prefs.single.label) { prefs.single = cycle(actions, prefs.single) }
        row(g, "Zweimal drücken", null, prefs.double.label) { prefs.double = cycle(actions, prefs.double) }
        row(g, "Lange drücken", null, prefs.long.label) { prefs.long = cycle(actions, prefs.long) }
        row(g, "Zeit für Doppeldruck", "Kürzer = einfacher Druck reagiert schneller", "${prefs.doubleMs} ms") {
            prefs.doubleMs = cycle(listOf(220, 260, 300, 350, 400), prefs.doubleMs)
        }
        row(g, "Zeit für langen Druck", null, "${prefs.longMs} ms") {
            prefs.longMs = cycle(listOf(350, 450, 600, 800), prefs.longMs)
        }

        // Glyphs
        val gl = section("Tastensymbole")
        row(gl, "Stil", "Automatisch folgt dem Layout in den Retroid-Einstellungen", prefs.glyphStyle.label) {
            prefs.glyphStyle = cycle(GlyphStyle.values().toList(), prefs.glyphStyle)
        }
        row(gl, "Farbig", null, if (prefs.glyphColored) "An" else "Aus") { prefs.glyphColored = !prefs.glyphColored }
        val preview = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(6), 0, d(8))
        }
        preview.addView(text("Vorschau:  ", 13f, pal.onSurfaceVariant))
        listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y).forEach { k ->
            preview.addView(Glyphs.chip(this, prefs, pal, k), LinearLayout.LayoutParams(d(26), d(26)).apply { marginEnd = d(8) })
        }
        preview.addView(text(if (Glyphs.nintendoLayout()) "  (Gerät im Retroid-/Nintendo-Layout)" else "  (Gerät im Xbox-Layout)", 12f, pal.onSurfaceVariant))
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

        val scroll = ScrollView(this).apply {
            setBackgroundColor(pal.surface)
            addView(content)
        }
        setContentView(scroll)
        buttons.firstOrNull()?.requestFocus()
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

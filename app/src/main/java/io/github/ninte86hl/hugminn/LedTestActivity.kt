package io.github.ninte86hl.hugminn

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Guided LED zone test: lights one zone of the left stick after the other and asks after each one
 * what the user sees. The answers are uploaded as feedback at the end.
 */
class LedTestActivity : Activity() {
    private val zones = 0..8
    private var zone = 0
    private val answers = StringBuilder()
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var pal: Palette
    private var pixel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Palette(this)
        pixel = Look.pixel(this)
        window.statusBarColor = pal.surface
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(pal.surface)
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        title = label("", 22f, pal.onSurface, true)
        info = label("", 14f, pal.onSurfaceVariant, false).apply { setPadding(0, dp(8), 0, dp(20)); gravity = Gravity.CENTER }
        buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        root.addView(title)
        root.addView(info)
        root.addView(buttons)
        setContentView(root)
        thread {
            val drv = LedFx.readDriver()
            answers.appendLine("Treiber vorher: $drv")
            runOnUiThread { step() }
        }
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Look.font(this@LedTestActivity, pixel, bold)
        gravity = Gravity.CENTER
    }

    private fun step() {
        if (zone > zones.last) { finishTest(); return }
        title.text = "LED-Test · Zone $zone von ${zones.last}"
        info.text = "Linker Stick: Zone $zone leuchtet jetzt weiß (alle anderen aus).\nWas siehst du?"
        buttons.removeAllViews()
        val z = zone
        thread {
            val err = LedFx.lightZone(z)
            if (err != null) answers.appendLine("Zone $z Schreibfehler: $err")
        }
        val options = listOf("Nichts leuchtet", "Ganzer Ring", "Nur ein Teil", "Anderer Stick / Farbe")
        options.forEachIndexed { i, o ->
            val b = label(o, 15f, if (i == 0) pal.onSurface else pal.onSurface, false).apply {
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = Look.shape(this@LedTestActivity, pixel, pal.surfaceHighest, dp(16).toFloat())
                isFocusable = true
                setOnFocusChangeListener { v, f -> v.foreground = if (f) Look.shape(this@LedTestActivity, pixel, 0, dp(16).toFloat(), dp(3), pal.onSurface) else null }
                setOnClickListener {
                    answers.appendLine("Zone $z: $o")
                    zone++
                    step()
                }
            }
            buttons.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        buttons.getChildAt(0)?.requestFocus()
    }

    private fun finishTest() {
        title.text = "Danke!"
        info.text = "Die Antworten werden hochgeladen, die normalen Farben kommen zurück."
        buttons.removeAllViews()
        val text = "LED-Zonentest (Antworten)\n" + answers.toString()
        Feedback.uploadText(applicationContext, "ledtest", text)
        thread {
            LedFx.restoreStatic(this)
            LedFx.resume(this)
        }
        buttons.postDelayed({ finish() }, 2500)
    }

    override fun onDestroy() {
        if (zone <= zones.last) thread { LedFx.restoreStatic(this); LedFx.resume(this) }
        super.onDestroy()
    }
}

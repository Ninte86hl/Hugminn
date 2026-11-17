package io.github.ninte86hl.hugminn

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Small window for a WLAN password (the Control Center cannot show the keyboard itself). */
class WifiPasswordActivity : Activity() {
    private var keyboard: PadKeyboard? = null
    private val hat by lazy { HatInput(android.os.Handler(mainLooper)) { code -> keyboard?.onKey(code) } }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean =
        hat.onMotion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_BUTTON_B || code == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) onBackKey()
            return true
        }
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
        val kb = keyboard ?: return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN && kb.onKey(code)) return true
        return if (event.action == android.view.KeyEvent.ACTION_UP) true else super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ssid = intent.getStringExtra("ssid") ?: run { finish(); return }
        val sec = intent.getStringExtra("sec") ?: "wpa2"
        val pal = Palette(this)
        val pixel = Look.pixel(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = Look.font(this@WifiPasswordActivity, pixel, bold)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.shape(this@WifiPasswordActivity, pixel, pal.surfaceHigh, dp(24).toFloat(), dp(1), pal.outline)
            setPadding(dp(24), dp(20), dp(24), dp(18))
        }
        box.addView(label("WLAN verbinden", 20f, pal.onSurface, bold = true))
        box.addView(label(ssid, 14f, pal.onSurfaceVariant).apply { setPadding(0, dp(4), 0, dp(12)) })
        val input = EditText(this).apply {
            hint = "Passwort"
            setHintTextColor(pal.onSurfaceVariant)
            setTextColor(pal.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            background = Look.shape(this@WifiPasswordActivity, pixel, pal.surface, dp(14).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(14), 0, 0) }
        fun button(text: String, primary: Boolean, run: () -> Unit) = label(text, 15f, if (primary) pal.onPrimaryContainer else pal.onSurface, bold = primary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = Look.shape(this@WifiPasswordActivity, pixel, if (primary) pal.primaryContainer else pal.surfaceHighest, dp(18).toFloat())
            isFocusable = true
            setOnFocusChangeListener { v, f -> v.foreground = if (f) Look.shape(this@WifiPasswordActivity, pixel, 0, dp(18).toFloat(), dp(2), pal.onSurface) else null }
            setOnClickListener { run() }
        }
        val connect = {
            val pw = input.text.toString()
            if (pw.length < 8 && sec != "open") {
                Toast.makeText(this, "Das Passwort hat mindestens 8 Zeichen", Toast.LENGTH_SHORT).show()
            } else {
                val app = applicationContext
                thread {
                    val out = Wifi.connect(ssid, sec, pw)
                    android.os.Handler(mainLooper).post {
                        Toast.makeText(app, if (out == null || out.contains("rror", true)) "Verbinden fehlgeschlagen: ${out ?: "keine Antwort"}" else "Verbinde mit $ssid …", Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { connect(); true } else false }
        buttons.addView(button("Abbrechen", false) { finish() })
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        buttons.addView(button("Verbinden", true) { connect() })
        box.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Hugminn's own keyboard in the chosen style (the system keyboard stays hidden)
        input.showSoftInputOnFocus = false
        keyboard = PadKeyboard(this, pal, input) { connect() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(0, dp(16), 0, 0)
            addView(box, LinearLayout.LayoutParams(dp(520), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(View(this@WifiPasswordActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), 0, dp(12), dp(10))
            })
        }
        setContentView(root)
        input.requestFocus()
    }

    private fun onBackKey() { finish() }
}

package io.github.ninte86hl.hugminn

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small window after Select was pressed: the screenshot is already taken, here the user can add a
 * short description or error message before everything is uploaded.
 */
class FeedbackActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = Palette(this)
        val pixel = Look.pixel(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setFinishOnTouchOutside(false)

        fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = Look.font(this@FeedbackActivity, pixel, bold)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.shape(this@FeedbackActivity, pixel, pal.surfaceHigh, dp(24).toFloat(), dp(1), pal.outline)
            setPadding(dp(24), dp(20), dp(24), dp(18))
        }
        box.addView(label("Feedback", 20f, pal.onSurface, bold = true))
        box.addView(label("Screenshot ist gemacht. Was ist dir aufgefallen? (optional)", 13f, pal.onSurfaceVariant).apply { setPadding(0, dp(4), 0, dp(12)) })

        val input = EditText(this).apply {
            hint = "z. B. Kachel Lüfter wirkt abgeschnitten"
            setHintTextColor(pal.onSurfaceVariant)
            setTextColor(pal.onSurface)
            typeface = Look.font(this@FeedbackActivity, pixel, false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            background = Look.shape(this@FeedbackActivity, pixel, pal.surface, dp(14).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // Quick labels, reachable with the D-pad without the keyboard
        val chips = LinearLayout(this).apply { setPadding(0, 0, 0, dp(10)) }
        listOf("Fehler", "Optik", "Bedienung", "Idee").forEach { tag ->
            chips.addView(label(tag, 13f, pal.onSurface).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = Look.shape(this@FeedbackActivity, pixel, pal.surfaceHighest, dp(14).toFloat())
                isFocusable = true
                setOnClickListener {
                    val t = input.text.toString()
                    if (!t.startsWith("[$tag]")) input.setText("[$tag] " + t.replace(Regex("^\\[[^\\]]*\\] "), ""))
                    input.setSelection(input.text.length)
                }
                setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.75f }
                alpha = 0.75f
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        box.addView(chips)
        box.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        fun button(text: String, primary: Boolean, run: () -> Unit) = label(text, 15f, if (primary) pal.onPrimaryContainer else pal.onSurface, bold = primary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = Look.shape(this@FeedbackActivity, pixel, if (primary) pal.primaryContainer else pal.surfaceHighest, dp(18).toFloat())
            isFocusable = true
            setOnFocusChangeListener { v, f -> v.foreground = if (f) Look.shape(this@FeedbackActivity, pixel, 0, dp(18).toFloat(), dp(2), pal.onSurface) else null }
            setOnClickListener { run() }
        }
        buttons.addView(button("Abbrechen", false) { cancel() })
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        buttons.addView(button("Senden", true) { send(input.text.toString()) })
        box.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val root = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            addView(box, LinearLayout.LayoutParams(dp(560), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        input.requestFocus()
        input.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun send(text: String) {
        if (handled) return
        handled = true
        Feedback.sendPending(this, text)
        finish()
    }

    private fun cancel() {
        if (handled) return
        handled = true
        Feedback.cancelPending()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = cancel()

    override fun onDestroy() {
        // Left in another way (Home …): nothing is sent, the next Select works again.
        if (!handled) Feedback.cancelPending()
        super.onDestroy()
    }
}

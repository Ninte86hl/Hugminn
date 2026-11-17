package io.github.ninte86hl.hugminn

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records every button and every axis the controller sends (what a normal app sees) next to what the
 * Hugminn service sees, so D-pad, sticks and triggers can be checked. Hold Select for one second to
 * upload the recording, Home leaves.
 */
class InputTestActivity : Activity() {
    companion object {
        @Volatile var active = false
        private val log = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun add(line: String) {
            val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY).format(Date())
            synchronized(log) {
                log.add("$t  $line")
                while (log.size > 2000) log.removeAt(0)
            }
        }
    }

    private lateinit var view: TextView
    private lateinit var scroll: ScrollView
    private val lastAxis = HashMap<String, Float>()
    private var selectDown = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = Palette(this)
        log.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            setPadding(dp(24), dp(16), dp(24), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Eingabetest"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(pal.onSurface)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Drücke nacheinander: Steuerkreuz hoch, runter, links, rechts · linker Stick in alle Richtungen · " +
                "rechter Stick · L1, R1, L2, R2 · A, B, X, Y · Start. Select 1 Sekunde halten = hochladen, Home = beenden."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.onSurfaceVariant)
            setPadding(0, dp(4), 0, dp(8))
        })
        view = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(pal.onSurface)
            typeface = Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply {
            isFocusable = false
            addView(view)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            text = "Hochladen"
            isFocusable = false
            setOnClickListener { upload() }
        })
        buttons.addView(Button(this).apply {
            text = "Beenden"
            isFocusable = false
            setOnClickListener { finish() }
        })
        root.addView(buttons)
        setContentView(root)
        add("Eingabegeräte: " + InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0 }
            .joinToString { "\"${it.name}\" ${"%04x".format(it.vendorId)}:${"%04x".format(it.productId)}" })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        active = true
    }

    override fun onPause() {
        active = false
        super.onPause()
    }

    private fun refresh() {
        val text = synchronized(log) { log.takeLast(300).joinToString("\n") }
        view.text = text
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun upload() {
        val text = synchronized(log) { log.joinToString("\n") }
        Feedback.uploadText(this, "eingabetest", text)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val act = when (e.action) { KeyEvent.ACTION_DOWN -> "DOWN"; KeyEvent.ACTION_UP -> "UP  "; else -> "?" }
        val synth = if (e.deviceId == KeyCharacterMapVirtual) " (virtuell)" else ""
        add("APP   $act ${KeyEvent.keyCodeToString(e.keyCode)} scan=${e.scanCode} rep=${e.repeatCount} " +
            "dev=\"${e.device?.name}\" src=0x${Integer.toHexString(e.source)}$synth")
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) selectDown = SystemClock.uptimeMillis()
            if (e.action == KeyEvent.ACTION_UP && SystemClock.uptimeMillis() - selectDown > 900) upload()
        }
        refresh()
        return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        val dev = e.device ?: return true
        val changes = mutableListOf<String>()
        for (range in dev.motionRanges) {
            if (range.source and e.source == 0) continue
            val axis = range.axis
            val v = e.getAxisValue(axis)
            val name = MotionEvent.axisToString(axis)
            val last = lastAxis[name] ?: 0f
            if (kotlin.math.abs(v - last) >= 0.25f || (v == 0f && last != 0f)) {
                lastAxis[name] = v
                changes += "$name=${"%.2f".format(v)}"
            }
        }
        if (changes.isNotEmpty()) {
            add("APP   ACHSE ${changes.joinToString(" ")} dev=\"${dev.name}\" src=0x${Integer.toHexString(e.source)}")
            refresh()
        }
        return true
    }

    private val KeyCharacterMapVirtual = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
}

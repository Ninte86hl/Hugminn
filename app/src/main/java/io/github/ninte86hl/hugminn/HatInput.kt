package io.github.ninte86hl.hugminn

import android.os.Handler
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * The D-pad of these handhelds is a joystick hat (AXIS_HAT_X/Y) and sends no keys at all. This turns
 * hat and left stick into D-pad presses (with key repeat while held) for Hugminn's own windows.
 */
class HatInput(private val handler: Handler, private val onPress: (Int) -> Unit) {
    private var current = 0
    private val repeat = object : Runnable {
        override fun run() {
            if (current == 0) return
            onPress(current)
            handler.postDelayed(this, 120)
        }
    }

    fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val sx = e.getAxisValue(MotionEvent.AXIS_X)
        val sy = e.getAxisValue(MotionEvent.AXIS_Y)
        val x = if (abs(hx) > 0.5f) hx else if (abs(sx) > 0.65f) sx else 0f
        val y = if (abs(hy) > 0.5f) hy else if (abs(sy) > 0.65f) sy else 0f
        val code = when {
            x == 0f && y == 0f -> 0
            abs(y) >= abs(x) -> if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            else -> if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        }
        if (code != current) {
            handler.removeCallbacks(repeat)
            current = code
            if (code != 0) {
                onPress(code)
                handler.postDelayed(repeat, 380)
            }
        }
        return true
    }

    fun stop() {
        current = 0
        handler.removeCallbacks(repeat)
    }
}

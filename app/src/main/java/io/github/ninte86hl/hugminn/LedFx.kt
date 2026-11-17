package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Light effects for the stick LEDs. */
enum class LedEffect(val label: String) {
    NONE("Statisch"),
    RAINBOW("Farbwechsel"),
    RING("Regenbogenring"),
    BREATHE("Atmen"),
    PULSE("Pulsieren"),
}

/**
 * Plays [LedEffect]s by writing the four LED zones (left/right stick, upper/lower half) directly,
 * the same way Bifrost does. Runs in Hugminn's service; the chosen effect is remembered and resumes
 * after a restart. Static colours are left to the firmware (joystick_led_light_picker_color).
 */
object LedFx {
    @Volatile private var thread: Thread? = null
    @Volatile private var effect = LedEffect.NONE
    private const val FRAME_MS = 50L

    fun current() = effect

    /** Starts the remembered effect (service start, LEDs switched on). */
    fun resume(ctx: Context) {
        val e = Prefs(ctx).ledEffect
        if (e != LedEffect.NONE && Device.stickLeds(ctx)) start(ctx, e) else stop()
    }

    fun set(ctx: Context, e: LedEffect) {
        Prefs(ctx).ledEffect = e
        if (e == LedEffect.NONE) {
            stop()
            // hand the LEDs back to the firmware with the stored colours
            Device.ledColors(ctx)?.let { (l, r) -> Device.setLedColors(l, r, turnOn = false) {} }
        } else start(ctx, e)
    }

    fun stop() {
        effect = LedEffect.NONE
        thread?.interrupt()
        thread = null
    }

    private fun start(ctx: Context, e: LedEffect) {
        stop()
        effect = e
        val app = ctx.applicationContext
        val (left, right) = Device.ledColors(app) ?: (Color.WHITE to Color.WHITE)
        val t = Thread {
            val t0 = System.currentTimeMillis()
            try {
                while (!Thread.currentThread().isInterrupted && effect == e) {
                    val s = (System.currentTimeMillis() - t0) / 1000f
                    val zones = frame(e, s, left, right)
                    Root.exec(command(zones), 1_000)
                    Thread.sleep(FRAME_MS)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Crash.note(app, t)
            }
        }
        t.isDaemon = true
        t.name = "hugminn-ledfx"
        thread = t
        t.start()
    }

    /** Colours of the zones: left upper, left lower, right upper, right lower. */
    private fun frame(e: LedEffect, s: Float, left: Int, right: Int): IntArray = when (e) {
        LedEffect.RAINBOW -> hsv(s * 40f).let { intArrayOf(it, it, it, it) }
        // clockwise around both sticks: left upper → right upper → right lower → left lower
        LedEffect.RING -> {
            val base = s * 120f
            intArrayOf(hsv(base), hsv(base + 270f), hsv(base + 90f), hsv(base + 180f))
        }
        LedEffect.BREATHE -> {
            val k = 0.08f + 0.92f * (0.5f - 0.5f * kotlin.math.cos(2 * PI * s / 4.0).toFloat())
            intArrayOf(scale(left, k), scale(left, k), scale(right, k), scale(right, k))
        }
        LedEffect.PULSE -> {
            // short double beat, then rest (like a heartbeat)
            val p = (s % 1.2f) / 1.2f
            val k = max(beat(p, 0.0f), beat(p, 0.22f)).coerceAtLeast(0.06f)
            intArrayOf(scale(left, k), scale(left, k), scale(right, k), scale(right, k))
        }
        LedEffect.NONE -> intArrayOf(left, left, right, right)
    }

    private fun beat(p: Float, at: Float): Float {
        val x = (p - at) / 0.18f
        return if (x < 0f || x > 1f) 0f else sin(PI * x).toFloat()
    }

    private fun hsv(h: Float) = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, 1f, 1f))

    private fun scale(c: Int, k: Float) = Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())

    private fun command(z: IntArray): String {
        fun part(zone: Int, c: Int, side: Char) =
            "echo $zone-${Color.red(c)}:${Color.green(c)}:${Color.blue(c)}:255 > /sys/class/sn3112$side/led/brightness"
        return listOf(part(1, z[0], 'l'), part(2, z[1], 'l'), part(1, z[2], 'r'), part(2, z[3], 'r')).joinToString(" && ")
    }
}

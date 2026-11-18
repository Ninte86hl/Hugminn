package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Light effects for the stick LEDs. */
enum class LedEffect(private val labelDe: String) {
    NONE("Statisch"),
    RAINBOW("Farbwechsel"),
    RING("Regenbogen versetzt"),
    BREATHE("Atmen"),
    PULSE("Pulsieren");

    val label get() = tr(labelDe)
}

/**
 * Plays [LedEffect]s by writing the four LED zones (left/right stick, upper/lower half) directly,
 * the same way Bifrost does. Runs in Hugminn's service; the chosen effect is remembered and resumes
 * after a restart. Static colours are left to the firmware (joystick_led_light_picker_color).
 */
object LedFx {
    @Volatile private var thread: Thread? = null
    @Volatile private var effect = LedEffect.NONE
    @Volatile private var running = false
    private const val FRAME_MS = 40L

    /** Effect speed factors and brightness steps offered in the LED window. */
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f)
    @Volatile var speed = 1f
    @Volatile var brightness = 100

    fun current() = if (running && !lowBattery) effect else LedEffect.NONE

    /** Battery at or below 10 % and not charging: the sticks pulse red, whatever is set otherwise. */
    @Volatile var lowBattery = false
        private set

    fun battery(ctx: Context, pct: Int, charging: Boolean) {
        val low = if (lowBattery) pct <= 12 && !charging else pct in 0..10 && !charging
        if (low == lowBattery) return
        lowBattery = low
        if (low) {
            if (Device.stickLeds(ctx)) start(ctx, LedEffect.PULSE, Color.RED to Color.RED)
        } else resume(ctx)
    }

    /** What the last effect start found out (for feedback reports). */
    @Volatile var diag: String = ""

    private fun load(ctx: Context) {
        speed = Prefs(ctx).ledSpeed
        brightness = (Device.ledBrightness(ctx) * 100).toInt()
    }

    /** Starts the remembered effect (service start, LEDs switched on). */
    fun resume(ctx: Context) {
        load(ctx)
        if (lowBattery) { if (Device.stickLeds(ctx)) start(ctx, LedEffect.PULSE, Color.RED to Color.RED); return }
        val e = Prefs(ctx).ledEffect
        if (Device.stickLeds(ctx) && e != LedEffect.NONE) start(ctx, e) else stop()
    }

    fun set(ctx: Context, e: LedEffect) {
        Prefs(ctx).ledEffect = e
        load(ctx)
        if (lowBattery) return
        if (e == LedEffect.NONE) {
            stop()
            // back to the stored static colours
            restoreStatic(ctx)
        } else start(ctx, e)
    }

    fun setSpeed(ctx: Context, v: Float) {
        Prefs(ctx).ledSpeed = v
        speed = v
    }

    /** Brightness is the firmware's own setting (same as Retroid's LED window); effects follow it. */
    fun setBrightness(ctx: Context, v: Int, then: () -> Unit) {
        brightness = v
        Device.setLedBrightness(v, then)
    }

    private val previewLock = Object()
    @Volatile private var previewNext: IntArray? = null
    @Volatile private var previewBusy = false

    /** Shows a colour on the sticks right away (colour wheel), without storing it; only the newest is sent. */
    fun preview(left: Int, right: Int, k: Float) {
        previewNext = intArrayOf(scale(left, k), scale(right, k))
        synchronized(previewLock) {
            if (previewBusy) return
            previewBusy = true
        }
        Thread {
            while (true) {
                val z = previewNext ?: break
                previewNext = null
                Root.exec(command(z), 1_000)
            }
            synchronized(previewLock) { previewBusy = false }
        }.start()
    }

    /** Writes the stored static colours (at the stored brightness) to the sticks. */
    fun restoreStatic(ctx: Context) {
        val (l, r) = Device.ledColors(ctx) ?: return
        val k = Device.ledBrightness(ctx)
        Thread { Root.exec(command(intArrayOf(scale(l, k), scale(r, k)))) }.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun start(ctx: Context, e: LedEffect, colors: Pair<Int, Int>? = null) {
        stop()
        effect = e
        running = true
        val app = ctx.applicationContext
        val (left, right) = colors ?: Device.ledColors(app) ?: (Color.WHITE to Color.WHITE)
        val t = Thread {
            if (diag.isEmpty()) diag = "Zonen: " + (Root.exec("ls /sys/class/sn3112l/led/ | tr '\\n' ' '") ?: "keine Antwort")
            var phase = 0f
            var last = System.currentTimeMillis()
            try {
                while (!Thread.currentThread().isInterrupted && running && effect == e) {
                    val now = System.currentTimeMillis()
                    // phase advances with the speed, so changing it never makes the effect jump
                    phase += (now - last) / 1000f * speed
                    last = now
                    val k = brightness / 100f
                    val zones = frame(e, phase, left, right).map { scale(it, k) }.toIntArray()
                    val out = Root.exec(command(zones) + " 2>&1", 1_000)
                    if (!out.isNullOrBlank() && !diag.contains("Schreibfehler")) diag += "\nSchreibfehler: $out"
                    // static colour (only dimmed): refresh now and then in case the firmware rewrites it
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

    /**
     * Colours of the two sticks (left, right). The zone test showed that each stick lights as one
     * whole: zone 1 colours the entire ring, other zones do nothing.
     */
    private fun frame(e: LedEffect, s: Float, left: Int, right: Int): IntArray = when (e) {
        LedEffect.RAINBOW -> hsv(s * 40f).let { intArrayOf(it, it) }
        // the two sticks run through the rainbow half a turn apart
        LedEffect.RING -> (s * 90f).let { intArrayOf(hsv(it), hsv(it + 180f)) }
        LedEffect.BREATHE -> {
            val k = 0.08f + 0.92f * (0.5f - 0.5f * kotlin.math.cos(2 * PI * s / 4.0).toFloat())
            intArrayOf(scale(left, k), scale(right, k))
        }
        LedEffect.PULSE -> {
            // short double beat, then rest (like a heartbeat)
            val p = (s % 1.2f) / 1.2f
            val k = max(beat(p, 0.0f), beat(p, 0.22f)).coerceAtLeast(0.06f)
            intArrayOf(scale(left, k), scale(right, k))
        }
        LedEffect.NONE -> intArrayOf(left, right)
    }

    /**
     * Zone test on the left stick: reads the driver's files, then lights indices 0–8 one after another
     * (each 1.5 s, the others dark) and reports each step via [show]. Shows whether single LEDs exist.
     */
    private const val LDIR = "/sys/class/sn3112l/led"

    /** Current content of the left driver's files. */
    fun readDriver(): String {
        val read = listOf("brightness", "breath", "enable").joinToString("; ") { "echo $it=\$(cat $LDIR/$it 2>&1 | head -c 200)" }
        return (Root.execLong(read) ?: "keine Antwort").replace('\n', ' ')
    }

    /** Left stick: only index [i] lit white, all others (0–8) dark. Returns an error text, if any. */
    fun lightZone(i: Int): String? {
        stop()
        fun w(v: String) = Root.exec("echo $v > $LDIR/brightness 2>&1")
        for (j in 0..8) if (j != i) w("$j-0:0:0:0")
        return w("$i-255:255:255:255")?.takeIf { it.isNotBlank() }
    }

    fun probeZones(show: (String) -> Unit) {
        stop()
        val dir = "/sys/class/sn3112l/led"
        val read = listOf("brightness", "breath", "enable").joinToString("; ") { "echo $it=\$(cat $dir/$it 2>&1 | head -c 200)" }
        diag = "Treiber: " + (Root.execLong(read) ?: "keine Antwort").replace('\n', ' ')
        fun w(v: String) = Root.exec("echo $v > $dir/brightness 2>&1")
        for (i in 0..8) {
            for (j in 0..8) if (j != i) w("$j-0:0:0:0")
            val err = w("$i-255:255:255:255")
            show("Zone $i" + if (!err.isNullOrBlank()) " – Fehler: $err" else "")
            diag += "\nZone $i: " + (err?.takeIf { it.isNotBlank() } ?: "ok")
            Thread.sleep(1_500)
        }
        show(tr("Test fertig – Farben werden wiederhergestellt"))
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
        return part(1, z[0], 'l') + "; " + part(1, z[1], 'r')
    }
}

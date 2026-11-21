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
    RING("Regenbogen"),
    BREATHE("Atmen"),
    PULSE("Pulsieren"),
    /** Not effects but displays: the sticks show the battery charge or the temperature as a colour. */
    BATTERY("Akku"),
    TEMP("Temperatur");

    val label get() = tr(labelDe)
    val indicator get() = this == BATTERY || this == TEMP
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

    fun current() = if (running && !lowBattery && !recording) effect else LedEffect.NONE

    /** A video is being recorded: the sticks pulse red like a camera's light. */
    @Volatile var recording = false
        private set

    /** Hugminn switched the LEDs on only for a recording. */
    @Volatile private var switchedOn = false

    fun setRecording(ctx: Context, on: Boolean) {
        if (on == recording) return
        recording = on
        if (on) {
            load(ctx)
            // LEDs switched off in the settings: the firmware shows nothing, so they go on just for the recording
            if (!Device.fxOn(ctx)) {
                switchedOn = true
                Device.fxCmds(ctx, true).forEach { Root.exec(it) }
                Thread.sleep(150)
            }
            start(ctx, LedEffect.PULSE, Color.RED to Color.RED)
            return
        }
        stop()
        if (switchedOn) {
            // they were off before: dark and off again
            switchedOn = false
            Root.exec(command(intArrayOf(0, 0)))
            Device.fxCmds(ctx, false).forEach { Root.exec(it) }
            return
        }
        when {
            // the LEDs were off: off again
            !Device.fxOn(ctx) -> Thread { Root.exec(command(intArrayOf(0, 0))) }.start()
            // an effect or low battery takes over again; otherwise the stored colours come back
            lowBattery || Prefs(ctx).ledEffect != LedEffect.NONE -> resume(ctx)
            else -> restoreStatic(ctx)
        }
    }

    /** Battery at or below 10 % and not charging: the sticks pulse red, whatever is set otherwise. */
    @Volatile var lowBattery = false
        private set

    /** Last known battery charge in percent (-1 = unknown yet). */
    @Volatile var batPct = -1
        private set

    fun battery(ctx: Context, pct: Int, charging: Boolean) {
        batPct = pct
        val low = if (lowBattery) pct <= 12 && !charging else pct in 0..10 && !charging
        if (low == lowBattery) return
        lowBattery = low
        if (recording) return
        if (low) {
            if (Device.fxOn(ctx)) start(ctx, LedEffect.PULSE, Color.RED to Color.RED)
        } else resume(ctx)
    }

    /** What the last effect start found out (for feedback reports). */
    @Volatile var diag: String = ""

    private fun load(ctx: Context) {
        speed = Prefs(ctx).ledSpeed
        brightness = (Device.ledBrightness(ctx) * 100).toInt()
        odin = Device.hasSideLights(ctx)
    }

    /**
     * Odin 2: sticks and sides are separate zones on the drivers (zone 2 stick, zone 1 side), each
     * with its own colours and effect. Hugminn then always drives them itself.
     */
    @Volatile private var odin = false

    /** The effect of the side lights (Odin 2). */
    fun currentSide() = if (running && !lowBattery && !recording) sideEffect else LedEffect.NONE
    @Volatile private var sideEffect = LedEffect.NONE

    fun setSide(ctx: Context, e: LedEffect) {
        Prefs(ctx).ledEffectSide = e
        load(ctx)
        if (lowBattery || recording) return
        resume(ctx)
    }

    /** Starts the remembered effect (service start, LEDs switched on). */
    fun resume(ctx: Context) {
        load(ctx)
        if (odin) {
            // Odin 2: one loop for sticks and sides (static colours too – the firmware would paint over them)
            if (recording || (lowBattery && Device.lightsOn(ctx))) { start(ctx, LedEffect.PULSE, Color.RED to Color.RED); return }
            if (Device.lightsOn(ctx)) start(ctx, Prefs(ctx).ledEffect) else stop()
            return
        }
        if (recording) { start(ctx, LedEffect.PULSE, Color.RED to Color.RED); return }
        if (lowBattery) { if (Device.fxOn(ctx)) start(ctx, LedEffect.PULSE, Color.RED to Color.RED); return }
        val e = Prefs(ctx).ledEffect
        if (Device.fxOn(ctx) && e != LedEffect.NONE) start(ctx, e) else {
            stop()
            // AYN with two stick colours: the firmware only knows one, Hugminn puts the second back
            if (Device.fxOn(ctx) && Device.aynSplit(ctx)) restoreStatic(ctx, delayMs = 600)
        }
    }

    fun set(ctx: Context, e: LedEffect) {
        Prefs(ctx).ledEffect = e
        load(ctx)
        if (lowBattery || recording) return
        if (odin) { resume(ctx); return }
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

    /** Odin 2: which zone the colour wheel previews – 2 the sticks, 1 the sides. */
    @Volatile var previewZone = 1

    /** Shows a colour on the sticks right away (colour wheel), without storing it; only the newest is sent. */
    fun preview(left: Int, right: Int, k: Float) {
        if (odin) stop()
        previewNext = intArrayOf(scale(left, k), scale(right, k))
        synchronized(previewLock) {
            if (previewBusy) return
            previewBusy = true
        }
        Thread {
            while (true) {
                val z = previewNext ?: break
                previewNext = null
                Root.exec(if (z.size == 4) command(intArrayOf(z[0], z[1]), 2) + "; " + command(intArrayOf(z[2], z[3]), 1)
                    else if (odin) command(z, previewZone) else command(z), 1_000)
            }
            synchronized(previewLock) { previewBusy = false }
        }.start()
    }

    /** Writes the stored static colours (at the stored brightness) to the sticks. */
    fun restoreStatic(ctx: Context, delayMs: Long = 0) {
        if (Device.hasSideLights(ctx)) {
            // Odin 2: the loop paints sticks and sides
            Thread { if (delayMs > 0) Thread.sleep(delayMs); resume(ctx) }.start()
            return
        }
        val (l, r) = Device.ledColors(ctx) ?: return
        val k = Device.ledBrightness(ctx)
        // Odin 2: the driver lights the sides – dark if they are switched off
        val sidesOff = Device.hasSideLights(ctx) && !Device.sideLights(ctx)
        val left = if (sidesOff) 0 else scale(l, k)
        val right = if (sidesOff) 0 else scale(r, k)
        Thread { if (delayMs > 0) Thread.sleep(delayMs); Root.exec(command(intArrayOf(left, right))) }.start()
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
        if (odin) { startOdin(app, e, colors); return }
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
                    if (e.indicator) {
                        // a status colour changes slowly: write it every few seconds only
                        val c = scale(indicatorColor(e), k)
                        Root.exec(command(intArrayOf(c, c)), 1_000)
                        Thread.sleep(3_000)
                        continue
                    }
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

    private fun animated(e: LedEffect) = e == LedEffect.RAINBOW || e == LedEffect.RING || e == LedEffect.BREATHE || e == LedEffect.PULSE

    /**
     * Odin 2: the four elements in one loop – left/right stick (zone 2), left/right side (zone 1),
     * each with its own state, colour and effect. Off elements stay dark.
     * [forced]: recording / low battery – every lit element pulses in that colour.
     */
    private fun startOdin(app: Context, stickE: LedEffect, forced: Pair<Int, Int>?) {
        val t = Thread {
            var phase = 0f
            var last = System.currentTimeMillis()
            var lastCmd = ""
            var lastWrite = 0L
            var state = 0L
            var st = IntArray(4) { 2 }
            var fx = Array(4) { stickE }
            var col = IntArray(4) { Color.WHITE }
            var kb = FloatArray(4) { 1f }
            try {
                while (!Thread.currentThread().isInterrupted && running) {
                    val now = System.currentTimeMillis()
                    phase += (now - last) / 1000f * speed
                    last = now
                    // settings and colours: read again once a second
                    if (now - state > 1000) {
                        state = now
                        st = Device.elStates(app)
                        if (forced != null) { fx = Array(4) { LedEffect.PULSE }; col = IntArray(4) { forced.first } }
                        else { fx = Array(4) { Device.elFx(app, it) }; col = Device.elColors(app) }
                        kb = FloatArray(4) { Device.elBright(app, it) / 100f }
                        effect = fx[0]
                        sideEffect = fx[2]
                    }
                    val z = IntArray(4) { i ->
                        if (st[i] == 0) 0
                        else scale(frame(fx[i], phase + (if (i >= 2) 0.37f else 0f), col[i], col[i])[i % 2], kb[i])
                    }
                    val cmd = command(intArrayOf(z[0], z[1]), 2) + "; " + command(intArrayOf(z[2], z[3]), 1)
                    // write when something changed – and every 2 s anyway, the firmware may paint over it
                    if (cmd != lastCmd || now - lastWrite > 2000) {
                        Root.exec(cmd, 1_000)
                        lastCmd = cmd
                        lastWrite = now
                    }
                    Thread.sleep(if (fx.any { animated(it) }) FRAME_MS else 250)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Crash.note(app, t)
            }
        }
        t.isDaemon = true
        t.name = "hugminn-ledfx-odin"
        thread = t
        t.start()
    }

    /** Sets the effect of the given elements (Retroid: the one effect of both sticks). */
    fun setEls(ctx: Context, els: List<Int>, e: LedEffect) {
        if (Device.hasSideLights(ctx)) {
            val p = Prefs(ctx)
            els.forEach { p.setLedElFx(it, e) }
            load(ctx)
            if (lowBattery || recording) return
            resume(ctx)
        } else set(ctx, e)
    }

    /** Odin 2: shows four element colours right away (colour wheel), already dimmed; not stored. */
    fun previewEls(z: IntArray, k: Float) {
        if (z.size < 4) { preview(z[0], z.getOrElse(1) { z[0] }, k); return }
        stop()
        previewNext = IntArray(4) { scale(z[it], k) }
        synchronized(previewLock) {
            if (previewBusy) return
            previewBusy = true
        }
        Thread {
            while (true) {
                val v = previewNext ?: break
                previewNext = null
                Root.exec(if (v.size == 4) command(intArrayOf(v[0], v[1]), 2) + "; " + command(intArrayOf(v[2], v[3]), 1)
                    else if (odin) command(v, previewZone) else command(v), 1_000)
            }
            synchronized(previewLock) { previewBusy = false }
        }.start()
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
        LedEffect.BATTERY, LedEffect.TEMP -> indicatorColor(e).let { intArrayOf(it, it) }
        LedEffect.NONE -> intArrayOf(left, right)
    }

    private const val GREEN = 0xFF00E040.toInt()
    private const val YELLOW = 0xFFFFC000.toInt()
    private const val RED = 0xFFFF1000.toInt()

    /**
     * Battery: green, yellow from 50 %, red from 20 %. Temperature (hottest CPU core): green,
     * yellow from 60 °C, red from 75 °C.
     */
    private fun indicatorColor(e: LedEffect): Int = when (e) {
        LedEffect.BATTERY -> batPct.let { if (it < 0) GREEN else if (it <= 20) RED else if (it <= 50) YELLOW else GREEN }
        else -> cpuTemp().let { if (it >= 75f) RED else if (it >= 60f) YELLOW else GREEN }
    }

    private val cpuZones: List<java.io.File> by lazy {
        java.io.File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().filter { z ->
            val type = runCatching { java.io.File(z, "type").readText().trim() }.getOrNull() ?: return@filter false
            type.startsWith("cpu-") || type.startsWith("cpuss-")
        }.map { java.io.File(it, "temp") }
    }

    private fun cpuTemp(): Float = cpuZones.mapNotNull { runCatching { it.readText().trim().toFloat() }.getOrNull() }
        .filter { it in 1f..150_000f }.maxOrNull()?.let { if (it > 1000) it / 1000f else it } ?: 0f

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

    /** One zone on both drivers (left, right). */
    private fun command(z: IntArray, zone: Int): String {
        fun part(c: Int, side: Char) =
            "echo $zone-${Color.red(c)}:${Color.green(c)}:${Color.blue(c)}:255 > /sys/class/sn3112$side/led/brightness"
        return part(z[0], 'l') + "; " + part(z[1], 'r')
    }

    private fun command(z: IntArray): String {
        fun part(zone: Int, c: Int, side: Char) =
            "echo $zone-${Color.red(c)}:${Color.green(c)}:${Color.blue(c)}:255 > /sys/class/sn3112$side/led/brightness"
        return part(1, z[0], 'l') + "; " + part(1, z[1], 'r')
    }
}

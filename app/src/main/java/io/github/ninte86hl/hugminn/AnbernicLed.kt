package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper

/**
 * Anbernic's stick lights in Hugminn's LED window. One light ("Sticks": the RG 406H has one colour
 * for both), its effects are the controller's own (led_effect), colour and brightness go straight
 * into the controller through the helper (custum_rgb / Led_rgb, led_level in percent).
 */
object AnbernicLed {
    /** Hugminn's effect → Anbernic's led_effect. */
    private val effect = mapOf(LedEffect.BATTERY to "0", LedEffect.TEMP to "0", LedEffect.NONE to "0", LedEffect.BREATHE to "1",
        // Anbernic's "Rainbow" changes all colours together (Hugminn's Farbwechsel), its "Cycle" runs the
        // colours round the stick (Hugminn's Regenbogen) – the same names as on Retroid and AYN
        LedEffect.RAINBOW to "2", LedEffect.RING to "3", LedEffect.MARQUEE to "3", LedEffect.CHASE to "4", LedEffect.GAMING to "5")

    val effects = listOf(LedEffect.NONE, LedEffect.BREATHE, LedEffect.RAINBOW, LedEffect.RING, LedEffect.CHASE, LedEffect.GAMING, LedEffect.BATTERY, LedEffect.TEMP)

    /** Battery or temperature as a colour (steady light, renewed every 30 s): green, yellow, red. */
    private fun indicator(ctx: Context, e: LedEffect): Int {
        val green = 0xFF00E040.toInt(); val yellow = 0xFFFFC000.toInt(); val red = 0xFFFF1000.toInt()
        return if (e == LedEffect.BATTERY) {
            val pct = (ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..20) red else if (pct in 21..50) yellow else green
        } else {
            val t = AnbernicHelper.stats()?.get("cpu")?.let { if (it > 1000) it / 1000f else it } ?: 0f
            if (t >= 75f) red else if (t >= 60f) yellow else green
        }
    }

    private val tick = object : Runnable {
        override fun run() { Device.app?.let { apply(it) }; main.postDelayed(this, 30_000) }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var last = ""

    /** Hugminn steers the lights only once they were set in its LED window – until then Anbernic's app keeps them. */
    fun inUse(ctx: Context) = Prefs(ctx).let { it.ledElMode.isNotEmpty() || it.ledElColor(0) != 0 }

    fun apply(ctx: Context) {
        if (!inUse(ctx) || !AnbernicHelper.alive(ctx)) return
        val mode = Device.elState(ctx, 0).let { if (it == 0 && Device.sysOn(ctx, "led_enable")) 0 else it }
        val p = Prefs(ctx)
        val wantMode = p.ledElMode.getOrNull(0)?.digitToIntOrNull() ?: mode
        if (wantMode == 0) {
            // Anbernic's service switches off through its setting; the controller itself through a mode without light (6)
            if (last != "off") { last = "off"; Device.put("led_enable", "0") {}; AnbernicHelper.joypad(listOf("led_mode" to 6, "led_set" to 1)) }
            return
        }
        // Kombi: Anbernic's chasing light with the combination's two colours (first and second channel)
        // Kombi: two colours – breathing when that is the chosen effect, otherwise the chasing light
        val fx = when (wantMode) {
            3 -> Device.elFx(ctx, 0)
            2 -> if (Device.elFx(ctx, 0) == LedEffect.BREATHE) LedEffect.BREATHE else LedEffect.CHASE
            else -> LedEffect.NONE
        }
        val eff = effect[fx] ?: "0"
        // the displays: steady light in the colour of the battery / temperature, looked at again every 30 s
        main.removeCallbacks(tick)
        if (fx.indicator) main.postDelayed(tick, 30_000)
        val colour = if (fx.indicator) indicator(ctx, fx) else if (wantMode == 2) Device.comboColor(ctx, 0) else Device.elOutColor(ctx, 0, wantMode)
        val colour2 = if (wantMode == 2) Device.comboColor(ctx, 1) else colour
        val level = Device.elBright(ctx, 0).coerceIn(5, 100)
        val key = "$eff/$colour/$colour2/$level"
        if (key == last) return
        val effectChanged = last.substringBefore('/') != eff || last == "off" || last.isEmpty()
        last = key
        val write = {
            val r = Color.red(colour); val g = Color.green(colour); val b = Color.blue(colour)
            val r2 = Color.red(colour2); val g2 = Color.green(colour2); val b2 = Color.blue(colour2)
            listOf("led_sync_colour" to 0, "custum_rgb_r" to r, "custum_rgb_g" to g, "custum_rgb_b" to b,
                "Led_rgb_r1" to r, "Led_rgb_g1" to g, "Led_rgb_b1" to b, "Led_rgb_r2" to r2, "Led_rgb_g2" to g2, "Led_rgb_b2" to b2,
                // the controller's own mode as well (tested: led_mode = led_effect + 1) – so the effect runs
                // even with Anbernic's control center switched off
                "led_switch" to 1, "led_mode" to (eff.toIntOrNull() ?: 0) + 1,
                "led_level" to level, "led_set" to 1).let { AnbernicHelper.joypad(it) }
        }
        if (effectChanged) {
            // Anbernic paints its own colour with a new effect: ours over it a moment later
            Device.put("led_effect", eff) { Device.put("led_enable", "1") { main.postDelayed({ write() }, 800) } }
        } else write()
    }

    /**
     * After standby: our colour again, twice (Anbernic's service may paint its own while waking up).
     * No switching off and on – that showed Anbernic's colour for a moment.
     */
    fun wake(ctx: Context) {
        if (!inUse(ctx) || !AnbernicHelper.alive(ctx)) return
        if ((Prefs(ctx).ledElMode.getOrNull(0)?.digitToIntOrNull() ?: Device.elState(ctx, 0)) == 0) return
        main.postDelayed({ forceColour(ctx) }, 300)
        main.postDelayed({ forceColour(ctx) }, 2800)
    }

    /** Colour, brightness and set again, even if Hugminn thinks they are already there. */
    private fun forceColour(ctx: Context) {
        val e = last.substringBefore('/')
        last = if (e.isNotEmpty() && e != "off") "$e/x" else last
        apply(ctx)
    }

    /** After a restart of the helper: our colour again (Anbernic's app put its own back). */
    fun reapply(ctx: Context) { last = ""; apply(ctx) }
}

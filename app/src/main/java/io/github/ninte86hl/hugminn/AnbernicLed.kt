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
    private val effect = mapOf(LedEffect.NONE to "0", LedEffect.BREATHE to "1", LedEffect.RING to "2",
        LedEffect.MARQUEE to "3", LedEffect.CHASE to "4", LedEffect.GAMING to "5")

    val effects = listOf(LedEffect.NONE, LedEffect.BREATHE, LedEffect.RING, LedEffect.MARQUEE, LedEffect.CHASE, LedEffect.GAMING)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var last = ""

    fun apply(ctx: Context) {
        val mode = Device.elState(ctx, 0).let { if (it == 0 && Device.sysOn(ctx, "led_enable")) 0 else it }
        val p = Prefs(ctx)
        val wantMode = p.ledElMode.getOrNull(0)?.digitToIntOrNull() ?: mode
        if (wantMode == 0) {
            if (last != "off") { last = "off"; Device.put("led_enable", "0") {} }
            return
        }
        val fx = if (wantMode == 3) Device.elFx(ctx, 0) else LedEffect.NONE
        val eff = effect[fx] ?: "0"
        val colour = Device.elOutColor(ctx, 0, wantMode)
        val level = Device.elBright(ctx, 0).coerceIn(5, 100)
        val key = "$eff/$colour/$level"
        if (key == last) return
        val effectChanged = last.substringBefore('/') != eff || last == "off" || last.isEmpty()
        last = key
        val write = {
            val r = Color.red(colour); val g = Color.green(colour); val b = Color.blue(colour)
            listOf("led_sync_colour" to 0, "custum_rgb_r" to r, "custum_rgb_g" to g, "custum_rgb_b" to b,
                "Led_rgb_r1" to r, "Led_rgb_g1" to g, "Led_rgb_b1" to b, "Led_rgb_r2" to r, "Led_rgb_g2" to g, "Led_rgb_b2" to b,
                "led_level" to level, "led_set" to 1).forEach { (k, v) -> AnbernicHelper.joypad(k, v) }
        }
        if (effectChanged) {
            // Anbernic paints its own colour with a new effect: ours over it a moment later
            Device.put("led_effect", eff) { Device.put("led_enable", "1") { main.postDelayed({ write() }, 800) } }
        } else write()
    }

    /** After a restart of the helper: our colour again (Anbernic's app put its own back). */
    fun reapply(ctx: Context) { last = ""; apply(ctx) }
}

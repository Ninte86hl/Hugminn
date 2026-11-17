package io.github.ninte86hl.hugminn

import android.content.Context
import android.provider.Settings

/** Reads and changes handheld settings. Reads work without root, changes go through the root service. */
object Device {
    fun sys(ctx: Context, key: String): String? = Settings.System.getString(ctx.contentResolver, key)

    val isRetroid get() = android.os.Build.MODEL.contains("Retroid", ignoreCase = true)

    // Performance mode as the Retroid quick tile sets it.
    fun performance(ctx: Context) = sys(ctx, "performance_mode") == "1"
    fun setPerformance(on: Boolean, then: () -> Unit) = Root.run(
        Root.putSetting("system", "performance_mode", if (on) "1" else "0"),
        "setprop persist.vendor.debug.mode ${if (on) 1 else 0}",
        then = then,
    )

    /** Known fan modes; other values are shown raw. */
    val fanModes = linkedMapOf("1" to "Leise", "4" to "Smart")
    fun fanLabel(ctx: Context): String {
        val v = sys(ctx, "fan_mode") ?: return "–"
        return fanModes[v] ?: "Modus $v"
    }
    fun nextFan(ctx: Context, then: () -> Unit) {
        val keys = fanModes.keys.toList()
        val cur = sys(ctx, "fan_mode")
        val next = keys[(keys.indexOf(cur) + 1).mod(keys.size)]
        Root.run(Root.putSetting("system", "fan_mode", next), then = then)
    }

    fun highRefresh(ctx: Context) = (sys(ctx, "peak_refresh_rate")?.toFloatOrNull() ?: 60f) > 61f
    fun setHighRefresh(on: Boolean, then: () -> Unit) = Root.run(
        Root.putSetting("system", "peak_refresh_rate", if (on) "120.00001" else "60.0"),
        Root.putSetting("system", "min_refresh_rate", if (on) "120.00001" else "60.0"),
        then = then,
    )

    fun stickLeds(ctx: Context) = sys(ctx, "joystick_light_enabled")?.contains("1") == true
    fun setStickLeds(on: Boolean, then: () -> Unit) =
        Root.run(Root.putSetting("system", "joystick_light_enabled", if (on) "1,1" else "0,0"), then = then)

    fun wifiOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "wifi_on", 0) != 0
    fun setWifi(on: Boolean, then: () -> Unit) = Root.run("svc wifi ${if (on) "enable" else "disable"}", then = then)

    fun btOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "bluetooth_on", 0) != 0
    fun setBt(on: Boolean, then: () -> Unit) =
        Root.run("cmd bluetooth_manager ${if (on) "enable" else "disable"}", then = then)

    fun brightness(ctx: Context) = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    fun setBrightness(ctx: Context, v: Int) {
        val value = v.coerceIn(1, 255)
        if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        } else {
            Root.run("settings put system screen_brightness_mode 0", "settings put system screen_brightness $value")
        }
    }

    /** Adds Hugminn's accessibility service via root. Returns true when it is in the list afterwards. */
    fun enableService(ctx: Context): Boolean {
        val comp = "${ctx.packageName}/${HugminnService::class.java.name}"
        val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
        if (cur.split(':').any { it == comp || it == "${ctx.packageName}/.HugminnService" }) return true
        val next = if (cur.isBlank() || cur == "null") comp else "$cur:$comp"
        Root.exec(Root.putSetting("secure", "enabled_accessibility_services", next))
        Root.exec("settings put secure accessibility_enabled 1")
        Thread.sleep(300)
        return Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty().contains(comp)
    }

    /** Grants usage access (helps to name apps in the switcher) and screen brightness writing via root. */
    fun grantExtras(ctx: Context) {
        Root.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        Root.exec("appops set ${ctx.packageName} WRITE_SETTINGS allow")
    }
}

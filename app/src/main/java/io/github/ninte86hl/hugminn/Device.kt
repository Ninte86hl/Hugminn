package io.github.ninte86hl.hugminn

import android.content.Context
import android.provider.Settings

/** Reads and changes handheld settings. Reads work without root, changes go through the root service. */
object Device {
    fun sys(ctx: Context, key: String): String? = Settings.System.getString(ctx.contentResolver, key)

    val isRetroid get() = android.os.Build.MODEL.contains("Retroid", ignoreCase = true)

    fun performance(ctx: Context) = (sys(ctx, "performance_mode") ?: "0") != "0"

    /** Known profiles (value → name). Values not listed yet are shown raw and can still be picked. */
    val perfProfiles = linkedMapOf("0" to "Normal", "1" to "Leistung")
    val fanProfiles = linkedMapOf("1" to "Leise", "4" to "Smart")

    fun profileLabel(ctx: Context, key: String, table: Map<String, String>): String {
        val v = sys(ctx, key) ?: return "–"
        return table[v] ?: "Profil $v"
    }

    fun fanLabel(ctx: Context) = profileLabel(ctx, "fan_mode", fanProfiles)
    fun perfLabel(ctx: Context) = profileLabel(ctx, "performance_mode", perfProfiles)

    fun setFan(value: String, then: () -> Unit) = Root.run(Root.putSetting("system", "fan_mode", value), then = then)
    fun setPerf(value: String, then: () -> Unit) = Root.run(
        Root.putSetting("system", "performance_mode", value),
        "setprop persist.vendor.debug.mode ${if (value == "0") 0 else 1}",
        then = then,
    )

    fun wifiName(ctx: Context): String? {
        val wm = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid?.trim('"') ?: return null
        return ssid.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /** Paired Bluetooth devices with their connection state (needs the Bluetooth permission). */
    fun pairedDevices(ctx: Context): List<Pair<String, Boolean>> = try {
        val bm = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
        @Suppress("MissingPermission")
        bm?.adapter?.bondedDevices?.map { d ->
            val connected = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
            (d.name ?: d.address) to connected
        }?.sortedByDescending { it.second } ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
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

    /** Retroid/AYN kill background apps that are not on this list (Settings.System, comma separated). */
    private const val WHITELIST = "app_whiteList"

    fun hasWhitelist(ctx: Context) = sys(ctx, WHITELIST) != null

    fun whitelisted(ctx: Context): Boolean =
        sys(ctx, WHITELIST)?.split(',')?.map { it.trim() }?.contains(ctx.packageName) == true

    /** Puts Hugminn on the firmware's background whitelist and exempts it from battery optimisation. */
    fun ensureWhitelist(ctx: Context): Boolean {
        if (!Root.available()) return whitelisted(ctx)
        val pkg = ctx.packageName
        if (hasWhitelist(ctx) && !whitelisted(ctx)) {
            val cur = sys(ctx, WHITELIST).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
            Root.exec(Root.putSetting("system", WHITELIST, (cur + pkg).joinToString(",")))
        }
        Root.exec("dumpsys deviceidle whitelist +$pkg")
        Root.exec("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        return !hasWhitelist(ctx) || whitelisted(ctx)
    }

    /** Grants usage access (helps to name apps in the switcher) and screen brightness writing via root. */
    fun grantExtras(ctx: Context) {
        Root.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        Root.exec("appops set ${ctx.packageName} WRITE_SETTINGS allow")
        Root.exec("pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION")
        Root.exec("pm grant ${ctx.packageName} android.permission.BLUETOOTH_CONNECT")
    }
}

/**
 * Writes down every change of a system setting while Hugminn runs, so the values behind the
 * firmware's profiles (performance, fan, ...) can be learned by simply switching through them.
 */
object SettingsWatch {
    private val skip = Regex("^(volume_|screen_brightness|screen_off_timeout|next_alarm|dim_screen|haptic|sound_effects)")
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private var observer: android.database.ContentObserver? = null

    fun start(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        val o = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                val key = uri?.lastPathSegment ?: return
                if (skip.containsMatchIn(key)) return
                val v = Device.sys(app, key)
                val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
                synchronized(log) {
                    log.add(0, "$t  $key = $v")
                    while (log.size > 60) log.removeAt(log.lastIndex)
                }
            }
        }
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, o)
        observer = o
    }

    fun stop(ctx: Context) {
        observer?.let { ctx.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}

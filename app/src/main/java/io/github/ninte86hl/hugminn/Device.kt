package io.github.ninte86hl.hugminn

import android.content.Context
import android.provider.Settings

/** Reads and changes handheld settings. Reads work without root, changes go through the root service. */
object Device {
    fun sys(ctx: Context, key: String): String? = Settings.System.getString(ctx.contentResolver, key)

    val isRetroid get() = android.os.Build.MODEL.contains("Retroid", ignoreCase = true)

    fun performance(ctx: Context) = (sys(ctx, "performance_mode") ?: "0") != "0"

    /** Known profiles (value → name). Values not listed yet are shown raw and can still be picked. */
    // Values as used by the shared Retroid/AYN firmware (fan 6 = custom speed from fan_speed).
    val perfProfiles get() = linkedMapOf("0" to "Normal", "1" to tr("Leistung"), "2" to tr("Hohe Leistung"))
    val fanProfiles get() = linkedMapOf("0" to tr("Aus"), "1" to tr("Leise"), "4" to "Smart", "5" to "Sport", "6" to tr("Eigene Kurve"))
    val triggerModes get() = linkedMapOf("2" to tr("Analog + Digital"), "1" to tr("Nur analog"), "0" to tr("Nur digital"))

    /** Simple on/off system settings of the firmware (1 = on). */
    fun sysOn(ctx: Context, key: String) = sys(ctx, key)?.trim() == "1"
    fun setSys(key: String, value: String, then: () -> Unit) = Root.run(Root.putSetting("system", key, value), then = then)

    /** Button layout: Xbox (A bottom) or Retroid/Nintendo (A right). */
    fun setControllerLayout(nintendo: Boolean, then: () -> Unit) = Root.run(
        Root.putSetting("system", "temp_abxy_layout_mode", if (nintendo) "1" else "0"),
        Root.putSetting("system", "flip_button_layout", if (nintendo) "0" else "1"),
        "setprop persist.sys.gamepad.type ${if (nintendo) 0 else 1}",
        then = then,
    )

    /** The firmware's third controller mode: the built-in controls are off (for an external controller). */
    fun builtinOff(ctx: Context) = sys(ctx, "temp_abxy_layout_mode")?.trim() == "2"

    /** Built-in controls on (back to the layout set before) or off. */
    fun setBuiltin(ctx: Context, on: Boolean, then: () -> Unit) {
        if (!on) { Root.run(Root.putSetting("system", "temp_abxy_layout_mode", "2"), then = then); return }
        // flip_button_layout keeps the layout while the controls are off: 0 = Retroid/Nintendo, 1 = Xbox
        val nintendo = sys(ctx, "flip_button_layout")?.trim() != "1"
        Root.run(Root.putSetting("system", "temp_abxy_layout_mode", if (nintendo) "1" else "0"), then = then)
    }

    /** A game controller other than the handheld's own is connected (Bluetooth or USB). */
    fun externalPad(): Boolean = android.view.InputDevice.getDeviceIds().any { id ->
        val d = android.view.InputDevice.getDevice(id) ?: return@any false
        d.isExternal && d.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
    }

    fun profileLabel(ctx: Context, key: String, table: Map<String, String>): String {
        val v = sys(ctx, key) ?: return "–"
        return table[v] ?: t("Profil $v", "Profile $v")
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

    /** Colours of the stick LEDs as Retroid's LED window stores them: "#AARRGGBB,#AARRGGBB" (left, right). */
    fun ledColors(ctx: Context): Pair<Int, Int>? = try {
        val parts = sys(ctx, "joystick_led_light_picker_color")?.split(',')?.map { android.graphics.Color.parseColor(it.trim()) }
        if (parts == null || parts.isEmpty()) null else parts[0] to parts.getOrElse(1) { parts[0] }
    } catch (t: Throwable) {
        null
    }

    fun setLedColors(left: Int, right: Int, turnOn: Boolean, then: () -> Unit) {
        fun hex(c: Int) = "#%08x".format(c or 0xFF000000.toInt())
        val cmds = mutableListOf(Root.putSetting("system", "joystick_led_light_picker_color", "${hex(left)},${hex(right)}"))
        if (turnOn) cmds += Root.putSetting("system", "joystick_light_enabled", "1,1")
        Root.run(*cmds.toTypedArray(), then = then)
    }

    /** Nine clear, fully saturated colours (the LEDs show pastel tones washed out). */
    /** Brightness of the stick LEDs as Retroid's LED window stores it (0.0–1.0). */
    fun ledBrightness(ctx: Context): Float = sys(ctx, "led_light_brightness_percent")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    fun setLedBrightness(percent: Int, then: () -> Unit) =
        Root.run(Root.putSetting("system", "led_light_brightness_percent", "%.2f".format(java.util.Locale.US, percent / 100f)), then = then)

    val ledPresets get() = listOf(
        tr("Weiß") to 0xFFFFFFFF.toInt(),
        tr("Rot") to 0xFFFF0000.toInt(),
        "Orange" to 0xFFFF6600.toInt(),
        tr("Gelb") to 0xFFFFDD00.toInt(),
        tr("Limette") to 0xFF99FF00.toInt(),
        tr("Grün") to 0xFF00FF00.toInt(),
        tr("Türkis") to 0xFF00FF99.toInt(),
        "Cyan" to 0xFF00FFFF.toInt(),
        tr("Blau") to 0xFF0000FF.toInt(),
        tr("Lila") to 0xFF8800FF.toInt(),
        "Magenta" to 0xFFFF00FF.toInt(),
        "Pink" to 0xFFFF0077.toInt(),
    )

    /** Installed memory: Android reports a bit less (reserved for the system), so round up to the module size. */
    fun ramInstalledGb(totalMb: Long): Int {
        val gb = totalMb / 1024f
        return listOf(2, 3, 4, 6, 8, 12, 16, 24, 32).firstOrNull { it >= gb } ?: Math.round(gb)
    }

    fun airplane(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    fun setAirplane(on: Boolean, then: () -> Unit) =
        Root.run("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}", then = then)

    fun dnd(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "zen_mode", 0) != 0
    fun setDnd(on: Boolean, then: () -> Unit) = Root.run("cmd notification set_dnd ${if (on) "on" else "off"}", then = then)

    /** True when the screen rotation is locked. */
    fun rotationLocked(ctx: Context) = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 0
    fun setRotationLocked(ctx: Context, locked: Boolean, then: () -> Unit) {
        val v = if (locked) 0 else 1
        if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, v)
            then()
        } else Root.run("settings put system accelerometer_rotation $v", then = then)
    }

    fun keepScreenOn(ctx: Context) = sys(ctx, "keep_screen_on") == "1"
    fun setKeepScreenOn(on: Boolean, then: () -> Unit) =
        Root.run(Root.putSetting("system", "keep_screen_on", if (on) "1" else "0"), then = then)

    fun fanSpeed(ctx: Context) = sys(ctx, "fan_speed")?.toIntOrNull()

    /** Ends background apps (like "Clean memory" on AYN); returns the freed RAM in MB. */
    fun cleanMemory(ctx: Context): Long {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        fun avail() = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_048_576
        val before = avail()
        Root.exec("am kill-all", 8_000)
        Thread.sleep(600)
        return (avail() - before).coerceAtLeast(0)
    }

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

    /** Turns the accessibility service off and on again (Android stops it after a crash). */
    fun restartService(ctx: Context): Boolean {
        val comp = "${ctx.packageName}/${HugminnService::class.java.name}"
        val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
        val others = cur.split(':').filter { it.isNotBlank() && it != "null" && !it.startsWith("${ctx.packageName}/") }
        Root.exec(Root.putSetting("secure", "enabled_accessibility_services", others.joinToString(":").ifEmpty { "\"\"" }))
        Thread.sleep(400)
        Root.exec(Root.putSetting("secure", "enabled_accessibility_services", (others + comp).joinToString(":")))
        Root.exec("settings put secure accessibility_enabled 1")
        Thread.sleep(500)
        return HugminnService.instance != null
    }

    /** Grants usage access (helps to name apps in the switcher) and screen brightness writing via root. */
    fun grantExtras(ctx: Context) {
        Root.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        Root.exec("appops set ${ctx.packageName} WRITE_SETTINGS allow")
        Root.exec("pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION")
        Root.exec("pm grant ${ctx.packageName} android.permission.BLUETOOTH_CONNECT")
    }
}


package io.github.ninte86hl.hugminn

import android.content.Context
import android.provider.Settings

/** Reads and changes handheld settings. Reads work without root, changes go through the root service. */
object Device {
    fun sys(ctx: Context, key: String): String? = Settings.System.getString(ctx.contentResolver, key)

    val isRetroid get() = android.os.Build.MODEL.contains("Retroid", ignoreCase = true)

    fun performance(ctx: Context) = (sys(ctx, perfKey) ?: "0") != "0"

    /** Known profiles (value → name). Values not listed yet are shown raw and can still be picked. */
    // Values as used by the shared Retroid/AYN firmware (fan 6 = custom speed from fan_speed).
    val isAnbernic get() = android.os.Build.MANUFACTURER.contains("Anbernic", true) || android.os.Build.MODEL.startsWith("RG", true)

    /** Anbernic stores its performance mode as cpu_mode (0 Balance, 1 Game). */
    val perfKey get() = if (isAnbernic) "cpu_mode" else "performance_mode"

    val perfProfiles get() = if (isAnbernic) linkedMapOf("0" to "Normal", "1" to tr("Leistung"))
        else linkedMapOf("0" to "Normal", "1" to tr("Leistung"), "2" to tr("Hohe Leistung"))
    /** Anbernic: 0 Silent, 1 Balance, 2 Turbo, 3 fixed speed (custom_fan_speed %). */
    val fanProfiles get() = if (isAnbernic) linkedMapOf("0" to tr("Leise"), "1" to tr("Ausgewogen"), "2" to "Turbo", "3" to tr("Fest"))
        else linkedMapOf("0" to tr("Aus"), "1" to tr("Leise"), "4" to "Smart", "5" to "Sport", "6" to tr("Eigene Kurve"))
    /** Anbernic lighting effects (led_effect). */
    val anbernicEffects get() = linkedMapOf("0" to tr("Leuchten"), "1" to tr("Atmen"), "2" to tr("Regenbogen"), "3" to "Marquee", "4" to tr("Lauflicht"), "5" to "Gaming")

    /** App context, for writing settings without root. */
    @Volatile var app: Context? = null

    /**
     * Without root Hugminn may still write system settings once WRITE_SECURE_SETTINGS was granted
     * (Anbernic: by its "Root Scripts", once).
     */
    fun canWrite(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** One system setting: root if there is root, otherwise directly with the granted permission. */
    fun put(key: String, value: String, then: () -> Unit) {
        val c = app
        if (!Root.available() && c != null && canWrite(c)) {
            Thread {
                runCatching { Settings.System.putString(c.contentResolver, key, value) }
                main.post(then)
            }.start()
        } else Root.run(Root.putSetting("system", key, value), then = then)
    }
    /** Trigger modes; AYN numbers analog and digital the other way round than Retroid. */
    val triggerModes get() = if (isAyn) linkedMapOf("2" to tr("Analog + Digital"), "0" to tr("Nur analog"), "1" to tr("Nur digital"))
        else linkedMapOf("2" to tr("Analog + Digital"), "1" to tr("Nur analog"), "0" to tr("Nur digital"))

    /** Simple on/off system settings of the firmware (1 = on). */
    fun sysOn(ctx: Context, key: String) = sys(ctx, key)?.trim() == "1"
    fun setSys(key: String, value: String, then: () -> Unit) = put(key, value, then)

    /**
     * AYN (Odin 2) has no temp_abxy_layout_mode: its "Controller style" is flip_button_layout
     * (1 = Xbox, 0 = Nintendo) and "Disconnect" is no_create_gamepad_button_layout = 1.
     */
    private fun aynPad(ctx: Context) = isAyn && sys(ctx, "temp_abxy_layout_mode") == null && sys(ctx, "flip_button_layout") != null
    fun hasControllerSetting(ctx: Context) = sys(ctx, "temp_abxy_layout_mode") != null || aynPad(ctx)
    /** The setting the device test shows for the button layout. */
    fun controllerKey(ctx: Context) = if (aynPad(ctx)) "flip_button_layout" else "temp_abxy_layout_mode"

    /** AYN: A on the right (Nintendo) – the controller's name does not tell, the setting does. */
    @Volatile var aynNintendo: Boolean? = null
    fun refreshLayout(ctx: Context) { if (isAyn) aynNintendo = sys(ctx, "flip_button_layout")?.trim() == "0" }

    /** Button layout: Xbox (A bottom) or Retroid/Nintendo (A right). */
    fun setControllerLayout(nintendo: Boolean, then: () -> Unit) {
        if (isAyn) aynNintendo = nintendo
        val cmds = mutableListOf(
            Root.putSetting("system", "flip_button_layout", if (nintendo) "0" else "1"),
            "setprop persist.sys.gamepad.type ${if (nintendo) 0 else 1}",
        )
        // AYN: back on if it was disconnected; Retroid: its own layout setting
        if (isAyn) cmds += Root.putSetting("system", "no_create_gamepad_button_layout", "0")
        else cmds += Root.putSetting("system", "temp_abxy_layout_mode", if (nintendo) "1" else "0")
        Root.run(*cmds.toTypedArray(), then = then)
    }

    /** The firmware's third controller mode: the built-in controls are off (for an external controller). */
    fun builtinOff(ctx: Context) =
        if (aynPad(ctx)) sysOn(ctx, "no_create_gamepad_button_layout") else sys(ctx, "temp_abxy_layout_mode")?.trim() == "2"

    /** Built-in controls on (back to the layout set before) or off. Like Retroid's own quick setting, this
     *  also needs no_create_gamepad_button_layout = 1, or the built-in controller stays active. */
    fun setBuiltin(ctx: Context, on: Boolean, then: () -> Unit) {
        if (aynPad(ctx)) { Root.run(Root.putSetting("system", "no_create_gamepad_button_layout", if (on) "0" else "1"), then = then); return }
        val store = ctx.getSharedPreferences("controller", Context.MODE_PRIVATE)
        if (!on) {
            val now = sys(ctx, "temp_abxy_layout_mode")?.trim()
            if (now == "0" || now == "1") store.edit().putString("mode", now).apply()
            Root.run(Root.putSetting("system", "no_create_gamepad_button_layout", "1"),
                Root.putSetting("system", "temp_abxy_layout_mode", "2"), then = then)
            return
        }
        // back to the layout from before; flip_button_layout (1 = Xbox) as a fallback
        val mode = store.getString("mode", null) ?: if (sys(ctx, "flip_button_layout")?.trim() == "1") "0" else "1"
        Root.run(Root.putSetting("system", "no_create_gamepad_button_layout", "0"),
            Root.putSetting("system", "temp_abxy_layout_mode", mode), then = then)
    }

    /** A game controller other than the handheld's own is connected (Bluetooth or USB). */
    fun externalPad(): Boolean = android.view.InputDevice.getDeviceIds().any { isExternalPad(it) }

    fun isExternalPad(id: Int): Boolean {
        val d = android.view.InputDevice.getDevice(id) ?: return false
        return d.isExternal && !builtinPad(d) && d.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
    }

    /** Connected input devices, for diagnostics. */
    fun padList() = android.view.InputDevice.getDeviceIds().toList().mapNotNull { android.view.InputDevice.getDevice(it) }
        .filter { it.isExternal }.joinToString { "${it.name} (${it.vendorId}/${it.productId}${if (builtinPad(it)) ", built-in" else ""})" }

    /** The handheld's own controls also show up as "external": Retroid's "Xbox Wireless Controller" (vendor
     *  0x2022, product 0x3002) and virtual mouse, AYN's "Odin Controller" (Thor, Odin 2 family). */
    private fun builtinPad(d: android.view.InputDevice) =
        d.name.contains("Retroid", true) || (d.vendorId == 0x2022 && d.productId == 0x3002) || d.name.contains("Odin Controller", true)

    /** AYN handhelds (Thor, Odin 2 family): same firmware settings as Retroid, plus their own quirks. */
    val isAyn get() = android.os.Build.MANUFACTURER.equals("AYN", true)

    /**
     * Firmware settings that exist on the device but are only written once they were changed (AYN's
     * trigger mode): until then the tile shows "Standard" instead of being hidden.
     */
    /**
     * The AYN button (Thor): it arrives as Home, but from "gpio-keys" instead of the controller.
     * Hugminn leaves it to AYN (press/double press open and close AYN's menu, a long press turns the screen off).
     */
    fun isAynKey(e: android.view.KeyEvent) = isAyn && e.keyCode == android.view.KeyEvent.KEYCODE_HOME && e.device?.name == "gpio-keys"

    fun lazySetting(key: String) = isAyn && key == "trigger_input_mode"

    fun profileLabel(ctx: Context, key: String, table: Map<String, String>): String {
        val v = sys(ctx, key) ?: return if (lazySetting(key)) t("Standard", "Default") else "–"
        return table[v] ?: t("Profil $v", "Profile $v")
    }

    fun fanLabel(ctx: Context) = profileLabel(ctx, "fan_mode", fanProfiles).let { l ->
        if (isAnbernic && sys(ctx, "fan_mode") == "3") "$l ${sys(ctx, "custom_fan_speed") ?: "50"} %" else l
    }
    fun perfLabel(ctx: Context) = profileLabel(ctx, perfKey, perfProfiles)

    fun setFan(value: String, then: () -> Unit) = put("fan_mode", value, then)
    fun setPerf(value: String, then: () -> Unit) = if (isAnbernic) put(perfKey, value, then) else Root.run(
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

    /**
     * Stick LEDs: Retroid keeps one setting for both sticks ("1,1"), AYN (Odin 2) one per stick
     * (left_/right_joystick_light_enabled) and a single colour as a number.
     */
    private fun aynLeds(ctx: Context) = isAyn && sys(ctx, "left_joystick_light_enabled") != null
    /** Retroid Pocket Classic (both variants): a D-pad only, no sticks – so no stick LEDs either. */
    val noSticks: Boolean = listOf(android.os.Build.MODEL, android.os.Build.DEVICE, android.os.Build.PRODUCT)
        .any { it?.contains("classic", true) == true }

    fun hasStickLeds(ctx: Context) = !noSticks && (sys(ctx, "joystick_light_enabled") != null || aynLeds(ctx))
    /** The setting the device test shows for the stick LEDs. */
    fun stickLedKey(ctx: Context) = if (aynLeds(ctx)) "left_joystick_light_enabled" else "joystick_light_enabled"

    fun stickLeds(ctx: Context) =
        if (aynLeds(ctx)) sysOn(ctx, "left_joystick_light_enabled") || sysOn(ctx, "right_joystick_light_enabled")
        else sys(ctx, "joystick_light_enabled")?.contains("1") == true

    /** The root commands that switch the stick LEDs on or off. */
    fun stickLedCmds(ctx: Context, on: Boolean): Array<String> =
        if (aynLeds(ctx)) arrayOf(
            Root.putSetting("system", "left_joystick_light_enabled", if (on) "1" else "0"),
            Root.putSetting("system", "right_joystick_light_enabled", if (on) "1" else "0"),
        ) else arrayOf(Root.putSetting("system", "joystick_light_enabled", if (on) "1,1" else "0,0"))

    fun setStickLeds(ctx: Context, on: Boolean, then: () -> Unit) = Root.run(*stickLedCmds(ctx, on), then = then)

    /** One stick on its own: [left] true = left stick, false = right stick. */
    fun stickOn(ctx: Context, left: Boolean): Boolean =
        if (aynLeds(ctx)) sysOn(ctx, if (left) "left_joystick_light_enabled" else "right_joystick_light_enabled")
        else sys(ctx, "joystick_light_enabled")?.split(',')?.getOrNull(if (left) 0 else 1)?.trim() == "1"

    fun setStick(ctx: Context, left: Boolean, on: Boolean, then: () -> Unit) {
        if (aynLeds(ctx)) {
            Root.run(Root.putSetting("system", if (left) "left_joystick_light_enabled" else "right_joystick_light_enabled", if (on) "1" else "0"), then = then)
            return
        }
        // Retroid keeps both in one setting: "left,right"
        val l = if (left) on else stickOn(ctx, true)
        val r = if (left) stickOn(ctx, false) else on
        Root.run(Root.putSetting("system", "joystick_light_enabled", "${if (l) 1 else 0},${if (r) 1 else 0}"), then = then)
    }

    /** Odin 2: the two side lights have different colours (Hugminn's own, written to the LED driver). */
    fun aynSplit(ctx: Context) = isAyn && Prefs(ctx).let { it.aynLedLeft != 0 && it.aynLedRight != 0 }

    /** The lights effects and the LED driver work on: the sides on the Odin 2, the sticks elsewhere. */
    fun fxOn(ctx: Context) = if (hasSideLights(ctx)) sideLights(ctx) else stickLeds(ctx)
    fun fxCmds(ctx: Context, on: Boolean): Array<String> = if (hasSideLights(ctx)) arrayOf(
        Root.putSetting("system", "left_handle_light_enabled", if (on) "1" else "0"),
        Root.putSetting("system", "right_handle_light_enabled", if (on) "1" else "0"),
    ) else stickLedCmds(ctx, on)

    /** Odin 2: the sticks' colour – AYN's one colour (it also lights the sides unless they have their own). */
    fun aynStickColor(ctx: Context): Int = sys(ctx, "led_light_picker_color")?.trim()?.toLongOrNull()?.toInt() ?: android.graphics.Color.WHITE

    fun setAynStickColor(ctx: Context, c: Int, then: () -> Unit) {
        val v = c or 0xFF000000.toInt()
        val cmds = mutableListOf(Root.putSetting("system", "led_light_picker_color", "$v"))
        if (!stickLeds(ctx)) cmds += stickLedCmds(ctx, true)
        // the firmware paints the sides too: their own colours go back on afterwards
        Root.run(*cmds.toTypedArray()) { if (aynSplit(ctx) && sideLights(ctx)) LedFx.restoreStatic(ctx, delayMs = 400); then() }
    }

    /** Odin 2: the sides follow the sticks' colour again. */
    fun clearSideColors(ctx: Context, then: () -> Unit) {
        val p = Prefs(ctx)
        p.aynLedLeft = 0
        p.aynLedRight = 0
        val v = aynStickColor(ctx) or 0xFF000000.toInt()
        // written twice (a hair different first), so the firmware repaints everything
        Root.run(Root.putSetting("system", "led_light_picker_color", "${v xor 0x010101}"), Root.putSetting("system", "led_light_picker_color", "$v"), then = then)
    }

    /**
     * Odin 2 (the LED tester showed it): on both drivers zone 2 is the stick, zone 1 the side.
     * Colours per area are Hugminn's own (0 = AYN's colour) and go to the driver.
     */
    fun areaColors(ctx: Context, side: Boolean): Pair<Int, Int> {
        val p = Prefs(ctx)
        val base = aynStickColor(ctx) or 0xFF000000.toInt()
        val (l, r) = if (side) p.aynLedLeft to p.aynLedRight else p.aynStickLeft to p.aynStickRight
        return (if (l != 0) l else base) to (if (r != 0) r else base)
    }

    fun setAreaColors(ctx: Context, side: Boolean, left: Int, right: Int, then: () -> Unit) {
        val p = Prefs(ctx)
        val l = left or 0xFF000000.toInt()
        val r = right or 0xFF000000.toInt()
        if (side) { p.aynLedLeft = l; p.aynLedRight = r } else { p.aynStickLeft = l; p.aynStickRight = r }
        // the area must be switched on for the driver to show anything
        val on = if (side) sideLights(ctx) else stickLeds(ctx)
        if (!on) { if (side) setSideLights(true, then) else setStickLeds(ctx, true, then) } else then()
    }

    // ---- LED elements: each stick (and on the Odin 2 each side) on its own ----

    fun ledElNames(ctx: Context): List<String> = if (hasSideLights(ctx))
        listOf(tr("Linker Stick"), tr("Rechter Stick"), tr("Linke Seite"), tr("Rechte Seite"))
        else listOf(tr("Linker Stick"), tr("Rechter Stick"))

    /**
     * Mode of an element – what it shows: 0 off, 1 its colour, 2 the combination, 3 its effect.
     * Colour, combination and effect are each set once; the mode picks which one lights.
     */
    fun elState(ctx: Context, i: Int): Int {
        val odin = hasSideLights(ctx)
        // switched off by AYN's/Retroid's own setting: off
        val powered = if (odin) (if (i < 2) stickLeds(ctx) else sideLights(ctx)) else stickOn(ctx, i == 0)
        if (!powered) return 0
        val p = Prefs(ctx)
        p.ledElMode.getOrNull(i)?.digitToIntOrNull()?.takeIf { it in 0..3 }?.let { return it }
        // from the earlier Aus · An · Benutzerdefiniert: on with an effect = effect, otherwise colour
        val old = p.ledElState.getOrNull(i)?.digitToIntOrNull() ?: 1
        return if (old == 0) 0 else if (elFx(ctx, i) != LedEffect.NONE) 3 else 1
    }

    fun elStates(ctx: Context) = IntArray(ledElNames(ctx).size) { elState(ctx, it) }

    /** Sets all modes; the power switches (AYN: both sticks / both sides; Retroid: each stick) follow. */
    fun setElStates(ctx: Context, s: IntArray, then: () -> Unit) {
        Prefs(ctx).ledElMode = s.joinToString("")
        if (hasSideLights(ctx)) {
            val sticks = s[0] > 0 || s[1] > 0
            val sides = s[2] > 0 || s[3] > 0
            val cmds = stickLedCmds(ctx, sticks).toMutableList()
            cmds += Root.putSetting("system", "left_handle_light_enabled", if (sides) "1" else "0")
            cmds += Root.putSetting("system", "right_handle_light_enabled", if (sides) "1" else "0")
            Root.run(*cmds.toTypedArray()) { LedFx.resume(ctx); then() }
        } else {
            Root.run(Root.putSetting("system", "joystick_light_enabled", "${if (s[0] > 0) 1 else 0},${if (s[1] > 0) 1 else 0}")) { LedFx.resume(ctx); then() }
        }
    }

    /** The element's own colour (also the colour of breathing and pulsing). */
    fun elColor(ctx: Context, i: Int): Int {
        Prefs(ctx).ledElColor(i).takeIf { it != 0 }?.let { return it }
        if (hasSideLights(ctx)) {
            val (l, r) = areaColors(ctx, side = i >= 2)
            return if (i % 2 == 0) l else r
        }
        val (l, r) = ledColors(ctx) ?: (android.graphics.Color.WHITE to android.graphics.Color.WHITE)
        return (if (i == 0) l else r) or 0xFF000000.toInt()
    }

    fun elColors(ctx: Context) = IntArray(ledElNames(ctx).size) { elColor(ctx, it) }

    fun setElColors(ctx: Context, c: IntArray, then: () -> Unit) {
        val p = Prefs(ctx)
        c.forEachIndexed { i, v -> p.setLedElColor(i, v or 0xFF000000.toInt()) }
        LedFx.resume(ctx)
        then()
    }

    /** Colour combinations: left lights (left stick, left side) the first, right ones the second. */
    val ledCombos get() = listOf(
        "Duo Rot/Blau" to (0xFFFF0000.toInt() to 0xFF0000FF.toInt()),
        tr("Feuer") to (0xFFFF0000.toInt() to 0xFFFF6600.toInt()),
        tr("Eisblau") to (0xFF00FFFF.toInt() to 0xFFFFFFFF.toInt()),
        tr("Ozean") to (0xFF0000FF.toInt() to 0xFF00FF99.toInt()),
        tr("Wald") to (0xFF00FF00.toInt() to 0xFF99FF00.toInt()),
        "Retro " + tr("Lila") to (0xFF8800FF.toInt() to 0xFFFF00FF.toInt()),
        tr("Sonnenuntergang") to (0xFFFF6600.toInt() to 0xFFFF00FF.toInt()),
        "Neon" to (0xFF99FF00.toInt() to 0xFFFF00FF.toInt()),
    )

    fun comboColor(ctx: Context, i: Int): Int {
        val list = ledCombos
        val pair = list[Prefs(ctx).ledComboSel.coerceIn(0, list.lastIndex)].second
        return if (i % 2 == 0) pair.first else pair.second
    }

    /** What an element shows now: its colour, the combination's or – with an effect – the effect in its colour. */
    fun elOutColor(ctx: Context, i: Int, mode: Int) = if (mode == 2) comboColor(ctx, i) else elColor(ctx, i)
    fun elOutFx(ctx: Context, i: Int, mode: Int) = if (mode == 3) elFx(ctx, i) else LedEffect.NONE

    /** Brightness of an element in percent (Odin 2: each its own; Retroid: the one value of both sticks). */
    fun elBright(ctx: Context, i: Int): Int {
        val global = (ledBrightness(ctx) * 100).toInt().coerceIn(5, 100)
        return Prefs(ctx).ledElB(i).takeIf { it in 1..100 } ?: global
    }

    fun setElBright(ctx: Context, i: Int, v: Int, then: () -> Unit) {
        val x = v.coerceIn(5, 100)
        Prefs(ctx).setLedElB(i, x); LedFx.resume(ctx); then()
    }

    /** Effect of an element (Retroid: one effect for both sticks). */
    fun elFx(ctx: Context, i: Int): LedEffect {
        val p = Prefs(ctx)
        return p.ledElFx(i) ?: if (i < 2 || !hasSideLights(ctx)) p.ledEffect else p.ledEffectSide
    }

    /** Any light on: a stick or (Odin 2) the side lights – what the tile shows. */
    fun lightsOn(ctx: Context) = stickLeds(ctx) || (hasSideLights(ctx) && sideLights(ctx))

    /** All lights on or off together: both sticks and, on the Odin 2, the sides. */
    fun setAllLights(ctx: Context, on: Boolean, then: () -> Unit) {
        val cmds = stickLedCmds(ctx, on).toMutableList()
        // everything on: elements that were switched off one by one light again too
        if (on) Prefs(ctx).let { p -> p.ledElMode = p.ledElMode.replace('0', '1') }
        if (hasSideLights(ctx)) {
            cmds += Root.putSetting("system", "left_handle_light_enabled", if (on) "1" else "0")
            cmds += Root.putSetting("system", "right_handle_light_enabled", if (on) "1" else "0")
        }
        Root.run(*cmds.toTypedArray(), then = then)
    }

    /** AYN Odin 2: lights in the sides of the case (left_/right_handle_light_enabled), same colour as the sticks. */
    fun hasSideLights(ctx: Context) = isAyn && sys(ctx, "left_handle_light_enabled") != null
    fun sideLights(ctx: Context) = sysOn(ctx, "left_handle_light_enabled") || sysOn(ctx, "right_handle_light_enabled")
    fun setSideLights(on: Boolean, then: () -> Unit) = Root.run(
        Root.putSetting("system", "left_handle_light_enabled", if (on) "1" else "0"),
        Root.putSetting("system", "right_handle_light_enabled", if (on) "1" else "0"),
        then = then,
    )

    /** Colours of the stick LEDs as Retroid's LED window stores them: "#AARRGGBB,#AARRGGBB" (left, right). */
    fun ledColors(ctx: Context): Pair<Int, Int>? = if (aynLeds(ctx)) sys(ctx, "led_light_picker_color")?.trim()?.toLongOrNull()?.toInt()?.let { c ->
        val p = Prefs(ctx)
        (if (p.aynLedLeft != 0) p.aynLedLeft else c) to (if (p.aynLedRight != 0) p.aynLedRight else c)
    } else try {
        val parts = sys(ctx, "joystick_led_light_picker_color")?.split(',')?.map { android.graphics.Color.parseColor(it.trim()) }
        if (parts == null || parts.isEmpty()) null else parts[0] to parts.getOrElse(1) { parts[0] }
    } catch (t: Throwable) {
        null
    }

    fun setLedColors(ctx: Context, left: Int, right: Int, turnOn: Boolean, then: () -> Unit) {
        fun hex(c: Int) = "#%08x".format(c or 0xFF000000.toInt())
        // AYN keeps one colour (sticks and sides): it gets the left one; two different colours for the
        // sticks are Hugminn's own and are written to the LED driver right after the firmware
        // Odin 2: AYN's one colour lights the sticks (setAynStickColor); the LED driver (sn3112 l/r)
        // reaches only the two sides – their colours are Hugminn's own and go to the driver
        val ayn = aynLeds(ctx)
        val split = ayn
        if (ayn) {
            val p = Prefs(ctx)
            p.aynLedLeft = left or 0xFF000000.toInt()
            p.aynLedRight = right or 0xFF000000.toInt()
        }
        val cmds = if (split) mutableListOf<String>()
            else if (ayn) mutableListOf(Root.putSetting("system", "led_light_picker_color", "${left or 0xFF000000.toInt()}"))
            else mutableListOf(Root.putSetting("system", "joystick_led_light_picker_color", "${hex(left)},${hex(right)}"))
        if (turnOn) cmds += fxCmds(ctx, true)
        if (split) { Root.run(*cmds.toTypedArray()) { LedFx.restoreStatic(ctx, delayMs = 150); then() }; return }
        Root.run(*cmds.toTypedArray(), then = then)
    }

    /** Nine clear, fully saturated colours (the LEDs show pastel tones washed out). */
    /** Brightness of the stick LEDs as Retroid's LED window stores it (0.0–1.0). */
    fun ledBrightness(ctx: Context): Float = sys(ctx, "led_light_brightness_percent")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    fun setLedBrightness(percent: Int, then: () -> Unit) =
        Root.run(Root.putSetting("system", "led_light_brightness_percent", "%.2f".format(java.util.Locale.US, percent / 100f)), then = then)

    /** The LED window's 3×3 colours. */
    val ledGrid get() = listOf(
        tr("Weiß") to 0xFFFFFFFF.toInt(),
        tr("Rot") to 0xFFFF0000.toInt(),
        "Orange" to 0xFFFF6600.toInt(),
        tr("Gelb") to 0xFFFFDD00.toInt(),
        tr("Grün") to 0xFF00FF00.toInt(),
        "Cyan" to 0xFF00FFFF.toInt(),
        tr("Blau") to 0xFF0000FF.toInt(),
        tr("Lila") to 0xFF8800FF.toInt(),
        "Magenta" to 0xFFFF00FF.toInt(),
    )

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

    /** Android's battery optimisation is off for Hugminn ("unrestricted"). */
    fun batteryFree(ctx: Context) = ctx.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

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
    /** Whether Hugminn may grant permissions to itself without asking (installs from before the consent setup). */
    fun autoGrant(ctx: Context) = !Prefs(ctx).askPermissions

    fun grantExtras(ctx: Context) {
        Root.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        Root.exec("appops set ${ctx.packageName} WRITE_SETTINGS allow")
        Root.exec("pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION")
        Root.exec("pm grant ${ctx.packageName} android.permission.BLUETOOTH_CONNECT")
    }
}


package io.github.ninte86hl.hugminn.probe

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Device-independent, read-only collectors. Every section catches its own errors. */
object Probe {

    private val SENSITIVE = Regex(
        "(?i)(serial|imei|meid|iccid|imsi|mac_?addr|wifi_?mac|bt_?addr|bluetooth_(address|name)|ssid|android_id|" +
            "device_name|account|email|password|passwd|token|lock_screen_owner|phone|gsm\\.|location|ril\\.|wifi_p2p)"
    )

    private val VENDOR_HINTS = Regex(
        "(?i)(retroid|com\\.ro\\.|com\\.rp\\.|odin|ayn|moorechip|cocoon|retroarch|bifrost|pulse|clustertune|shizuku|" +
            "launcher|dualscreen|mapping|wayfinder|esde|daijisho|beacon)"
    )

    /** Hidden system settings other AYN/Retroid tools are known to use. Read directly to see if no-root reads work. */
    val KNOWN_KEYS = listOf(
        "performance_mode", "fan_mode", "fan_speed", "temp_abxy_layout_mode", "flip_button_layout",
        "no_create_gamepad_button_layout", "prevent_press_home_accidentally", "joystick_light_enabled",
        "led_light_brightness_percent", "vibrate_on", "peak_refresh_rate", "min_refresh_rate",
        "is_charging_separation", "trigger_input_mode", "key_adapter_enable", "global_gamepad_to_mouse_mode",
        "keep_screen_on", "force_landscape", "screen_brightness", "screen_brightness_mode"
    )

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun now(): String = timeFmt.format(Date())

    fun safe(block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        "Fehler: ${t.javaClass.simpleName}: ${t.message}"
    }

    fun redact(text: String): String = text.lineSequence().joinToString("\n") { line ->
        if (!SENSITIVE.containsMatchIn(line)) line
        else {
            val cut = listOf(line.indexOf("]: ["), line.indexOf('=')).filter { it >= 0 }.minOrNull()
            if (cut == null) "<entfernt>" else line.substring(0, cut) + " = <entfernt>"
        }
    }

    fun all(ctx: Context): LinkedHashMap<String, String> = linkedMapOf(
        "Gerät" to safe { device(ctx) },
        "Root-Dienst (PServerBinder)" to safe { rootInfo(ctx) },
        "Bekannte Einstellungen (ohne Root gelesen)" to safe { knownKeys(ctx) },
        "Systemeinstellungen (alle)" to safe { settingsDump(ctx) },
        "Systemeigenschaften (getprop)" to safe { props() },
        "Eingabegeräte" to safe { inputDevices(ctx) },
        "Lesbarkeit ohne Root" to safe { readability() },
        "Hardware, Dienste, Pakete (Root)" to safe { hardware(ctx) },
        "Installierte relevante Apps" to safe { packages(ctx) }
    )

    fun device(ctx: Context): String = buildString {
        appendLine("Hersteller: ${Build.MANUFACTURER}   Marke: ${Build.BRAND}")
        appendLine("Modell: ${Build.MODEL}   Gerät: ${Build.DEVICE}   Produkt: ${Build.PRODUCT}   Board: ${Build.BOARD}")
        append("Hardware: ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= 31) append("   SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine()
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})   Sicherheitspatch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine("Fingerprint: ${Build.FINGERPRINT}")
        val dm = ctx.getSystemService(DisplayManager::class.java)
        for (d in dm.displays) {
            val m = d.mode
            appendLine("Display ${d.displayId} \"${d.name}\": ${m.physicalWidth}x${m.physicalHeight} @ ${"%.1f".format(Locale.US, m.refreshRate)} Hz")
            appendLine("  Modi: " + d.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(Locale.US, it.refreshRate)}" })
        }
        val met = ctx.resources.displayMetrics
        appendLine(
            "Dichte: ${met.densityDpi} dpi (Faktor ${met.density})   Fenster: ${met.widthPixels}x${met.heightPixels} px = " +
                "${(met.widthPixels / met.density).toInt()}x${(met.heightPixels / met.density).toInt()} dp"
        )
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        appendLine("RAM: ${mi.totalMem / 1048576} MB gesamt, ${mi.availMem / 1048576} MB frei")
    }

    fun rootInfo(ctx: Context): String = buildString {
        val b = Root.binder()
        appendLine("Dienst gefunden: ${b != null}" + (Root.lastError?.let { "   ($it)" } ?: ""))
        if (b == null) return@buildString
        appendLine("Binder lebt: ${b.isBinderAlive}")
        appendLine("Direkte Antwort auf 'id': ${Root.rawExec("id")?.trim()}")
        appendLine("Über Datei:")
        appendLine(Root.exec(ctx, "id; getenforce; whoami") ?: "keine Antwort")
    }

    fun knownKeys(ctx: Context): String = buildString {
        val cr = ctx.contentResolver
        for (key in KNOWN_KEYS) {
            val sys = runCatching { Settings.System.getString(cr, key) }.fold({ it ?: "–" }, { "verweigert (${it.javaClass.simpleName})" })
            val glob = runCatching { Settings.Global.getString(cr, key) }.fold({ it ?: "–" }, { "verweigert" })
            val sec = runCatching { Settings.Secure.getString(cr, key) }.fold({ it ?: "–" }, { "verweigert" })
            appendLine("$key   system=$sys   global=$glob   secure=$sec")
        }
    }

    fun settingsDump(ctx: Context): String = buildString {
        for (ns in listOf("system", "secure", "global")) {
            appendLine("### $ns")
            val viaRoot = Root.exec(ctx, "settings list $ns")
            if (viaRoot != null) appendLine(redact(viaRoot))
            else appendLine(redact(settingsViaApi(ctx, ns).entries.joinToString("\n") { "${it.key}=${it.value}" }) + "\n(ohne Root gelesen, evtl. unvollständig)")
        }
    }

    private fun settingsViaApi(ctx: Context, ns: String): Map<String, String> {
        val uri = when (ns) {
            "system" -> Settings.System.CONTENT_URI
            "secure" -> Settings.Secure.CONTENT_URI
            else -> Settings.Global.CONTENT_URI
        }
        val map = sortedMapOf<String, String>()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(0) ?: continue] = c.getString(1) ?: "null"
            }
        }
        return map
    }

    fun props(): String = redact(getpropRaw())

    private fun getpropRaw(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop"))
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrDefault("")

    fun inputDevices(ctx: Context): String = buildString {
        val keys = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP
        )
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            appendLine("[$id] \"${d.name}\"  vendor=0x${Integer.toHexString(d.vendorId)} product=0x${Integer.toHexString(d.productId)}  extern=${d.isExternal}  controller#=${d.controllerNumber}")
            appendLine("   Quellen=0x${Integer.toHexString(d.sources)}  Gamepad=${(d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD}  Joystick=${(d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK}")
            val has = d.hasKeys(*keys)
            appendLine("   Tasten: " + keys.indices.filter { has[it] }.joinToString(" ") { KeyEvent.keyCodeToString(keys[it]).removePrefix("KEYCODE_") })
            val axes = d.motionRanges.map { MotionEvent.axisToString(it.axis).removePrefix("AXIS_") }.distinct()
            if (axes.isNotEmpty()) appendLine("   Achsen: " + axes.joinToString(" "))
        }
    }

    fun readability(): String = buildString {
        val paths = listOf(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/battery/temp",
            "/proc/stat",
            "/proc/meminfo",
            "/sys/class/sn3112l/led/brightness",
            "/sys/class/sn3112r/led/brightness"
        )
        for (p in paths) {
            val r = runCatching { File(p).readText().lineSequence().first().trim().take(80) }
            appendLine(if (r.isSuccess) "OK          $p = ${r.getOrNull()}" else "verweigert  $p  (${r.exceptionOrNull()?.javaClass?.simpleName})")
        }
    }

    fun hardware(ctx: Context): String =
        Root.runAsset(ctx, "hardware.sh", timeoutMs = 45_000)?.let { redact(it) } ?: "Nur mit Root-Dienst möglich."

    fun packages(ctx: Context): String = buildString {
        val pm = ctx.packageManager
        val list = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        appendLine("${list.size} Pakete installiert, davon relevant:")
        list.filter { VENDOR_HINTS.containsMatchIn(it.packageName) }
            .sortedBy { it.packageName }
            .forEach { appendLine("${it.packageName}  ${it.versionName ?: ""}") }
    }

    // ---- Snapshots for before/after comparison ----

    fun snapshot(ctx: Context): Map<String, String> {
        val map = sortedMapOf<String, String>()
        for (ns in listOf("system", "secure", "global")) {
            val raw = Root.exec(ctx, "settings list $ns")
            if (raw != null) {
                raw.lineSequence().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) map["$ns:${line.substring(0, i)}"] = line.substring(i + 1)
                }
            } else {
                settingsViaApi(ctx, ns).forEach { (k, v) -> map["$ns:$k"] = v }
            }
        }
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            map["input:${d.name}"] = "vendor=0x${Integer.toHexString(d.vendorId)} product=0x${Integer.toHexString(d.productId)}"
        }
        val propLine = Regex("^\\[(.+?)]: \\[(.*)]$")
        getpropRaw().lineSequence().forEach { line ->
            propLine.find(line)?.let { map["prop:${it.groupValues[1]}"] = it.groupValues[2] }
        }
        return map
    }

    fun diff(a: Map<String, String>, b: Map<String, String>): String = buildString {
        var n = 0
        for (k in (a.keys + b.keys).toSortedSet()) {
            val x = a[k]
            val y = b[k]
            if (x != y) {
                n++
                appendLine(redact("$k=${x ?: "–"}  →  ${y ?: "–"}"))
            }
        }
        if (n == 0) appendLine("Keine Änderungen gefunden.") else appendLine("\n$n Änderung(en).")
    }

    fun describeKey(src: String, e: KeyEvent): String {
        val act = when (e.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP  "
            else -> "MULT"
        }
        return "${now()} $src $act ${KeyEvent.keyCodeToString(e.keyCode)}(${e.keyCode}) scan=${e.scanCode} rep=${e.repeatCount} " +
            "dev=\"${e.device?.name}\" src=0x${Integer.toHexString(e.source)}"
    }
}

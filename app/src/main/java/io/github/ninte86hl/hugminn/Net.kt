package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/** WLAN through the system shell (root): scan, saved networks, connect. */
object Wifi {
    /** A network in range. [security]: open, owe, wpa2 or wpa3 (as `cmd wifi connect-network` takes it). */
    data class Net(val ssid: String, val level: Int, val security: String)

    fun startScan() = Root.run("cmd wifi start-scan")

    /** Raw answer of the last scan listing (for feedback reports). */
    @Volatile var diag = ""

    /** Networks in range, strongest first, one entry per name. */
    fun scanResults(): List<Net> {
        val out = Root.execLong("cmd wifi list-scan-results 2>&1")
        diag = "Scan: " + (out?.lines()?.take(4)?.joinToString(" ⏎ ")?.take(400) ?: "keine Antwort")
        if (out == null) return emptyList()
        val res = LinkedHashMap<String, Net>()
        for (line in out.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.size < 5 || !Regex("^[0-9a-fA-F:]{17}$").matches(t[0])) continue
            // RSSI may come as "-45(0:-48/1:-47)"
            val rssi = Regex("^-?\\d+").find(t[2])?.value?.toIntOrNull() ?: continue
            val flagsAt = t.indexOfFirst { it.startsWith("[") }.let { if (it < 0) t.size else it }
            val ssid = t.subList(4, flagsAt).joinToString(" ").trim()
            if (ssid.isEmpty()) continue
            val flags = t.drop(flagsAt).joinToString("")
            val sec = when {
                flags.contains("PSK") -> "wpa2"
                flags.contains("SAE") -> "wpa3"
                flags.contains("OWE") -> "owe"
                flags.contains("EAP") -> "eap"
                flags.contains("WEP") -> "wep"
                else -> "open"
            }
            val old = res[ssid]
            if (old == null || old.level < rssi) res[ssid] = Net(ssid, rssi, sec)
        }
        return res.values.sortedByDescending { it.level }
    }

    /** Saved networks with their passwords (from the WLAN store, root). */
    fun saved(): Map<String, String?> {
        val xml = Root.execLong("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null || cat /data/misc/wifi/WifiConfigStore.xml")
            ?: return emptyMap()
        fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val res = LinkedHashMap<String, String?>()
        for (block in Regex("<WifiConfiguration>(.*?)</WifiConfiguration>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val b = block.groupValues[1]
            val ssid = Regex("""<string name="SSID">(.*?)</string>""").find(b)?.groupValues?.get(1)?.let(::unescape)?.trim('"') ?: continue
            val psk = Regex("""<string name="PreSharedKey">(.*?)</string>""").find(b)?.groupValues?.get(1)?.let(::unescape)?.trim('"')
            res[ssid] = psk
        }
        return res
    }

    /** Connects; returns the shell's answer (null: no answer). */
    fun connect(ssid: String, security: String, password: String?): String? {
        val sec = if (security == "wpa3" && password != null) "wpa3" else if (password != null) "wpa2" else security
        val cmd = "cmd wifi connect-network ${Root.quote(ssid)} $sec" + (password?.let { " " + Root.quote(it) } ?: "")
        return Root.exec(cmd, 8_000)
    }

    /** Signal as bars for a menu line. */
    fun bars(level: Int) = when {
        level >= -55 -> "▂▄▆█"
        level >= -66 -> "▂▄▆"
        level >= -77 -> "▂▄"
        else -> "▂"
    }
}

/** Bluetooth: paired devices, connecting them, finding and pairing new ones. */
@SuppressLint("MissingPermission")
object Bt {
    /** What the last device search reported (for feedback reports). */
    @Volatile var diag = ""
    private fun adapter(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun paired(ctx: Context): List<BluetoothDevice> = runCatching { adapter(ctx)?.bondedDevices?.toList() }.getOrNull().orEmpty()

    fun isConnected(d: BluetoothDevice) = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)

    fun name(d: BluetoothDevice) = runCatching { d.name }.getOrNull() ?: d.address

    /**
     * Connects (or disconnects) a paired device. Android allows this only to system apps; if it is
     * refused, Hugminn opens the Bluetooth settings and taps the device there itself.
     */
    fun toggle(svc: HugminnService, d: BluetoothDevice, connect: Boolean, done: (Boolean) -> Unit) {
        val res = runCatching { d.javaClass.getMethod(if (connect) "connect" else "disconnect").invoke(d) }
        val ok = res.getOrNull().let { it == null && res.isSuccess || it == 0 || it == true }
        diag = "${if (connect) "Verbinden" else "Trennen"} ${name(d)}: " +
            (res.exceptionOrNull()?.let { (it.cause ?: it).javaClass.simpleName + ": " + (it.cause ?: it).message } ?: "Antwort ${res.getOrNull()}") +
            if (ok) "" else " → über die Einstellungen"
        if (ok) { done(true); return }
        tapInSettings(svc, name(d), done)
    }

    /** Opens Android's Bluetooth settings, taps the device's row and comes back. */
    private fun tapInSettings(svc: HugminnService, name: String, done: (Boolean) -> Unit) {
        svc.startApp(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        var tries = 0
        val look = object : Runnable {
            override fun run() {
                val node = svc.roots().firstNotNullOfOrNull { r -> r.findAccessibilityNodeInfosByText(name).firstOrNull { it.text?.toString() == name } }
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    svc.ui.postDelayed({
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        done(true)
                    }, 900)
                } else if (++tries < 25) svc.ui.postDelayed(this, 120) else done(false)
            }
        }
        svc.ui.postDelayed(look, 300)
    }

    /** Device search: [found] gets every new device (also the ones already listed again with a name). */
    class Scan(private val ctx: Context, private val found: (List<BluetoothDevice>) -> Unit) {
        private val list = LinkedHashMap<String, BluetoothDevice>()
        private val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.bondState == BluetoothDevice.BOND_BONDED) return
                val known = list[d.address]
                diag += " · gefunden: ${d.address}"
                if (known == null || (runCatching { known.name }.getOrNull() == null && runCatching { d.name }.getOrNull() != null)) {
                    list[d.address] = d
                    found(list.values.toList())
                }
            }
        }

        fun start() {
            runCatching { ctx.registerReceiver(rx, IntentFilter(BluetoothDevice.ACTION_FOUND)) }
            // make sure the search may run (rights granted through the system shell)
            listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.ACCESS_FINE_LOCATION)
                .filter { ctx.checkSelfPermission(it) != 0 }
                .forEach { Root.exec("pm grant ${ctx.packageName} $it") }
            val r = runCatching { adapter(ctx)?.startDiscovery() }
            diag = "Suche gestartet: ${r.getOrNull()} ${r.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message } ?: ""}" +
                " · Rechte: scan=${ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == 0}" +
                " connect=${ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == 0}" +
                " ort=${ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 0}"
        }

        fun stop() {
            runCatching { adapter(ctx)?.cancelDiscovery() }
            runCatching { ctx.unregisterReceiver(rx) }
        }
    }

    fun pair(ctx: Context, d: BluetoothDevice) = runCatching { adapter(ctx)?.cancelDiscovery(); d.createBond() }.getOrDefault(false)

    /**
     * Android asks for the pairing in a dialog of its own (code shown on both devices). Hugminn
     * confirms it by itself, since the pairing was just started on purpose; a code to type in stays
     * with Android.
     */
    fun confirmPairing(svc: HugminnService, name: String) {
        var tries = 0
        val words = listOf("Koppeln", "KOPPELN", "Pair", "PAIR", "Verbinden", "OK")
        val look = object : Runnable {
            override fun run() {
                for (r in svc.roots()) {
                    val pkg = r.packageName?.toString() ?: continue
                    if (!pkg.contains("settings") && !pkg.contains("bluetooth") && !pkg.contains("systemui")) continue
                    // only the pairing dialog: it names the device or asks for a pairing code
                    val isPairing = r.findAccessibilityNodeInfosByText(name).isNotEmpty() ||
                        r.findAccessibilityNodeInfosByText("Kopplung").isNotEmpty() || r.findAccessibilityNodeInfosByText("pair").isNotEmpty()
                    if (!isPairing) continue
                    // a field for typing a code: leave it to the user
                    if (r.findAccessibilityNodeInfosByViewId("com.android.settings:id/text").isNotEmpty()) {
                        diag += " · Code-Eingabe bei Android"
                        svc.closeOverlays() // the dialog must be visible for typing the code
                        return
                    }
                    val btn = words.firstNotNullOfOrNull { w -> r.findAccessibilityNodeInfosByText(w).firstOrNull { it.isClickable && it.text?.toString()?.equals(w, true) == true } }
                    if (btn != null) {
                        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        diag += " · Kopplung bestätigt"
                        return
                    }
                }
                if (++tries < 60) svc.ui.postDelayed(this, 250)
            }
        }
        svc.ui.postDelayed(look, 300)
    }
}

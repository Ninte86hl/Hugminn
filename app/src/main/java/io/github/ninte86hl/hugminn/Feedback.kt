package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Feedback for development: takes a screenshot (with Hugminn's overlays) plus a text description of
 * everything on screen and uploads both to the private repository Hugminn-Feedback (never to the public one).
 * Needs a GitHub token with write access to the repository's contents (entered in the settings).
 */
object Feedback {
    private const val REPO = "Ninte86hl/Hugminn-Feedback"
    private const val BRANCH = "main"
    @Volatile private var busy = false

    /** [quick]: upload right away without asking for a description (the game keeps running). */
    fun capture(svc: HugminnService, where: String, quick: Boolean = false) {
        val token = svc.prefs.githubToken
        if (token.isNullOrBlank()) {
            toast(svc, "Feedback: Bitte zuerst in den Hugminn-Einstellungen einen GitHub-Token eintragen")
            return
        }
        if (busy) return
        busy = true
        val info = describe(svc, where)
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                val go: (Bitmap?) -> Unit = { full ->
                    val p = Pending(token, where, full, info)
                    // no comment box any more: every report goes straight out
                    pending = p; sendPending(svc, if (quick) "[Schnellbericht aus dem Overlay]" else "–")
                }
                // dual screen: the second screen too, drawn below the first like on the device
                // (Android allows one screenshot per second, hence the short wait)
                val second = Displays.second(svc)
                if (second == null || bmp == null) { go(bmp); return }
                svc.ui.postDelayed({
                    svc.takeScreenshot(second.id, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(r2: AccessibilityService.ScreenshotResult) {
                            val b2 = Bitmap.wrapHardwareBuffer(r2.hardwareBuffer, r2.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            r2.hardwareBuffer.close()
                            go(if (b2 == null) bmp else stack(bmp, b2))
                        }
                        override fun onFailure(errorCode: Int) { go(bmp) }
                    })
                }, 1100)
            }

            override fun onFailure(errorCode: Int) {
                val p = Pending(token, where, null, "$info\n\nScreenshot fehlgeschlagen (Code $errorCode)")
                if (quick) { pending = p; sendPending(svc, "[Schnellbericht aus dem Overlay]") } else ask(svc, p)
            }
        })
    }

    /** Both screens in one picture: the first on top, the second centred below it, a thin dark gap between. */
    private fun stack(top: Bitmap, bottom: Bitmap): Bitmap {
        val gap = 24
        val out = Bitmap.createBitmap(maxOf(top.width, bottom.width), top.height + gap + bottom.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFF101010.toInt())
        c.drawBitmap(top, ((out.width - top.width) / 2).toFloat(), 0f, null)
        c.drawBitmap(bottom, ((out.width - bottom.width) / 2).toFloat(), (top.height + gap).toFloat(), null)
        return out
    }

    /** Screenshot and report waiting for the user's description. */
    class Pending(val token: String, val where: String, val bmp: Bitmap?, val info: String)
    @Volatile private var pending: Pending? = null

    /** The screenshot is taken; now ask for a short description in a small window of its own. */
    private fun ask(svc: HugminnService, p: Pending) {
        pending = p
        svc.ui.post {
            svc.closeOverlays()
            svc.startApp(android.content.Intent(svc, FeedbackActivity::class.java))
        }
    }

    /** Sends what [ask] collected, with the description on top of the report. */
    fun sendPending(ctx: Context, description: String) {
        val p = pending ?: return
        pending = null
        val head = if (description.isBlank()) "Beschreibung: –" else "Beschreibung:\n${description.trim()}"
        upload(ctx, p.token, p.where, p.bmp, "$head\n\n${p.info}")
    }

    fun cancelPending() {
        if (pending == null) return
        pending = null
        busy = false
    }

    private fun upload(svc: Context, token: String, where: String, bmp: Bitmap?, info: String) {
        val note = UploadNotification(svc)
        note.progress("Feedback wird vorbereitet …", -1)
        thread {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
                val base = "feedback/${stamp}_$where"
                val image = bmp?.let {
                    val out = ByteArrayOutputStream()
                    it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
                val text = info.toByteArray()
                val total = (image?.size ?: 0) + text.size
                var done = 0
                var ok = true
                if (image != null) {
                    ok = put(token, "$base.jpg", image, "Feedback screenshot ($where)") { sent ->
                        note.progress("Screenshot wird hochgeladen …", ((done + sent) * 100L / total).toInt())
                    }
                    done += image.size
                }
                ok = put(token, "$base.txt", text, "Feedback info ($where)") { sent ->
                    note.progress("Beschreibung wird hochgeladen …", ((done + sent) * 100L / total).toInt())
                } && ok
                if (ok) note.done("Feedback hochgeladen ✓", "$stamp · $where") else note.failed("Hochladen fehlgeschlagen", "GitHub hat abgelehnt – Token prüfen (Contents: Read and write)")
            } catch (t: Throwable) {
                note.failed("Hochladen fehlgeschlagen", "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                busy = false
            }
        }
    }

    /** Creates a file through the GitHub contents API. */
    private fun put(token: String, path: String, bytes: ByteArray, message: String, progress: (Int) -> Unit): Boolean {
        val c = URL("https://api.github.com/repos/$REPO/contents/$path").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("message", message)
            .put("branch", BRANCH)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .toString().toByteArray()
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { out ->
            var off = 0
            var lastReport = 0L
            while (off < body.size) {
                val n = minOf(32 * 1024, body.size - off)
                out.write(body, off, n)
                off += n
                val now = System.currentTimeMillis()
                if (now - lastReport > 150 || off == body.size) {
                    lastReport = now
                    progress((off.toLong() * bytes.size / body.size).toInt())
                }
            }
        }
        val code = c.responseCode
        c.disconnect()
        return code in 200..299
    }

    /**
     * Uploads the last error together with the system log (needs root for the log). Runs once per
     * error: after a crash it is sent when Hugminn starts again.
     */
    fun sendErrorLogIfPending(ctx: Context) {
        if (!Crash.pending(ctx)) return
        sendLog(ctx, "fehler")
    }

    /** Uploads device info, the last error and the recent system log. */
    fun sendLog(ctx: Context, where: String) {
        val token = Prefs(ctx).githubToken
        if (token.isNullOrBlank() || busy) return
        busy = true
        val note = UploadNotification(ctx)
        note.progress("Protokoll wird gesammelt …", -1)
        thread {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
                val base = "feedback/${stamp}_$where"
                val info = buildString {
                    appendLine("Hugminn ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName} · $where")
                    appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                    appendLine()
                    appendLine(Crash.last(ctx) ?: "Kein Fehler gespeichert.")
                    if (LedFx.diag.isNotEmpty()) { appendLine(); appendLine("LED-Test:"); appendLine(LedFx.diag) }
                }.toByteArray()
                val log = (Root.execLong("logcat -d -b main,system,crash -v time -t 3000", 30_000)
                    ?: "Systemprotokoll nicht lesbar (kein Root-Dienst).").toByteArray()
                val total = info.size + log.size
                var ok = put(token, "$base.txt", info, "Error report ($where)") { sent ->
                    note.progress("Fehlerbericht wird hochgeladen …", (sent * 100L / total).toInt())
                }
                ok = put(token, "${base}_logcat.txt", log, "System log ($where)") { sent ->
                    note.progress("Systemprotokoll wird hochgeladen …", ((info.size + sent) * 100L / total).toInt())
                } && ok
                if (ok) {
                    Crash.markSent(ctx)
                    note.done("Fehlerbericht hochgeladen ✓", "$stamp · Systemprotokoll ${log.size / 1024} KB")
                } else note.failed("Hochladen fehlgeschlagen", "GitHub hat abgelehnt – Token prüfen (Contents: Read and write)")
            } catch (t: Throwable) {
                note.failed("Hochladen fehlgeschlagen", "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                busy = false
            }
        }
    }

    /** Uploads a text report (e.g. the input test) with the progress notification. */
    fun uploadText(ctx: Context, where: String, text: String) {
        val token = Prefs(ctx).githubToken
        if (token.isNullOrBlank()) {
            android.widget.Toast.makeText(ctx, "Bitte zuerst einen GitHub-Token eintragen", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val note = UploadNotification(ctx)
        note.progress("Bericht wird hochgeladen …", -1)
        thread {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
                val body = ("Hugminn ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName} · $where\n" +
                    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n\n$text").toByteArray()
                val ok = put(token, "feedback/${stamp}_$where.txt", body, "Report ($where)") { sent ->
                    note.progress("Bericht wird hochgeladen …", (sent * 100L / body.size).toInt())
                }
                if (ok) note.done("Bericht hochgeladen ✓", "$stamp · $where") else note.failed("Hochladen fehlgeschlagen", "Token prüfen")
            } catch (t: Throwable) {
                note.failed("Hochladen fehlgeschlagen", "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun toast(svc: HugminnService, s: String) = svc.ui.post { Toast.makeText(svc, s, Toast.LENGTH_SHORT).show() }

    /** Device, settings and a dump of all windows on screen (ids, texts, bounds). */
    fun describe(svc: HugminnService, where: String): String = buildString {
        appendLine("Hugminn ${svc.packageManager.getPackageInfo(svc.packageName, 0).versionName} · $where")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        val m = svc.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        appendLine("Bildschirm ${m.width()}x${m.height()} · Dichte ${svc.resources.displayMetrics.density}")
        val pal = Palette(svc)
        appendLine("Dunkelmodus: ${pal.dark} · Tastensymbole: ${svc.prefs.glyphStyle} farbig=${svc.prefs.glyphColored} · Nintendo-Layout: ${Glyphs.nintendoLayout()}")
        appendLine("Statusleiste: ${svc.prefs.statusBar}")
        appendLine()
        append(Displays.describe(svc, svc))
        Displays.beforeOpen.takeIf { it.isNotEmpty() }?.let { appendLine(); append(it) }
        Crash.last(svc)?.let { appendLine(); appendLine("Letzter Fehler:"); appendLine(it) }
        runCatching {
            svc.contentResolver.query(android.provider.Settings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { c ->
                val lines = mutableListOf<String>()
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    if (Regex("joystick|led|light", RegexOption.IGNORE_CASE).containsMatchIn(n)) lines += "  $n = ${c.getString(1)}"
                }
                if (lines.isNotEmpty()) { appendLine(); appendLine("LED-Einstellungen:"); lines.forEach { appendLine(it) } }
            }
        }
        // controller / input settings of the firmware (to find switches such as "disable built-in controller")
        runCatching {
            val rx = Regex("gamepad|handle|controller|abxy|button|input|keyboard|key_|joy|trigger|charg|separation|disable", RegexOption.IGNORE_CASE)
            val lines = mutableListOf<String>()
            for ((tbl, uri) in listOf("system" to android.provider.Settings.System.CONTENT_URI, "global" to android.provider.Settings.Global.CONTENT_URI, "secure" to android.provider.Settings.Secure.CONTENT_URI)) {
                runCatching {
                    svc.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val n = c.getString(0) ?: continue
                            if (rx.containsMatchIn(n)) lines += "  $tbl/$n = ${c.getString(1)}"
                        }
                    }
                }
            }
            Root.exec("getprop | grep -iE 'gamepad|handle|controller|joy|abxy' 2>/dev/null")?.lines()?.filter { it.isNotBlank() }?.forEach { lines += "  prop $it" }
            if (lines.isNotEmpty()) { appendLine(); appendLine("Controller-Einstellungen:"); lines.forEach { appendLine(it) } }
        }
        // every firmware setting (to find unknown switches by comparing a log before and after changing them)
        runCatching {
            val all = mutableListOf<String>()
            for ((tbl, uri) in listOf("system" to android.provider.Settings.System.CONTENT_URI, "global" to android.provider.Settings.Global.CONTENT_URI, "secure" to android.provider.Settings.Secure.CONTENT_URI)) {
                runCatching {
                    svc.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                        while (c.moveToNext()) all += "  $tbl/${c.getString(0)} = ${c.getString(1)?.take(120)}"
                    }
                }
            }
            Root.exec("getprop | grep -iE 'odin|ayn|trig|hall|joy|gamepad|key' 2>/dev/null", 4_000)?.lines()?.filter { it.isNotBlank() }?.forEach { all += "  prop $it" }
            Root.exec("grep -E '^N: Name|^H: Handlers' /proc/bus/input/devices 2>/dev/null", 3_000)?.lines()?.filter { it.isNotBlank() }?.forEach { all += "  input ${it.trim()}" }
            if (all.isNotEmpty()) { appendLine(); appendLine("Alle Einstellungen (${all.size}):"); all.sorted().forEach { appendLine(it) } }
        }
        // Anbernic helper: what it found out about the hardware (LEDs, temperatures, fan), and its log
        if (Device.isAnbernic) runCatching {
            val d = AnbernicHelper.dir()
            java.io.File(d, "hw.txt").takeIf { it.exists() }?.let { appendLine(); appendLine("Anbernic-Hardware (Helfer):"); appendLine(it.readText().take(40_000)) }
            java.io.File(d, "log").takeIf { it.exists() }?.let { appendLine(); appendLine("Helfer-Log:"); it.readLines().takeLast(30).forEach { l -> appendLine("  $l") } }
        }
        runCatching { svc.own.diag + svc.own.removeDiag }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { appendLine(); appendLine("Eigener Switcher:"); appendLine(it) }
        appendLine(); appendLine("Spieltitel: ${GameTitle.titles}"); if (GameTitle.diag.isNotEmpty()) appendLine(GameTitle.diag)
        appendLine(); appendLine("Musik: Listener ${Media.listenerInfo} · spielt=${Media.playing} · app=${Media.app} · titel=${Media.title} · cover=${Media.art != null}"); if (Media.diag.isNotEmpty()) appendLine(Media.diag)
        if (FrameRate.diag.isNotEmpty()) { appendLine(); appendLine("FPS (${svc.foregroundPkg}):"); appendLine(FrameRate.diag) }
        if (Wifi.diag.isNotEmpty() || Bt.diag.isNotEmpty()) { appendLine(); appendLine("WLAN/Bluetooth:"); appendLine(Wifi.diag); appendLine(Bt.diag) }
        if (LedFx.diag.isNotEmpty()) { appendLine(); appendLine("LED-Effekte (${LedFx.current()}, Akku-Warnung=${LedFx.lowBattery}):"); appendLine(LedFx.diag) }
        val keys = synchronized(svc.keyLog) { svc.keyLog.toList() }
        val pads = android.view.InputDevice.getDeviceIds().toList().mapNotNull { android.view.InputDevice.getDevice(it) }
            .filter { it.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD || it.isExternal }
            .map { "  ${it.name} (extern=${it.isExternal}, id=${it.id}, vendor=${it.vendorId}, product=${it.productId})" }
        if (pads.isNotEmpty()) { appendLine(); appendLine("Eingabegeräte:"); pads.forEach { appendLine(it) } }
        WakeDiag.report().takeIf { it.isNotEmpty() }?.let { appendLine(); appendLine("Aufwach-Diagnose (20 s nach Bildschirm an):"); appendLine(it) }
        appendLine(); appendLine("Countdown Steuerung: ${svc.guardDiag.ifEmpty { "–" }}")
        appendLine("Eingabegeräte jetzt: ${Device.padList()}")
        runCatching { Bt.batteries(svc) }.getOrNull()?.let { appendLine(); appendLine("Geräte-Akku: $it"); appendLine("  ${Bt.batteryDiag}") }
        if (keys.isNotEmpty()) { appendLine(); appendLine("Letzte Tasten (Hugminn sieht sie):"); keys.forEach { appendLine("  $it") } }
        // Retroid's assistant (its control center on a double Home): how it can be called
        for (pk in listOf("com.retroidpocket.dualscreen.assistant", "com.rp.gameassistant"))
            if (Root.available() && runCatching { svc.packageManager.getPackageInfo(pk, 0) }.isSuccess)
                Root.execLong("dumpsys package $pk | grep -iE 'Action:|$pk/|Activity|Service|Receiver|exported' | head -120", 8_000)?.let { appendLine(); appendLine("Retroid-Assistent $pk:"); appendLine(it) }
        if (Device.isDuo) {
            // its components straight from Android (no root needed)
            runCatching {
                val f = android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.GET_RECEIVERS
                for (pk in listOf("com.rp.gameassistant", "com.retroidpocket.dualscreen.assistant")) {
                    val pi = svc.packageManager.getPackageInfo(pk, f)
                    appendLine(); appendLine("Bausteine $pk:")
                    pi.activities?.forEach { appendLine("  A ${it.name} exp=${it.exported}") }
                    pi.services?.forEach { appendLine("  S ${it.name} exp=${it.exported} perm=${it.permission}") }
                    pi.receivers?.forEach { appendLine("  R ${it.name} exp=${it.exported}") }
                }
            }
            // what Retroid's assistant consists of (how its floating window – the control center – could be started)
            Root.execLong("a=\$(pm path com.rp.gameassistant | head -1 | cut -d: -f2); unzip -p \$a 'classes*.dex' | grep -a -o -E 'Lcom/ro/gameassistant/[A-Za-z0-9_/]*(Float|Window|Panel|Service|Receiver|Activity)[A-Za-z0-9_]*;' | sort -u | head -80; settings get secure sysui_qs_tiles", 10_000)
                ?.let { appendLine(); appendLine("Retroid-Assistent Klassen + QS:"); appendLine(it) }
            svc.homeTrace.toList().takeIf { it.isNotEmpty() }?.let { t -> appendLine(); appendLine("Home roh (getevent):"); t.forEach { appendLine(it) } }
            Root.execLong("logcat -d -v time -t 600 | grep -iE 'dualscreen|gameassist|retroid|doubleclick|double_click|HomeKey|home key' | tail -80", 8_000)
                ?.let { appendLine(); appendLine("Logcat (Retroid):"); appendLine(it) }
        }
        AnbernicAutostart.trace.toList().takeIf { it.isNotEmpty() }?.let { t -> appendLine(); appendLine("Anbernic-Autostart:"); t.forEach { appendLine("  $it") } }
        Glyphs.trace.toList().takeIf { it.isNotEmpty() }?.let { t -> appendLine(); appendLine("Tasten-Umrechnung (roh → Hugminn):"); t.forEach { appendLine("  $it") } }
        val opened = synchronized(svc.windowLog) { svc.windowLog.toList() }
        if (opened.isNotEmpty()) { appendLine(); appendLine("Zuletzt geöffnete Fenster:"); opened.forEach { appendLine("  $it") } }
        appendLine()
        val ws = runCatching { svc.windows }.getOrDefault(emptyList())
        appendLine("${ws.size} Fenster")
        for (w in ws) {
            appendLine("FENSTER type=${w.type} layer=${w.layer} title=${w.title} aktiv=${w.isActive}")
            val root = w.root ?: continue
            appendLine("  Paket: ${root.packageName}")
            dump(root, 1, this, intArrayOf(0))
        }
    }

    private fun dump(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, count: IntArray) {
        if (count[0]++ > 2000 || depth > 45) return
        val r = Rect().also(n::getBoundsInScreen)
        sb.append("  ".repeat(depth)).append(n.className?.toString()?.substringAfterLast('.')).append(" id=").append(n.viewIdResourceName)
        n.text?.let { sb.append(" text=\"").append(it.toString().take(40)).append('"') }
        n.contentDescription?.let { sb.append(" desc=\"").append(it.toString().take(40)).append('"') }
        sb.append(' ').append(r.toShortString()).append('\n')
        for (i in 0 until n.childCount) n.getChild(i)?.let { dump(it, depth + 1, sb, count) }
    }
}

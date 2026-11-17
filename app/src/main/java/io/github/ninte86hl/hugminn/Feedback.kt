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
 * everything on screen and uploads both to the "feedback" branch of the Hugminn repository.
 * Needs a GitHub token with write access to the repository's contents (entered in the settings).
 */
object Feedback {
    private const val REPO = "Ninte86hl/Hugminn"
    private const val BRANCH = "feedback"
    @Volatile private var busy = false

    fun capture(svc: HugminnService, where: String) {
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
                ask(svc, Pending(token, where, bmp, info))
            }

            override fun onFailure(errorCode: Int) {
                ask(svc, Pending(token, where, null, "$info\n\nScreenshot fehlgeschlagen (Code $errorCode)"))
            }
        })
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
        Crash.last(svc)?.let { appendLine(); appendLine("Letzter Fehler:"); appendLine(it) }
        val changes = synchronized(SettingsWatch.log) { SettingsWatch.log.toList() }
        if (changes.isNotEmpty()) { appendLine(); appendLine("Geänderte Systemeinstellungen:"); changes.forEach { appendLine("  $it") } }
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
        if (count[0]++ > 800 || depth > 25) return
        val r = Rect().also(n::getBoundsInScreen)
        sb.append("  ".repeat(depth)).append(n.className?.toString()?.substringAfterLast('.')).append(" id=").append(n.viewIdResourceName)
        n.text?.let { sb.append(" text=\"").append(it.toString().take(40)).append('"') }
        n.contentDescription?.let { sb.append(" desc=\"").append(it.toString().take(40)).append('"') }
        sb.append(' ').append(r.toShortString()).append('\n')
        for (i in 0 until n.childCount) n.getChild(i)?.let { dump(it, depth + 1, sb, count) }
    }
}

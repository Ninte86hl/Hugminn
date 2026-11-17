package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
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
                upload(svc, token, where, bmp, info)
            }

            override fun onFailure(errorCode: Int) {
                upload(svc, token, where, null, "$info\n\nScreenshot fehlgeschlagen (Code $errorCode)")
            }
        })
    }

    private fun upload(svc: HugminnService, token: String, where: String, bmp: Bitmap?, info: String) {
        toast(svc, "Feedback wird hochgeladen …")
        thread {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
                val base = "feedback/${stamp}_$where"
                var ok = true
                if (bmp != null) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    ok = put(token, "$base.jpg", out.toByteArray(), "Feedback screenshot ($where)")
                }
                ok = put(token, "$base.txt", info.toByteArray(), "Feedback info ($where)") && ok
                toast(svc, if (ok) "Feedback hochgeladen ✓" else "Feedback: Hochladen fehlgeschlagen (Token prüfen)")
            } catch (t: Throwable) {
                toast(svc, "Feedback: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                busy = false
            }
        }
    }

    /** Creates a file through the GitHub contents API. */
    private fun put(token: String, path: String, bytes: ByteArray, message: String): Boolean {
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
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        return code in 200..299
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

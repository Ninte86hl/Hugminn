package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Anbernic: after a restart nobody starts Hugminn's helper, and Anbernic's app can't be asked to run a
 * script. So Hugminn does what the person would do: open Anbernic's control app, go to
 * Advanced → Root Scripts → Script Management and press ▶ next to "hugminn-anbernic".
 * It goes by what is on screen at each moment (not a fixed path), and gives up after 25 seconds.
 */
object AnbernicAutostart {
    private const val PKG = "com.anbernic.control"
    private const val SCRIPT = "hugminn-anbernic"

    /** What happened, for the feedback. */
    val trace = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun note(s: String) {
        trace.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()) + " " + s)
        while (trace.size > 40) trace.pollFirst()
    }

    @Volatile private var running = false

    /** After Hugminn started (that is: after a restart): helper known but not running → start it. Tries for two minutes while locked. */
    fun afterStart(svc: HugminnService, tries: Int = 0) {
        if (!Device.isAnbernic || !svc.prefs.anbAutostart || running) return
        if (!Device.anbernicKnown(svc)) return
        if (AnbernicHelper.alive(svc)) { if (tries == 0) note("helper already running"); return }
        val locked = svc.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        val screenOn = svc.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        if (locked || !screenOn) {
            if (tries < 24) svc.ui.postDelayed({ afterStart(svc, tries + 1) }, 5_000) else note("gave up: screen locked")
            return
        }
        start(svc)
    }

    fun start(svc: HugminnService) {
        if (running) return
        val launch = svc.packageManager.getLaunchIntentForPackage(PKG) ?: run { note("Anbernic's app not found"); return }
        running = true
        note("starting the helper through Anbernic's script management")
        Notice.show(svc, t("Hugminn startet den Anbernic-Helfer …", "Hugminn is starting the Anbernic helper …"))
        runCatching { svc.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { note("open failed: ${it.message}"); finish(svc, false); return }
        next(svc, System.currentTimeMillis())
    }

    private fun next(svc: HugminnService, t0: Long, delay: Long = 900) {
        svc.ui.postDelayed({
            if (System.currentTimeMillis() - t0 > 25_000) { note("gave up after 25 s"); finish(svc, false); return@postDelayed }
            runCatching { act(svc, t0) }.onFailure { note("error: ${it.message}"); finish(svc, false) }
        }, delay)
    }

    private fun act(svc: HugminnService, t0: Long) {
        val root = svc.rootInActiveWindow
        if (root == null || root.packageName?.toString() != PKG) { note("in front: ${root?.packageName}"); next(svc, t0); return }
        // the dialog with our script: ▶ in its row
        find(root, SCRIPT)?.parent?.let { row ->
            val play = (0 until row.childCount).mapNotNull { row.getChild(it) }.firstOrNull { it.text?.toString()?.trim() == "▶" }
            if (play != null) {
                click(svc, play)
                note("pressed ▶")
                svc.ui.postDelayed({
                    svc.rootInActiveWindow?.let { r -> (find(r, "Cancel") ?: find(r, "Abbrechen"))?.let { click(svc, it) } }
                    svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); finish(svc, true) }, 600)
                }, 1500)
                return
            }
        }
        // otherwise the next step towards it, deepest first
        for (label in listOf("Script Management", "Skriptverwaltung", "Root Scripts", "Root-Skripte", "Advanced", "Erweitert", "Device", "Gerät")) {
            val n = find(root, label) ?: continue
            click(svc, n)
            note("tapped \"$label\"")
            next(svc, t0)
            return
        }
        note("nothing known on screen: " + texts(root).take(12).joinToString(" | "))
        next(svc, t0)
    }

    private fun finish(svc: HugminnService, pressed: Boolean) {
        // the helper's heartbeat comes a moment later
        svc.ui.postDelayed({
            running = false
            val ok = AnbernicHelper.alive(svc)
            note(if (ok) "helper running" else "helper not running (pressed ▶: $pressed)")
            if (!ok) Notice.show(svc, t("Anbernic-Helfer konnte nicht gestartet werden – bitte in Anbernics Skriptverwaltung ▶ drücken",
                "Couldn't start the Anbernic helper – press ▶ in Anbernic's script management"))
        }, if (pressed) 6_000 else 0)
    }

    private fun find(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser && it.text?.toString()?.trim().equals(text, ignoreCase = true) }

    private fun texts(n: AccessibilityNodeInfo, out: MutableList<String> = mutableListOf(), depth: Int = 0): List<String> {
        if (depth > 25 || out.size > 30) return out
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (i in 0 until n.childCount) n.getChild(i)?.let { texts(it, out, depth + 1) }
        return out
    }

    /** A click on the element (or the nearest clickable one around it); a real tap on its middle if that doesn't take. */
    private fun click(svc: HugminnService, n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        runCatching { svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null) }
    }
}

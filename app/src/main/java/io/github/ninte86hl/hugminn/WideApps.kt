package io.github.ninte86hl.hugminn

import kotlin.concurrent.thread

/**
 * "Tablet view" for apps that think the handheld is a phone (YouTube): while such an app is in front
 * on the top screen, the top screen's density is lowered for the time being, so its smallest side
 * counts as a tablet (600 dp and more) – the app then shows its whole tablet layout in landscape,
 * not only full-screen videos. Back to the old value as soon as another app comes to the front.
 * Needs root (wm density). Everything gets a little smaller meanwhile.
 */
class WideApps(private val svc: HugminnService) {

    companion object {
        @Volatile var diag = ""
        /** Smallest side Android treats as a tablet, plus a little room. */
        private const val TABLET_DP = 616
    }

    @Volatile private var applied = false
    @Volatile private var busy = false
    private var last: String? = null

    /** Called with the app on the top screen (null: home screen) whenever it may have changed. */
    fun onTop(pkg: String?) {
        val want = pkg != null && pkg in svc.prefs.wideApps && svc.prefs.wideOn
        if (want == applied || busy) { last = pkg; return }
        last = pkg
        busy = true
        thread {
            runCatching { if (want) apply() else restore() }.onFailure { Crash.note(svc, it) }
            busy = false
            // the app may have changed meanwhile
            svc.ui.post { val p = last; val w = p != null && p in svc.prefs.wideApps && svc.prefs.wideOn; if (w != applied) onTop(p) }
        }
    }

    private fun apply() {
        if (!Root.available()) { diag = "kein Root"; return }
        val out = Root.execLong("wm density -d 0", 3_000) ?: run { diag = "wm density: keine Antwort"; return }
        val physical = Regex("""Physical density:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val override = Regex("""Override density:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val now = override ?: physical
        val short = Displays.list(svc).firstOrNull { it.isDefault }?.let { minOf(it.width, it.height) } ?: return
        val target = short * 160 / TABLET_DP
        if (now <= target) { diag = "schon Tablet ($now dpi)"; return }
        // remember what to go back to – also across a restart of Hugminn
        svc.prefs.wideRestore = override ?: 0
        svc.prefs.wideKeep = now
        svc.ui.post { svc.keepDensity(now) }
        Root.exec("wm density $target -d 0")
        applied = true
        diag = "Tablet an: $now → $target dpi"
    }

    private fun restore() {
        if (!applied && svc.prefs.wideRestore < 0) return
        val back = svc.prefs.wideRestore
        Root.exec(if (back > 0) "wm density $back -d 0" else "wm density reset -d 0")
        svc.prefs.wideRestore = -1
        applied = false
        // Hugminn's own size follows Android again once the change has arrived
        svc.ui.postDelayed({ if (!applied) svc.keepDensity(0) }, 1200)
        diag = "Tablet aus" + if (back > 0) " ($back dpi)" else ""
    }

    /** Hugminn (re)started: a density left over from before is put back. */
    fun recover() {
        if (svc.prefs.wideRestore < 0) return
        svc.prefs.wideKeep.takeIf { it > 0 }?.let { svc.keepDensity(it) }
        thread { runCatching { applied = true; restore() } }
    }

    fun stop() {
        if (applied) thread { runCatching { restore() } }
    }
}

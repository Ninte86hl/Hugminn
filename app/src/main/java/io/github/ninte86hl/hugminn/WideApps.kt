package io.github.ninte86hl.hugminn

import kotlin.concurrent.thread

/**
 * "Tablet view" for apps that think the handheld is a phone (YouTube): while such an app is in front
 * on a screen, that screen's density is lowered for the time being, so its smallest side counts as a
 * tablet – the app then shows its whole tablet layout in landscape, not only full-screen videos.
 *
 * Only the screen the app is on changes (wm density -d): on the Thor, YouTube on the lower screen
 * leaves the game on the top screen alone and the other way round. Hugminn's own windows keep their
 * size (the service draws with the density from before). Android has no density per app, so on the
 * same screen the status bar and quick settings get smaller meanwhile. Needs root.
 */
class WideApps(private val svc: HugminnService) {

    companion object {
        @Volatile var diag = ""
        /** Smallest side Android treats as a tablet, plus a little room. */
        private const val TABLET_DP = 616
        /** Choices in the settings (0 = automatic). */
        val CHOICES = listOf(0, 270, 260, 250, 240, 230, 220)
    }

    /** Screen → density set by Hugminn right now. */
    private val applied = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private fun screens(): List<Int> = listOfNotNull(0, Displays.second(svc)?.id)

    /** Looks at every screen: a tablet app in front gets its density, the others go back. */
    fun check() {
        for (id in screens()) {
            val pkg = svc.topAppOn(id)
            val want = svc.prefs.wideOn && pkg != null && pkg in svc.prefs.wideApps
            val on = applied.containsKey(id)
            if (want == on || id in busy) continue
            busy += id
            thread {
                runCatching { if (want) apply(id) else restore(id) }.onFailure { Crash.note(svc, it) }
                busy -= id
                // the app may have changed meanwhile
                svc.ui.postDelayed({ check() }, 200)
            }
        }
    }

    /** The density the tablet view uses on screen [id] right now (null: off there). */
    fun current(id: Int = 0): Int? = applied[id]

    /** A new value chosen (settings or the test buttons): use it at once where the tablet view is on. */
    fun reapply() {
        for (id in applied.keys.toList()) thread {
            val t = target(id) ?: return@thread
            Root.exec("wm density $t -d $id")
            applied[id] = t
            diag = "Tablet #$id: $t dpi (neu gewählt)"
        }
    }

    private fun target(id: Int): Int? {
        svc.prefs.wideDpi.takeIf { it > 0 }?.let { return it }
        val info = Displays.list(svc).firstOrNull { it.id == id } ?: return null
        return minOf(info.width, info.height) * 160 / TABLET_DP
    }

    private fun apply(id: Int) {
        if (!Root.available()) { diag = "kein Root"; return }
        val out = Root.execLong("wm density -d $id", 3_000) ?: run { diag = "wm density: keine Antwort"; return }
        val physical = Regex("""Physical density:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val override = Regex("""Override density:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val now = override ?: physical
        val t = target(id) ?: return
        if (now <= t) { diag = "#$id schon Tablet ($now dpi)"; applied[id] = now; return }
        // remember what to go back to – also across a restart of Hugminn
        svc.prefs.setWideRestore(id, override ?: 0)
        if (id == 0) {
            // Hugminn's Control Center, switcher and overlays keep the size from before
            svc.prefs.wideKeep = now
            svc.ui.post { svc.keepDensity(now) }
        }
        Root.exec("wm density $t -d $id")
        applied[id] = t
        diag = "Tablet #$id an: $now → $t dpi"
    }

    private fun restore(id: Int) {
        val back = svc.prefs.wideRestoreOf(id)
        if (back < 0) { applied.remove(id); return }
        Root.exec(if (back > 0) "wm density $back -d $id" else "wm density reset -d $id")
        svc.prefs.setWideRestore(id, -1)
        applied.remove(id)
        diag = "Tablet #$id aus" + if (back > 0) " ($back dpi)" else ""
        // Hugminn's own size follows Android again once the change has arrived
        if (id == 0) svc.ui.postDelayed({ if (!applied.containsKey(0)) svc.keepDensity(0) }, 1200)
    }

    /** Hugminn (re)started: densities left over from before are put back. */
    fun recover() {
        if (svc.prefs.wideRestore >= 0) svc.prefs.wideKeep.takeIf { it > 0 }?.let { svc.keepDensity(it) }
        thread { for (id in screens()) runCatching { if (svc.prefs.wideRestoreOf(id) >= 0) restore(id) } }
    }

    fun stop() {
        if (applied.isNotEmpty()) thread { for (id in applied.keys.toList()) runCatching { restore(id) } }
    }
}

package io.github.ninte86hl.hugminn

import android.os.Looper
import android.os.SystemClock

/**
 * For 20 seconds after waking up: how long each key took to reach Hugminn, and whether Hugminn's main
 * thread was stuck (and where). Keys wait for an accessibility service that filters them, so a busy
 * main thread makes the whole device feel frozen. Test builds put the result into feedback reports.
 */
object WakeDiag {
    private const val WINDOW_MS = 20_000L
    @Volatile private var since = 0L
    private val lines = java.util.ArrayDeque<String>()

    val active get() = since > 0 && SystemClock.uptimeMillis() - since < WINDOW_MS

    /** The wake-up before the last one stays too (the log is often sent after a second wake-up). */
    private var previous = ""

    fun report(): String = synchronized(lines) {
        (if (previous.isNotEmpty()) "Vorheriges Aufwachen:\n$previous\n\nLetztes Aufwachen:\n" else "") + lines.joinToString("\n")
    }

    /** Anything worth knowing in the window (root commands, input devices coming and going). */
    fun note(s: String) { if (active) add(s) }

    private fun add(s: String) = synchronized(lines) {
        lines.addLast("+%5d ms  %s".format(SystemClock.uptimeMillis() - since, s))
        while (lines.size > 80) lines.removeFirst()
    }

    /** Screen on: starts recording, with a watchdog that pings the main thread every 100 ms. */
    fun start(svc: HugminnService) {
        synchronized(lines) { if (lines.isNotEmpty()) previous = lines.joinToString("\n"); lines.clear() }
        since = SystemClock.uptimeMillis()
        val km = svc.getSystemService(android.app.KeyguardManager::class.java)
        add("Bildschirm an · gesperrt=${km?.isKeyguardLocked} · vorne=${svc.foregroundPkg} · CC offen=${svc.controlCenterOpen()}")
        val main = Looper.getMainLooper().thread
        Thread {
            while (active) {
                val sent = SystemClock.uptimeMillis()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                svc.ui.post { done.set(true) }
                var stuckLogged = false
                while (!done.get() && active) {
                    Thread.sleep(20)
                    val lag = SystemClock.uptimeMillis() - sent
                    if (lag > 300 && !stuckLogged) {
                        stuckLogged = true
                        val where = main.stackTrace.take(8).joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                        add("HÄNGER Hauptthread >300 ms in: $where")
                    }
                }
                val lag = SystemClock.uptimeMillis() - sent
                if (stuckLogged) add("Hauptthread wieder frei nach $lag ms")
                Thread.sleep(100)
            }
            add("Ende der Aufzeichnung")
        }.apply { name = "hugminn-wakediag"; isDaemon = true; start() }
    }

    /** A key as Hugminn sees it: delay since the press, and how long Hugminn took to answer. */
    fun key(code: Int, down: Boolean, eventTime: Long, handledMs: Long, consumed: Boolean) {
        if (!active || !down) return
        add("Taste ${android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")}: kam nach ${SystemClock.uptimeMillis() - eventTime - handledMs} ms an, Hugminn brauchte $handledMs ms, " +
            if (consumed) "von Hugminn BEHALTEN" else "an Android/App weitergegeben")
    }
}

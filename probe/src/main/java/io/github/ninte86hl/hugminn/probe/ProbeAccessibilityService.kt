package io.github.ninte86hl.hugminn.probe

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections

/** Logs key events (including Home, if the firmware delivers it here) and window changes; never consumes anything. */
class ProbeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ProbeAccessibilityService? = null
        val keyLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val windowLog: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun add(list: MutableList<String>, line: String) {
            synchronized(list) {
                list.add(line)
                while (list.size > 500) list.removeAt(0)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            add(windowLog, "${Probe.now()} ${event.packageName} / ${event.className}")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        add(keyLog, Probe.describeKey("A11Y", event))
        return false
    }

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun backToProbe() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    /** All windows on screen with their view tree: ids, classes, texts and bounds. */
    fun dumpWindows(): String = buildString {
        val ws = runCatching { windows }.getOrDefault(emptyList())
        appendLine("${ws.size} Fenster")
        for (w in ws) {
            appendLine("FENSTER type=${w.type} layer=${w.layer} title=${w.title} aktiv=${w.isActive} display=${w.displayId}")
            val root = w.root ?: continue
            appendLine("  Paket: ${root.packageName}")
            dumpNode(root, 1, this, intArrayOf(0))
        }
    }

    private fun dumpNode(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, count: IntArray) {
        if (count[0]++ > 1000 || depth > 30) return
        val r = Rect()
        n.getBoundsInScreen(r)
        sb.append("  ".repeat(depth))
            .append(n.className?.toString()?.substringAfterLast('.'))
            .append(" id=").append(n.viewIdResourceName)
        n.text?.let { sb.append(" text=\"").append(it.toString().take(40)).append('"') }
        n.contentDescription?.let { sb.append(" desc=\"").append(it.toString().take(40)).append('"') }
        sb.append(' ').append(r.toShortString())
        if (n.isClickable) sb.append(" [klick]")
        if (n.isScrollable) sb.append(" [scroll]")
        sb.append('\n')
        for (i in 0 until n.childCount) n.getChild(i)?.let { dumpNode(it, depth + 1, sb, count) }
    }
}

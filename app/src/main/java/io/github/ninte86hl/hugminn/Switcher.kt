package io.github.ninte86hl.hugminn

import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Muninn, the Game Switcher. Opens Android's own recents view and lays a controller bar over its
 * bottom area, plus a frame around the selected card. D-pad/LB/RB choose, A opens, X closes the app,
 * Y shows actions for the app type, B goes back.
 */
class Switcher(private val svc: HugminnService) {
    data class Card(val node: AccessibilityNodeInfo, val label: String?, val pkg: String?, val type: AppType)
    private data class Item(val label: String, val run: () -> Unit)

    var isOpen = false
        private set
    private val wm = svc.getSystemService(WindowManager::class.java)
    private lateinit var pal: Palette
    private var bar: FrameLayout? = null
    private var frame: View? = null
    private var cards: List<Card> = emptyList()
    private var sel = 0
    private var menu: List<Item>? = null
    private var menuSel = 0
    private var launcherPkg: String? = null
    private var attempts = 0
    private var busyUntil = 0L

    fun open() {
        isOpen = true
        pal = Palette(svc)
        attempts = 0
        busyUntil = System.currentTimeMillis() + 1500
        val already = findPanel() != null
        if (!already) svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        svc.ui.postDelayed(::scan, if (already) 0 else 250)
    }

    fun close(goBack: Boolean) {
        if (!isOpen) return
        isOpen = false
        menu = null
        svc.ui.removeCallbacksAndMessages(TOKEN)
        bar?.let { runCatching { wm.removeView(it) } }
        frame?.let { runCatching { wm.removeView(it) } }
        bar = null
        frame = null
        if (goBack) svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun findPanel(): AccessibilityNodeInfo? {
        for (r in svc.roots()) {
            val n = r.findAccessibilityNodeInfosByViewId("${r.packageName}:id/overview_panel").firstOrNull()
            if (n != null) {
                launcherPkg = r.packageName?.toString()
                return n
            }
        }
        return null
    }

    private fun findIn(pkg: String?, id: String): AccessibilityNodeInfo? =
        svc.roots().firstNotNullOfOrNull { it.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull() }

    private fun scan() {
        if (!isOpen) return
        val panel = findPanel()
        if (panel == null) {
            if (attempts++ < 20) svc.ui.postDelayed(::scan, 100) else {
                Toast.makeText(svc, "Hugminn: Android-Übersicht nicht gefunden", Toast.LENGTH_SHORT).show()
                close(goBack = false)
            }
            return
        }
        val nodes = (0 until panel.childCount).mapNotNull { panel.getChild(it) }.filter { it.isClickable }
        val labels = Apps.labels(svc)
        val used = mutableSetOf<String>()
        val resolved = nodes.map { n ->
            val label = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val pkg = label?.let { labels[it] }
            pkg?.let { used += it }
            label to pkg
        }.toMutableList()
        // Cards without a usable label: fill in from usage history (newest card is the last one).
        if (resolved.any { it.second == null }) {
            val history = Apps.recentPackages(svc).filter { it !in used && it != launcherPkg && it != svc.packageName && labels.containsValue(it) }
                .iterator()
            for (i in resolved.indices.reversed()) {
                if (resolved[i].second == null && history.hasNext()) {
                    val p = history.next()
                    resolved[i] = (resolved[i].first ?: labels.entries.firstOrNull { it.value == p }?.key) to p
                }
            }
        }
        val prevPkg = cards.getOrNull(sel)?.pkg
        cards = nodes.mapIndexed { i, n -> Card(n, resolved[i].first, resolved[i].second, Apps.typeOf(resolved[i].second)) }
        if (cards.isEmpty()) {
            close(goBack = false)
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            return
        }
        sel = cards.indexOfFirst { it.pkg != null && it.pkg == prevPkg }.takeIf { it >= 0 } ?: cards.lastIndex
        if (bar == null) addBar()
        select(sel)
    }

    // ---------- views ----------

    private fun addBar() {
        val m = wm.currentWindowMetrics.bounds
        val actions = findIn(launcherPkg, "overview_actions_view")
        val top = actions?.let { Rect().also(it::getBoundsInScreen).top }?.takeIf { it > m.height() / 2 } ?: (m.height() * 0.8f).toInt()
        bar = FrameLayout(svc).also { v ->
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, m.height() - top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = top
            }
            v.background = rounded(pal.surface, svc.dp(24).toFloat())
            wm.addView(v, lp)
        }
        frame = View(svc).also { v ->
            v.background = rounded(0, svc.dp(18).toFloat(), svc.dp(4), pal.primary)
            val lp = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            v.visibility = View.INVISIBLE
            wm.addView(v, lp)
        }
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }

    private fun hint(key: Int, label: String) = LinearLayout(svc).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Glyphs.chip(svc, svc.prefs, pal, key))
        addView(text(label, 13f, pal.onSurfaceVariant).apply { setPadding(svc.dp(6), 0, svc.dp(14), 0) })
    }

    private fun render() {
        val b = bar ?: return
        b.removeAllViews()
        val row = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(20), 0, svc.dp(12), 0)
        }
        val m = menu
        if (m == null) {
            val c = cards.getOrNull(sel) ?: return
            row.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, c.pkg)) }, LinearLayout.LayoutParams(svc.dp(40), svc.dp(40)))
            val col = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(svc.dp(12), 0, 0, 0)
                addView(text(c.label ?: "Unbekannte App", 17f, pal.onSurface, bold = true))
                addView(text("${c.type.label} · ${sel + 1} von ${cards.size}", 12f, pal.onSurfaceVariant))
            }
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(hint(KeyEvent.KEYCODE_DPAD_LEFT, "Wechseln"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_A, "Öffnen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_X, "Schließen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_Y, "Optionen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
        } else {
            row.addView(text(cards.getOrNull(sel)?.label ?: "Optionen", 15f, pal.onSurface, bold = true).apply {
                setPadding(0, 0, svc.dp(16), 0)
            })
            val items = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            m.forEachIndexed { i, it ->
                val on = i == menuSel
                items.addView(text(it.label, 14f, if (on) pal.onPrimaryContainer else pal.onSurfaceVariant, bold = on).apply {
                    setPadding(svc.dp(14), svc.dp(8), svc.dp(14), svc.dp(8))
                    background = rounded(if (on) pal.primaryContainer else pal.surfaceHigh, svc.dp(20).toFloat())
                    setOnClickListener { runMenu(i) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = svc.dp(8)
                })
            }
            row.addView(android.widget.HorizontalScrollView(svc).apply {
                isHorizontalScrollBarEnabled = false
                addView(items)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_A, "Ausführen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
        }
        b.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun select(i: Int) {
        sel = i.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        render()
        val c = cards.getOrNull(sel) ?: return
        c.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        svc.ui.postAtTime({ placeFrame() }, TOKEN, android.os.SystemClock.uptimeMillis() + 220)
    }

    private fun placeFrame() {
        val f = frame ?: return
        val c = cards.getOrNull(sel) ?: return
        c.node.refresh()
        val r = Rect().also(c.node::getBoundsInScreen)
        val w = wm.currentWindowMetrics.bounds.width()
        if (r.width() < 10 || r.right <= 0 || r.left >= w) {
            f.visibility = View.INVISIBLE
            return
        }
        val pad = svc.dp(6)
        val lp = f.layoutParams as WindowManager.LayoutParams
        lp.x = r.left - pad
        lp.y = r.top - pad
        lp.width = r.width() + 2 * pad
        lp.height = r.height() + 2 * pad
        runCatching { wm.updateViewLayout(f, lp) }
        f.visibility = View.VISIBLE
    }

    // ---------- input ----------

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val m = menu
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 ->
                if (m != null) { menuSel = (menuSel - 1).coerceAtLeast(0); render() } else select(sel - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 ->
                if (m != null) { menuSel = (menuSel + 1).coerceAtMost(m.lastIndex); render() } else select(sel + 1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (m != null) runMenu(menuSel) else launch()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                if (m != null) { menu = null; render() } else close(goBack = true)
            KeyEvent.KEYCODE_BUTTON_X -> if (m == null) closeApp()
            KeyEvent.KEYCODE_BUTTON_Y -> if (m == null) openMenu()
        }
        return true
    }

    /** Leaving the recents view by touch (or any other way) closes the switcher. */
    fun onEvent(e: AccessibilityEvent) {
        if (!isOpen || System.currentTimeMillis() < busyUntil) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == launcherPkg || pkg == svc.packageName || pkg == "com.android.systemui") return
        close(goBack = false)
    }

    private fun launch() {
        val c = cards.getOrNull(sel) ?: return
        close(goBack = false)
        c.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun closeApp() {
        val c = cards.getOrNull(sel) ?: return
        busyUntil = System.currentTimeMillis() + 1200
        val dismiss = c.node.actionList.firstOrNull {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id ||
                it.label?.toString()?.contains(Regex("schließen|close|dismiss|entfernen", RegexOption.IGNORE_CASE)) == true
        }
        if (dismiss == null || !c.node.performAction(dismiss.id)) swipeUp(c)
        svc.ui.postDelayed(::scan, 450)
    }

    private fun swipeUp(c: Card) {
        val r = Rect().also(c.node::getBoundsInScreen)
        val path = Path().apply {
            moveTo(r.exactCenterX(), r.exactCenterY())
            lineTo(r.exactCenterX(), 5f)
        }
        svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 180)).build(), null, null)
    }

    private fun openMenu() {
        val c = cards.getOrNull(sel) ?: return
        val items = mutableListOf<Item>()
        if (c.type == AppType.RETROARCH) {
            items += Item("Speichern & zurück") { launchThen(c, "SAVE_STATE") }
            items += Item("Laden & zurück") { launchThen(c, "LOAD_STATE") }
            items += Item("RetroArch-Menü") { launchThen(c, "MENU_TOGGLE") }
        }
        c.pkg?.let { pkg ->
            items += Item("App-Info") {
                close(goBack = false)
                svc.startApp(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }
            if (Root.available()) items += Item("Beenden erzwingen") {
                busyUntil = System.currentTimeMillis() + 1500
                menu = null
                Root.run("am force-stop $pkg") { svc.ui.post { closeApp() } }
            }
        }
        findIn(launcherPkg, "clear_all_button")?.let { btn ->
            items += Item("Alle schließen") {
                close(goBack = false)
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        menu = items
        menuSel = 0
        render()
    }

    private fun runMenu(i: Int) {
        val item = menu?.getOrNull(i) ?: return
        menu = null
        item.run()
        if (isOpen) render()
    }

    /** Opens the RetroArch card and sends a network command (needs "Network Commands" on in RetroArch). */
    private fun launchThen(c: Card, cmd: String) {
        close(goBack = false)
        c.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        svc.ui.postDelayed({ RetroArch.send(cmd) }, 900)
    }

    private companion object {
        val TOKEN = Any()
    }
}

object RetroArch {
    fun send(cmd: String) = thread {
        runCatching {
            DatagramSocket().use { s ->
                val b = cmd.toByteArray()
                s.send(DatagramPacket(b, b.size, InetAddress.getByName("127.0.0.1"), 55355))
            }
        }
    }
}

package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
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
import kotlin.math.abs

/**
 * Muninn, the Game Switcher. Android's own recents view does all the moving (the D-pad goes straight
 * to it, so it stays as fast as Android); Hugminn lays a frame over the screen with a cut-out for every
 * window, app icon and lock symbol, highlights the centred card and shows a controller bar below.
 * A opens, X closes the app, Y shows actions for the app type, B goes back.
 */
class Switcher(private val svc: HugminnService) {
    class Card(val node: AccessibilityNodeInfo, val label: String?, val pkg: String?, val type: AppType)
    private class Item(val label: String, val run: () -> Unit)

    /** Card geometry relative to the card's top-left corner, measured on a fully visible card. */
    private class Layout(val w: Int, val h: Int, val snap: RectF?, val icon: RectF?, val lock: RectF?)

    /** What the frame draws in one frame. */
    class Frame(val snaps: List<RectF>, val icons: List<RectF>, val locks: List<RectF>, val selected: Int)

    var isOpen = false
        private set
    private val wm = svc.getSystemService(WindowManager::class.java)
    private lateinit var pal: Palette
    private var bar: FrameLayout? = null
    private var shell: ShellView? = null
    @Volatile private var cards: List<Card> = emptyList()
    @Volatile private var layout: Layout? = null
    @Volatile private var panel: AccessibilityNodeInfo? = null
    private var sel = -1
    private var menu: List<Item>? = null
    private var menuSel = 0
    @Volatile private var launcherPkg: String? = null
    private var busyUntil = 0L
    @Volatile private var activeUntil = 0L
    @Volatile private var generation = 0

    private var tracker: HandlerThread? = null
    private var trackHandler: Handler? = null

    fun open() {
        isOpen = true
        pal = Palette(svc)
        generation++
        cards = emptyList()
        layout = null
        sel = -1
        menu = null
        busyUntil = System.currentTimeMillis() + 1500
        markActive(1200)
        if (findPanel() == null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        tracker = HandlerThread("hugminn-switcher").also { it.start() }
        trackHandler = Handler(tracker!!.looper)
        val gen = generation
        trackHandler?.post { trackLoop(gen, 0) }
    }

    fun close(goBack: Boolean) {
        if (!isOpen) return
        isOpen = false
        generation++
        menu = null
        tracker?.quitSafely()
        tracker = null
        trackHandler = null
        listOf(shell, bar).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        bar = null
        shell = null
        if (goBack) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun markActive(ms: Long = 900) {
        activeUntil = SystemClock.uptimeMillis() + ms
    }

    // ---------- tracking (background thread) ----------

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

    private fun findIn(id: String): AccessibilityNodeInfo? =
        svc.roots().firstNotNullOfOrNull { it.findAccessibilityNodeInfosByViewId("$launcherPkg:id/$id").firstOrNull() }

    private fun trackLoop(gen: Int, attempt: Int) {
        if (gen != generation || !isOpen) return
        val h = trackHandler ?: return
        var p = panel
        if (p == null || !p.refresh()) {
            p = findPanel()
            panel = p
            if (p == null) {
                if (attempt > 40) svc.ui.post {
                    Toast.makeText(svc, "Hugminn: Android-Übersicht nicht gefunden", Toast.LENGTH_SHORT).show()
                    close(goBack = false)
                } else h.postDelayed({ trackLoop(gen, attempt + 1) }, 50)
                return
            }
            cards = emptyList()
        }
        val nodes = (0 until p.childCount).mapNotNull { p.getChild(it) }.filter { it.isClickable }
        if (nodes.size != cards.size || layout == null) {
            cards = resolve(nodes)
            layout = measure(nodes)
            if (cards.isEmpty()) {
                svc.ui.post {
                    close(goBack = false)
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
                return
            }
        }
        val frame = computeFrame()
        svc.ui.post { if (gen == generation) apply(frame) }
        val delay = if (SystemClock.uptimeMillis() < activeUntil) 16L else 180L
        h.postDelayed({ trackLoop(gen, 0) }, delay)
    }

    private fun resolve(nodes: List<AccessibilityNodeInfo>): List<Card> {
        val labels = Apps.labels(svc)
        val used = mutableSetOf<String>()
        val resolved = nodes.map { n ->
            val label = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val pkg = label?.let { labels[it] }
            pkg?.let { used += it }
            label to pkg
        }.toMutableList()
        if (resolved.any { it.second == null }) {
            val history = Apps.recentPackages(svc)
                .filter { it !in used && it != launcherPkg && labels.containsValue(it) }.iterator()
            for (i in resolved.indices.reversed()) {
                if (resolved[i].second == null && history.hasNext()) {
                    val p = history.next()
                    resolved[i] = (resolved[i].first ?: labels.entries.firstOrNull { it.value == p }?.key) to p
                }
            }
        }
        return nodes.mapIndexed { i, n -> Card(n, resolved[i].first, resolved[i].second, Apps.typeOf(resolved[i].second)) }
    }

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)

    private fun child(n: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        (0 until n.childCount).mapNotNull { n.getChild(it) }.firstOrNull { it.viewIdResourceName?.endsWith(":id/$id") == true }

    private fun measure(nodes: List<AccessibilityNodeInfo>): Layout? {
        val full = nodes.maxByOrNull { bounds(it).width() } ?: return null
        val b = bounds(full)
        if (b.width() < 10) return null
        fun rel(id: String) = child(full, id)?.let { c ->
            val r = bounds(c)
            if (r.width() <= 0) null else RectF((r.left - b.left).toFloat(), (r.top - b.top).toFloat(), (r.right - b.left).toFloat(), (r.bottom - b.top).toFloat())
        }
        return Layout(b.width(), b.height(), rel("snapshot"), rel("icon"), rel("lock_icon"))
    }

    private fun computeFrame(): Frame {
        val l = layout
        val screenW = wm.currentWindowMetrics.bounds.width()
        val mid = screenW / 2f
        val snaps = mutableListOf<RectF>()
        val icons = mutableListOf<RectF>()
        val locks = mutableListOf<RectF>()
        var best = -1
        var bestDist = Float.MAX_VALUE
        cards.forEachIndexed { i, c ->
            c.node.refresh()
            val r = bounds(c.node)
            if (r.right <= 0 || r.left >= screenW || r.width() <= 0) {
                snaps += RectF(); icons += RectF(); locks += RectF()
                return@forEachIndexed
            }
            // Bounds are clipped at the screen edge: rebuild the full card from its known width.
            var left = r.left.toFloat()
            if (l != null && r.width() < l.w - 2) {
                left = if (r.left <= 0) (r.right - l.w).toFloat() else r.left.toFloat()
            }
            val top = r.top.toFloat()
            fun off(x: RectF?) = x?.let { RectF(it.left + left, it.top + top, it.right + left, it.bottom + top) } ?: RectF()
            val snap = if (l?.snap != null) off(l.snap) else RectF(left, top, left + (l?.w ?: r.width()), r.bottom.toFloat())
            snaps += snap
            icons += off(l?.icon)
            locks += off(l?.lock)
            val dist = abs(snap.centerX() - mid)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return Frame(snaps, icons, locks, best)
    }

    // ---------- views (UI thread) ----------

    private fun apply(f: Frame) {
        if (!isOpen) return
        if (shell == null) addViews()
        shell?.frame = f
        if (f.selected != sel && menu == null) {
            sel = f.selected
            render()
        }
    }

    private fun addViews() {
        val m = wm.currentWindowMetrics
        val actions = findIn("overview_actions_view")
        val barTop = actions?.let { bounds(it).top }?.takeIf { it > m.bounds.height() / 2 } ?: (m.bounds.height() * 0.8f).toInt()
        val statusH = m.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top.takeIf { it > 0 } ?: svc.dp(24)
        val s = ShellView(svc, pal, svc.prefs.statusBar, statusH, barTop)
        val lp = WindowManager.LayoutParams(
            m.bounds.width(), m.bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        s.alpha = 0f
        wm.addView(s, lp)
        s.animate().alpha(1f).setDuration(140).start()
        shell = s

        val b = FrameLayout(svc)
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, m.bounds.height() - barTop,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = barTop
        }
        wm.addView(b, blp)
        bar = b
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun hint(key: Int, label: String) = LinearLayout(svc).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Glyphs.chip(svc, svc.prefs, pal, key, 24))
        addView(text(label, 13f, pal.onSurfaceVariant).apply { setPadding(svc.dp(7), 0, svc.dp(16), 0) })
    }

    private fun render() {
        val b = bar ?: return
        b.removeAllViews()
        val row = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(24), 0, svc.dp(10), 0)
        }
        val m = menu
        val c = cards.getOrNull(sel)
        if (m == null) {
            if (c != null) {
                row.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, c.pkg)) }, LinearLayout.LayoutParams(svc.dp(40), svc.dp(40)))
                val col = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(svc.dp(12), 0, 0, 0)
                    addView(text(c.label ?: "Unbekannte App", 17f, pal.onSurface, bold = true))
                    addView(text("${c.type.label} · ${sel + 1} von ${cards.size}", 12f, pal.onSurfaceVariant))
                }
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            } else row.addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f))
            if (!svc.prefs.githubToken.isNullOrBlank()) row.addView(hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Feedback"))
            row.addView(hint(KeyEvent.KEYCODE_DPAD_LEFT, "Wechseln"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_A, "Öffnen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_X, "Schließen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_Y, "Optionen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
        } else {
            row.addView(text(c?.label ?: "Optionen", 15f, pal.onSurface, bold = true).apply { setPadding(0, 0, svc.dp(16), 0) })
            val items = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            m.forEachIndexed { i, it ->
                val on = i == menuSel
                items.addView(text(it.label, 14f, if (on) pal.onPrimaryContainer else pal.onSurfaceVariant, bold = on).apply {
                    setPadding(svc.dp(16), svc.dp(9), svc.dp(16), svc.dp(9))
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

    // ---------- input ----------

    fun onKey(e: KeyEvent): Boolean {
        val m = menu
        if (m == null && (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // Android's recents view moves itself (same speed as always); we only follow it.
            markActive()
            return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> if (m != null) { menuSel = (menuSel - 1).coerceAtLeast(0); render() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> if (m != null) { menuSel = (menuSel + 1).coerceAtMost(m.lastIndex); render() }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (m != null) runMenu(menuSel) else launch()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                if (m != null) { menu = null; render() } else close(goBack = true)
            KeyEvent.KEYCODE_BUTTON_X -> if (m == null) closeApp()
            KeyEvent.KEYCODE_BUTTON_Y -> if (m == null) openMenu()
        }
        return true
    }

    fun onEvent(e: AccessibilityEvent) {
        if (!isOpen) return
        val pkg = e.packageName?.toString()
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkg == launcherPkg) markActive()
            return
        }
        if (System.currentTimeMillis() < busyUntil || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || pkg == null) return
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
        markActive(1500)
        val dismiss = c.node.actionList.firstOrNull {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id ||
                it.label?.toString()?.contains(Regex("schließen|close|dismiss|entfernen", RegexOption.IGNORE_CASE)) == true
        }
        if (dismiss == null || !c.node.performAction(dismiss.id)) swipeUp(c)
    }

    private fun swipeUp(c: Card) {
        val r = bounds(c.node)
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
                Root.run("am force-stop $pkg") { svc.ui.post { closeApp() } }
            }
        }
        findIn("clear_all_button")?.let { btn ->
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

    private fun launchThen(c: Card, cmd: String) {
        close(goBack = false)
        c.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        svc.ui.postDelayed({ RetroArch.send(cmd) }, 900)
    }
}

/**
 * Full-screen frame (does not take touches): fills the screen in the theme's surface colour and cuts
 * out every visible window, app icon and lock symbol of the recents view; the centred card is outlined.
 */
class ShellView(
    ctx: android.content.Context,
    private val pal: Palette,
    private val mode: StatusBar,
    private val statusH: Int,
    private val barTop: Int,
) : FrameLayout(ctx) {
    var frame: Switcher.Frame? = null
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat()
        color = (pal.outline and 0x00FFFFFF) or 0x80000000.toInt()
    }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3).toFloat()
        color = pal.primary
    }
    private val snapR = ctx.dp(16).toFloat()
    private val lockR = ctx.dp(10).toFloat()
    private val gap = ctx.dp(3).toFloat()

    init {
        setWillNotDraw(false)
        if (mode == StatusBar.OVERLAY) {
            val band = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(22), 0, ctx.dp(22), 0)
            }
            band.addView(android.widget.TextClock(ctx).apply {
                format24Hour = "HH:mm"
                format12Hour = "h:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(pal.onSurface)
                setTypeface(typeface, Typeface.BOLD)
            })
            band.addView(TextView(ctx).apply {
                text = "Muninn · Game Switcher"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(pal.onSurfaceVariant)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct >= 0) band.addView(TextView(ctx).apply {
                text = "$pct %" + if (bm?.isCharging == true) " ⚡" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(pal.onSurface)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(band, LayoutParams(LayoutParams.MATCH_PARENT, statusH))
        }
    }

    private fun grow(r: RectF, by: Float) = RectF(r.left - by, r.top - by, r.right + by, r.bottom + by)

    override fun onDraw(c: Canvas) {
        val f = frame
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        }
        val clip = Path()
        if (f != null) {
            for (i in f.snaps.indices) {
                val s = f.snaps[i]
                if (s.isEmpty) continue
                clip.addRoundRect(s, snapR, snapR, Path.Direction.CW)
                f.icons[i].takeIf { !it.isEmpty }?.let { clip.addOval(grow(it, gap), Path.Direction.CW) }
                f.locks[i].takeIf { !it.isEmpty }?.let { clip.addRoundRect(grow(it, gap), lockR, lockR, Path.Direction.CW) }
            }
        }
        if (mode == StatusBar.SYSTEM) clip.addRect(0f, 0f, width.toFloat(), statusH.toFloat(), Path.Direction.CW)
        // Only cut holes inside the area between status band and controller bar.
        val area = Path().apply { addRect(0f, if (mode == StatusBar.SYSTEM) 0f else statusH.toFloat(), width.toFloat(), barTop.toFloat(), Path.Direction.CW) }
        clip.op(area, Path.Op.INTERSECT)
        path.op(clip, Path.Op.DIFFERENCE)
        c.drawPath(path, fill)
        if (f == null) return
        for (i in f.snaps.indices) {
            val s = f.snaps[i]
            if (s.isEmpty) continue
            val p = if (i == f.selected) sel else edge
            c.save()
            c.clipRect(0f, statusH.toFloat(), width.toFloat(), barTop.toFloat())
            c.drawRoundRect(s, snapR, snapR, p)
            f.icons[i].takeIf { !it.isEmpty }?.let { c.drawOval(grow(it, gap), p) }
            f.locks[i].takeIf { !it.isEmpty }?.let { c.drawRoundRect(grow(it, gap), lockR, lockR, edge) }
            c.restore()
        }
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

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

    /**
     * Geometry of the card in the middle (taken right after the view opens) and the distance between
     * cards. The recents view does not report positions while it scrolls, so Hugminn moves its frame
     * with the same animation the launcher uses (750 ms, quintic ease-out) and corrects itself with the
     * launcher's own signals (input focus, scroll events).
     */
    private class Model(val card: RectF, val snap: RectF?, val icon: RectF?, val lock: RectF?, val pitch: Float)

    /** What the frame draws in one frame. */
    class Frame(
        val snaps: List<RectF>,
        val labels: List<String>,
        val icons: List<android.graphics.drawable.Drawable?>,
        val selected: Int,
    )

    var isOpen = false
        private set
    private val wm = svc.getSystemService(WindowManager::class.java)
    private lateinit var pal: Palette
    private var bar: FrameLayout? = null
    private var shell: ShellView? = null
    private var cards: List<Card> = emptyList()
    private var model: Model? = null
    /** False until the cards have been read; the frame is already shown from the remembered geometry. */
    private var scanned = false
    private var pendingKey = 0
    private var pos = 0f // continuous index of the card in the middle
    /** +1: higher index = further right on screen, -1: further left (depends on how the launcher lists cards). */
    private var dir = 1f
    private val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()
    private var target = 0
    private var anim: android.animation.ValueAnimator? = null
    private var lastKeyAt = 0L
    private var sel = -1
    private var menu: List<Item>? = null
    private var menuSel = 0
    @Volatile private var launcherPkg: String? = null
    private var busyUntil = 0L
    @Volatile private var generation = 0
    private var closedAt = 0L
    private var lastAutoCheck = 0L
    private var worker: HandlerThread? = null
    private var work: Handler? = null

    fun open() {
        if (isOpen) return
        isOpen = true
        pal = Palette(svc)
        generation++
        cards = emptyList()
        model = loadGeometry()
        scanned = false
        pendingKey = 0
        sel = -1
        menu = null
        busyUntil = System.currentTimeMillis() + 1500
        if (findPanel() == null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        // Show the frame right away with the geometry from last time; it is refined once the cards are read.
        if (model != null) svc.ui.post { if (isOpen && !scanned) redraw() }
        worker = HandlerThread("hugminn-switcher").also { it.start() }
        work = Handler(worker!!.looper)
        val gen = generation
        work?.post { scan(gen, 0, null) }
    }

    fun close(goBack: Boolean) {
        if (!isOpen) return
        isOpen = false
        closedAt = SystemClock.uptimeMillis()
        generation++
        menu = null
        anim?.cancel()
        anim = null
        worker?.quitSafely()
        worker = null
        work = null
        listOf(shell, bar).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        bar = null
        shell = null
        if (goBack) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    // ---------- reading the recents view (worker thread) ----------

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

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)

    private fun cardNodes(): List<AccessibilityNodeInfo> {
        val p = findPanel() ?: return emptyList()
        return (0 until p.childCount).mapNotNull { p.getChild(it) }.filter { it.isClickable }
    }

    /** Waits until the recents view has settled (same card bounds twice), then builds the model. */
    private fun scan(gen: Int, attempt: Int, last: List<Rect>?, emptyStreak: Int = 0, stable: Int = 0) {
        if (gen != generation) return
        val h = work ?: return
        val nodes = try { cardNodes() } catch (t: Throwable) { emptyList() }
        val now = nodes.map { bounds(it) }
        // No open apps: the recents view is shown but has no cards. Wait a moment to be sure.
        val panelShown = nodes.isEmpty() && (try { findPanel()?.isVisibleToUser == true } catch (t: Throwable) { false })
        val streak = if (panelShown) emptyStreak + 1 else 0
        // While the view opens the cards are scaled down; only accept full-size, unchanged cards.
        val screen = wm.currentWindowMetrics.bounds
        val fullSize = now.any { it.width() > screen.width() * 0.3f && it.height() > screen.height() * 0.4f }
        val same = nodes.isNotEmpty() && last != null && last == now && fullSize
        val stableNow = if (same) stable + 1 else 0
        val settled = stableNow >= 2 || streak >= 5
        if (!settled) {
            if (attempt > 60) svc.ui.post {
                Toast.makeText(svc, "Hugminn: Android-Übersicht nicht gefunden", Toast.LENGTH_SHORT).show()
                close(goBack = false)
            } else h.postDelayed({ scan(gen, attempt + 1, now, streak, stableNow) }, 50)
            return
        }
        val newCards = resolve(nodes)
        val m = buildModel(nodes)
        val focused = nodes.indexOfFirst { it.isFocused }
        svc.ui.post {
            if (gen != generation) return@post
            cards = newCards
            if (m != null) {
                model = m
                saveGeometry(m)
            }
            scanned = true
            val mid = wm.currentWindowMetrics.bounds.width() / 2f
            val centred = focused.takeIf { it >= 0 }
                ?: now.indices.minByOrNull { abs(now[it].exactCenterX() - mid) } ?: 0
            // Android always shows the newest app on the far right and centres it when recents opens.
            // The card in the middle is therefore the newest; older ones go to its left.
            dir = when {
                cards.size < 2 -> 1f
                centred == 0 -> -1f
                centred == cards.lastIndex -> 1f
                else -> dir
            }
            sel = -1
            jumpTo(centred)
            watch(gen, 0)
            // A button pressed while the cards were still being read is carried out now.
            val k = pendingKey
            pendingKey = 0
            if (k != 0) handleKey(k)
        }
    }

    private fun loadGeometry(): Model? = try {
        val b = wm.currentWindowMetrics.bounds
        val parts = svc.prefs.switcherGeometry?.split('|') ?: emptyList()
        if (parts.size != 4 || parts[0] != "${b.width()},${b.height()}") null else {
            fun rect(s: String) = s.split(',').map { it.toFloat() }.let { RectF(it[0], it[1], it[2], it[3]) }
            Model(rect(parts[1]), parts[2].takeIf { it != "-" }?.let(::rect), null, null, parts[3].toFloat())
        }
    } catch (t: Throwable) {
        null
    }

    private fun saveGeometry(m: Model) {
        val b = wm.currentWindowMetrics.bounds
        fun str(r: RectF) = "${r.left},${r.top},${r.right},${r.bottom}"
        svc.prefs.switcherGeometry = "${b.width()},${b.height()}|${str(m.card)}|${m.snap?.let(::str) ?: "-"}|${m.pitch}"
    }

    /** Closes the frame as soon as the recents view is gone (app opened, swiped home, ...). */
    private fun watch(gen: Int, misses: Int) {
        val h = work ?: return
        h.postDelayed({
            if (gen != generation) return@postDelayed
            val visible = try { findPanel()?.isVisibleToUser == true } catch (t: Throwable) { false }
            val m = if (visible) 0 else misses + 1
            if (m >= 2) svc.ui.post { if (gen == generation) close(goBack = false) } else watch(gen, m)
        }, 250)
    }

    /** Called for launcher events while closed: opens the frame when the recents view appeared by gesture. */
    private fun autoOpen(e: AccessibilityEvent) {
        if (!svc.prefs.autoSwitcher) return
        val pkg = e.packageName?.toString() ?: return
        val known = launcherPkg
        if (known != null) { if (pkg != known) return } else if (!pkg.contains("launcher", true) && !pkg.contains("quickstep", true)) return
        val now = SystemClock.uptimeMillis()
        if (now - closedAt < 1200 || now - lastAutoCheck < 200) return
        lastAutoCheck = now
        val p = try { findPanel() } catch (t: Throwable) { null } ?: return
        if (p.isVisibleToUser) open()
    }

    private fun buildModel(nodes: List<AccessibilityNodeInfo>): Model? {
        val screenW = wm.currentWindowMetrics.bounds.width()
        val rs = nodes.map { bounds(it) }
        val full = rs.indices.maxByOrNull { rs[it].width() } ?: return null
        val b = rs[full]
        if (b.width() < 10) return null
        fun child(n: AccessibilityNodeInfo, id: String) =
            (0 until n.childCount).mapNotNull { n.getChild(it) }.firstOrNull { it.viewIdResourceName?.endsWith(":id/$id") == true }
        fun rel(id: String) = child(nodes[full], id)?.let { c ->
            val r = bounds(c)
            if (r.width() <= 0) null else RectF((r.left - b.left).toFloat(), (r.top - b.top).toFloat(), (r.right - b.left).toFloat(), (r.bottom - b.top).toFloat())
        }
        // Left edge of a card even if it is cut off at the screen edge.
        fun left(i: Int): Float {
            val r = rs[i]
            return if (r.width() < b.width() - 2 && r.left <= 0) (r.right - b.width()).toFloat() else r.left.toFloat()
        }
        // Card distance (the launcher reports positions mirrored, only the size of the step is used).
        val pitch = abs(when {
            full + 1 < rs.size && rs[full + 1].right > 0 && rs[full + 1].left < screenW -> left(full + 1) - left(full)
            full > 0 && rs[full - 1].right > 0 -> left(full) - left(full - 1)
            else -> b.width() * 1.04f
        }).takeIf { it > b.width() * 0.5f } ?: (b.width() * 1.04f)
        // Card rect as it sits in the middle of the screen.
        val mid = screenW / 2f
        val card = RectF(mid - b.width() / 2f, b.top.toFloat(), mid + b.width() / 2f, b.bottom.toFloat())
        return Model(card, rel("snapshot"), rel("icon"), rel("lock_icon"), pitch)
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

    /** After a move: ask the launcher which card has focus and correct the model if needed. */
    private fun verifyFocus() {
        val gen = generation
        work?.post {
            val nodes = try { cardNodes() } catch (t: Throwable) { return@post }
            val focused = nodes.indexOfFirst { it.isFocused }
            val count = nodes.size
            svc.ui.post {
                if (gen != generation || anim?.isRunning == true) return@post
                if (count != cards.size) {
                    rescan()
                    return@post
                }
                if (focused >= 0 && focused != target) jumpTo(focused)
            }
        }
    }

    /** Re-reads cards (after one was closed). */
    private fun rescan() {
        val gen = generation
        work?.post {
            val nodes = try { cardNodes() } catch (t: Throwable) { emptyList() }
            val newCards = resolve(nodes)
            val focused = nodes.indexOfFirst { it.isFocused }
            svc.ui.post {
                if (gen != generation) return@post
                cards = newCards
                sel = -1
                jumpTo(if (focused >= 0) focused else target.coerceIn(0, (cards.size - 1).coerceAtLeast(0)))
            }
        }
    }

    // ---------- moving the frame (UI thread) ----------

    private fun jumpTo(i: Int) {
        anim?.cancel()
        target = i
        pos = i.toFloat()
        redraw()
    }

    /** Same motion as the launcher's page snap: 750 ms, quintic ease-out; a new press first jumps to the old target. */
    private fun animateTo(i: Int) {
        val from = if (anim?.isRunning == true) target.toFloat() else pos
        anim?.cancel()
        target = i
        anim = android.animation.ValueAnimator.ofFloat(from, i.toFloat()).apply {
            duration = 750
            interpolator = android.animation.TimeInterpolator { t -> val u = t - 1f; u * u * u * u * u + 1f }
            addUpdateListener {
                pos = it.animatedValue as Float
                redraw()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (anim === a) svc.ui.postDelayed({ verifyFocus() }, 60)
                }
            })
            start()
        }
        redraw()
    }

    private fun computeFrame(): Frame {
        val m = model
        val snaps = mutableListOf<RectF>()
        val labels = mutableListOf<String>()
        val icons = mutableListOf<android.graphics.drawable.Drawable?>()
        if (!scanned && m != null) {
            val snap = m.snap?.let { RectF(it.left + m.card.left, it.top + m.card.top, it.right + m.card.left, it.bottom + m.card.top) } ?: RectF(m.card)
            return Frame(listOf(snap), listOf(""), listOf(null), 0)
        }
        for (i in cards.indices) {
            if (m == null) {
                snaps += RectF(); labels += ""; icons += null
                continue
            }
            // Mirrored: a higher index (older app) sits further left on screen.
            val dx = dir * (i - pos) * m.pitch
            val left = m.card.left + dx
            val top = m.card.top
            fun off(x: RectF?) = x?.let { RectF(it.left + left, it.top + top, it.right + left, it.bottom + top) } ?: RectF()
            snaps += m.snap?.let { off(it) } ?: RectF(m.card).also { it.offset(dx, 0f) }
            val c = cards[i]
            labels += c.label ?: "Unbekannte App"
            icons += c.pkg?.let { p -> iconCache.getOrPut(p) { Apps.icon(svc, p) } }
        }
        return Frame(snaps, labels, icons, target)
    }

    private fun redraw() {
        if (!isOpen) return
        if (shell == null) addViews()
        shell?.frame = computeFrame()
        if (target != sel && menu == null) {
            sel = target
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
        s.animate().alpha(1f).setDuration(70).start()
        shell = s

        val b = object : FrameLayout(svc) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) try { handleKey(event.keyCode) } catch (t: Throwable) { Crash.note(svc, t) }
                return true
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
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
        if (!scanned) {
            row.addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f))
            row.addView(hint(KeyEvent.KEYCODE_DPAD_LEFT, "Wechseln"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_A, "Öffnen"))
            row.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
            b.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            return
        }
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
            } else {
                val col = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("Keine offenen Apps", 17f, pal.onSurface, bold = true))
                    addView(text("Starte ein Spiel, dann taucht es hier auf", 12f, pal.onSurfaceVariant))
                }
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (!svc.prefs.githubToken.isNullOrBlank()) row.addView(hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Feedback"))
                row.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
                b.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                return
            }
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
            // Android's recents view moves itself; the frame follows its focus events (see onEvent).
            return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (!scanned && e.keyCode in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            pendingKey = e.keyCode
            return true
        }
        handleKey(e.keyCode)
        return true
    }

    private fun handleKey(keyCode: Int) {
        val m = menu
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> if (m != null) { menuSel = (menuSel - 1).coerceAtLeast(0); render() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> if (m != null) { menuSel = (menuSel + 1).coerceAtMost(m.lastIndex); render() }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (m != null) runMenu(menuSel) else launch()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                if (m != null) { menu = null; render() } else close(goBack = true)
            KeyEvent.KEYCODE_BUTTON_X -> if (m == null) closeApp()
            KeyEvent.KEYCODE_BUTTON_Y -> if (m == null) openMenu()
        }
        // While the option menu is open the bar takes input focus, so the D-pad (a joystick hat) reaches it.
        setBarFocus(menu != null)
    }

    private fun setBarFocus(on: Boolean) {
        val b = bar ?: return
        val lp = b.layoutParams as? WindowManager.LayoutParams ?: return
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val want = if (on) lp.flags and notFocusable.inv() else lp.flags or notFocusable
        if (want == lp.flags) return
        lp.flags = want
        runCatching { wm.updateViewLayout(b, lp) }
        if (on) b.requestFocus()
    }

    fun onEvent(e: AccessibilityEvent) {
        if (!isOpen) {
            autoOpen(e)
            return
        }
        val pkg = e.packageName?.toString()
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && pkg == launcherPkg) {
            // The launcher focuses the card it snaps to right when the move starts (D-pad, stick, keyboard).
            val src = e.source
            var i = if (src != null) cards.indexOfFirst { it.node == src } else -1
            if (i < 0) {
                val label = e.contentDescription?.toString() ?: e.text.firstOrNull()?.toString()
                if (label != null) i = cards.indexOfFirst { it.label == label }
            }
            if (i >= 0 && i != target && model != null) {
                lastKeyAt = SystemClock.uptimeMillis()
                animateTo(i)
            }
            return
        }
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Scrolled by touch: the launcher reports which cards are visible once it has settled.
            if (pkg == launcherPkg && SystemClock.uptimeMillis() - lastKeyAt > 900 && e.fromIndex >= 0 && e.toIndex >= 0) {
                val lo = minOf(e.fromIndex, e.toIndex)
                val hi = maxOf(e.fromIndex, e.toIndex)
                val guess = if (hi - lo >= 2) (lo + hi) / 2 else listOf(lo, hi).minByOrNull { abs(it - pos) } ?: lo
                if (guess in cards.indices && guess != target) jumpTo(guess)
                svc.ui.postDelayed({ verifyFocus() }, 100)
            }
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
        val dismiss = c.node.actionList.firstOrNull {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id ||
                it.label?.toString()?.contains(Regex("schließen|close|dismiss|entfernen", RegexOption.IGNORE_CASE)) == true
        }
        if (dismiss == null || !c.node.performAction(dismiss.id)) swipeUp()
        svc.ui.postDelayed({ rescan() }, 500)
    }

    private fun swipeUp() {
        val r = computeFrame().snaps.getOrNull(target) ?: return
        val path = Path().apply {
            moveTo(r.centerX(), r.centerY())
            lineTo(r.centerX(), 5f)
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
        setBarFocus(false)
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
        try {
            draw(c, frame)
        } catch (t: Throwable) {
            Crash.note(context, t)
        }
    }

    private val iconSize = ctx.dp(34).toFloat()
    private val iconGap = ctx.dp(10).toFloat()
    private val titleGap = ctx.dp(14).toFloat()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.resources.displayMetrics.scaledDensity * 16f
    }
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.secondaryContainer }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.onSurfaceVariant
        textAlign = Paint.Align.CENTER
        textSize = ctx.resources.displayMetrics.scaledDensity * 20f
    }

    private fun draw(c: Canvas, f: Switcher.Frame?) {
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
            }
        }
        if (mode == StatusBar.SYSTEM) clip.addRect(0f, 0f, width.toFloat(), statusH.toFloat(), Path.Direction.CW)
        // Only cut holes inside the area between status band and controller bar.
        val area = Path().apply { addRect(0f, if (mode == StatusBar.SYSTEM) 0f else statusH.toFloat(), width.toFloat(), barTop.toFloat(), Path.Direction.CW) }
        clip.op(area, Path.Op.INTERSECT)
        path.op(clip, Path.Op.DIFFERENCE)
        c.drawPath(path, fill)
        if (f == null) return
        if (f.snaps.isEmpty()) {
            c.drawText("Keine offenen Apps", width / 2f, (statusH + barTop) / 2f, emptyPaint)
            return
        }
        for (i in f.snaps.indices) {
            val s = f.snaps[i]
            if (s.isEmpty) continue
            val p = if (i == f.selected) sel else edge
            c.save()
            c.clipRect(0f, statusH.toFloat(), width.toFloat(), barTop.toFloat())
            c.drawRoundRect(s, snapR, snapR, p)
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

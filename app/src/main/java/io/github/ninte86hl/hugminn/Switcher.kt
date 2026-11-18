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
import android.view.MotionEvent
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
        /** Neighbouring apps, drawn as calm placeholders left/right of the window instead of the live cards. */
        val left: Peek? = null,
        val right: Peek? = null,
    )

    class Peek(val label: String, val icon: android.graphics.drawable.Drawable?)

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

    /** App that was in front when the switcher opened (B returns there). */
    private var fromPkg: String? = null

    fun open() {
        if (isOpen) return
        isOpen = true
        lingering?.let { l -> lingering = null; runCatching { wm.removeView(l) } }
        // Opened from the home screen: B leads back there, not into the last game.
        val front = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        fromPkg = if (front != null && front.contains("launcher", true)) null else svc.foregroundPkg
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
        svc.ui.post { if (isOpen && !scanned) redraw() }
        worker = HandlerThread("hugminn-switcher").also { it.start() }
        work = Handler(worker!!.looper)
        val gen = generation
        work?.post { scan(gen, 0, null) }
    }

    fun close(goBack: Boolean) {
        if (!isOpen) return
        isOpen = false
        hat.stop()
        closedAt = SystemClock.uptimeMillis()
        generation++
        menu = null
        anim?.cancel()
        anim = null
        worker?.quitSafely()
        worker = null
        work = null
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        val s = shell
        shell = null
        if (goBack && s != null) {
            // Leave the frame (with its window) in place and fade it out while Android switches back,
            // so neither the full overview nor a black screen shows.
            lingering = s
            leaveRecents()
            dropLingering(120)
        } else if (s != null) {
            // also when an app is opened: fade instead of a hard cut
            lingering = s
            dropLingering(60)
        }
    }

    /** Frame that stays over the closing overview for a moment (see [close]). */
    private var lingering: ShellView? = null

    private fun dropLingering(delay: Long) {
        val s = lingering ?: return
        svc.ui.postDelayed({
            if (lingering !== s) return@postDelayed
            lingering = null
            s.animate().alpha(0f).setDuration(180).withEndAction { runCatching { wm.removeView(s) } }.start()
        }, delay)
    }

    /**
     * Closes Android's own recents view too: back into the app that was open before, or home.
     */
    private fun leaveRecents() {
        val back = cards.firstOrNull { fromPkg != null && it.pkg == fromPkg }
        // Back into the app: its card grows out of the window, like Android's own switch.
        if (back != null && back.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        // Opened from the home screen (or the app is gone): straight home.
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
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
        // (The empty view is drawn by the launcher itself, so the panel may not count as visible; after
        // a short while without any card the view is taken as empty as well.)
        val panelShown = nodes.isEmpty() && (attempt >= 8 || (try { findPanel() != null } catch (t: Throwable) { false }))
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
        if (svc.prefs.ownSwitcher) {
            // Own switcher: cover Android's view as soon as it has settled (checked every 40 ms).
            if (svc.own.isOpen || now - svc.own.closedAt < 1200 || now - lastAutoCheck < 150) return
            lastAutoCheck = now
            pollRecents(0)
            return
        }
        if (now - closedAt < 1200 || now - lastAutoCheck < 200) return
        lastAutoCheck = now
        // The recents view also flashes up while swiping home; only open once it has settled
        // (still visible a moment later, with its action buttons shown).
        svc.ui.postDelayed({ if (!isOpen && settledRecents()) open() }, 380)
    }

    private fun pollRecents(n: Int) {
        svc.ui.postDelayed({
            if (svc.own.isOpen) return@postDelayed
            if (settledRecents()) svc.openOwnFromSwipe() else if (n < 30) pollRecents(n + 1)
        }, 30)
    }

    private fun settledRecents(): Boolean = try {
        val p = findPanel()
        val actions = findIn("clear_all_button") ?: findIn("action_screenshot")
        p != null && p.isVisibleToUser && actions != null && actions.isVisibleToUser
    } catch (t: Throwable) {
        false
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

    /**
     * A fixed window exactly where the selected card rests (the middle). Cards slide through it when
     * switching, so nothing has to follow Android's animation, and no empty background shows next to
     * the first or last card.
     */
    private fun computeFrame(): Frame {
        // Until the cards are read the window opens right away at the remembered place (Android's own
        // animation shows through it). Only without any remembered geometry it stays closed.
        if (!scanned && model == null) return Frame(listOf(RectF()), emptyList(), emptyList(), target)
        val m = model ?: return Frame(emptyList(), emptyList(), emptyList(), target)
        if (scanned && cards.isEmpty()) return Frame(emptyList(), emptyList(), emptyList(), 0)
        val snap = m.snap?.let { RectF(it.left + m.card.left, it.top + m.card.top, it.right + m.card.left, it.bottom + m.card.top) } ?: RectF(m.card)
        // Neighbours are not shown live (half-cut cards, gaps and launcher parts made it look restless);
        // instead a calm placeholder with icon and name shows which app lies in which direction.
        var left: Peek? = null
        var right: Peek? = null
        if (scanned && cards.size > 1) {
            val olderLeft = dir < 0 // dir < 0: higher index = further left
            fun peek(i: Int) = cards.getOrNull(i)?.let { c ->
                Peek(c.label ?: "App", c.pkg?.let { p -> iconCache.getOrPut(p) { Apps.icon(svc, p) } })
            }
            left = peek(if (olderLeft) target + 1 else target - 1)
            right = peek(if (olderLeft) target - 1 else target + 1)
        }
        return Frame(listOf(snap), listOf(""), listOf(null), -1, left, right)
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
        s.animate().alpha(1f).setDuration(50).start()
        shell = s

        val b = object : FrameLayout(svc) {
            // The D-pad is a joystick hat: Hugminn reads it here and moves the recents view itself.
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                hat.onMotion(event) || super.dispatchGenericMotionEvent(event)

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) try {
                    val lr = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (menu == null && lr) navigate(event.keyCode) else handleKey(event.keyCode)
                } catch (t: Throwable) {
                    Crash.note(svc, t)
                }
                return true
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, m.bounds.height() - barTop,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCH_MODAL: the bar takes key focus, but touches outside it (swiping a card away)
            // must still reach the recents view.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = barTop
        }
        wm.addView(b, blp)
        bar = b
        b.requestFocus()
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Look.font(svc, svc.prefs.uiStyle == UiStyle.PIXEL, bold)
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
                row.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
                b.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                return
            }
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
                    background = Look.shape(svc, svc.prefs.uiStyle == UiStyle.PIXEL, if (on) pal.primaryContainer else pal.surfaceHigh, svc.dp(20).toFloat())
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

    private val hat = HatInput(svc.ui) { code ->
        try {
            if (menu != null) handleKey(code) else navigate(code)
        } catch (t: Throwable) {
            Crash.note(svc, t)
        }
    }

    /** D-pad left/right: snap the recents view to the neighbouring card (on screen, not by index). */
    private fun navigate(code: Int) {
        if (!scanned || cards.size < 2) return
        val step = when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            else -> return
        }
        // dir < 0: higher index sits further left
        val next = target + if (dir < 0) -step else step
        if (next !in cards.indices) return
        lastKeyAt = SystemClock.uptimeMillis()
        val node = cards[next].node
        animateTo(next)
        // The launcher snaps to a card asked to be shown on screen (same animation as with a D-pad).
        if (!node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }
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

    @Suppress("UNUSED_PARAMETER")
    private fun setBarFocus(on: Boolean) {
        // The bar keeps input focus the whole time (needed for the D-pad); nothing to switch.
        return
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
 * Full-screen frame (does not take touches): fills the screen in the theme's surface colour and leaves
 * one calm window over the selected app. Neighbouring apps appear as quiet placeholders at the sides.
 * Corners, outlines and fonts follow the chosen style (modern, pixel, dot matrix).
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

    private val style = Prefs(ctx).uiStyle
    private val pixel = style == UiStyle.PIXEL
    private val dots = style == UiStyle.DOTS
    private val unit = ctx.dp(3).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        when {
            dots -> {
                strokeWidth = ctx.dp(3).toFloat()
                color = pal.outline
                strokeCap = Paint.Cap.ROUND
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, ctx.dp(8).toFloat()), 0f)
            }
            pixel -> {
                isAntiAlias = false
                strokeWidth = unit
                color = pal.surfaceHighest
            }
            else -> {
                strokeWidth = ctx.dp(1).toFloat()
                color = (pal.outline and 0x00FFFFFF) or 0x55000000
            }
        }
    }
    private val peekFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surfaceHigh }
    private val peekText = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.onSurfaceVariant
        textAlign = Paint.Align.CENTER
        textSize = ctx.resources.displayMetrics.scaledDensity * 13f
        typeface = Look.font(ctx, pixel, false)
    }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(if (pixel) 3 else 2).toFloat()
        strokeCap = if (pixel) Paint.Cap.SQUARE else Paint.Cap.ROUND
        strokeJoin = if (pixel) Paint.Join.MITER else Paint.Join.ROUND
        color = pal.onSurfaceVariant
        if (dots) {
            strokeWidth = ctx.dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, ctx.dp(5).toFloat()), 0f)
        }
    }
    private val radius = ctx.dp(if (dots) 10 else 16).toFloat()
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.onSurfaceVariant
        textAlign = Paint.Align.CENTER
        textSize = ctx.resources.displayMetrics.scaledDensity * 20f
        typeface = Look.font(ctx, pixel, false)
    }

    init {
        setWillNotDraw(false)
        if (mode == StatusBar.OVERLAY) addView(StatusBand(ctx, pal, "Muninn · Game Switcher"), LayoutParams(LayoutParams.MATCH_PARENT, statusH))
    }

    /** Window/placeholder outline in the chosen style. */
    private fun shape(r: RectF, into: Path = Path()): Path =
        if (pixel) pixelPath(r, unit, 3, into) else into.apply { addRoundRect(r, radius, radius, Path.Direction.CW) }

    override fun onDraw(c: Canvas) {
        try {
            draw(c, frame)
        } catch (t: Throwable) {
            Crash.note(context, t)
        }
    }

    private fun draw(c: Canvas, f: Switcher.Frame?) {
        val top = if (mode == StatusBar.SYSTEM) 0f else statusH.toFloat()
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        }
        val clip = Path()
        // A little inside the snapshot so no launcher edge or rounded card corner shows through.
        val holeInset = context.dp(3).toFloat()
        val hole = f?.snaps?.firstOrNull()?.takeIf { !it.isEmpty }?.let { RectF(it.left + holeInset, it.top + holeInset, it.right - holeInset, it.bottom - holeInset) }
        if (hole != null) shape(hole, clip)
        if (mode == StatusBar.SYSTEM) clip.addRect(0f, 0f, width.toFloat(), statusH.toFloat(), Path.Direction.CW)
        val area = Path().apply { addRect(0f, top, width.toFloat(), barTop.toFloat(), Path.Direction.CW) }
        clip.op(area, Path.Op.INTERSECT)
        path.op(clip, Path.Op.DIFFERENCE)
        c.drawPath(path, fill)
        if (f == null) return
        if (hole == null) {
            if (f.snaps.isEmpty()) c.drawText("Keine offenen Apps", width / 2f, (statusH + barTop) / 2f, emptyPaint)
            return
        }
        c.save()
        c.clipRect(0f, top, width.toFloat(), barTop.toFloat())
        c.drawPath(shape(hole), edge)
        c.restore()
        val gap = context.dp(18).toFloat()
        val side = context.dp(18).toFloat()
        val inset = hole.height() * 0.12f
        f.left?.let { drawPeek(c, RectF(side, hole.top + inset, hole.left - gap, hole.bottom - inset), it, true) }
        f.right?.let { drawPeek(c, RectF(hole.right + gap, hole.top + inset, width - side, hole.bottom - inset), it, false) }
    }

    /** Quiet placeholder for the app in that direction: icon, name and an arrow pointing outwards. */
    private fun drawPeek(c: Canvas, r: RectF, p: Switcher.Peek, left: Boolean) {
        if (r.width() < context.dp(56) || r.height() < context.dp(80)) return
        val bg = shape(r)
        c.drawPath(bg, peekFill)
        if (dots) c.drawPath(bg, edge)
        val icon = minOf(context.dp(48).toFloat(), r.width() * 0.5f)
        val cx = r.centerX()
        val label = android.text.TextUtils.ellipsize(p.label, peekText, r.width() - context.dp(20), android.text.TextUtils.TruncateAt.END).toString()
        val block = icon + context.dp(12) + peekText.textSize
        var y = r.centerY() - block / 2
        p.icon?.let { d ->
            d.setBounds((cx - icon / 2).toInt(), y.toInt(), (cx + icon / 2).toInt(), (y + icon).toInt())
            if (dots || pixel) {
                // Monochrome look stays calm: icons are drawn slightly dimmed.
                d.alpha = 200
            }
            d.draw(c)
            d.alpha = 255
        }
        y += icon + context.dp(12) + peekText.textSize * 0.8f
        c.drawText(label, cx, y, peekText)
        // Arrow at the outer edge
        val a = context.dp(7).toFloat()
        val ax = if (left) r.left + context.dp(18) else r.right - context.dp(18)
        val ay = r.centerY()
        val d = if (left) a else -a
        c.drawPath(Path().apply {
            moveTo(ax + d, ay - a * 1.6f)
            lineTo(ax, ay)
            lineTo(ax + d, ay + a * 1.6f)
        }, chevron)
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

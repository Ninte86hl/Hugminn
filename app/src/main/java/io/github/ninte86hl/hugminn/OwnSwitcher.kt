package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/** An app in Hugminn's own switcher: package and (if known) Android's task id. */
data class RecentApp(val pkg: String, val taskId: Int)

/**
 * Pictures of the apps as they looked when last seen, taken by Hugminn itself (Android does not hand
 * out its own task snapshots). Kept in memory and as small JPEGs in the cache folder.
 */
object Snapshots {
    private val mem = HashMap<String, Bitmap>()
    @Volatile private var lastShot = 0L

    private fun file(ctx: Context, pkg: String) = File(File(ctx.cacheDir, "snaps").apply { mkdirs() }, "$pkg.jpg")

    fun get(ctx: Context, pkg: String): Bitmap? = synchronized(mem) {
        mem[pkg] ?: runCatching { BitmapFactory.decodeFile(file(ctx, pkg).path) }.getOrNull()?.also { mem[pkg] = it }
    }

    fun remove(ctx: Context, pkg: String) {
        synchronized(mem) { mem.remove(pkg) }
        runCatching { file(ctx, pkg).delete() }
    }

    /** Screenshot of the screen as it is now, stored for [pkg]; [then] runs on the main thread in any case. */
    fun capture(svc: HugminnService, pkg: String, then: (() -> Unit)? = null) {
        val now = SystemClock.uptimeMillis()
        // Android allows about one screenshot per second
        if (now - lastShot < 1050) { then?.invoke(); return }
        lastShot = now
        var done = false
        val finish = { if (!done) { done = true; then?.invoke() } }
        // never keep the user waiting for long
        svc.ui.postDelayed({ finish() }, 260)
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hw = runCatching { Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) }.getOrNull()
                    val small = hw?.let { b ->
                        val w = 640
                        val h = (b.height * w / b.width.toFloat()).toInt().coerceAtLeast(1)
                        runCatching { Bitmap.createScaledBitmap(b.copy(Bitmap.Config.ARGB_8888, false), w, h, true) }.getOrNull()
                    }
                    result.hardwareBuffer.close()
                    if (small != null) {
                        synchronized(mem) { mem[pkg] = small }
                        thread { runCatching { file(svc, pkg).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) } } }
                    }
                    finish()
                }

                override fun onFailure(errorCode: Int) = finish()
            })
        } catch (t: Throwable) {
            finish()
        }
    }
}

/**
 * Hugminn's own game switcher (Muninn): a carousel of the recently used apps with their last picture.
 * Android's recents view is not involved at all, so nothing of it can show through.
 */
class OwnSwitcher(private val svc: HugminnService) {
    var isOpen = false
        private set
    var closedAt = 0L
        private set
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var carousel: Carousel? = null
    private var bar: LinearLayout? = null
    private lateinit var pal: Palette
    private var apps: List<RecentApp> = emptyList()
    private var sel = 0
    /** Opened by the swipe gesture: Android's recents view lies underneath and must be left properly. */
    private var fromSwipe = false
    /** App that was in front when the switcher opened (null: home screen). */
    private var fromPkg: String? = null
    private var menu: List<Pair<String, () -> Unit>>? = null
    private var menuSel = 0

    /** What reading Android's task list found (for feedback reports). */
    @Volatile var diag = ""

    /** Recently used apps, newest first; updated as apps come to the front and from Android's task list. */
    private var known: MutableList<RecentApp> = mutableListOf()

    private val hat = HatInput(svc.ui) { code -> runCatching { key(code) }.onFailure { Crash.note(svc, it) } }

    /** An app came to the front: it moves to the start of the list. */
    fun touch(pkg: String) {
        synchronized(this) {
            val id = known.firstOrNull { it.pkg == pkg }?.taskId ?: -1
            known.removeAll { it.pkg == pkg }
            known.add(0, RecentApp(pkg, id))
        }
    }

    /** Reads Android's task list (root) in the background. */
    fun refresh(then: (() -> Unit)? = null) {
        thread {
            val list = loadTasks()
            // only trust Android's list if it could be read (otherwise keep what Hugminn has seen)
            if (list != null) synchronized(this) { known = list.toMutableList() }
            then?.let { svc.ui.post(it) }
        }
    }

    /**
     * Android's recent tasks, newest first. Each task is read as a whole block of the dump, and its app
     * is taken from the activity it really runs (realActivity / the start intent), not its affinity.
     */
    private fun loadTasks(): List<RecentApp>? {
        val out = Root.execLong("dumpsys activity recents | grep -E 'Recent #|realActivity=|cmp='") ?: run { diag = "Taskliste: keine Antwort"; return null }
        val starts = Regex("""\* Recent #\d+: Task\{\S+ #(\d+)[^\n]*""").findAll(out).toList()
        if (starts.isEmpty()) { diag = "Taskliste: nicht lesbar"; return null }
        val launchable = Apps.labels(svc).values.toSet()
        val res = mutableListOf<RecentApp>()
        val skipped = mutableListOf<String>()
        starts.forEachIndexed { i, m ->
            val block = out.substring(m.range.first, if (i + 1 < starts.size) starts[i + 1].range.first else out.length)
            val head = m.value
            val type = Regex("""type=(\w+)""").find(head)?.groupValues?.get(1)
            if (type != null && type != "standard" && type != "undefined") return@forEachIndexed
            val id = m.groupValues[1].toIntOrNull() ?: -1
            val pkg = Regex("""realActivity=\{?([\w.]+)/""").find(block)?.groupValues?.get(1)
                ?: Regex("""cmp=([\w.]+)/""").find(block)?.groupValues?.get(1)
                ?: Regex("""I=([\w.]+)/""").find(head)?.groupValues?.get(1)
                ?: Regex("""A=\d+:([\w.]+)""").find(head)?.groupValues?.get(1)
                ?: return@forEachIndexed
            if (pkg.contains("launcher", true)) return@forEachIndexed
            // apps without their own launcher icon (e.g. a gallery opened from elsewhere) count too;
            // only system parts without a window of their own are left out
            if (pkg !in launchable && (pkg == "com.android.systemui" || pkg.startsWith("com.android.permissioncontroller") ||
                    runCatching { svc.packageManager.getApplicationInfo(pkg, 0) }.isFailure)) { skipped += pkg; return@forEachIndexed }
            if (res.none { it.pkg == pkg }) res += RecentApp(pkg, id)
        }
        diag = "Taskliste: ${starts.size} Tasks, ${res.size} Apps: ${res.joinToString { it.pkg }}" +
            (if (skipped.isNotEmpty()) "\nÜbergangen: ${skipped.distinct().joinToString()}" else "")
        return res
    }

    fun open(swipe: Boolean) {
        if (isOpen) return
        isOpen = true
        fromSwipe = swipe
        val front = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        fromPkg = if (swipe) svc.foregroundPkg?.takeIf { svc.appRecentlyInFront() }
            else if (front == null || front.contains("launcher", true)) null else svc.foregroundPkg
        // without root there is no task list: the usage history is the best guess (with root it would
        // bring back apps that are long closed)
        if (known.isEmpty() && !Root.available()) Apps.recentPackages(svc).filter { it != svc.packageName && !it.contains("launcher", true) && it in Apps.labels(svc).values }
            .take(12).forEach { p -> known += RecentApp(p, -1) }
        fromPkg?.let { touch(it) }
        val show = {
            if (isOpen) {
                apps = synchronized(this) { known.toList() }.let { l ->
                    val f = fromPkg
                    if (f != null && l.any { it.pkg == f }) listOf(l.first { it.pkg == f }) + l.filter { it.pkg != f } else l
                }
                sel = 0
                build()
                // complete the list with Android's tasks (ids for switching, apps opened elsewhere)
                refresh {
                    if (!isOpen) return@refresh
                    val cur = apps.getOrNull(sel)?.pkg
                    apps = synchronized(this) { known.toList() }.let { l ->
                        // keep the app in front first even if Android lists it differently
                        val f = fromPkg
                        if (f != null && l.firstOrNull()?.pkg != f && l.any { it.pkg == f }) listOf(l.first { it.pkg == f }) + l.filter { it.pkg != f } else l
                    }
                    sel = apps.indexOfFirst { it.pkg == cur }.takeIf { it >= 0 } ?: 0
                    carousel?.set(apps, sel, animate = false)
                    renderBar()
                }
            }
        }
        // the picture of the current app first (before Hugminn covers it)
        // Android's current list first (at most ~0.45 s), so no app that was closed elsewhere shows up
        var shown = false
        val once = { if (!shown) { shown = true; show() } }
        val afterList = {
            // swipe: Android's view is already on screen, cover it at once (the list follows right after)
            if (Root.available() && !swipe) {
                thread {
                    val list = loadTasks()
                    if (list != null) synchronized(this) {
                        known = list.toMutableList()
                        fromPkg?.let { f -> if (known.none { it.pkg == f }) fromPkg = null }
                    }
                    svc.ui.post { once() }
                }
                svc.ui.postDelayed({ once() }, 450)
            } else once()
        }
        val f = fromPkg
        // cover the app with a black screen so it pauses (and goes quiet) while the switcher is open
        val pauseIt = { if (isOpen && fromPkg != null && svc.prefs.pauseInSwitcher) PauseActivity.hold(svc) }
        if (f != null && !swipe) Snapshots.capture(svc, f) { pauseIt(); afterList() } else { pauseIt(); afterList() }
    }

    fun close(back: Boolean) {
        if (!isOpen) return
        isOpen = false
        closedAt = SystemClock.uptimeMillis()
        hat.stop()
        menu = null
        val r = root
        root = null
        carousel = null
        bar = null
        // removed at once: a fade-out left a ghost of the cards over the home screen
        r?.let { runCatching { wm.removeViewImmediate(it) } }
        val under = recentsUnder
        recentsUnder = false
        if (back && (fromSwipe || under)) {
            // Android's recents view is still underneath: back into the app, or home
            val f = fromPkg
            if (f != null) bring(apps.firstOrNull { it.pkg == f } ?: RecentApp(f, -1)) else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            PauseActivity.release(svc, 400)
        } else PauseActivity.release(svc, if (back) 0 else 500)
    }

    /** Brings an app (its existing task) to the front. */
    private fun bring(a: RecentApp) {
        var moved = false
        if (a.taskId > 0) moved = runCatching {
            svc.getSystemService(ActivityManager::class.java).moveTaskToFront(a.taskId, 0)
            true
        }.getOrDefault(false)
        val launch = svc.packageManager.getLaunchIntentForPackage(a.pkg)
        if (!moved) launch?.let { svc.startApp(it.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
        // check: if the app did not come up, ask the system shell (root) to start it
        svc.ui.postDelayed({
            if (svc.foregroundPkg != a.pkg && launch?.component != null && Root.available()) {
                Root.run("am start -n ${launch.component!!.flattenToShortString()} -f 0x10200000")
            }
        }, 450)
    }

    private fun openSelected() {
        val a = apps.getOrNull(sel) ?: return
        val same = !fromSwipe && !recentsUnder && a.pkg == fromPkg
        close(back = false)
        // the app is already underneath: just uncover it
        if (!same) bring(a) else PauseActivity.release(svc, 0)
    }

    private fun closeSelected() {
        val a = apps.getOrNull(sel) ?: return
        if (!Root.available()) {
            svc.startApp(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
            close(back = false)
            return
        }
        removeTask(a)
        Snapshots.remove(svc, a.pkg)
        synchronized(this) { known.removeAll { it.pkg == a.pkg } }
        if (a.pkg == fromPkg) fromPkg = null
        apps = apps.filter { it.pkg != a.pkg }
        sel = sel.coerceAtMost((apps.size - 1).coerceAtLeast(0))
        carousel?.set(apps, sel, animate = true)
        renderBar()
    }

    /** Android's recents view was opened underneath (to remove cards): leave it like after a swipe. */
    private var recentsUnder = false
    /** What removing apps did (for feedback reports). */
    @Volatile var removeDiag = ""

    private fun removeTaskCode(): Int? = runCatching {
        Class.forName("android.app.IActivityTaskManager\$Stub").getDeclaredField("TRANSACTION_removeTask").apply { isAccessible = true }.getInt(null)
    }.getOrNull()

    private fun stillListed(pkg: String) = loadTasks()?.any { it.pkg == pkg }

    /**
     * Ends the app and removes its card from Android's recents view as well: first through the system
     * (root), checked against Android's list; if the card is still there, Hugminn removes it in
     * Android's own view, hidden behind the switcher.
     */
    private fun removeTask(a: RecentApp) {
        thread {
            val id = a.taskId.takeIf { it > 0 } ?: loadTasks()?.firstOrNull { it.pkg == a.pkg }?.taskId ?: -1
            val log = StringBuilder("${a.pkg} (Task $id):")
            if (id > 0) {
                val code = removeTaskCode()
                if (code != null) log.append(" service call $code → ${Root.exec("service call activity_task $code i32 $id")}")
                else log.append(" kein Transaktionscode")
                if (stillListed(a.pkg) == true) log.append(" | am stack remove → ${Root.exec("am stack remove $id 2>&1")}")
            }
            Root.exec("am force-stop ${a.pkg}")
            val still = stillListed(a.pkg) == true
            log.append(if (still) " → noch in Androids Liste, entferne in Androids Ansicht" else " → entfernt")
            removeDiag = (removeDiag + "\n" + log).takeLast(1500)
            if (still) svc.ui.post { dismissInRecents(Apps.label(svc, a.pkg)) }
        }
    }

    /** Opens Android's recents view under the switcher and swipes the app's card away there. */
    private fun dismissInRecents(label: String) {
        if (!isOpen) return
        recentsUnder = true
        if (findPanel() == null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        var tries = 0
        val look = object : Runnable {
            override fun run() {
                if (!isOpen) return
                val card = findPanel()?.let { p -> (0 until p.childCount).mapNotNull { p.getChild(it) } }
                    ?.firstOrNull { it.contentDescription?.toString() == label }
                if (card != null) {
                    val dismiss = card.actionList.firstOrNull {
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id ||
                            it.label?.toString()?.contains(Regex("schließen|close|dismiss|entfernen", RegexOption.IGNORE_CASE)) == true
                    }
                    val ok = dismiss != null && card.performAction(dismiss.id)
                    removeDiag += "\nAndroids Ansicht: Karte \"$label\" ${if (ok) "entfernt" else "gefunden, aber nicht entfernbar (${card.actionList.joinToString { it.label?.toString() ?: it.id.toString() }})"}"
                } else if (++tries < 20) svc.ui.postDelayed(this, 100)
                else removeDiag += "\nAndroids Ansicht: Karte \"$label\" nicht gefunden"
            }
        }
        svc.ui.postDelayed(look, 250)
    }

    private fun findPanel(): AccessibilityNodeInfo? {
        for (r in svc.roots()) {
            r.findAccessibilityNodeInfosByViewId("${r.packageName}:id/overview_panel").firstOrNull()?.let { return it }
        }
        return null
    }

    private fun openMenu() {
        val a = apps.getOrNull(sel) ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "App-Info" to {
            close(back = false)
            svc.startApp(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
        }
        if (Root.available() && apps.size > 1) items += "Alle anderen schließen" to {
            apps.filter { it.pkg != a.pkg }.forEach { o ->
                removeTask(o)
                Snapshots.remove(svc, o.pkg)
                synchronized(this) { known.removeAll { it.pkg == o.pkg } }
            }
            apps = listOf(a)
            sel = 0
            carousel?.set(apps, sel, animate = true)
        }
        menu = items
        menuSel = 0
        renderBar()
    }

    // ---------- input ----------

    fun onKey(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) key(e.keyCode)
        return true
    }

    private fun key(code: Int) {
        val m = menu
        if (m != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> menuSel = (menuSel - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> menuSel = (menuSel + 1).coerceAtMost(m.lastIndex)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val run = m.getOrNull(menuSel)?.second
                    menu = null
                    renderBar()
                    run?.invoke()
                    return
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_Y -> menu = null
            }
            renderBar()
            return
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> move(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openSelected()
            KeyEvent.KEYCODE_BUTTON_X -> closeSelected()
            KeyEvent.KEYCODE_BUTTON_Y -> openMenu()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> close(back = true)
        }
    }

    private fun move(d: Int) {
        val n = (sel + d).coerceIn(0, (apps.size - 1).coerceAtLeast(0))
        if (n == sel) return
        sel = n
        carousel?.set(apps, sel, animate = true)
        renderBar()
    }

    // ---------- views ----------

    private fun build() {
        pal = Palette(svc)
        val m = wm.currentWindowMetrics
        val statusH = m.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top.takeIf { it > 0 } ?: svc.dp(24)
        val mode = svc.prefs.statusBar
        val r = object : FrameLayout(svc) {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = hat.onMotion(event) || super.dispatchGenericMotionEvent(event)
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) return false
                if (event.action == KeyEvent.ACTION_DOWN) runCatching { key(event.keyCode) }.onFailure { Crash.note(svc, it) }
                return true
            }
        }.apply {
            setBackgroundColor(pal.surface)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val top = if (mode == StatusBar.HIDDEN) 0 else statusH
        if (mode == StatusBar.OVERLAY) r.addView(StatusBand(svc, pal, "Muninn · Game Switcher"), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, statusH))
        val barH = svc.dp(76)
        val c = Carousel(svc, pal,
            onTap = { i -> if (i == sel) openSelected() else { sel = i; carousel?.set(apps, sel, true); renderBar() } },
            onScroll = { i -> sel = i; carousel?.set(apps, sel, true); renderBar() },
            onSwipeUp = { i -> sel = i; closeSelected() },
        )
        r.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = top
            bottomMargin = barH
        })
        val b = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(24), 0, svc.dp(10), 0)
        }
        r.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM))
        val sysTop = if (mode == StatusBar.SYSTEM) statusH else 0
        val lp = WindowManager.LayoutParams(
            m.bounds.width(), m.bounds.height() - sysTop,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = sysTop
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // opened by swipe: appear at once (Android's view is underneath); otherwise a very short fade
        r.alpha = if (fromSwipe) 1f else 0f
        wm.addView(r, lp)
        if (!fromSwipe) r.animate().alpha(1f).setDuration(90).start()
        r.requestFocus()
        root = r
        carousel = c
        bar = b
        c.set(apps, sel, animate = false)
        renderBar()
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Look.font(svc, Look.pixel(svc), bold)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun hint(key: Int, label: String) = LinearLayout(svc).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Glyphs.chip(svc, svc.prefs, pal, key, 24))
        addView(text(label, 13f, pal.onSurfaceVariant).apply { setPadding(svc.dp(7), 0, svc.dp(16), 0) })
    }

    private fun renderBar() {
        val b = bar ?: return
        b.removeAllViews()
        val feedback = !svc.prefs.githubToken.isNullOrBlank()
        val m = menu
        val a = apps.getOrNull(sel)
        if (m != null) {
            b.addView(text(a?.let { Apps.label(svc, it.pkg) } ?: "Optionen", 15f, pal.onSurface, bold = true).apply { setPadding(0, 0, svc.dp(16), 0) })
            val items = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            m.forEachIndexed { i, (label, _) ->
                val on = i == menuSel
                items.addView(text(label, 14f, if (on) pal.onPrimaryContainer else pal.onSurfaceVariant, bold = on).apply {
                    setPadding(svc.dp(16), svc.dp(9), svc.dp(16), svc.dp(9))
                    background = Look.shape(svc, Look.pixel(svc), if (on) pal.primaryContainer else pal.surfaceHigh, svc.dp(20).toFloat())
                    setOnClickListener { menuSel = i; key(KeyEvent.KEYCODE_BUTTON_A) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = svc.dp(8) })
            }
            b.addView(android.widget.HorizontalScrollView(svc).apply { isHorizontalScrollBarEnabled = false; addView(items) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            b.addView(hint(KeyEvent.KEYCODE_BUTTON_A, "Ausführen"))
            b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
            return
        }
        if (a == null) {
            b.addView(LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Keine offenen Apps", 17f, pal.onSurface, bold = true))
                addView(text("Starte ein Spiel, dann taucht es hier auf", 12f, pal.onSurfaceVariant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (feedback) b.addView(hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Feedback"))
            b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
            return
        }
        b.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, a.pkg)) }, LinearLayout.LayoutParams(svc.dp(40), svc.dp(40)))
        b.addView(LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(svc.dp(12), 0, 0, 0)
            addView(text(Apps.label(svc, a.pkg), 17f, pal.onSurface, bold = true))
            addView(text("${Apps.typeOf(a.pkg).label} · ${sel + 1} von ${apps.size}", 12f, pal.onSurfaceVariant))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (feedback) b.addView(hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Feedback"))
        if (apps.size > 1) b.addView(hint(KeyEvent.KEYCODE_DPAD_LEFT, "Wechseln"))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_A, "Öffnen"))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_X, "Schließen"))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_Y, "Optionen"))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück"))
    }

    /** The cards: the selected one large in the middle, the others smaller to the sides. */
    private class Carousel(
        ctx: Context,
        private val pal: Palette,
        private val onTap: (Int) -> Unit,
        private val onScroll: (Int) -> Unit,
        private val onSwipeUp: (Int) -> Unit,
    ) : View(ctx) {
        private var apps: List<RecentApp> = emptyList()
        private var pos = 0f
        private var anim: android.animation.ValueAnimator? = null
        private val style = Prefs(ctx).uiStyle
        private val pixel = style == UiStyle.PIXEL
        private val dots = style == UiStyle.DOTS
        private val radius = ctx.dp(if (dots) 10 else 18).toFloat()
        private val unit = ctx.dp(3).toFloat()
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surfaceHigh }
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            when {
                dots -> {
                    strokeWidth = ctx.dp(3).toFloat()
                    color = pal.onSurface
                    strokeCap = Paint.Cap.ROUND
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, ctx.dp(8).toFloat()), 0f)
                }
                pixel -> { isAntiAlias = false; strokeWidth = unit; color = pal.onSurface }
                else -> { strokeWidth = ctx.dp(2).toFloat(); color = (pal.onSurface and 0x00FFFFFF) or 0xAA000000.toInt() }
            }
        }
        private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.onSurfaceVariant
            textAlign = Paint.Align.CENTER
            textSize = ctx.resources.displayMetrics.scaledDensity * 13f
            typeface = Look.font(ctx, pixel, false)
        }
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.onSurfaceVariant
            textAlign = Paint.Align.CENTER
            textSize = ctx.resources.displayMetrics.scaledDensity * 20f
            typeface = Look.font(ctx, pixel, false)
        }
        private val icons = HashMap<String, android.graphics.drawable.Drawable?>()

        fun set(list: List<RecentApp>, selected: Int, animate: Boolean) {
            apps = list
            anim?.cancel()
            if (!animate) {
                pos = selected.toFloat()
                invalidate()
                return
            }
            anim = android.animation.ValueAnimator.ofFloat(pos, selected.toFloat()).apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator(2f)
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        private fun shape(r: RectF): Path = if (pixel) pixelPath(r, unit, 3) else Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }

        /** Card frame of item [i] for the current scroll position. */
        private fun frame(i: Int): Pair<RectF, Float> {
            val h = height * 0.70f
            val w = min(h * 16f / 9f, width * 0.56f)
            val ch = w * 9f / 16f
            val gap = context.dp(22).toFloat()
            val d = i - pos
            val ad = abs(d)
            val small = 0.66f
            val scale = if (ad <= 1f) 1f - (1f - small) * ad else small
            // distance of the centres: selected to neighbour, then neighbour to neighbour
            val first = w / 2f + gap + w * small / 2f
            val next = w * small + gap
            val off = if (ad <= 1f) ad * first else first + (ad - 1f) * next
            val cx = width / 2f + Math.signum(d) * off
            val cy = height * 0.47f
            return RectF(cx - w * scale / 2f, cy - ch * scale / 2f, cx + w * scale / 2f, cy + ch * scale / 2f) to scale
        }

        override fun onDraw(c: Canvas) {
            if (apps.isEmpty()) {
                c.drawText("Keine offenen Apps", width / 2f, height / 2f, emptyPaint)
                return
            }
            val order = apps.indices.sortedByDescending { abs(it - pos) }
            for (i in order) {
                val (r, scale) = frame(i)
                if (i == liftIndex && lift < 0f) r.offset(0f, lift)
                if (r.right < 0 || r.left > width) continue
                val path = shape(r)
                val shot = Snapshots.get(context, apps[i].pkg)
                c.save()
                c.clipPath(path)
                if (shot != null) {
                    // centre crop into the card
                    val sr = shot.width / shot.height.toFloat()
                    val rr = r.width() / r.height()
                    val src = if (sr > rr) {
                        val w = shot.height * rr
                        Rect(((shot.width - w) / 2).toInt(), 0, ((shot.width + w) / 2).toInt(), shot.height)
                    } else {
                        val h = shot.width / rr
                        Rect(0, ((shot.height - h) / 2).toInt(), shot.width, ((shot.height + h) / 2).toInt())
                    }
                    c.drawBitmap(shot, src, r, bmpPaint)
                } else {
                    c.drawRect(r, cardFill)
                    val icon = icons.getOrPut(apps[i].pkg) { Apps.icon(context, apps[i].pkg) }
                    val s = r.height() * 0.36f
                    icon?.setBounds((r.centerX() - s / 2).toInt(), (r.centerY() - s / 2).toInt(), (r.centerX() + s / 2).toInt(), (r.centerY() + s / 2).toInt())
                    icon?.draw(c)
                }
                // the cards at the side are dimmed
                val k = ((1f - scale) / (1f - 0.66f)).coerceIn(0f, 1f)
                if (k > 0f) {
                    dim.color = (pal.surface and 0x00FFFFFF) or ((k * 120).toInt() shl 24)
                    c.drawRect(r, dim)
                }
                c.restore()
                if (abs(i - pos) < 0.5f) c.drawPath(shape(r), edge)
                else {
                    // name under the card, only for the part of the card that is on screen
                    val pad = context.dp(12).toFloat()
                    val left = r.left.coerceAtLeast(pad)
                    val right = r.right.coerceAtMost(width - pad)
                    if (right - left > context.dp(40)) {
                        val label = TextUtils.ellipsize(Apps.label(context, apps[i].pkg), labelPaint, right - left, TextUtils.TruncateAt.END).toString()
                        c.drawText(label, (left + right) / 2f, r.bottom + labelPaint.textSize * 1.5f, labelPaint)
                    }
                }
            }
        }

        private var downX = 0f
        private var downY = 0f
        private var downPos = 0f
        private var mode = 0 // 0 undecided, 1 scrolling, 2 swiping a card up
        private var lift = 0f
        private var liftIndex = -1

        /** Distance between two neighbouring cards (for dragging). */
        private fun pitch(): Float {
            val h = height * 0.70f
            val w = min(h * 16f / 9f, width * 0.56f)
            return w * 0.66f + context.dp(22)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    anim?.cancel()
                    downX = e.x; downY = e.y; downPos = pos; mode = 0
                    liftIndex = apps.indices.firstOrNull { frame(it).first.contains(e.x, e.y) } ?: -1
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    val slop = context.dp(10)
                    if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) mode = if (abs(dy) > abs(dx) && dy < 0 && liftIndex >= 0) 2 else 1
                    if (mode == 1 && apps.isNotEmpty()) {
                        pos = (downPos - dx / pitch()).coerceIn(-0.3f, apps.size - 0.7f)
                        invalidate()
                    } else if (mode == 2) {
                        lift = dy.coerceAtMost(0f)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (mode) {
                        0 -> if (e.actionMasked == MotionEvent.ACTION_UP) apps.indices.firstOrNull { frame(it).first.contains(e.x, e.y) }?.let(onTap)
                        1 -> onScroll(Math.round(pos).coerceIn(0, (apps.size - 1).coerceAtLeast(0)))
                        2 -> {
                            val i = liftIndex
                            if (-lift > height * 0.18f && i >= 0) onSwipeUp(i)
                            lift = 0f
                            liftIndex = -1
                            invalidate()
                        }
                    }
                    mode = 0
                }
            }
            return true
        }
    }
}

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
    private var wm = svc.getSystemService(WindowManager::class.java)
    /** The main screen's window manager: the layout always goes by the main screen (see [Displays.fitted]). */
    private val mainWm = svc.getSystemService(WindowManager::class.java)
    /** What is in the window manager: the root itself, or on another screen a frame that scales it. */
    private var window: View? = null
    private var otherScreen = false
    private var root: FrameLayout? = null
    private var carousel: Carousel? = null
    private var bar: LinearLayout? = null
    /** Dual-screen handhelds: the second screen's window (name, hints, shortcuts) and its window manager. */
    private var lower: LinearLayout? = null
    private var lowerWm: WindowManager? = null
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

    private val hat = HatInput(svc.ui) { code -> runCatching { if (svc.comboMode) svc.comboKey(KeyEvent(KeyEvent.ACTION_DOWN, code)) else key(code) }.onFailure { Crash.note(svc, it) } }

    /** An app came to the front: it moves to the start of the list. */
    fun touch(pkg: String) {
        synchronized(this) {
            val id = known.firstOrNull { it.pkg == pkg }?.taskId ?: -1
            known.removeAll { it.pkg == pkg }
            known.add(0, RecentApp(pkg, id))
        }
    }

    fun taskOf(pkg: String): Int = synchronized(this) { known.firstOrNull { it.pkg == pkg }?.taskId ?: -1 }

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
        val out = Root.execLong("dumpsys activity recents | grep -E 'Recent #|realActivity=|cmp=|dat='") ?: run { diag = "Taskliste: keine Antwort"; return null }
        val starts = Regex("""\* Recent #\d+: Task\{\S+ #(\d+)[^\n]*""").findAll(out).toList()
        if (starts.isEmpty()) { diag = "Taskliste: nicht lesbar"; return null }
        val launchable = Apps.labels(svc).values.toSet()
        val res = mutableListOf<RecentApp>()
        val skipped = mutableListOf<String>()
        val data = mutableListOf<Pair<String, String?>>()
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
            if (Apps.isHome(svc, pkg)) return@forEachIndexed
            // Hugminn's helper screens (black pause screen, Wi-Fi password) are not apps to switch to;
            // Hugminn itself counts only with its settings open
            if (pkg == svc.packageName && !block.contains("MainActivity")) return@forEachIndexed
            // apps without their own launcher icon (e.g. a gallery opened from elsewhere) count too;
            // only system parts without a window of their own are left out
            if (pkg !in launchable && (pkg == "com.android.systemui" || pkg.startsWith("com.android.permissioncontroller") ||
                    runCatching { svc.packageManager.getApplicationInfo(pkg, 0) }.isFailure)) { skipped += pkg; return@forEachIndexed }
            if (res.none { it.pkg == pkg }) {
                res += RecentApp(pkg, id)
                data += pkg to Regex("""dat=(\S+)""").find(block)?.groupValues?.get(1)
            }
        }
        // the game titles need a few more root calls: in the background, the list is shown at once
        thread { runCatching { GameTitle.update(svc, data) }; svc.ui.post { renderTitle(); renderLower() } }
        diag = "Taskliste: ${starts.size} Tasks, ${res.size} Apps: ${res.joinToString { it.pkg }}" +
            (if (skipped.isNotEmpty()) "\nÜbergangen: ${skipped.distinct().joinToString()}" else "")
        return res
    }

    fun open(swipe: Boolean, display: Int = android.view.Display.DEFAULT_DISPLAY) {
        Displays.snapshot(svc, svc, "Switcher")
        if (isOpen) return
        wm = Displays.wmFor(svc, display)
        otherScreen = display != android.view.Display.DEFAULT_DISPLAY
        isOpen = true
        svc.overlayState()
        HugminnService.overlayOpened?.let { cb -> svc.ui.post { cb("switcher") } }
        fromSwipe = swipe
        val front = svc.windowPkg // from the window events: no waiting for the app in front
        fromPkg = if (swipe) svc.foregroundPkg?.takeIf { svc.appRecentlyInFront() }
            else if (front == null || Apps.isHome(svc, front)) null else svc.foregroundPkg
        // without root there is no task list: the usage history is the best guess (with root it would
        // bring back apps that are long closed)
        if (known.isEmpty() && !Root.available()) Apps.recentPackages(svc).filter { it != svc.packageName && !Apps.isHome(svc, it) && it in Apps.labels(svc).values }
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
        // music and videos keep playing: pausing them would only break the flow
        val pauseIt = { if (isOpen && fromPkg != null && svc.prefs.pauseInSwitcher && !Media.playsIn(svc, fromPkg)) PauseActivity.hold(svc, "switcher") }
        if (f != null && !swipe) Snapshots.capture(svc, f) { pauseIt(); afterList() } else { pauseIt(); afterList() }
    }

    /** Switcher + Control Center on two screens: the one the buttons work in is clear, the other one dimmed. */
    fun look(active: Boolean) {
        val r = root ?: return
        r.animate().cancel()
        r.alpha = 1f
        r.foreground = null
        // no extra frame: the active one shows its selection and the button hints, the other one neither
        carousel?.marked = active
        bar?.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    fun close(back: Boolean) {
        if (!isOpen) return
        isOpen = false
        svc.ui.post { svc.comboClosed() }
        svc.overlayState()
        closedAt = SystemClock.uptimeMillis()
        hat.stop()
        menu = null
        help = null
        val r = root
        root = null
        carousel = null
        titleView = null
        bar = null
        // removed at once: a fade-out left a ghost of the cards over the home screen
        (window ?: r)?.let { runCatching { wm.removeViewImmediate(it) } }
        window = null
        closeLower()
        val under = recentsUnder
        recentsUnder = false
        if (back && (fromSwipe || under)) {
            // Android's recents view is still underneath: back into the app, or home
            val f = fromPkg
            if (f != null) bring(apps.firstOrNull { it.pkg == f } ?: RecentApp(f, -1)) else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            PauseActivity.release(svc, "switcher", 400)
        } else PauseActivity.release(svc, "switcher", if (back) 0 else 500)
    }

    /** Brings an app (its existing task) to the front. */
    private fun bring(a: RecentApp) {
        // a paused player stays paused (Prime Video would start again by itself)
        thread { runCatching { Media.keepPaused(svc, a.pkg) } }
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
        if (!same) bring(a) else PauseActivity.release(svc, "switcher", 0)
    }

    private fun closeSelected() {
        val a = apps.getOrNull(sel) ?: return
        if (Hotkeys.isPinned(svc, a.pkg)) {
            Notice.show(svc, t("Angeheftet – lösen mit Y", "Pinned – unpin with Y"), corner = true)
            return
        }
        val helper = !Root.available() && Device.canWrite(svc)
        if (!Root.available() && !helper) {
            svc.startApp(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
            close(back = false)
            return
        }
        // Anbernic without root: its helper ends the app; the card leaves Hugminn's list
        if (helper) AnbernicHelper.stop(svc, a.pkg) else removeTask(a)
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
        if (Apps.typeOf(a.pkg) != AppType.APP && Hotkeys.list(svc, a.pkg).isNotEmpty())
            items += t("Tastenkürzel", "Shortcuts") to { showHelp(a.pkg) }
        val pinned = Hotkeys.isPinned(svc, a.pkg)
        items += (if (pinned) t("Lösen", "Unpin") else t("Anheften", "Pin")) to {
            Hotkeys.setPinned(svc, a.pkg, !pinned)
            Notice.show(svc, if (!pinned) t("Angeheftet – X schließt die App nicht mehr", "Pinned – X no longer closes the app") else t("Gelöst", "Unpinned"), corner = true)
            renderBar()
        }
        items += tr("App-Info") to {
            close(back = false)
            svc.startApp(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
        }
        if (Root.available() && apps.size > 1) items += tr("Alle anderen schließen") to {
            val keep = Hotkeys.pinned(svc)
            apps.filter { it.pkg != a.pkg && it.pkg !in keep }.forEach { o ->
                removeTask(o)
                Snapshots.remove(svc, o.pkg)
                synchronized(this) { known.removeAll { it.pkg == o.pkg } }
            }
            apps = apps.filter { it.pkg == a.pkg || it.pkg in keep }
            sel = apps.indexOfFirst { it.pkg == a.pkg }.coerceAtLeast(0)
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
        if (help != null) {
            if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_BUTTON_A) hideHelp()
            return
        }
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

    // ---------- shortcut help ----------

    private var help: View? = null

    /** A card with the emulator's shortcuts, drawn with the controller's button symbols. */
    private fun showHelp(pkg: String) {
        val r = root ?: return
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(svc.dp(24), svc.dp(18), svc.dp(24), svc.dp(16))
            background = Look.shape(svc, Look.pixel(svc), pal.surfaceHigh, svc.dp(24).toFloat())
            elevation = svc.dp(12).toFloat()
        }
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, pkg)) }, LinearLayout.LayoutParams(svc.dp(32), svc.dp(32)))
        head.addView(text(Apps.label(svc, pkg) + " · " + t("Tastenkürzel", "Shortcuts"), 18f, pal.onSurface, bold = true).apply { setPadding(svc.dp(12), 0, 0, 0) })
        card.addView(head)
        val rows = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; setPadding(0, svc.dp(12), 0, svc.dp(8)) }
        for ((act, keys) in Hotkeys.list(svc, pkg)) {
            val row = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, svc.dp(5), 0, svc.dp(5)) }
            val chips = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            keys.forEachIndexed { i, k ->
                if (i > 0) chips.addView(text(if (k == KeyEvent.KEYCODE_DPAD_RIGHT) "/" else "+", 14f, pal.onSurfaceVariant).apply { setPadding(svc.dp(6), 0, svc.dp(6), 0) })
                chips.addView(Glyphs.chip(svc, svc.prefs, pal, k, 26))
            }
            row.addView(chips, LinearLayout.LayoutParams(svc.dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(text(act.label, 15f, pal.onSurface), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rows.addView(row)
        }
        card.addView(rows)
        val note = if (Hotkeys.mode(svc, pkg) == Hotkeys.Mode.COMMON)
            t("Gängige Belegung (Select als Hotkey) – im Emulator kann sie anders eingestellt sein. Anpassen: Hugminn-Einstellungen → Tastenkürzel.",
                "Common layout (Select as hotkey) – the emulator may be set up differently. Change: Hugminn settings → Shortcuts.")
        else t("Eigene Belegung aus den Hugminn-Einstellungen.", "Own layout from Hugminn's settings.")
        card.addView(text(note, 12f, pal.onSurfaceVariant).apply { maxLines = 3 })
        card.addView(LinearLayout(svc).apply {
            gravity = Gravity.END; setPadding(0, svc.dp(10), 0, 0)
            addView(hint(KeyEvent.KEYCODE_BUTTON_B, tr("Zurück")))
        })
        val w = (r.width * 0.55f).toInt().coerceAtLeast(svc.dp(420))
        r.addView(card, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        help = card
    }

    private fun hideHelp() {
        help?.let { root?.removeView(it) }
        help = null
    }

    // ---------- views ----------

    private fun build() {
        pal = Palette(svc)
        val m = mainWm.currentWindowMetrics
        val statusH = m.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top.takeIf { it > 0 } ?: svc.dp(24)
        val mode = svc.prefs.statusBar
        val r = object : FrameLayout(svc) {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = hat.onMotion(event) || super.dispatchGenericMotionEvent(event)
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // left behind by a close that went wrong: remove it instead of swallowing keys
                if (!isOpen || root !== this) { runCatching { wm.removeViewImmediate(window ?: this) }; return false }
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) return false
                if (svc.comboMode) { svc.comboKey(Glyphs.logical(event)); return true }
                if (event.action == KeyEvent.ACTION_DOWN) runCatching { key(Glyphs.logical(event.keyCode)) }.onFailure { Crash.note(svc, it) }
                return true
            }
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) svc.comboFocus(cc = false)
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            background = Look.ground(svc, pal)
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
        // header like the Control Center's: "Muninn · Game Switcher", clock and battery on the right
        val headH = svc.dp(50)
        val head = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(28), svc.dp(14), svc.dp(28), 0)
        }
        head.addView(text("Muninn", 20f, 0xFF2F7DF0.toInt(), bold = true)) // blue, like its shortcut icon
        // on a picture the grey texts of the ground would drown: they take the full text colour there
        val picture = Backdrop.active(svc)
        val onGround = if (picture) pal.onSurface else pal.onSurfaceVariant
        head.addView(text("  ·  Game Switcher", 15f, onGround), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (mode != StatusBar.OVERLAY) {
            val bm = svc.getSystemService(android.os.BatteryManager::class.java)
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct >= 0) head.addView(text("$pct %", 14f, onGround).apply { setPadding(0, 0, svc.dp(14), 0) })
            head.addView(android.widget.TextClock(svc).apply {
                format24Hour = "HH:mm"
                format12Hour = "h:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(pal.onSurface)
                typeface = Look.font(svc, Look.pixel(svc), true)
            })
        }
        r.addView(head, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, headH, Gravity.TOP).apply { topMargin = top })
        // the game the selected emulator is running, above the cards
        val titleH = svc.dp(44)
        val tv = text("", 22f, pal.onSurface, bold = true).apply { gravity = Gravity.CENTER; setPadding(svc.dp(24), 0, svc.dp(24), 0) }
        r.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, titleH, Gravity.TOP).apply { topMargin = top + headH })
        titleView = tv
        r.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = top + headH + titleH
            bottomMargin = barH
        })
        val b = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(24), 0, svc.dp(10), 0)
        }
        if (picture) {
            // on a picture the bottom bar sits on a calm capsule, like the Control Center's hints
            b.background = Look.capsule(svc, pal)
            r.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, barH - svc.dp(14), Gravity.BOTTOM).apply {
                leftMargin = svc.dp(16); rightMargin = svc.dp(16); bottomMargin = svc.dp(8)
            })
        } else r.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM))
        val sysTop = if (mode == StatusBar.SYSTEM) statusH else 0
        val lp = WindowManager.LayoutParams(
            m.bounds.width(), m.bounds.height() - sysTop,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // music/video plays behind: the switcher does not take the focus, some players (Crunchyroll)
                // stop as soon as they lose it. The buttons still reach the switcher through Hugminn.
                (if (Media.playsIn(svc, fromPkg)) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = sysTop
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // opened by swipe: appear at once (Android's view is underneath); otherwise a very short fade
        r.alpha = if (fromSwipe) 1f else 0f
        val w: View = if (!otherScreen) r else Displays.fitted(svc, r, m.bounds.width(), m.bounds.height() - sysTop, wm, 0xFF000000.toInt()).also {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT; lp.height = WindowManager.LayoutParams.MATCH_PARENT; lp.y = 0
        }
        wm.addView(w, lp)
        window = w
        if (!fromSwipe) r.animate().alpha(1f).setDuration(90).start()
        r.requestFocus()
        root = r
        carousel = c
        if (svc.comboMode) svc.comboBuilt()
        bar = b
        // dual screen: name, hints and shortcuts move to the second screen, the cards get the room
        if (openLower()) {
            // room for the small hint capsule at the bottom; 16:9 a bit narrower, so the corner marks fit
            (c.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = svc.dp(58); c.layoutParams = it }
            c.wide = 0.60f
            c.tall = 0.84f
        }
        c.set(apps, sel, animate = false)
        renderBar()
    }

    // ---------- second screen (AYN Thor and other dual-screen handhelds) ----------

    /** Opens the window on the second screen; false if there is none (or it may not be used). */
    private fun openLower(): Boolean {
        // together with the Control Center the other screen is taken
        if (!svc.prefs.dualSwitcher || svc.comboMode) return false
        val info = Displays.second(svc) ?: return false
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(info.id) ?: return false
        return runCatching {
            // a display context of the accessibility service: its window manager may add overlays there
            val dwm = svc.createDisplayContext(display).getSystemService(WindowManager::class.java)
            val v = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.ground(svc, pal)
                setPadding(svc.dp(28), svc.dp(24), svc.dp(28), svc.dp(20))
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            dwm.addView(v, lp)
            lower = v
            lowerWm = dwm
            true
        }.onFailure { Crash.note(svc, it) }.getOrDefault(false)
    }

    private fun closeLower() {
        val v = lower ?: return
        lower = null
        runCatching { lowerWm?.removeViewImmediate(v) }
        lowerWm = null
    }

    /**
     * The second screen – "look up top, operate down here":
     * top: the app's icon, name, game and "2 of 3"; middle: the emulator's shortcuts; bottom: the pin, in a field of its own, only as big as it needs.
     * The button hints stay small on the top screen.
     */
    private fun renderLower() {
        val v = lower ?: return
        v.removeAllViews()
        val a = apps.getOrNull(sel)
        val m = menu
        val picture = Backdrop.active(svc)
        val onGround = if (picture) pal.onSurface else pal.onSurfaceVariant
        if (a == null) {
            v.addView(text("Muninn", 18f, 0xFF2F7DF0.toInt(), bold = true).apply { setPadding(0, 0, 0, svc.dp(14)) })
            v.addView(text(tr("Keine offenen Apps"), 24f, pal.onSurface, bold = true))
            v.addView(text(tr("Starte ein Spiel, dann taucht es hier auf"), 15f, onGround))
            v.addView(View(svc), LinearLayout.LayoutParams(1, 0, 1f))
            return
        }
        // --- top: the app
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, a.pkg)) }, LinearLayout.LayoutParams(svc.dp(64), svc.dp(64)))
        head.addView(LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(svc.dp(16), 0, 0, 0)
            addView(text(Apps.label(svc, a.pkg), 24f, pal.onSurface, bold = true))
            GameTitle.titles[a.pkg]?.let { addView(text(it, 16f, pal.onSurface)) }
            addView(text(t("${Apps.typeOf(a.pkg).label} · ${sel + 1} von ${apps.size}", "${Apps.typeOf(a.pkg).label} · ${sel + 1} of ${apps.size}"), 14f, onGround))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        v.addView(head)
        // --- middle: the emulator's shortcuts (music/video has its own player outside the switcher)
        val middle = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        if (m == null) {
            val emu = Apps.typeOf(a.pkg) != AppType.APP
            val keys = if (emu) runCatching { Hotkeys.list(svc, a.pkg) }.getOrDefault(emptyList()) else emptyList()
            if (keys.isNotEmpty()) middle.addView(shortcutCard(keys, onGround), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        v.addView(middle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = svc.dp(12); bottomMargin = svc.dp(12) })
        // --- bottom: the pin, a field of its own (tap to pin or unpin; Y → options does the same)
        val pinned = Hotkeys.isPinned(svc, a.pkg)
        val pin = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Look.shape(svc, Look.pixel(svc), if (pinned) pal.primaryContainer else pal.tile, svc.dp(18).toFloat(), if (pal.tileEdge != 0) svc.dp(1) else 0, pal.tileEdge)
            setPadding(svc.dp(14), svc.dp(8), svc.dp(16), svc.dp(8))
            addView(IconView(svc, IconView.Kind.PIN, 18).styled(pal, Look.pixel(svc)).apply { setColor(if (pinned) pal.onPrimaryContainer else pal.onSurface) },
                LinearLayout.LayoutParams(svc.dp(20), svc.dp(20)))
            addView(text(if (pinned) t("Angeheftet – bleibt im Switcher", "Pinned – stays in the switcher") else t("Anheften", "Pin"), 14f,
                if (pinned) pal.onPrimaryContainer else pal.onSurface, bold = pinned).apply { setPadding(svc.dp(10), 0, 0, 0) })
            setOnClickListener { Hotkeys.setPinned(svc, a.pkg, !pinned); renderBar() }
        }
        v.addView(pin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** The emulator's shortcuts as a card made like the tiles; two columns when there are many. */
    private fun shortcutCard(keys: List<Pair<Hotkeys.Act, IntArray>>, onGround: Int): View {
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.shape(svc, Look.pixel(svc), pal.tile, svc.dp(22).toFloat(), if (pal.tileEdge != 0) svc.dp(1) else 0, pal.tileEdge)
            setPadding(svc.dp(16), svc.dp(10), svc.dp(12), svc.dp(8))
        }
        card.addView(text(t("Tastenkürzel", "Shortcuts"), 15f, pal.onSurface, bold = true).apply { setPadding(0, 0, 0, svc.dp(4)) })
        val two = keys.size > 5
        val cols = LinearLayout(svc)
        val columns = if (two) listOf(keys.take((keys.size + 1) / 2), keys.drop((keys.size + 1) / 2)) else listOf(keys)
        columns.forEach { part ->
            val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            for ((act, combo) in part) {
                val row = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, svc.dp(2), svc.dp(6), svc.dp(2)) }
                val chips = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
                combo.forEachIndexed { i, k ->
                    if (i > 0) chips.addView(text(if (k == KeyEvent.KEYCODE_DPAD_RIGHT) "/" else "+", 12f, onGround).apply { setPadding(svc.dp(3), 0, svc.dp(3), 0) })
                    chips.addView(Glyphs.chip(svc, svc.prefs, pal, k, if (two) 20 else 22))
                }
                row.addView(chips, LinearLayout.LayoutParams(svc.dp(if (two) 96 else 130), LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(text(act.label, if (two) 12f else 14f, pal.onSurface).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                col.addView(row)
            }
            cols.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(cols)
        return card
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Look.font(svc, Look.pixel(svc), bold)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun hint(key: Int, label: String, small: Boolean = false) = LinearLayout(svc).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Glyphs.chip(svc, svc.prefs, pal, key, if (small) 20 else 24))
        // narrow 4:3 screens (Pocket Nova): smaller labels and gaps, so the app name keeps its room
        val w = svc.resources.displayMetrics.widthPixels / svc.resources.displayMetrics.density
        val narrow = small || w < 720
        // even narrower (Pocket Classic, 620 dp)
        val tiny = !small && w < 630
        addView(text(label, if (tiny) 11f else if (narrow) 12f else 13f, if (Backdrop.active(svc) || small) pal.onSurface else pal.onSurfaceVariant).apply {
            maxLines = 1
            setPadding(svc.dp(if (tiny) 4 else if (narrow) 5 else 7), 0, svc.dp(if (tiny) 9 else if (narrow) 12 else 16), 0)
        })
    }

    /** See-through capsule (glass look) for the hints on the top screen of dual-screen handhelds. */
    private fun glassCapsule(): android.graphics.drawable.Drawable {
        val fill = if (pal.tileStyle == TileStyle.GLASS) pal.tile
            else (pal.surface and 0x00FFFFFF) or ((if (pal.dark) 150 else 190) shl 24)
        val edge = if (pal.dark) android.graphics.Color.argb(70, 255, 255, 255) else android.graphics.Color.argb(110, 255, 255, 255)
        return rounded(fill, 999f, svc.dp(1), edge)
    }

    private var titleView: TextView? = null

    /** Shows the game of the selected card (if Hugminn could find out which one it is). */
    private fun renderTitle() {
        val tv = titleView ?: return
        val pkg = apps.getOrNull(sel)?.pkg
        val title = pkg?.let { GameTitle.titles[it] }
        tv.text = title ?: ""
        tv.visibility = if (title == null || menu != null) View.INVISIBLE else View.VISIBLE
    }

    private fun renderBar() {
        renderTitle()
        renderLower()
        val b = bar ?: return
        b.removeAllViews()
        // with a second screen the bar up here becomes a small see-through capsule with the button
        // hints only ("look up top, a small reminder"); the options menu still uses the full bar
        if (lower != null) {
            val compact = menu == null
            (b.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (compact) {
                    lp.width = FrameLayout.LayoutParams.WRAP_CONTENT; lp.height = svc.dp(42)
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.bottomMargin = svc.dp(10)
                } else {
                    lp.width = FrameLayout.LayoutParams.MATCH_PARENT; lp.height = svc.dp(62)
                    lp.gravity = Gravity.BOTTOM; lp.bottomMargin = svc.dp(8)
                }
                b.layoutParams = lp
            }
            b.background = glassCapsule()
            b.setPadding(svc.dp(if (compact) 16 else 24), 0, svc.dp(if (compact) 2 else 10), 0)
            if (compact) {
                val a = apps.getOrNull(sel)
                if (a == null) b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, tr("Zurück"), small = true))
                else {
                    if (apps.size > 1) b.addView(hint(KeyEvent.KEYCODE_DPAD_LEFT, tr("Wechseln"), small = true))
                    b.addView(hint(KeyEvent.KEYCODE_BUTTON_A, tr("Öffnen"), small = true))
                    b.addView(hint(KeyEvent.KEYCODE_BUTTON_X, tr("Schließen"), small = true))
                    b.addView(hint(KeyEvent.KEYCODE_BUTTON_Y, tr("Optionen"), small = true))
                    b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, tr("Zurück"), small = true))
                }
                return
            }
        }
        val m = menu
        val a = apps.getOrNull(sel)
        if (m != null) {
            b.addView(text(a?.let { Apps.label(svc, it.pkg) } ?: tr("Optionen"), 15f, pal.onSurface, bold = true).apply { setPadding(0, 0, svc.dp(16), 0) })
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
            b.addView(hint(KeyEvent.KEYCODE_BUTTON_A, tr("Ausführen")))
            b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, tr("Zurück")))
            return
        }
        if (a == null) {
            b.addView(LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(tr("Keine offenen Apps"), 17f, pal.onSurface, bold = true))
                addView(text(tr("Starte ein Spiel, dann taucht es hier auf"), 12f, pal.onSurfaceVariant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, tr("Zurück")))
            return
        }
        b.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, a.pkg)) }, LinearLayout.LayoutParams(svc.dp(40), svc.dp(40)))
        b.addView(LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(svc.dp(12), 0, 0, 0)
            // the name, and right next to it a pin in the style's look when the app is pinned
            addView(LinearLayout(svc).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(Apps.label(svc, a.pkg), 17f, pal.onSurface, bold = true).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f))
                if (Hotkeys.isPinned(svc, a.pkg)) addView(IconView(svc, IconView.Kind.PIN, 16).styled(pal, Look.pixel(svc)).apply { setColor(pal.primary) },
                    LinearLayout.LayoutParams(svc.dp(18), svc.dp(18)).apply { marginStart = svc.dp(8) })
            })
            addView(text(t("${Apps.typeOf(a.pkg).label} · ${sel + 1} von ${apps.size}", "${Apps.typeOf(a.pkg).label} · ${sel + 1} of ${apps.size}"), 12f, pal.onSurfaceVariant))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (apps.size > 1 && svc.resources.displayMetrics.let { it.widthPixels / it.density } >= 630) b.addView(hint(KeyEvent.KEYCODE_DPAD_LEFT, tr("Wechseln")))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_A, tr("Öffnen")))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_X, tr("Schließen")))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_Y, tr("Optionen")))
        b.addView(hint(KeyEvent.KEYCODE_BUTTON_B, tr("Zurück")))
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
        /** Switcher + Control Center: the selection only shows where the buttons are. */
        var marked = true
            set(v) { field = v; invalidate() }
        private var anim: android.animation.ValueAnimator? = null
        private val style = Prefs(ctx).uiStyle
        private val pixel = style == UiStyle.PIXEL
        private val dots = style == UiStyle.DOTS
        private val cocoon = style == UiStyle.COCOON
        private val radius = ctx.dp(if (dots) 10 else if (cocoon) 22 else 18).toFloat()
        /** Cocoon: every card sits in a white frame. */
        private val framePad = if (cocoon) ctx.dp(8).toFloat() else 0f
        private val unit = ctx.dp(3).toFloat()
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.tile }
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
                cocoon -> { strokeWidth = ctx.dp(4).toFloat(); color = pal.primary }
                else -> { strokeWidth = ctx.dp(2).toFloat(); color = (pal.onSurface and 0x00FFFFFF) or 0xAA000000.toInt() }
            }
        }
        private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (Backdrop.active(ctx)) pal.onSurface else pal.onSurfaceVariant
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
        /** Share of the width the selected card may take: more when the bar sits on a second screen. */
        var wide = 0.56f

        /** Corner brackets around the selected card (when chosen): made anew per card, so they settle in once. */
        private var corners: CornerFrame? = null
        private var cornersFor = -1
        override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === corners || super.verifyDrawable(who)

        private fun drawMark(c: Canvas, i: Int, o: RectF, r: Float) {
            if (!pal.focusCorners) return
            if (cornersFor != i || corners == null) {
                cornersFor = i
                corners = CornerFrame(if (cocoon) pal.primary else pal.onSurface, r, context.dp(4).toFloat()).also { it.callback = this }
            }
            val cf = corners ?: return
            cf.setBounds(o.left.toInt(), o.top.toInt(), o.right.toInt(), o.bottom.toInt())
            cf.draw(c)
        }

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
        /** Height share of the cards: taller when the bar sits on a second screen. */
        var tall = 0.70f

        /**
         * The picture inside a preview without black bars (letterbox/pillarbox), so a 4:3 or 8:7 game
         * gets a card in its own shape. Measured once per preview.
         */
        private val content = java.util.WeakHashMap<Bitmap, Rect>()

        private fun contentOf(b: Bitmap): Rect = content.getOrPut(b) {
            fun dark(c: Int) = (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) < 3 * 22
            // a line is a bar when (almost) every sample on it is near black
            fun rowDark(y: Int): Boolean { var n = 0; for (k in 0 until 24) if (dark(b.getPixel((b.width * (k + 0.5f) / 24).toInt(), y))) n++; return n >= 22 }
            fun colDark(x: Int): Boolean { var n = 0; for (k in 0 until 24) if (dark(b.getPixel(x, (b.height * (k + 0.5f) / 24).toInt()))) n++; return n >= 22 }
            runCatching {
                var l = 0; var r = b.width - 1; var t = 0; var btm = b.height - 1
                val maxX = (b.width * 0.3f).toInt(); val maxY = (b.height * 0.3f).toInt()
                while (l < maxX && colDark(l)) l++
                while (r > b.width - 1 - maxX && colDark(r)) r--
                while (t < maxY && rowDark(t)) t++
                while (btm > b.height - 1 - maxY && rowDark(btm)) btm--
                if (r - l < b.width / 3 || btm - t < b.height / 3) Rect(0, 0, b.width, b.height) else Rect(l, t, r + 1, btm + 1)
            }.getOrDefault(Rect(0, 0, b.width, b.height))
        }

        /** Shape of a card: the game's own (snapped to 1:1, 8:7, 4:3, 3:2 or 16:9), else 16:9. */
        private fun aspectOf(i: Int): Float {
            val shot = apps.getOrNull(i)?.let { Snapshots.get(context, it.pkg) } ?: return 16f / 9f
            val c = contentOf(shot)
            val a = (c.width().toFloat() / c.height().coerceAtLeast(1)).coerceIn(1f, 16f / 9f)
            return floatArrayOf(1f, 8f / 7f, 4f / 3f, 3f / 2f, 16f / 9f).minByOrNull { abs(it - a) }!!.let { if (abs(it - a) / it < 0.05f) it else a }
        }

        private fun frame(i: Int): Pair<RectF, Float> {
            val h = height * tall
            // the slot every card sits in: 16:9, as wide as allowed
            val w = min(h * 16f / 9f, width * wide)
            // the card in the game's own shape: narrower shapes may use the full height
            val a = aspectOf(i)
            val cw = min(w, h * a)
            val ch = cw / a
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
            return RectF(cx - cw * scale / 2f, cy - ch * scale / 2f, cx + cw * scale / 2f, cy + ch * scale / 2f) to scale
        }

        override fun onDraw(c: Canvas) {
            if (apps.isEmpty()) {
                c.drawText(tr("Keine offenen Apps"), width / 2f, height / 2f, emptyPaint)
                return
            }
            val order = apps.indices.sortedByDescending { abs(it - pos) }
            for (i in order) {
                val (r, scale) = frame(i)
                if (i == liftIndex && lift < 0f) r.offset(0f, lift)
                if (r.right < 0 || r.left > width) continue
                if (cocoon) {
                    val o = RectF(r).apply { inset(-framePad * scale, -framePad * scale) }
                    c.drawRoundRect(o, radius + framePad, radius + framePad, cardFill)
                }
                val path = shape(r)
                val shot = Snapshots.get(context, apps[i].pkg)
                c.save()
                c.clipPath(path)
                if (shot != null) {
                    // the picture without black bars, centre-cropped into the card
                    val cr = contentOf(shot)
                    val sr = cr.width() / cr.height().toFloat()
                    val rr = r.width() / r.height()
                    val src = if (sr > rr) {
                        val w = cr.height() * rr
                        Rect((cr.centerX() - w / 2).toInt(), cr.top, (cr.centerX() + w / 2).toInt(), cr.bottom)
                    } else {
                        val h = cr.width() / rr
                        Rect(cr.left, (cr.centerY() - h / 2).toInt(), cr.right, (cr.centerY() + h / 2).toInt())
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
                if (abs(i - pos) < 0.5f) {
                    if (!marked) {}
                    else if (pal.focusCorners) {
                        // corner brackets just outside the card (outside the white frame in Cocoon)
                        val g = context.dp(7).toFloat() + framePad
                        drawMark(c, i, RectF(r).apply { inset(-g, -g) }, radius + g)
                    } else if (cocoon) {
                        val o = RectF(r).apply { inset(-framePad - edge.strokeWidth / 2, -framePad - edge.strokeWidth / 2) }
                        c.drawRoundRect(o, radius + framePad + edge.strokeWidth / 2, radius + framePad + edge.strokeWidth / 2, edge)
                    } else c.drawPath(shape(r), edge)
                }
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
            val h = height * tall
            val w = min(h * 16f / 9f, width * wide)
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

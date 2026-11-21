package io.github.ninte86hl.hugminn

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Dual-screen handhelds (AYN Thor): while a music or video app is in front on the top screen
 * (playing or paused), the second screen shows it the whole time – cover, song and artist for
 * music, only the title for videos – with a progress bar to seek and touch buttons. Gone as soon
 * as the app leaves the top screen. The switcher's own second-screen view lies over it while open.
 *
 * The top screen's app is read from the windows of display 0 only: the Thor's lower screen runs
 * its own launcher (Cocoon), whose events must not count as "home".
 */
class DualMedia(private val svc: HugminnService) {

    companion object {
        /** For feedback reports. */
        @Volatile var diag = ""
    }

    /** The window: the player, and over it a black shade that falls after a while (battery, OLED). */
    private var view: FrameLayout? = null
    private var content: LinearLayout? = null
    private var shade: View? = null
    private var dark = false
    private val darkAfter = 20_000L
    private val goDark = Runnable { setDark(true) }
    private var wm: WindowManager? = null
    private var shownKey: String? = null
    private var running = false
    private var hasSecond: Boolean? = null
    @Volatile private var busy = false
    /** The last app seen on the top screen (kept while Hugminn's own windows lie over it). */
    private var top: String? = null

    private var play: IconView? = null
    private var pause: IconView? = null
    private var bar: View? = null
    private var rest: View? = null
    private var thumb: View? = null
    private var song: TextView? = null
    private var who: TextView? = null
    private var cover: ImageView? = null
    private var time: TextView? = null
    private var dragging = false

    fun start() {
        if (running) return
        running = true
        svc.ui.postDelayed(tick, 1500)
    }

    fun stop() {
        running = false
        svc.ui.removeCallbacks(tick)
        svc.ui.removeCallbacks(kickNow)
        hide()
    }

    /** A window changed: look again right away (a little later, so the window list has settled). */
    fun kick() {
        if (!running || hasSecond == false) return
        svc.ui.removeCallbacks(kickNow)
        svc.ui.postDelayed(kickNow, 120)
    }

    private val kickNow = Runnable { check() }

    private fun second(): Boolean = hasSecond ?: (Displays.second(svc) != null).also { hasSecond = it }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            svc.ui.removeCallbacks(this)
            svc.ui.postDelayed(this, if (view != null) 1000 else 1200)
        }
    }

    /** The app in front on the top screen, or null on the home screen. */
    private fun topApp(): String? = svc.topScreenApp()

    private fun check() {
        val awake = svc.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        if (!svc.prefs.dualMedia || !second() || !awake) { hide(); return }
        top = topApp()
        val fg = top
        if (fg == null) { hide(); diag = "oben: Startbildschirm"; return }
        if (busy) return
        busy = true
        thread {
            runCatching { Media.refresh(svc, prefer = fg) }
            busy = false
            svc.ui.post { if (running) update(fg) }
        }
    }

    private fun keyOf() = "${Media.app}|${Media.title}|${Media.art != null}"

    private fun update(fg: String) {
        val wanted = Media.title != null && Media.app == fg && top == fg
        diag = "oben=$fg · Sitzung=${Media.app} · spielt=${Media.playing} · sichtbar=$wanted" + action
        if (!wanted) { hide(); return }
        // the switcher's own second-screen view must stay on top while it is open
        if (view == null && (svc.anySwitcherOpen || !show())) return
        if (keyOf() != shownKey) { render(); setDark(false) } else if (!dark) refreshParts()
        focusBack(fg)
        youtubeControls(fg)
    }

    private fun show(): Boolean {
        val info = Displays.second(svc) ?: return false
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(info.id) ?: return false
        return runCatching {
            val dwm = svc.createDisplayContext(display).getSystemService(WindowManager::class.java)
            val c = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            val sh = View(svc).apply { setBackgroundColor(android.graphics.Color.BLACK); alpha = 0f }
            val v = object : FrameLayout(svc) {
                // every touch: light up again (the first touch on a dark screen only wakes it)
                override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        val wasDark = dark
                        setDark(false)
                        if (wasDark) { swallow = true; return true }
                        swallow = false
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP) this@DualMedia.top?.let { p -> svc.ui.postDelayed({ focusBack(p) }, 200) }
                    if (swallow) return true
                    super.dispatchTouchEvent(e)
                    return true
                }
            }
            v.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            v.addView(sh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            dwm.addView(v, lp)
            view = v
            content = c
            shade = sh
            dark = false
            wm = dwm
            shownKey = null
            svc.ui.removeCallbacks(goDark)
            svc.ui.postDelayed(goDark, darkAfter)
            true
        }.onFailure { Crash.note(svc, it) }.getOrDefault(false)
    }

    fun hide() {
        val v = view ?: return
        view = null
        content = null
        shade = null
        dark = false
        svc.ui.removeCallbacks(goDark)
        shownKey = null
        dragging = false
        runCatching { wm?.removeViewImmediate(v) }
        wm = null
    }

    private fun isVideo(pkg: String?) = pkg != null &&
        Regex("youtube|newpipe|vlc|video|player|netflix|twitch|plex|jellyfin|kodi|stremio|disney|prime|crunchyroll", RegexOption.IGNORE_CASE).containsMatchIn(pkg)

    private var swallow = false

    /** Dark: the lower screen goes black (OLED pixels off); a touch brings the player back. */
    private fun setDark(on: Boolean) {
        svc.ui.removeCallbacks(goDark)
        val sh = shade ?: return
        if (!on) svc.ui.postDelayed(goDark, darkAfter)
        if (on == dark) return
        dark = on
        sh.animate().cancel()
        sh.animate().alpha(if (on) 1f else 0f).setDuration(if (on) 900 else 160).start()
        if (!on) refreshParts()
    }

    // ---------- feel ----------

    /** A short click from the motor (works even with touch vibration off in Android). */
    private fun buzz(strong: Boolean = false) {
        runCatching {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31)
                svc.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") svc.getSystemService(android.os.Vibrator::class.java)
            vib?.vibrate(android.os.VibrationEffect.createPredefined(
                if (strong) android.os.VibrationEffect.EFFECT_HEAVY_CLICK else android.os.VibrationEffect.EFFECT_CLICK))
        }
    }

    // ---------- the view ----------

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Look.font(svc, Look.pixel(svc), bold)
        maxLines = lines
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun render() {
        val v = content ?: return
        v.removeAllViews()
        val pal = Palette(svc)
        val px = Look.pixel(svc)
        v.background = Look.ground(svc, pal)
        v.setPadding(svc.dp(28), svc.dp(20), svc.dp(28), svc.dp(24))
        v.gravity = Gravity.CENTER_HORIZONTAL
        val picture = Backdrop.active(svc)
        val onGround = if (picture) pal.onSurface else pal.onSurfaceVariant
        val pkg = Media.app
        val video = isVideo(pkg)
        shownKey = keyOf()

        // top: the app
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, pkg)) }, LinearLayout.LayoutParams(svc.dp(28), svc.dp(28)))
        head.addView(text(pkg?.let { Apps.label(svc, it) } ?: "", 15f, onGround).apply { gravity = Gravity.START; setPadding(svc.dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // test builds: a feedback report with screenshots of both screens, without asking for a text
        quickReport?.let { report ->
            head.addView(FrameLayout(svc).apply {
                background = rounded(pal.tile, svc.dp(20).toFloat(), if (pal.tileEdge != 0) svc.dp(1) else 0, pal.tileEdge)
                addView(IconView(svc, IconView.Kind.CAMERA, 18).styled(pal, px).apply { setColor(pal.onSurface) },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                setOnClickListener { buzz(strong = true); runCatching { report() }.onFailure { Crash.note(svc, it) } }
            }, LinearLayout.LayoutParams(svc.dp(40), svc.dp(40)).apply { marginEnd = svc.dp(10) })
        }
        // dark at once: the lower screen goes black, a touch brings it back
        head.addView(FrameLayout(svc).apply {
            background = rounded(pal.tile, svc.dp(20).toFloat(), if (pal.tileEdge != 0) svc.dp(1) else 0, pal.tileEdge)
            addView(IconView(svc, IconView.Kind.MOON, 18).styled(pal, px).apply { setColor(pal.onSurface) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { buzz(); svc.ui.postDelayed({ setDark(true) }, 120) }
        }, LinearLayout.LayoutParams(svc.dp(40), svc.dp(40)))
        v.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        v.addView(View(svc), LinearLayout.LayoutParams(1, 0, 1f))
        // middle: cover (music only), song and artist – or just the video's title
        val c = ImageView(svc).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, svc.dp(if (px) 4 else 20).toFloat()) }
            }
        }
        // music: square cover; video: the thumbnail in 16:9 (as in the Control Center)
        if (!video) v.addView(c, LinearLayout.LayoutParams(svc.dp(170), svc.dp(170)).apply { bottomMargin = svc.dp(16) })
        else if (Media.art != null) v.addView(c, LinearLayout.LayoutParams(svc.dp(304), svc.dp(171)).apply { bottomMargin = svc.dp(14) })
        val s = text("", if (video) 20f else 21f, pal.onSurface, bold = true, lines = if (video && Media.art == null) 3 else 2)
        val w = text("", 15f, onGround)
        v.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (!video) v.addView(w, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = svc.dp(4) })
        v.addView(View(svc), LinearLayout.LayoutParams(1, 0, 1f))

        // progress in the accent colour – touch or drag it to jump
        val seek = FrameLayout(svc)
        val track = LinearLayout(svc).apply { background = rounded(pal.surfaceHighest, svc.dp(3).toFloat()) }
        val done = View(svc).apply { background = rounded(pal.primary, svc.dp(3).toFloat()) }
        val left = View(svc)
        track.addView(done, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f))
        track.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        seek.addView(track, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, svc.dp(6), Gravity.CENTER_VERTICAL))
        val knob = View(svc).apply { background = rounded(pal.primary, svc.dp(9).toFloat(), svc.dp(2), pal.onPrimary) }
        seek.addView(knob, FrameLayout.LayoutParams(svc.dp(18), svc.dp(18), Gravity.CENTER_VERTICAL or Gravity.START))
        v.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, svc.dp(36)))
        val tm = text("", 13f, onGround).apply { gravity = Gravity.END }
        v.addView(tm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        seek.setOnTouchListener { sv, e ->
            val dur = Media.duration
            if (dur <= 0 || !Media.canSeek) return@setOnTouchListener false
            sv.parent?.requestDisallowInterceptTouchEvent(true)
            val f = (e.x / sv.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; buzz(); showProgress(f, (f * dur).toLong(), dur) }
                MotionEvent.ACTION_MOVE -> showProgress(f, (f * dur).toLong(), dur)
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    val ms = (f * dur).toLong()
                    showProgress(f, ms, dur)
                    buzz(strong = true)
                    pressedAt = SystemClock.uptimeMillis()
                    Media.app?.let { p -> thread { Media.seek(svc, p, ms) } }
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; refreshParts() }
            }
            true
        }

        // buttons, big enough for a finger
        val ctrl = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        fun btn(code: Int, main: Boolean): FrameLayout {
            val size = if (main) 72 else 58
            val f = FrameLayout(svc).apply {
                background = if (main) rounded(pal.primary, svc.dp(size / 2).toFloat()) else rounded(pal.tile, svc.dp(size / 2).toFloat(), if (pal.tileEdge != 0) svc.dp(1) else 0, pal.tileEdge)
                setOnClickListener { press(code) }
            }
            ctrl.addView(f, LinearLayout.LayoutParams(svc.dp(size), svc.dp(size)).apply { marginStart = svc.dp(14); marginEnd = svc.dp(14) })
            return f
        }
        val center = { FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER) }
        btn(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false).addView(IconView(svc, IconView.Kind.PREV, 24).styled(pal, px).apply { setColor(pal.onSurface) }, center())
        val pb = btn(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
        val pl = IconView(svc, IconView.Kind.PLAY, 28).styled(pal, px).apply { setColor(pal.onPrimary) }
        val pa = IconView(svc, IconView.Kind.PAUSE, 28).styled(pal, px).apply { setColor(pal.onPrimary) }
        pb.addView(pl, center()); pb.addView(pa, center())
        btn(KeyEvent.KEYCODE_MEDIA_NEXT, false).addView(IconView(svc, IconView.Kind.NEXT, 24).styled(pal, px).apply { setColor(pal.onSurface) }, center())
        v.addView(ctrl, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = svc.dp(12) })
        // YouTube: playlist and full screen, worked up top through YouTube's own buttons
        if (pkg?.contains("youtube") == true) {
            val extra = LinearLayout(svc).apply { gravity = Gravity.CENTER }
            fun small(kind: IconView.Kind, label: String, act: () -> Unit) {
                extra.addView(LinearLayout(svc).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(pal.tile, svc.dp(18).toFloat(), if (pal.tileEdge != 0) svc.dp(1) else 0, pal.tileEdge)
                    setPadding(svc.dp(14), svc.dp(8), svc.dp(16), svc.dp(8))
                    addView(IconView(svc, kind, 18).styled(pal, px).apply { setColor(pal.onSurface) }, LinearLayout.LayoutParams(svc.dp(20), svc.dp(20)))
                    addView(text(label, 14f, pal.onSurface).apply { setPadding(svc.dp(8), 0, 0, 0) })
                    setOnClickListener { buzz(); pressedAt = SystemClock.uptimeMillis(); thread { runCatching { act() }.onFailure { Crash.note(svc, it) } } }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = svc.dp(8); marginEnd = svc.dp(8) })
            }
            small(IconView.Kind.LIST, t("Wiedergabeliste", "Playlist")) { ytPlaylist(pkg) }
            small(IconView.Kind.EXPAND, t("Vollbild", "Full screen")) { ytFullscreen(pkg) }
            v.addView(extra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = svc.dp(14) })
        }

        play = pl; pause = pa; bar = done; rest = left; thumb = knob; song = s; who = w; cover = c; time = tm
        refreshParts()
        // the knob needs the bar's width: place it once laid out
        seek.post { refreshParts() }
    }

    private fun press(code: Int) {
        buzz()
        pressedAt = SystemClock.uptimeMillis()
        Media.key(svc, code)
        svc.ui.postDelayed({ thread { runCatching { Media.refresh(svc, prefer = top) }; svc.ui.post { if (view != null) refreshParts() } } }, 350)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun showProgress(f: Float, pos: Long, dur: Long) {
        (bar?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = f; bar?.layoutParams = it }
        (rest?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 1f - f; rest?.layoutParams = it }
        thumb?.let { k ->
            val parent = k.parent as? View
            val w = (parent?.width ?: 0) - k.width
            k.translationX = if (w > 0) f * w else 0f
            // the knob only where jumping works; without a length no bar at all
            k.visibility = if (dur > 0 && Media.canSeek) View.VISIBLE else View.INVISIBLE
            parent?.visibility = if (dur > 0) View.VISIBLE else View.INVISIBLE
        }
        time?.text = if (dur > 0 && pos >= 0) "${clock(pos.coerceAtMost(dur))} / ${clock(dur)}" else ""
    }

    /** The parts that move: play/pause, progress, time, title, cover. */
    private fun refreshParts() {
        if (view == null || dark) return
        play?.visibility = if (Media.playing) View.GONE else View.VISIBLE
        pause?.visibility = if (Media.playing) View.VISIBLE else View.GONE
        song?.text = Media.song ?: Media.title ?: ""
        who?.text = Media.artist?.takeIf { Media.song != null } ?: ""
        val art = Media.art
        if (art != null) cover?.setImageBitmap(art) else cover?.setImageDrawable(Apps.icon(svc, Media.app))
        if (dragging) return
        val dur = Media.duration
        val pos = Media.positionNow()
        val f = if (dur > 0 && pos >= 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        showProgress(f, pos, dur)
    }

    // ---------- focus: buttons belong to the top screen ----------

    private var focusAt = 0L

    /**
     * A touch on the lower screen gives Android's focus to that screen (to Cocoon underneath): the
     * controller would then drive Cocoon, and the app up top loses its focus (YouTube then keeps its
     * controls up). While the player covers the lower screen, the top app gets the focus back.
     */
    private fun focusBack(pkg: String) {
        if (android.os.Build.VERSION.SDK_INT < 30 || view == null) return
        val all = runCatching { svc.windowsOnAllDisplays }.getOrNull() ?: return
        // the top app still active (it gets the buttons): nothing to do – each screen keeps a focused
        // window of its own on the Thor, so "focused" on the lower screen says nothing
        val topApp = all.get(Display.DEFAULT_DISPLAY)?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return
        if (topApp.isActive) return
        val lowerActive = (0 until all.size()).any { k -> all.keyAt(k) != Display.DEFAULT_DISPLAY && all.valueAt(k).any { it.isActive } }
        if (!lowerActive) return
        val now = SystemClock.uptimeMillis()
        if (now - focusAt < 1500) return
        focusAt = now
        val task = svc.taskOf(pkg)
        val moved = task > 0 && runCatching {
            svc.getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(task, 0)
            true
        }.getOrDefault(false)
        if (!moved) svc.packageManager.getLaunchIntentForPackage(pkg)?.let {
            svc.startApp(it.addFlags(android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        diag += " · Fokus zurück nach oben (${if (moved) "Task $task" else "Start"})"
    }

    // ---------- YouTube remote: playlist and full screen ----------

    private fun ytRoot(): AccessibilityNodeInfo? = runCatching {
        svc.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY)?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
    }.getOrNull()

    private fun ytNode(root: AccessibilityNodeInfo, pkg: String, id: String): AccessibilityNodeInfo? =
        runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") }.getOrNull()?.firstOrNull()

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    /** Clicks [n] or the first clickable parent. */
    private fun click(n: AccessibilityNodeInfo?): Boolean {
        var c = n
        var i = 0
        while (c != null && i++ < 6) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
        }
        return false
    }

    private fun tap(x: Float, y: Float) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 40)).build()
        svc.ui.post { runCatching { svc.dispatchGesture(g, null, null) } }
    }

    /** YouTube's controls up (a tap on an empty spot of the video), so its buttons can be pressed. */
    private fun ytControlsUp(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo {
        val btn = ytNode(root, pkg, "player_control_play_pause_replay_button")
        if (btn?.isVisibleToUser == true) return root
        val player = ytNode(root, pkg, "watch_player") ?: ytNode(root, pkg, "player_view") ?: return root
        val r = bounds(player)
        tap(r.left + r.width() * 0.08f, r.top + r.height() * 0.4f)
        Thread.sleep(450)
        return ytRoot() ?: root
    }

    /** The playlist panel (right side) when it is open. */
    private fun ytPanel(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? =
        ytNode(root, pkg, "fullscreen_engagement_panel_holder")?.takeIf { it.isVisibleToUser && it.childCount > 0 && bounds(it).width() > 0 }

    private fun ytClosePanel(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val panel = ytPanel(root, pkg) ?: return false
        val pr = bounds(panel)
        val close = listOf("Schließen", "Close").flatMap { runCatching { root.findAccessibilityNodeInfosByText(it) }.getOrNull().orEmpty() }
            .filter { it.isVisibleToUser && pr.contains(bounds(it)) }
            .minByOrNull { bounds(it).top }
        if (click(close)) return true
        // no button found: Back closes the panel in YouTube
        return svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun ytPlaylist(pkg: String) {
        var root = ytRoot() ?: return
        if (ytClosePanel(root, pkg)) { action = " · Wiedergabeliste zu"; return }
        root = ytControlsUp(root, pkg)
        // the playlist's own button: its name says "playlist" / "Wiedergabeliste" / "Mix"
        val words = listOf("Wiedergabeliste", "Playlist", "Mix")
        val player = ytNode(root, pkg, "watch_player")?.let { bounds(it) }
        val hit = words.flatMap { runCatching { root.findAccessibilityNodeInfosByText(it) }.getOrNull().orEmpty() }
            .firstOrNull { it.isVisibleToUser && (player == null || player.contains(bounds(it))) }
        action = if (click(hit)) " · Wiedergabeliste auf" else " · Wiedergabeliste: kein Knopf gefunden"
    }

    private fun ytFullscreen(pkg: String) {
        var root = ytRoot() ?: return
        // full screen closes the playlist first
        if (ytClosePanel(root, pkg)) { Thread.sleep(400); root = ytRoot() ?: return }
        root = ytControlsUp(root, pkg)
        val fs = ytNode(root, pkg, "fullscreen_button")
        action = if (click(fs)) " · Vollbild umgeschaltet (${fs?.contentDescription})" else " · Vollbild: kein Knopf gefunden"
    }

    // ---------- YouTube: its controls stay on screen ----------

    private var pressedAt = 0L
    /** The last thing done up top in YouTube (for feedback reports). */
    @Volatile private var action = ""

    /** Feedback exists only in test builds: found by name, so release builds simply have no button. */
    private val quickReport: (() -> Unit)? by lazy {
        runCatching {
            val c = Class.forName("io.github.ninte86hl.hugminn.Feedback")
            val inst = c.getField("INSTANCE").get(null)
            val m = c.getMethod("capture", HugminnService::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            val go: () -> Unit = { m.invoke(inst, svc, "Player unten", true) }
            go
        }.getOrNull()
    }
    private var ytIdsFor: String? = null
    private var ytIds = ""

    /** For feedback: the view names of YouTube's visible player parts (to find its controls). */
    private fun playerIds(root: AccessibilityNodeInfo): String {
        val found = sortedSetOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || found.size > 40) return
            val id = n.viewIdResourceName
            if (id != null && n.isVisibleToUser && Regex("player|control|play|pause|overlay", RegexOption.IGNORE_CASE).containsMatchIn(id)) found += id.substringAfter(":id/")
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        runCatching { walk(root, 0) }
        return found.joinToString(",")
    }
    private var controlsSince = 0L

    /**
     * YouTube leaves its controls over the video while it is controlled from the other screen.
     * When they have stood there for a few seconds while the video plays, one tap on an empty spot
     * of the player puts them away, as a finger would.
     */
    private fun youtubeControls(fg: String) {
        if (!fg.contains("youtube") || !Media.playing || android.os.Build.VERSION.SDK_INT < 30) { controlsSince = 0; return }
        val root = runCatching {
            svc.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY)?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
        }.getOrNull() ?: return
        val btn = listOf("player_control_play_pause_replay_button", "player_control_play_pause_button", "play_pause_button").firstNotNullOfOrNull { id ->
            runCatching { root.findAccessibilityNodeInfosByViewId("$fg:id/$id") }.getOrNull()?.firstOrNull { it.isVisibleToUser }
        }
        if (btn == null && ytIdsFor != Media.title) { ytIdsFor = Media.title; ytIds = playerIds(root) }
        if (ytIds.isNotEmpty()) diag += " · YouTube-IDs: $ytIds"
        diag += " · YouTube-Tasten sichtbar=${btn != null}"
        if (btn == null) { controlsSince = 0; return }
        val now = SystemClock.uptimeMillis()
        if (controlsSince == 0L) { controlsSince = now; return }
        if (now - controlsSince < 3500 || now - pressedAt < 3000) return
        // YouTube's own "hide controls" button (the × up top) first
        val hide = runCatching { root.findAccessibilityNodeInfosByViewId("$fg:id/hide_controls_button_top") }.getOrNull()?.firstOrNull { it.isVisibleToUser }
        if (hide != null && hide.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            controlsSince = 0
            action = " · YouTube-Tasten ausgeblendet (×)"
            return
        }
        // the player: the first parent clearly bigger than the play button
        val b = Rect().also { btn.getBoundsInScreen(it) }
        var area = Rect(b)
        var n: AccessibilityNodeInfo? = btn.parent
        while (n != null) {
            val r = Rect().also { n!!.getBoundsInScreen(it) }
            if (r.width() >= b.width() * 4 && r.height() >= b.height() * 2) { area = r; break }
            n = n.parent
        }
        if (area.width() < b.width() * 4) return
        // left edge, a bit above the middle: no button there (a single tap only toggles the controls)
        val x = area.left + area.width() * 0.08f
        val y = area.top + area.height() * 0.4f
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 40)).build()
        controlsSince = 0
        action = " · YouTube-Tasten weggetippt bei ${x.toInt()},${y.toInt()}"
        runCatching { svc.dispatchGesture(g, null, null) }
    }
}

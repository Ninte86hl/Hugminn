package io.github.ninte86hl.hugminn

import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Dual-screen handhelds (AYN Thor): while a music or video app is in front on the top screen,
 * the second screen shows what plays – cover, song and artist for music, only the title for
 * videos – with progress and touch buttons. Gone when the app leaves the front or nothing is
 * left to play. The switcher's own second-screen view lies over it while it is open.
 */
class DualMedia(private val svc: HugminnService) {

    private var view: LinearLayout? = null
    private var wm: WindowManager? = null
    private var shownKey: String? = null
    /** Closed by hand (×): stays away until something else plays. */
    private var dismissed: String? = null
    private var running = false
    private var hasSecond: Boolean? = null

    private var play: IconView? = null
    private var pause: IconView? = null
    private var bar: View? = null
    private var rest: View? = null
    private var song: TextView? = null
    private var who: TextView? = null
    private var cover: ImageView? = null
    private var time: TextView? = null

    fun start() {
        if (running) return
        running = true
        svc.ui.postDelayed(tick, 3000)
    }

    fun stop() {
        running = false
        svc.ui.removeCallbacks(tick)
        hide()
    }

    private fun second(): Boolean = hasSecond ?: (Displays.second(svc) != null).also { hasSecond = it }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val awake = svc.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
            val fg = svc.foregroundPkg
            if (!svc.prefs.dualMedia || !second() || !awake || svc.homeInFront || fg == null) {
                hide()
                svc.ui.postDelayed(this, 2000)
                return
            }
            thread {
                runCatching { Media.refresh(svc) }
                svc.ui.post { if (running) update(fg) }
            }
            svc.ui.postDelayed(this, if (view != null) 1000 else 2000)
        }
    }

    private fun keyOf() = "${Media.app}|${Media.title}"

    private fun update(fg: String) {
        // the app in front is the one with the music/video (playing or paused)
        val wanted = Media.title != null && Media.app == fg && svc.foregroundPkg == fg && !svc.homeInFront
        if (!wanted) { hide(); return }
        val key = keyOf()
        if (dismissed != null && dismissed != key) dismissed = null
        if (dismissed == key) { hide(); return }
        // the switcher's own second-screen view must stay on top while it is open
        if (view == null && (svc.anySwitcherOpen || !show())) return
        if (key + "|" + Media.art?.generationId != shownKey) render() else refreshParts()
    }

    private fun show(): Boolean {
        val info = Displays.second(svc) ?: return false
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(info.id) ?: return false
        return runCatching {
            val dwm = svc.createDisplayContext(display).getSystemService(WindowManager::class.java)
            val v = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            dwm.addView(v, lp)
            view = v
            wm = dwm
            shownKey = null
            true
        }.onFailure { Crash.note(svc, it) }.getOrDefault(false)
    }

    fun hide() {
        val v = view ?: return
        view = null
        shownKey = null
        runCatching { wm?.removeViewImmediate(v) }
        wm = null
    }

    private fun isVideo(pkg: String?) = pkg != null &&
        Regex("youtube|newpipe|vlc|video|player|netflix|twitch|plex|jellyfin|kodi|stremio|disney|prime|crunchyroll", RegexOption.IGNORE_CASE).containsMatchIn(pkg)

    private fun text(s: String, sp: Float, color: Int, pal: Palette, bold: Boolean = false, lines: Int = 1) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Look.font(svc, Look.pixel(svc), bold)
        maxLines = lines
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun render() {
        val v = view ?: return
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
        shownKey = keyOf() + "|" + Media.art?.generationId

        // top: the app, and × to put the player away (until something else plays)
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(svc).apply { setImageDrawable(Apps.icon(svc, pkg)) }, LinearLayout.LayoutParams(svc.dp(28), svc.dp(28)))
        head.addView(text(pkg?.let { Apps.label(svc, it) } ?: "", 15f, onGround, pal).apply { gravity = Gravity.START; setPadding(svc.dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text("×", 26f, onGround, pal).apply {
            setPadding(svc.dp(14), 0, svc.dp(4), 0)
            setOnClickListener { dismissed = keyOf(); hide() }
        })
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
        if (!video) v.addView(c, LinearLayout.LayoutParams(svc.dp(170), svc.dp(170)).apply { bottomMargin = svc.dp(16) })
        val s = text("", if (video) 22f else 21f, pal.onSurface, pal, bold = true, lines = if (video) 3 else 2)
        val w = text("", 15f, onGround, pal)
        v.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (!video) v.addView(w, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = svc.dp(4) })
        v.addView(View(svc), LinearLayout.LayoutParams(1, 0, 1f))

        // progress in the accent colour, with the time
        val track = LinearLayout(svc).apply { background = rounded(pal.surfaceHighest, svc.dp(3).toFloat()) }
        val done = View(svc).apply { background = rounded(pal.primary, svc.dp(3).toFloat()) }
        val left = View(svc)
        track.addView(done, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f))
        track.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        v.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, svc.dp(6)))
        val tm = text("", 12f, onGround, pal).apply { gravity = Gravity.END }
        v.addView(tm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = svc.dp(4) })

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
        v.addView(ctrl, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = svc.dp(14) })

        play = pl; pause = pa; bar = done; rest = left; song = s; who = w; cover = c; time = tm
        refreshParts()
    }

    private fun press(code: Int) {
        Media.key(svc, code)
        svc.ui.postDelayed({ thread { runCatching { Media.refresh(svc) }; svc.ui.post { if (view != null) refreshParts() } } }, 350)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The parts that move: play/pause, progress, time, title, cover. */
    private fun refreshParts() {
        if (view == null) return
        play?.visibility = if (Media.playing) View.GONE else View.VISIBLE
        pause?.visibility = if (Media.playing) View.VISIBLE else View.GONE
        song?.text = Media.song ?: Media.title ?: ""
        who?.text = Media.artist?.takeIf { Media.song != null } ?: ""
        val art = Media.art
        if (art != null) cover?.setImageBitmap(art) else cover?.setImageDrawable(Apps.icon(svc, Media.app))
        val dur = Media.duration
        val pos = Media.positionNow()
        val f = if (dur > 0 && pos >= 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        (bar?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = f; bar?.layoutParams = it }
        (rest?.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = 1f - f; rest?.layoutParams = it }
        time?.text = if (dur > 0 && pos >= 0) "${clock(pos.coerceAtMost(dur))} / ${clock(dur)}" else ""
    }
}

package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.widget.Toast
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import kotlin.concurrent.thread
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Huginn, the Control Center: a full window over the running game with quick tiles (most open a
 * small menu), brightness and volume, and live CPU/GPU/RAM/battery graphs.
 * Controller: D-pad moves, A chooses, left/right change sliders, B goes back/closes.
 */
class ControlCenter(private val svc: HugminnService) {
    private class Item(val view: View, val onA: (() -> Unit)?, val onAdjust: ((Int) -> Unit)? = null, val vertical: Boolean = false, val onY: (() -> Unit)? = null, val round: Boolean = false, val rows: Rows? = null)

    /** The lines of a large list card (networks, devices) that A steps into and the D-pad walks. */
    private class Rows { val list = mutableListOf<Pair<View, () -> Unit>>(); val keys = mutableListOf<String>() }
    private class MenuEntry(val label: String, val checked: Boolean = false, val hint: String? = null, val swatch: Int = 0, val header: Boolean = false, val side: ((Int) -> Unit)? = null, val run: (() -> Unit)?)

    var isOpen = false
        private set
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val audio = svc.getSystemService(AudioManager::class.java)
    private lateinit var pal: Palette
    private var root: FrameLayout? = null
    private val items = mutableListOf<Item>()
    private var focus = 0
    /** No element is highlighted until the D-pad (or A) is used for the first time. */
    private var focusVisible = false
    /** A slider was confirmed with A: the D-pad now changes its value instead of moving. */
    private var adjusting = false
    /** The large list card the D-pad is inside of (A entered it), and the selected line. */
    private var inside: Rows? = null
    private var insideSel = 0
    /** Name of the selected line, so a list that re-sorts itself (new scan) keeps the same network/device. */
    private var insideKey: String? = null
    private var metrics: Metrics? = null
    private val refreshers = mutableListOf<() -> Unit>()
    private val graphUpdates = mutableListOf<(Metrics.Sample) -> Unit>()
    /** Last measured values: a rebuilt panel shows them at once instead of empty cards for a second. */
    private var lastSample: Metrics.Sample? = null

    // Open sub menu (null = none)
    private var menuView: View? = null
    private var menuEntries: List<MenuEntry> = emptyList()
    private val menuRows = mutableListOf<View>()
    private var menuSel = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            val m = metrics ?: return
            Thread {
                val s = m.sample()
                svc.ui.post { if (isOpen) { lastSample = s; graphUpdates.forEach { it(s) } } }
            }.start()
            svc.ui.postDelayed(this, 1000)
        }
    }

    private val d get() = svc.dp(1)

    private val hat = HatInput(svc.ui) { code ->
        try {
            onKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        } catch (t: Throwable) {
            Crash.note(svc, t)
        }
    }

    fun open() {
        if (isOpen) return
        Displays.snapshot(svc, svc, "Control Center")
        Device.refreshLayout(svc)
        isOpen = true
        HugminnService.overlayOpened?.let { cb -> svc.ui.post { cb("cc") } }
        pal = Palette(svc)
        items.clear()
        inside = null
        refreshers.clear()
        graphUpdates.clear()
        focus = 0
        focusVisible = false
        adjusting = false
        metrics = Metrics(svc) { svc.foregroundPkg }
        // the running game (for "Remember for <game>" in the profile menus)
        svc.foregroundPkg?.takeIf { Root.available() && Apps.typeOf(it) != AppType.APP }?.let { pkg -> thread { runCatching { GameTitle.current(pkg) } } }
        // Full window over everything; the status bar is covered unless the user wants to keep it.
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val statusH = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        // The window takes input focus: the D-pad of these handhelds is a joystick hat, which Android
        // only turns into D-pad keys inside the focused window (an accessibility service never sees it).
        val r = object : FrameLayout(svc) {
            // The D-pad is a joystick hat: read it directly (Android would only fake keys from it).
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                (triggerMotion(event) or hat.onMotion(event)) || super.dispatchGenericMotionEvent(event)

            // Swiping down from the top edge opens Android's quick settings, as a way out when no buttons work
            // (e.g. built-in controls off and the external controller gone).
            private var edgeY = -1f
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> edgeY = if (ev.rawY < svc.dp(40)) ev.rawY else -1f
                    MotionEvent.ACTION_MOVE -> if (edgeY >= 0 && ev.rawY - edgeY > svc.dp(60)) {
                        edgeY = -1f
                        close()
                        svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }, 150)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeY = -1f
                }
                return super.dispatchTouchEvent(ev)
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean = try {
                // a window left behind by a close that went wrong: remove it instead of swallowing keys
                if (!isOpen || root !== this) { runCatching { wm.removeViewImmediate(this) }; false } else
                if (volumeKey(event)) true else
                onKey(event)
            } catch (t: Throwable) {
                Crash.note(svc, t)
                close()
                true
            }
        }.apply {
            setBackgroundColor(pal.surface)
            setOnClickListener { if (menuView != null) closeMenu() }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        statusMode = svc.prefs.ccStatusBar
        val bandH = statusH.takeIf { it > 0 } ?: svc.dp(24)
        // OVERLAY: own bar in the chosen style; SYSTEM: the window starts below Android's status bar.
        topInset = if (statusMode == StatusBar.HIDDEN) 0 else bandH
        val panel = build()
        panel.setPadding(panel.paddingLeft, panel.paddingTop + padTop(), panel.paddingRight, panel.paddingBottom)
        r.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panelView = panel
        if (statusMode == StatusBar.OVERLAY) r.addView(StatusBand(svc, pal, ""), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bandH))
        val sysTop = if (statusMode == StatusBar.SYSTEM) topInset else 0
        val lp = WindowManager.LayoutParams(
            bounds.width(), bounds.height() - sysTop,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // music/video plays behind: the app keeps the focus, players like Crunchyroll stop without it
                // (the buttons still reach the Control Center through Hugminn)
                (if (!svc.homeInFront && Media.playsIn(svc, svc.foregroundPkg)) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = sysTop
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        r.alpha = 0f
        wm.addView(r, lp)
        // a game under the Control Center pauses (covered like by Android's own overview) and goes on afterwards
        if (svc.prefs.pauseInCc && !svc.homeInFront && svc.foregroundPkg != null && !Media.playsIn(svc, svc.foregroundPkg)) PauseActivity.hold(svc, "cc")
        r.animate().alpha(1f).setDuration(120).start()
        root = r
        r.requestFocus()
        r.post { showFocus() }
        svc.ui.post(tick)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hat.stop()
        stopBtScan()
        if (editing) {
            svc.prefs.ccGrid = entries.toList()
            editing = false
            holding = false
        }
        menuView = null
        editView = null
        gridEdit = null
        svc.ui.removeCallbacks(tick)
        // removed at once; if that fails, at least hidden and without focus, so it can never trap anyone
        root?.let { v ->
            if (runCatching { wm.removeViewImmediate(v) }.isFailure) {
                v.visibility = View.GONE
                runCatching { svc.ui.postDelayed({ runCatching { wm.removeViewImmediate(v) } }, 300) }
            }
        }
        root = null
        PauseActivity.release(svc, "cc")
    }

    // ---------- layout helpers ----------

    private var pixel = false

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Look.font(svc, pixel, bold)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun shape(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = Look.shape(svc, pixel, color, radius, stroke, strokeColor)

    private fun refreshAll() = svc.ui.postDelayed({ refreshers.forEach { it() } }, 300)

    /** Colour of active tiles: chosen in the editor, or the Material You accent. */
    private fun tileOn() = svc.prefs.tileColor.takeIf { it != 0 } ?: if (pal.dots) pal.onSurface else pal.primary
    /**
     * Background of a tile: switched on in the tile colour, off in the tile style with its edge.
     * A very dark own colour on OLED-black tiles would look switched off, so those get a light edge.
     */
    private fun tileFill(on: Boolean, r: Float): android.graphics.drawable.Drawable {
        val fill = if (on) tileOn() else pal.tile
        val edge = if (on) (if (pal.tileStyle == TileStyle.OLED && Color.luminance(fill) < 0.06f) pal.onSurface else 0) else pal.tileEdge
        val w = if (edge != 0) (1.5f * d).toInt().coerceAtLeast(1) else 0
        return if (r >= 999f) rounded(fill, 999f, w, edge) else shape(fill, r, w, edge)
    }

    private val markKeys = java.util.WeakHashMap<View, String>()

    /**
     * Focus mark for [v]: corner brackets or the full frame, as chosen. The same mark on the same
     * element is kept, so the corners settle in once per move and never replay while standing still.
     */
    private fun focusMark(v: View, r: Float, width: Int, color: Int): android.graphics.drawable.Drawable {
        val key = "${System.identityHashCode(v)}|$r|$width|$color|${pal.focusCorners}"
        val old = v.foreground
        if (markKeys[v] == key && old != null) return old
        markKeys[v] = key
        return if (pal.focusCorners) CornerFrame(color, r, width.toFloat()) else shape(0, r, width, color)
    }

    private fun tileOnFg() = if (svc.prefs.tileColor == 0) (if (pal.dots) pal.surface else pal.onPrimary) else onColor(svc.prefs.tileColor)

    private var gearItem: Item? = null
    /** Cards that open something when chosen (frame rate, performance, fan). */
    private val cardItems = mutableListOf<Item>()
    private var panelView: View? = null
    private var topInset = 0
    private var statusMode = StatusBar.OVERLAY
    /** Extra top padding of the panel: room for Hugminn's own status bar. */
    private fun padTop() = if (statusMode == StatusBar.OVERLAY) topInset else 0

    /** Rebuilds the Control Center in place (live preview while editing). */
    private fun rebuildPanel() {
        val r = root ?: return
        graphUpdates.clear()
        refreshers.clear()
        items.clear()
        inside = null
        val p = build()
        p.setPadding(p.paddingLeft, p.paddingTop + padTop(), p.paddingRight, p.paddingBottom)
        panelView?.let { r.removeView(it) }
        r.addView(p, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panelView = p
        lastSample?.let { s -> graphUpdates.forEach { it(s) } }
        r.post { showFocus() }
    }

    private fun size(id: String) = svc.prefs.ccSizes[id] ?: 1f

    private fun build(): View {
        pixel = svc.prefs.uiStyle == UiStyle.PIXEL
        val panel = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = Look.ground(svc, pal)
            setPadding(28 * d, 14 * d, 28 * d, 12 * d)
            isClickable = true
        }

        // Header with a permanent button for Android's own settings
        val header = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Huginn", 20f, 0xFFE0303C.toInt(), bold = true)) // red, like its shortcut icon
        // on a picture the grey texts of the ground would drown: they take the full text colour there
        val onGround = if (Backdrop.active(svc)) pal.onSurface else pal.onSurfaceVariant
        header.addView(text("  ·  Control Center", 15f, onGround), lp(0, WRAP, 1f))
        val battery = text("", 14f, onGround).apply { setPadding(0, 0, 14 * d, 0) }
        // With Hugminn's own status bar, clock and battery live up there instead.
        if (statusMode != StatusBar.OVERLAY) {
            header.addView(battery)
            header.addView(TextClock(svc).apply {
                format24Hour = "HH:mm"
                format12Hour = "h:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(pal.onSurface)
                typeface = Look.font(svc, pixel, true)
            })
        }
        val gear = FrameLayout(svc).apply {
            // follows the style: filled circle (modern), stepped square (pixel), ring of dots (dot matrix)
            background = if (pal.dots) shape(0, 20f * d, 3 * d, pal.onSurfaceVariant) else shape(pal.surfaceHighest, 20f * d)
            addView(IconView(svc, IconView.Kind.GEAR, 22).apply { setColor(pal.onSurface) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { openSystem(Settings.ACTION_SETTINGS) }
        }
        header.addView(gear, lp(40 * d, 40 * d).apply { marginStart = 16 * d })
        gearItem = Item(gear, { openSystem(Settings.ACTION_SETTINGS) })
        panel.addView(header)
        graphUpdates += { s -> battery.text = "${s.batPct} %${if (s.charging) " ⚡" else ""}" }

        // Body: grid of square cells
        val body = FrameLayout(svc).apply { setPadding(0, 12 * d, 0, 8 * d) }
        bodyView = body
        panel.addView(body, lp(MATCH, 0, 1f))
        val footer = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        footerView = footer
        if (Backdrop.active(svc)) {
            // on a picture the button hints sit on a calm capsule on the right, so they stay readable
            footer.gravity = Gravity.CENTER_VERTICAL
            footer.background = Look.capsule(svc, pal)
            footer.setPadding(14 * d, 0, 0, 0)
            panel.addView(footer, lp(WRAP, 38 * d).apply { gravity = Gravity.END })
        } else panel.addView(footer, lp(MATCH, 34 * d))
        renderGrid()
        return panel
    }

    // ---------- grid ----------

    private var bodyView: FrameLayout? = null
    private var footerView: LinearLayout? = null
    private var gridPage = 0
    private var gridCols = 6
    private var placed: List<Placed> = emptyList()
    private var entries: MutableList<GridEntry> = mutableListOf()
    private var editing = false
    private var holding = false
    private var editIndex = 0
    /** Item index on screen → index in [entries]. */
    private val itemEntry = mutableListOf<Int>()

    private fun computeCols(): Int {
        svc.prefs.gridCols.takeIf { it > 0 }?.let { return it.coerceIn(3, 12) }
        val m = wm.currentWindowMetrics.bounds
        val gap = 10 * d
        val bodyH = m.height() - topInset - (14 + 12 + 44 + 34 + 12 + 8) * d
        val cell = (bodyH - gap * (GridLayout.ROWS - 1)) / GridLayout.ROWS
        return ((m.width() - 56 * d + gap) / (cell + gap)).coerceIn(4, 10)
    }

    private fun pages() = (placed.maxOfOrNull { it.page } ?: 0) + 1

    /** (Re)builds the grid page and the footer, keeping the edit state. */
    private fun renderGrid() {
        val body = bodyView ?: return
        GridLayout.ROWS = svc.prefs.gridRows.coerceIn(2, 6)
        gridCols = computeCols()
        if (!editing) entries = svc.prefs.ccGrid.toMutableList()
        placed = GridLayout.place(entries, gridCols)
        val pageCount = pages() + if (editing) 1 else 0 // one empty page to add more while editing
        gridPage = gridPage.coerceIn(0, pageCount - 1)
        graphUpdates.removeAll { it is GridUpdate }
        refreshers.clear()
        sliderItems.clear()
        switchTitles.clear()
        volumeSliders.clear()
        cardItems.clear()
        items.clear()
        inside = null
        itemEntry.clear()
        body.removeAllViews()
        val board = GridBoard(svc, gridCols, 10 * d)
        val viewIndex = HashMap<View, Int>()
        board.editing = { editing }
        board.onLongPress = { v -> viewIndex[v]?.let { beginEditAt(it) } }
        board.onDrop = { v, x, y -> viewIndex[v]?.let { dropAt(it, x, y) } }
        val rootOk = Root.available()
        val covered = Array(GridLayout.ROWS) { BooleanArray(gridCols) }
        val cursorEl = if (editing) elementAt(cursorX, cursorY) else -1
        placed.forEachIndexed { idx, pl ->
            if (pl.page != gridPage) return@forEachIndexed
            for (yy in pl.y until pl.y + pl.entry.h) for (xx in pl.x until (pl.x + pl.entry.w).coerceAtMost(gridCols)) covered[yy][xx] = true
            val (view, item) = element(pl.entry, rootOk)
            viewIndex[view] = idx
            if (editing) {
                val sel = idx == cursorEl
                view.setOnClickListener { cursorX = pl.x; cursorY = pl.y; elementMenu(idx) }
                view.foreground = if (sel) shape(0, 22f * d, (if (holding) 5 else 3) * d, if (holding) tileOn() else pal.onSurface) else null
                if (sel && holding) { view.scaleX = 0.94f; view.scaleY = 0.94f }
            } else if (item != null) {
                items += item
                itemEntry += idx
            }
            board.place(view, pl.x, pl.y, pl.entry.w.coerceAtMost(gridCols), pl.entry.h, drag = true)
        }
        if (editing) {
            // Free cells as dashed squares; A on one adds something there
            for (y in 0 until GridLayout.ROWS) for (x in 0 until gridCols) {
                if (covered[y][x]) continue
                val sel = cursorEl < 0 && x == cursorX && y == cursorY
                val cell = View(svc).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 22f * d
                        setStroke(2 * d, if (sel) pal.onSurface else pal.outline, 8f * d, 6f * d)
                        setColor(if (sel) (pal.onSurface and 0x00FFFFFF) or 0x1A000000 else 0)
                    }
                    val cx = x
                    val cy = y
                    setOnClickListener { cursorX = cx; cursorY = cy; editKey(KeyEvent.KEYCODE_BUTTON_A) }
                }
                board.place(cell, x, y, 1, 1)
            }
        }
        body.addView(board, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (!editing && placed.isEmpty()) body.addView(text(tr("Leer – Y drücken und „Anordnen“ wählen"), 15f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        gearItem?.let { if (!editing) items += it }
        if (!editing) {
            focus = focus.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            if (focus == 0) placed.withIndex().filter { it.value.page == gridPage }.minByOrNull { it.value.y * 100 + it.value.x }?.let { f ->
                itemEntry.indexOf(f.index).takeIf { it >= 0 }?.let { focus = it; navX = f.value.x; navY = f.value.y }
            }
        }
        renderFooter()
        body.post { showFocus() }
    }

    private var cursorX = 0
    private var cursorY = 0
    private var navX = 0
    private var navY = 0

    /** Focus the element covering a cell (normal mode). */
    private fun focusCell(x: Int, y: Int) {
        val el = elementAt(x, y)
        val i = itemEntry.indexOf(el)
        if (i < 0) return
        navX = x
        navY = y
        focus = i
        showFocus()
    }

    /**
     * D-pad in the grid: always to the neighbouring cell in that direction, staying on the same row
     * (or column) where possible; empty cells are skipped; past the edge: next page, above the top row:
     * the settings button.
     */
    private fun gridNav(dx: Int, dy: Int) {
        val gearIdx = gearItem?.let { items.indexOf(it) } ?: -1
        if (focus == gearIdx && gearIdx >= 0) {
            if (dy > 0 || dx < 0) focusCell(navX.coerceAtMost(gridCols - 1), 0).also { if (items.getOrNull(focus) == gearItem) gridNavFirst() }
            return
        }
        val cur = itemEntry.getOrNull(focus)?.let { placed.getOrNull(it) }
        if (cur == null) { gridNavFirst(); return }
        // keep the remembered row/column if it lies inside the current element
        val rx = navX.coerceIn(cur.x, cur.x + cur.entry.w - 1)
        val ry = navY.coerceIn(cur.y, cur.y + cur.entry.h - 1)
        val rows = GridLayout.ROWS
        if (dx != 0) {
            var x = if (dx > 0) cur.x + cur.entry.w else cur.x - 1
            while (x in 0 until gridCols) {
                // same row first, then the nearest rows
                val order = (0 until rows).sortedBy { abs(it - ry) }
                for (y in order) {
                    val el = elementAt(x, y)
                    if (el >= 0 && itemEntry.contains(el)) {
                        navX = x; navY = ry
                        focus = itemEntry.indexOf(el)
                        showFocus()
                        return
                    }
                }
                x += dx
            }
            if (pages() > 1) {
                changePage(dx)
                val target = placed.withIndex().filter { it.value.page == gridPage }
                    .minByOrNull { (if (dx > 0) it.value.x else gridCols - it.value.x) * 10 + abs(it.value.y - ry) }
                target?.let { focusCell(it.value.x, it.value.y) }
            }
        } else {
            var y = if (dy > 0) cur.y + cur.entry.h else cur.y - 1
            while (y in 0 until rows) {
                val order = (0 until gridCols).sortedBy { abs(it - rx) }
                for (x in order) {
                    val el = elementAt(x, y)
                    if (el >= 0 && itemEntry.contains(el)) {
                        navX = rx; navY = y
                        focus = itemEntry.indexOf(el)
                        showFocus()
                        return
                    }
                }
                y += dy
            }
            if (dy < 0 && gearIdx >= 0) {
                focus = gearIdx
                showFocus()
            }
        }
    }

    private fun gridNavFirst() {
        val first = placed.withIndex().filter { it.value.page == gridPage }.minByOrNull { it.value.y * 100 + it.value.x } ?: return
        focusCell(first.value.x, first.value.y)
    }

    /** Index of the element covering a cell on the current page, or -1. */
    private fun elementAt(x: Int, y: Int): Int = placed.indexOfFirst {
        it.page == gridPage && x >= it.x && x < it.x + it.entry.w && y >= it.y && y < it.y + it.entry.h
    }

    /** True if [e] fits at x/y on [page] without overlapping others (except [ignore]). */
    private fun fitsAt(e: GridEntry, x: Int, y: Int, page: Int, ignore: Int): Boolean {
        if (x < 0 || y < 0 || x + e.w > gridCols || y + e.h > GridLayout.ROWS) return false
        return placed.withIndex().none { (i, p) ->
            i != ignore && p.page == page && x < p.x + p.entry.w && p.x < x + e.w && y < p.y + p.entry.h && p.y < y + e.h
        }
    }

    private fun renderFooter() {
        val footer = footerView ?: return
        footer.removeAllViews()
        // every hint can be tapped: it does what its button does (touch: "Y Bearbeiten" opens editing)
        fun hint(k: Int, s: String) {
            footer.addView(LinearLayout(svc).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(Glyphs.chip(svc, svc.prefs, pal, k, 22))
                addView(text(s, 13f, if (Backdrop.active(svc)) pal.onSurface else pal.onSurfaceVariant).apply { setPadding(6 * d, 0, 16 * d, 0) })
                setOnClickListener {
                    val now = android.os.SystemClock.uptimeMillis()
                    onKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, k, 0))
                    onKey(KeyEvent(now, now, KeyEvent.ACTION_UP, k, 0))
                }
            })
        }
        // LED window: its own hints (the north button sets what the light shows)
        if (ledCells != null && (menuView as? View)?.tag == "led") {
            if (ledFocus == "st" && ledTab == 0 && Device.elState(svc, ledEl) > 0) hint(northKey(), tr("Einstellen"))
            hint(KeyEvent.KEYCODE_BUTTON_R1, "Tab")
            hint(KeyEvent.KEYCODE_BUTTON_A, if (ledEditing != null) tr("Fertig") else tr("Auswählen"))
            hint(KeyEvent.KEYCODE_BUTTON_B, if (ledTab != 0) tr("Zurück") else tr("Schließen"))
            return
        }
        val pc = pages() + if (editing) 1 else 0
        if (pc > 1) footer.addView(text(t("Seite ${gridPage + 1}/$pc", "Page ${gridPage + 1}/$pc"), 13f, pal.onSurfaceVariant).apply { setPadding(0, 0, 16 * d, 0) })
        if (editing) {
            footer.addView(text(if (holding) tr("Verschieben: Steuerkreuz") else tr("Bearbeiten"), 13f, pal.primary, bold = true).apply { setPadding(0, 0, 16 * d, 0) },
                if (footer.layoutParams?.width == WRAP) lp(WRAP, WRAP) else lp(0, WRAP, 1f))
            if (holding) hint(KeyEvent.KEYCODE_BUTTON_A, tr("Ablegen")) else {
                hint(KeyEvent.KEYCODE_BUTTON_A, if (elementAt(cursorX, cursorY) >= 0) tr("Optionen") else tr("Hinzufügen"))
                hint(KeyEvent.KEYCODE_BUTTON_X, tr("Entfernen"))
                hint(KeyEvent.KEYCODE_BUTTON_R1, tr("Seite"))
            }
            hint(KeyEvent.KEYCODE_BUTTON_B, tr("Fertig"))
        } else {
            hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Screenshot")
            if (pages() > 1) hint(KeyEvent.KEYCODE_BUTTON_R1, tr("Seite"))
            val menuHere = focusVisible && items.getOrNull(focus)?.onY != null
            if (menuHere) hint(KeyEvent.KEYCODE_BUTTON_X, tr("Optionen"))
            if (inside != null) {
                hint(KeyEvent.KEYCODE_BUTTON_A, t("Verbinden/Trennen", "Connect/disconnect"))
                hint(KeyEvent.KEYCODE_BUTTON_B, t("Liste verlassen", "Leave list"))
            } else {
                hint(KeyEvent.KEYCODE_BUTTON_Y, tr("Bearbeiten"))
                hint(KeyEvent.KEYCODE_BUTTON_A, if (adjusting) tr("Fertig") else tr("Auswählen"))
                hint(KeyEvent.KEYCODE_BUTTON_B, tr("Zurück"))
            }
        }
    }

    /** Marker for live updates that belong to the grid (cleared on every re-render). */
    private class GridUpdate(val f: (Metrics.Sample) -> Unit) : (Metrics.Sample) -> Unit {
        override fun invoke(s: Metrics.Sample) = f(s)
    }

    private fun live(f: (Metrics.Sample) -> Unit) {
        graphUpdates += GridUpdate(f)
    }

    /** Volume sliders on screen (the device's volume keys move them). */
    private val volumeSliders = mutableListOf<BigSlider>()

    /**
     * The device's volume keys while the Control Center is open: like everywhere else. With a volume
     * slider on screen it moves (5 % steps, the slider shows it), otherwise Android's own volume panel.
     */
    private fun volumeKey(e: KeyEvent): Boolean {
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            KeyEvent.KEYCODE_VOLUME_MUTE -> 0
            else -> return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val shown = volumeSliders.filter { it.isAttachedToWindow }
        if (dir != 0 && shown.isNotEmpty()) {
            shown.first().step(5 * dir)
            val v = shown.first().value
            shown.drop(1).forEach { it.setValue(v) }
        } else {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (dir > 0) AudioManager.ADJUST_RAISE else if (dir < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_TOGGLE_MUTE,
                AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    /** View for one grid element at its size, plus what A does on it. */
    private fun element(e: GridEntry, rootOk: Boolean): Pair<View, Item?> {
        val id = e.id
        if (GridLayout.isSlider(id)) {
            val vertical = e.h > e.w
            val v = if (id == "brightness") BigSlider(svc, pal, IconView.Kind.SUN, tr("Helligkeit"), 255, Device.brightness(svc), pixel, vertical) { Device.setBrightness(svc, it) }
            else {
                // Android has only 16 media steps: Hugminn keeps its own 0-100 % (5 % steps) and sets the nearest one
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val idx = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val saved = svc.prefs.volumePct
                val pct = if (saved >= 0 && Math.round(saved * max / 100f) == idx) saved else Math.round(idx * 100f / max)
                BigSlider(svc, pal, IconView.Kind.SPEAKER, tr("Lautstärke"), 100, pct, pixel, vertical) { p ->
                    svc.prefs.volumePct = p
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p * max / 100f), 0)
                }.also { volumeSliders += it }
            }
            // Cocoon: brightness yellow, volume purple (unless a tile colour was chosen)
            if (pal.cocoon && svc.prefs.tileColor == 0) v.setAccent(if (id == "brightness") 0xFFF5A623.toInt() else 0xFF7C5CF2.toInt(), Color.WHITE)
            else v.setAccent(tileOn(), tileOnFg())
            val wrap = FrameLayout(svc)
            wrap.addView(v, if (vertical) FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            else FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 56 * d, Gravity.CENTER_VERTICAL))
            return wrap to Item(v, null, { dir -> v.step(5 * dir) }, vertical)
        }
        val big = e.w >= 2 && e.h >= 2
        if (id == "system") return systemElement(e)
        if (big && (id == "perf" || id == "fan") && rootOk) return profileElement(e)
        if (GridLayout.isInfo(id)) return infoElement(e)
        val t = tileFor(id, rootOk) ?: return View(svc).let { it to Item(it, null) }
        if (id == "media" && big) return mediaElement(e, t)
        if (id == "bt" && big) return btElement(e, t)
        if (id == "wifi" && big && rootOk) return wifiElement(e, t)
        val v = toggleElement(t, e)
        return v to Item(v, t.act, onY = t.menu, round = e.w == 1 && e.h == 1)
    }

    /**
     * Shrinks a one-line text (down to [minSp]) until it fits its width, so names on narrow screens
     * (Pocket Nova) are not cut off; only what still does not fit gets "…".
     */
    /** Titles of the 2×1 switches: they share one size, the smallest any of them needs. */
    private val switchTitles = mutableListOf<TextView>()

    private fun evenSwitchTitles() = svc.ui.post {
        val live = switchTitles.filter { it.isAttachedToWindow }
        if (live.size < 2) return@post
        val px = live.minOf { it.textSize }
        live.forEach { if (Math.abs(it.textSize - px) > 0.5f) it.setTextSize(TypedValue.COMPLEX_UNIT_PX, px) }
    }

    private fun fitWidth(tv: TextView, maxSp: Float, minSp: Float) {
        tv.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or2, _ ->
            if (r - l == or2 - ol && tv.tag == "fit:${tv.text}") return@addOnLayoutChangeListener
            val avail = (r - l) - v.paddingLeft - v.paddingRight
            if (avail <= 0) return@addOnLayoutChangeListener
            val p = android.graphics.Paint(tv.paint).apply { textSize = maxSp * v.resources.displayMetrics.scaledDensity }
            val w = p.measureText(tv.text.toString())
            val sp = if (w <= avail) maxSp else maxOf(minSp, maxSp * avail / w * 0.98f)
            tv.tag = "fit:${tv.text}"
            if (Math.abs(tv.textSize / v.resources.displayMetrics.scaledDensity - sp) > 0.2f) v.post { tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }
        }
        // still cut off after that (wide fonts, narrow 4:3 screens): one step smaller until it fits
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            tv.post {
                val l = tv.layout
                val cur = tv.textSize / tv.resources.displayMetrics.scaledDensity
                if (l != null && l.lineCount > 0 && l.getEllipsisCount(0) > 0 && cur > minSp + 0.1f)
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(minSp, cur - 1f))
                else if (tv in switchTitles) evenSwitchTitles()
            }
        }
    }

    /** Runs [f] every [ms] while [v] is on screen. */
    private fun whileShown(v: View, ms: Long, f: () -> Unit) {
        val r = object : Runnable {
            override fun run() {
                if (!v.isAttachedToWindow) return
                f()
                svc.ui.postDelayed(this, ms)
            }
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { svc.ui.postDelayed(r, ms) }
            override fun onViewDetachedFromWindow(x: View) { svc.ui.removeCallbacks(r) }
        })
    }

    /**
     * Surface inside a large card (cover, list line, chip, button) in the style's own way:
     * dot matrix: a ring of dots while off, pixel: stepped corners, cocoon: rounder, modern: rounded.
     */
    private fun inner(on: Boolean, radius: Float, fill: Int = pal.surfaceHighest): android.graphics.drawable.Drawable =
        if (pal.dots && !on) DotOutline(0, radius, 2f * d, pal.outline)
        else shape(if (on) tileOn() else fill, radius)

    /** Icon badge of the Cocoon style for large cards (colourful square with a white icon). */
    private fun cocoonBadge(id: String, icon: IconView.Kind, size: Int): View {
        val colors = Look.badge(id, pal.primary).let { c -> if (svc.prefs.tileColor != 0) intArrayOf(svc.prefs.tileColor, svc.prefs.tileColor) else c }
        return FrameLayout(svc).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply { cornerRadius = size * 0.33f }
            addView(IconView(svc, icon, (size / d * 0.55f).toInt()).apply { setColor(Color.WHITE) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    /** Header of a large card: icon (Cocoon: colourful badge), title, optional right-hand text. */
    private fun cardHead(icon: IconView.Kind, title: String, id: String = ""): Pair<LinearLayout, TextView> {
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        if (pal.cocoon) head.addView(cocoonBadge(id, icon, 26 * d), lp(26 * d, 26 * d))
        else head.addView(IconView(svc, icon, 16).apply { setColor(pal.onSurfaceVariant) })
        head.addView(text(title, 11f, pal.onSurfaceVariant, bold = true).apply { setPadding(6 * d, 0, 0, 0) }, lp(0, WRAP, 1f))
        val right = text("", 11f, pal.onSurfaceVariant, bold = true)
        head.addView(right)
        return head to right
    }

    /** One line of a list inside a large card (network, device); touch runs [act]. */
    private fun listRow(title: String, sub: String, on: Boolean, act: (() -> Unit)?): View {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12 * d, 4 * d, 12 * d, 4 * d)
            background = inner(on, 14f * d)
            if (act != null) setOnClickListener { if (!editing) act() }
        }
        row.addView(text(title, 13f, if (on) tileOnFg() else pal.onSurface, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) row.addView(text(sub, 10f, if (on) tileOnFg() else pal.onSurfaceVariant).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        return row
    }

    /**
     * System overview: FPS, CPU, GPU, RAM, battery and power draw as rings (4×2 in one row,
     * 4×4 in two rows with frame time and temperature graphs below).
     */
    private fun systemElement(e: GridEntry): Pair<View, Item?> {
        val gp = svc.prefs.gaugePalette
        val tall = e.h >= 4
        val c = card().apply { setPadding(14 * d, 10 * d, 14 * d, 10 * d) }
        c.addView(cardHead(IconView.Kind.GAMEPAD, t("SYSTEM", "SYSTEM"), "refresh").first)
        val ids = listOf("fps", "cpu", "gpu", "ram", "battery", "power")
        val grid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        c.addView(grid, lp(MATCH, 0, 1f))
        val perRow = if (tall) 3 else 6
        var row: LinearLayout? = null
        ids.forEachIndexed { i, id ->
            if (i % perRow == 0) row = LinearLayout(svc).also { grid.addView(it, lp(MATCH, 0, 1f)) }
            val sp = spec(id) ?: return@forEachIndexed
            val cell = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            val ring = RingView(svc, pal, "", pal.primary, pixel)
            cell.addView(ring, lp(MATCH, 0, 1f))
            val name = text(sp.title, 10f, pal.onSurfaceVariant, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 }
            cell.addView(name, lp(MATCH, WRAP))
            val sub = text("", 9f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            cell.addView(sub, lp(MATCH, WRAP))
            // the ring only as big as fits, so the labels sit right under it (not at the bottom)
            cell.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val room = v.height - name.height - sub.height - v.paddingTop - v.paddingBottom
                val size = minOf(v.width, room).coerceAtLeast(1)
                val lpR = ring.layoutParams as LinearLayout.LayoutParams
                if (lpR.height != size || lpR.weight != 0f) {
                    lpR.height = size; lpR.weight = 0f; lpR.width = size
                    v.post { ring.requestLayout() }
                }
            }
            row!!.addView(cell, lp(0, MATCH, 1f).apply { marginStart = 3 * d; marginEnd = 3 * d })
            live { s ->
                val f = if (id == "fps") (if (s.fps > 0) (s.fps / 60f).coerceAtMost(1f) else 0f) else sp.fraction?.invoke(s) ?: 0f
                // FPS: more is better, so its colour runs the other way
                val col = GaugeColors.of(pal, gp, if (id == "fps") 1f - f else f)
                ring.setColor(col)
                ring.set(f, sp.value(s), "")
                sub.text = sp.sub(s)
            }
        }
        if (tall) {
            val graphs = LinearLayout(svc)
            fun graphBox(label: String, g: GraphView, value: TextView): View {
                val box = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; background = inner(false, 14f * d); setPadding(10 * d, 6 * d, 10 * d, 6 * d) }
                val h = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
                h.addView(text(label, 10f, pal.onSurfaceVariant, bold = true), lp(0, WRAP, 1f))
                h.addView(value)
                box.addView(h)
                box.addView(g, lp(MATCH, 0, 1f).apply { topMargin = 4 * d })
                return box
            }
            val ft = GraphView(svc, pal.primary, 50f).styled(pal, pixel).apply { setColors(GaugeColors.graph(pal, gp, 50f) { GaugeColors.frameTime(it) }) }
            val ftV = text("", 10f, pal.onSurface, bold = true)
            val tg = GraphView(svc, pal.primary, 95f, 25f).styled(pal, pixel).apply { setColors(GaugeColors.graph(pal, gp, 95f, 25f) { (it - 40f) / 45f }) }
            val tV = text("", 10f, pal.onSurface, bold = true)
            graphs.addView(graphBox(t("FRAMETIME", "FRAME TIME"), ft, ftV), lp(0, MATCH, 1f).apply { marginEnd = 5 * d })
            graphs.addView(graphBox(t("TEMPERATUR", "TEMPERATURE"), tg, tV), lp(0, MATCH, 1f).apply { marginStart = 5 * d })
            c.addView(graphs, lp(MATCH, 0, 0.8f).apply { topMargin = 8 * d })
            live { s ->
                s.frameTimesMs.takeLast(20).forEach { ft.push(it) }
                ftV.text = s.frameTimesMs.lastOrNull()?.let { "%.1f ms".format(it) } ?: ""
                if (s.cpuTemp > 0) { tg.push(s.cpuTemp); tV.text = "${s.cpuTemp.toInt()} °C" }
            }
        }
        return c to Item(c, { overlayMenu() })
    }

    /** Large performance or fan card: the current profile, big, with live details (A opens the profiles). */
    private fun profileElement(e: GridEntry): Pair<View, Item?> {
        val perf = e.id == "perf"
        val sp = spec(e.id)!!
        val c = card().apply { setPadding(16 * d, 12 * d, 16 * d, 12 * d) }
        c.addView(cardHead(sp.icon, sp.title, e.id).first)
        c.addView(View(svc), lp(MATCH, 0, 1f))
        // sizes in step with the other cards; narrow 4:3 screens (Pocket Nova) a little smaller
        val narrow = svc.resources.displayMetrics.widthPixels / svc.resources.displayMetrics.density < 720
        val valueSp = (if (e.w >= 3) 26f else 22f) - (if (narrow) 4f else 0f)
        val value = text("", valueSp, pal.onSurface, bold = true).apply {
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(12, valueSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        }
        c.addView(value, lp(MATCH, (valueSp * 1.45f).toInt() * d))
        c.addView(View(svc), lp(MATCH, 0, 1f))
        // live: CPU clock (performance) or how fast the fan turns (fan), with a bar in the style
        val metricRow = LinearLayout(svc).apply { gravity = Gravity.BOTTOM }
        val metric = text("", (if (e.w >= 3) 20f else 17f) - (if (narrow) 2f else 0f), pal.onSurface, bold = true).apply { maxLines = 1 }
        val metricLabel = text(if (perf) t("CPU-Takt", "CPU clock") else t("Drehzahl", "Fan speed"), 11f, pal.onSurfaceVariant).apply { setPadding(8 * d, 0, 0, 3 * d) }
        metricRow.addView(metric)
        metricRow.addView(metricLabel, lp(0, WRAP, 1f))
        c.addView(metricRow)
        val bar = GaugeBar(svc, pal, pixel)
        c.addView(bar, lp(MATCH, (if (pal.dots || pixel) 6 else 5) * d).apply { topMargin = 4 * d; bottomMargin = 6 * d })
        val more = text("", 11f, pal.onSurfaceVariant).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        c.addView(more)
        val gp = svc.prefs.gaugePalette
        val refresh = { value.text = if (perf) Device.perfLabel(svc) else Device.fanLabel(svc) }
        refresh()
        refreshers += refresh
        var topMhz = 1
        live { s ->
            if (perf) {
                topMhz = maxOf(topMhz, s.cpuMhz)
                metric.text = "%.2f GHz".format(s.cpuMhz / 1000f)
                val f = s.cpuMhz.toFloat() / topMhz
                bar.set(f, GaugeColors.of(pal, gp, f))
                more.text = "CPU ${s.cpuPct.toInt()} % · GPU ${s.gpuPct.toInt()} % · ${s.gpuMhz} MHz"
            } else {
                more.text = listOfNotNull(s.cpuTemp.takeIf { it > 0 }?.let { "CPU ${it.toInt()} °C" }, s.gpuTemp.takeIf { it > 0 }?.let { "GPU ${it.toInt()} °C" }).joinToString(" · ")
            }
        }
        if (!perf) {
            fun fan() = thread {
                val pct = runCatching { FanCurve.speedPct() }.getOrNull()
                svc.ui.post {
                    metric.text = pct?.let { "$it %" } ?: "–"
                    if (pct != null) bar.set(pct / 100f, GaugeColors.of(pal, gp, pct / 100f))
                }
            }
            fan()
            whileShown(c, 3000) { fan() }
        }
        // touch opens the profiles, like A
        sp.onA?.let { a -> c.setOnClickListener { if (!editing) a() } }
        return c to Item(c, sp.onA)
    }

    /** Large Bluetooth card: on/off and the paired devices with their battery; touch connects. */
    private fun btElement(e: GridEntry, tile: Tile): Pair<View, Item?> {
        val c = card().apply { setPadding(14 * d, 10 * d, 14 * d, 10 * d) }
        val (head, right) = cardHead(IconView.Kind.BLUETOOTH, "BLUETOOTH", "bt")
        c.addView(head)
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        c.addView(list, lp(MATCH, 0, 1f).apply { topMargin = 6 * d })
        val maxRows = if (e.h >= 4) 6 else 3
        val rows = Rows()
        val refresh = {
            rows.list.clear(); rows.keys.clear()
            val on = Device.btOn(svc)
            right.text = if (on) tr("An") else tr("Aus")
            right.setTextColor(if (on) tileOn() else pal.onSurfaceVariant)
            list.removeAllViews()
            if (!on) list.addView(offNote(IconView.Kind.BLUETOOTH, t("Bluetooth ist aus · A schaltet ein", "Bluetooth is off · A turns it on")), lp(MATCH, MATCH))
            else {
                val devs = Bt.paired(svc).sortedByDescending { Bt.isConnected(it) }
                if (devs.isEmpty()) list.addView(text(tr("Noch keine"), 12f, pal.onSurfaceVariant))
                devs.take(maxRows).forEachIndexed { i, dv ->
                    val conn = Bt.isConnected(dv)
                    val bat = if (conn) Bt.battery(dv) else null
                    val sub = if (!conn) t("Gekoppelt", "Paired") else if (bat != null) t("Verbunden · Akku $bat %", "Connected · battery $bat %") else tr("Verbunden")
                    val act: () -> Unit = {
                        Toast.makeText(svc, if (conn) t("Trenne ${Bt.name(dv)} …", "Disconnecting ${Bt.name(dv)} …") else t("Verbinde mit ${Bt.name(dv)} …", "Connecting to ${Bt.name(dv)} …"), Toast.LENGTH_SHORT).show()
                        Bt.toggle(svc, dv, !conn) { svc.ui.postDelayed({ refreshAll() }, 1500) }
                    }
                    val row = listRow(Bt.name(dv), sub, conn, act)
                    rows.list += row to act; rows.keys += dv.address
                    list.addView(row, lp(MATCH, 0, 1f).apply { if (i > 0) topMargin = 5 * d })
                }
                repeat((maxRows - devs.size).coerceAtLeast(0)) { list.addView(View(svc), lp(MATCH, 0, 1f).apply { topMargin = 5 * d }) }
            }
            if (inside === rows) markRows()
        }
        refresh()
        refreshers += refresh
        whileShown(c, 5000) { refresh() }
        return c to Item(c, tile.act, onY = tile.menu, rows = rows)
    }

    /** Networks for the large Wi-Fi card (shared, so a re-drawn grid keeps them). */
    @Volatile private var wifiNets: List<Wifi.Net>? = null
    @Volatile private var wifiSaved: Map<String, String?> = emptyMap()
    @Volatile private var wifiScanning = false

    /** Known results at once, then a fresh scan; every large Wi-Fi card updates. */
    private fun wifiScan() {
        if (wifiScanning || !Device.wifiOn(svc)) return
        wifiScanning = true
        thread {
            try {
                val sv = runCatching { Wifi.saved() }.getOrDefault(emptyMap())
                val first = Wifi.scanResults()
                svc.ui.post { wifiSaved = sv; if (first.isNotEmpty()) wifiNets = first; refreshers.forEach { it() } }
                Wifi.startScan()
                Thread.sleep(3500)
                val fresh = Wifi.scanResults()
                svc.ui.post { if (fresh.isNotEmpty()) wifiNets = fresh; refreshers.forEach { it() } }
            } finally {
                wifiScanning = false
            }
        }
    }

    /** Centered icon and text for a large card whose function is switched off. */
    private fun offNote(icon: IconView.Kind, msg: String): View = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(IconView(svc, icon, 36).apply { setColor(pal.onSurfaceVariant) })
        addView(text(msg, 12f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER; setPadding(0, 8 * d, 0, 0) })
    }

    /** Large Wi-Fi card: current network and the strongest ones in range; touch connects. */
    private fun wifiElement(e: GridEntry, tile: Tile): Pair<View, Item?> {
        val c = card().apply { setPadding(14 * d, 10 * d, 14 * d, 10 * d) }
        val (head, right) = cardHead(IconView.Kind.WIFI, tr("WLAN").uppercase(), "wifi")
        c.addView(head)
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        c.addView(list, lp(MATCH, 0, 1f).apply { topMargin = 6 * d })
        val maxRows = if (e.h >= 4) 6 else 3
        val rows = Rows()
        val refresh = {
            rows.list.clear(); rows.keys.clear()
            val nets = wifiNets
            val saved = wifiSaved
            val on = Device.wifiOn(svc)
            val cur = Device.wifiName(svc)
            right.text = if (on) tr("An") else tr("Aus")
            right.setTextColor(if (on) tileOn() else pal.onSurfaceVariant)
            list.removeAllViews()
            if (!on) list.addView(offNote(IconView.Kind.WIFI, t("WLAN ist aus · A schaltet ein", "Wi-Fi is off · A turns it on")), lp(MATCH, MATCH))
            else {
                val shown = (nets ?: cur?.let { listOf(Wifi.Net(it, -50, "wpa2")) }.orEmpty()).sortedByDescending { it.ssid == cur }.take(maxRows)
                if (shown.isEmpty()) list.addView(text(tr("Wird geladen …"), 12f, pal.onSurfaceVariant))
                shown.forEachIndexed { i, n ->
                    val connected = n.ssid == cur
                    val known = saved.containsKey(n.ssid)
                    val sub = "${Wifi.bars(n.level)}  " + when {
                        connected -> tr("Verbunden")
                        known -> t("Gespeichert", "Saved")
                        n.security == "open" -> tr("offen")
                        else -> tr("gesichert")
                    }
                    val act: () -> Unit = {
                        when {
                            connected -> {}
                            known || n.security == "open" || n.security == "owe" -> {
                                Toast.makeText(svc, t("Verbinde mit ${n.ssid} …", "Connecting to ${n.ssid} …"), Toast.LENGTH_SHORT).show()
                                Thread { Wifi.connect(n.ssid, n.security, saved[n.ssid]); svc.ui.postDelayed({ refreshAll() }, 2500) }.start()
                            }
                            // new secured network: the Wi-Fi menu asks for the password
                            else -> tile.menu?.invoke()
                        }
                        Unit
                    }
                    val row = listRow(n.ssid, sub, connected, act)
                    rows.list += row to act; rows.keys += n.ssid
                    list.addView(row, lp(MATCH, 0, 1f).apply { if (i > 0) topMargin = 5 * d })
                }
                repeat((maxRows - shown.size).coerceAtLeast(0)) { list.addView(View(svc), lp(MATCH, 0, 1f).apply { topMargin = 5 * d }) }
            }
            if (inside === rows) markRows()
        }
        refresh()
        refreshers += refresh
        wifiScan()
        whileShown(c, 20_000) { wifiScan() }
        return c to Item(c, tile.act, onY = tile.menu, rows = rows)
    }

    /** Square whose side is the height it gets (cover next to the text in a wide music tile). */
    private class SquareByHeight(ctx: android.content.Context) : FrameLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(h, h)
    }

    /**
     * Large music tile (2×2 and up): cover (from 3 cells on), song, artist, progress and buttons.
     * A plays/pauses, X opens the music menu; touch on the buttons works too.
     */
    private fun mediaElement(e: GridEntry, tile: Tile): Pair<View, Item?> {
        val wide = e.w > e.h
        val withArt = e.w >= 3 || e.h >= 3
        val big = e.w >= 4 || e.h >= 4
        val c = card().apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(16 * d, 14 * d, 16 * d, 14 * d)
        }
        val art = android.widget.ImageView(svc).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        val artIcon = IconView(svc, IconView.Kind.MUSIC, if (big) 64 else 44).apply { setColor(pal.onSurfaceVariant) }
        if (withArt) {
            val box = (if (wide) SquareByHeight(svc) else FrameLayout(svc)).apply {
                background = if (pal.dots || pixel) inner(false, 18f * d) else rounded(pal.surfaceHighest, (if (pal.cocoon) 24f else 18f) * d)
                // modern and cocoon: the cover follows the rounded corners; pixel and dots keep their own frame
                if (!pal.dots && !pixel) { clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
                else { val m = (if (pal.dots) 6 else 4) * d; setPadding(m, m, m, m) }
                addView(art, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(artIcon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            c.addView(box, if (wide) lp(WRAP, MATCH).apply { marginEnd = 16 * d } else lp(MATCH, 0, 1f).apply { bottomMargin = 12 * d })
        }
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        c.addView(col, if (wide) lp(0, MATCH, 1f) else lp(MATCH, WRAP))
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        if (!withArt) {
            if (pal.cocoon) head.addView(cocoonBadge("media", IconView.Kind.MUSIC, 26 * d), lp(26 * d, 26 * d))
            else head.addView(IconView(svc, IconView.Kind.MUSIC, 16).apply { setColor(pal.onSurfaceVariant) })
        }
        val appName = text("", 11f, pal.onSurfaceVariant, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(if (withArt) 0 else 6 * d, 0, 0, 0) }
        head.addView(appName, lp(0, WRAP, 1f))
        col.addView(head)
        // two rows high: one line for the song, so progress and buttons always fit
        val low = e.h <= 2
        // one line; a song too long for it runs from right to left (marquee)
        val song = text("", if (big && !low) 22f else if (low) 16f else 17f, pal.onSurface, bold = true).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            setHorizontallyScrolling(true)
            isSelected = true
            setPadding(0, 4 * d, 0, 0)
        }
        val artist = text("", if (big) 15f else 13f, pal.onSurfaceVariant).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        col.addView(song)
        col.addView(artist)
        if (wide || !withArt) col.addView(View(svc), lp(MATCH, 0, 1f))
        // progress: the style's own bar (smooth, blocks or dots) and times
        val bar = GaugeBar(svc, pal, pixel)
        col.addView(bar, lp(MATCH, (if (pal.dots || pixel) 6 else 4) * d).apply { topMargin = 8 * d })
        val times = LinearLayout(svc)
        val posT = text("", 10f, pal.onSurfaceVariant)
        val durT = text("", 10f, pal.onSurfaceVariant).apply { gravity = Gravity.END }
        times.addView(posT, lp(0, WRAP, 1f))
        times.addView(durT, lp(0, WRAP, 1f))
        col.addView(times, lp(MATCH, WRAP).apply { topMargin = 2 * d })
        // buttons
        val btnSize = (if (big && !low) 52 else if (low) 40 else 42) * d
        val ctrl = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        fun btn(kind: IconView.Kind, main: Boolean, run: () -> Unit): IconView {
            val ic = IconView(svc, kind, if (main) 24 else 20).styled(pal, pixel).apply { setColor(if (main) tileOnFg() else pal.onSurface) }
            val f = FrameLayout(svc).apply {
                // cocoon: soft round buttons like its badges
                background = if (main) rounded(tileOn(), btnSize / 2f) else if (pal.cocoon) rounded(pal.surfaceHighest, btnSize / 2f) else null
                addView(ic, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                setOnClickListener { if (!editing) run() }
            }
            ctrl.addView(f, lp(btnSize, btnSize).apply { marginStart = 6 * d; marginEnd = 6 * d })
            return ic
        }
        val again = { svc.ui.postDelayed({ thread { Media.refresh(svc); refreshAll() } }, 400) }
        btn(IconView.Kind.PREV, false) { Media.key(svc, KeyEvent.KEYCODE_MEDIA_PREVIOUS); again() }
        val playBox = FrameLayout(svc)
        val play = IconView(svc, IconView.Kind.PLAY, 24).styled(pal, pixel).apply { setColor(tileOnFg()) }
        val pause = IconView(svc, IconView.Kind.PAUSE, 24).styled(pal, pixel).apply { setColor(tileOnFg()) }
        // play: filled circle (modern, cocoon), stepped square (pixel), ring of dots in the accent (dot matrix)
        playBox.background = when {
            pal.dots -> DotOutline(0, btnSize / 2f, 2.5f * d, tileOn())
            pixel -> shape(tileOn(), 10f * d)
            else -> rounded(tileOn(), btnSize / 2f)
        }
        if (pal.dots) { play.setColor(tileOn()); pause.setColor(tileOn()) }
        playBox.addView(play, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        playBox.addView(pause, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        playBox.setOnClickListener { if (!editing) tile.act() }
        ctrl.addView(playBox, lp(btnSize, btnSize).apply { marginStart = 6 * d; marginEnd = 6 * d })
        btn(IconView.Kind.NEXT, false) { Media.key(svc, KeyEvent.KEYCODE_MEDIA_NEXT); again() }
        col.addView(ctrl, lp(MATCH, WRAP).apply { topMargin = (if (low) 2 else 6) * d })

        fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
        fun tick() {
            val dur = Media.duration
            val pos = Media.positionNow()
            val known = dur > 0 && pos >= 0
            if (known) bar.set((pos.toFloat() / dur).coerceIn(0f, 1f), tileOn())
            bar.visibility = if (known) View.VISIBLE else View.INVISIBLE
            posT.text = if (known) mmss(pos) else ""
            durT.text = if (known) mmss(dur) else ""
        }
        val refresh = {
            appName.text = Media.app?.let { Apps.label(svc, it) }?.uppercase() ?: t("MUSIK", "MUSIC")
            // only when it changes, so a running marquee does not start over
            val now = Media.song ?: Media.title ?: t("Nichts läuft", "Nothing playing")
            if (song.text.toString() != now) song.text = now
            artist.text = if (Media.song != null) Media.artist ?: "" else ""
            artist.visibility = if (artist.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            val a = Media.art
            art.setImageBitmap(a)
            artIcon.visibility = if (a == null) View.VISIBLE else View.GONE
            play.visibility = if (Media.playing) View.GONE else View.VISIBLE
            pause.visibility = if (Media.playing) View.VISIBLE else View.GONE
            tick()
        }
        refresh()
        refreshers += refresh
        // position every second; what is playing every few seconds (song changes by itself)
        var n = 0
        val ticker = object : Runnable {
            override fun run() {
                if (!c.isAttachedToWindow) return
                tick()
                if (++n % 5 == 0) thread { Media.refresh(svc); svc.ui.post { if (c.isAttachedToWindow) refresh() } }
                svc.ui.postDelayed(this, 1000)
            }
        }
        c.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { svc.ui.postDelayed(ticker, 1000) }
            override fun onViewDetachedFromWindow(v: View) { svc.ui.removeCallbacks(ticker) }
        })
        return c to Item(c, tile.act, onY = tile.menu)
    }

    /** Quick toggle: 1×1 only the icon, larger with name and state. */
    private fun toggleElement(t: Tile, e: GridEntry): View {
        if (pal.cocoon) return cocoonToggle(t, e)
        val round = pal.dots
        val icon = IconView(svc, t.icon, if (e.w == 1) 30 else 24)
        val v: ViewGroup
        var title: TextView? = null
        var state: TextView? = null
        if (e.w == 1 && e.h == 1) {
            v = FrameLayout(svc).apply {
                addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                contentDescription = t.title
            }
        } else {
            v = LinearLayout(svc).apply {
                gravity = Gravity.CENTER_VERTICAL
                // narrow cells (Pocket Nova's 4:3): less padding, so the name keeps its room
                val narrow = svc.resources.displayMetrics.widthPixels / svc.resources.displayMetrics.density < 720
                setPadding((if (narrow) 10 else 16) * d, 0, (if (narrow) 8 else 12) * d, 0)
                addView(icon)
                val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
                title = text(t.title, 15f, pal.onSurface, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; fitWidth(this, 15f, 8f); switchTitles += this }
                state = text("", 12f, pal.onSurfaceVariant).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                col.addView(title, lp(MATCH, WRAP))
                col.addView(state, lp(MATCH, WRAP))
                addView(col, lp(0, WRAP, 1f).apply { marginStart = (if (narrow) 8 else 12) * d })
            }
        }
        v.setOnClickListener { if (!editing) t.act() }
        val refresh = {
            val on = t.on()
            val r = if (round && e.w == 1 && e.h == 1) 999f else 22f * d
            v.background = when {
                // dot matrix: switched off = only a ring of dots; on = filled
                pal.dots && !on -> DotOutline(pal.tile, r, 2f * d, pal.outline)
                round && e.w == 1 && e.h == 1 -> tileFill(on, 999f)
                // pixel: switched off with a pixel frame
                pixel && !on -> shape(pal.tile, r, 2 * d, pal.outline)
                else -> tileFill(on, r)
            }
            val fg = if (on) tileOnFg() else pal.onSurface
            icon.setColor(fg)
            title?.setTextColor(fg)
            state?.setTextColor(if (on) fg else pal.onSurfaceVariant)
            state?.text = t.state() ?: ""
        }
        refresh()
        refreshers += refresh
        return v
    }

    /**
     * Cocoon style: the tile stays white, the icon sits in a colourful badge (grey while off),
     * small tiles show their name under the badge.
     */
    private fun cocoonToggle(t: Tile, e: GridEntry): View {
        val small = e.w == 1 && e.h == 1
        val icon = IconView(svc, t.icon, if (small) 28 else 24)
        val badge = FrameLayout(svc).apply {
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val title = text(t.title, if (small) 12f else 15f, pal.onSurface, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (small) gravity = Gravity.CENTER
            fitWidth(this, if (small) 12f else 15f, 8f)
            if (!small) switchTitles += this
        }
        val state = text("", 12f, pal.onSurfaceVariant).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val bs = if (small) 54 * d else 46 * d
        val v: ViewGroup = if (small) LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(6 * d, 6 * d, 6 * d, 6 * d)
            addView(badge, lp(bs, bs))
            addView(title, lp(MATCH, WRAP).apply { topMargin = 8 * d })
            contentDescription = t.title
        } else LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16 * d, 0, 12 * d, 0)
            addView(badge, lp(bs, bs))
            val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            col.addView(title)
            col.addView(state)
            addView(col, lp(0, WRAP, 1f).apply { marginStart = 14 * d })
        }
        v.background = tileFill(false, 22f * d)
        // a shadow under black or see-through tiles only looks dirty
        v.elevation = if (pal.tileStyle == TileStyle.STANDARD) 3f * d else 0f
        v.setOnClickListener { if (!editing) t.act() }
        val colors = Look.badge(e.id, pal.primary).let { c -> if (svc.prefs.tileColor != 0) intArrayOf(svc.prefs.tileColor, svc.prefs.tileColor) else c }
        val refresh = {
            val on = t.on()
            badge.background = if (on) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply { cornerRadius = bs * 0.33f }
            else rounded(pal.surfaceHighest, bs * 0.33f)
            icon.setColor(if (on) Color.WHITE else pal.onSurfaceVariant)
            title.setTextColor(if (on || !small) pal.onSurface else pal.onSurfaceVariant)
            state.text = t.state() ?: ""
        }
        refresh()
        refreshers += refresh
        return v
    }

    /** Music: what is playing, play/pause, next, previous (the menu stays open). */
    private fun mediaMenu(sel: Int = -1) {
        val entries = mutableListOf<MenuEntry>()
        Media.title?.let { entries += MenuEntry(it, header = true, run = null) }
        Media.app?.let { entries += MenuEntry(Apps.label(svc, it), run = null) }
        val again = { i: Int -> svc.ui.postDelayed({ thread { Media.refresh(svc); svc.ui.post { refreshAll(); mediaMenu(i) } } }, 400) }
        entries += MenuEntry(if (Media.playing) t("Pause", "Pause") else t("Abspielen", "Play")) {
            Media.key(svc, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); again(entries.size - 3)
        }
        entries += MenuEntry(t("Nächster Titel", "Next track")) { Media.key(svc, KeyEvent.KEYCODE_MEDIA_NEXT); again(entries.size - 2) }
        entries += MenuEntry(t("Vorheriger Titel", "Previous track")) { Media.key(svc, KeyEvent.KEYCODE_MEDIA_PREVIOUS); again(entries.size - 1) }
        showMenu(t("Musik", "Music"), entries, sel)
    }

    /** "noch 2 h 40 min" / "voll in 35 min", or null while unknown. */
    private fun batteryTime(s: Metrics.Sample): String? {
        val m = s.batMinutes.takeIf { it > 0 } ?: return null
        val txt = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
        return if (s.charging) t("voll in $txt", "full in $txt") else t("noch $txt", "$txt left")
    }

    private class InfoSpec(
        val icon: IconView.Kind, val title: String,
        val value: (Metrics.Sample) -> String, val sub: (Metrics.Sample) -> String,
        val fraction: ((Metrics.Sample) -> Float)? = null, val colorValue: Boolean = false,
        val onA: (() -> Unit)? = null,
    )

    private fun spec(id: String): InfoSpec? {
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        return when (id) {
            "fps" -> InfoSpec(IconView.Kind.REFRESH, "FPS", { s -> if (s.fps >= 0) "${s.fps}" else "–" },
                { _ -> t("${Math.round(display?.refreshRate ?: 60f)} Hz Modus", "${Math.round(display?.refreshRate ?: 60f)} Hz mode") }, onA = { overlayMenu() })
            "perf" -> InfoSpec(IconView.Kind.BOLT, tr("LEISTUNG"), { _ -> Device.perfLabel(svc) }, { s -> t("CPU bis %.2f GHz", "CPU up to %.2f GHz").format(s.cpuMhz / 1000f) },
                onA = { profileMenu(tr("Leistungsprofil"), "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } } })
            "fan" -> InfoSpec(IconView.Kind.FAN, tr("LÜFTER"), { _ -> Device.fanLabel(svc) },
                { _ -> if (FanCurve.isRunning()) t("${FanCurve.currentPct} % bei ${FanCurve.currentTemp.toInt()} °C", "${FanCurve.currentPct} % at ${FanCurve.currentTemp.toInt()} °C") else tr("Profil") },
                onA = { profileMenu(tr("Lüfterprofil"), "fan_mode", Device.fanProfiles) { v -> chooseFan(v) } })
            "temp" -> InfoSpec(IconView.Kind.THERMO, "TEMP", { s -> if (s.cpuTemp > 0) "${s.cpuTemp.toInt()}°" else "–" },
                { s -> listOfNotNull(s.gpuTemp.takeIf { it > 0 }?.let { "GPU ${it.toInt()}°" }, s.batTemp.takeIf { it > 0 }?.let { t("Akku ${it.toInt()}°", "Battery ${it.toInt()}°") }).joinToString(" · ") },
                { s -> (s.cpuTemp - 40f) / 45f }, colorValue = true)
            "battery" -> InfoSpec(IconView.Kind.PLUG, t("AKKU", "BATT"), { s -> "${s.batPct} %" }, { s -> batteryTime(s) ?: ("%.1f W".format(s.watts) + if (s.charging) tr(" · lädt") else "") },
                { s -> 1f - s.batPct / 100f }, colorValue = true)
            "cpu" -> InfoSpec(IconView.Kind.BOLT, "CPU", { s -> "${s.cpuPct.toInt()} %" }, { s -> "%.2f GHz".format(s.cpuMhz / 1000f) }, { s -> s.cpuPct / 100f })
            "gpu" -> InfoSpec(IconView.Kind.REFRESH, "GPU", { s -> "${s.gpuPct.toInt()} %" }, { s -> "${s.gpuMhz} MHz" }, { s -> s.gpuPct / 100f })
            "ram" -> InfoSpec(IconView.Kind.SCREEN, "RAM", { s -> "%.1f GB".format(s.ramUsedMb / 1024f) },
                { s -> t("von ${Device.ramInstalledGb(s.ramTotalMb)} GB", "of ${Device.ramInstalledGb(s.ramTotalMb)} GB") }, { s -> if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f })
            "power" -> InfoSpec(IconView.Kind.PLUG, "WATT", { s -> "%.1f W".format(s.watts) }, { s -> if (s.charging) tr("lädt") else tr("aus dem Akku") }, { s -> s.watts / 15f })
            else -> null
        }
    }

    /** Value card; 1×1 compact, 2×1 with details and bar, 2×2 with a ring (FPS: frame time graph). */
    private fun infoElement(e: GridEntry): Pair<View, Item?> {
        val sp = spec(e.id) ?: return View(svc) to null
        val gp = svc.prefs.gaugePalette
        val c = card().apply { setPadding(14 * d, 10 * d, 14 * d, 10 * d) }
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(IconView(svc, sp.icon, 16).apply { setColor(pal.onSurfaceVariant) })
        head.addView(text(sp.title, 11f, pal.onSurfaceVariant, bold = true).apply { setPadding(6 * d, 0, 0, 0) }, lp(0, WRAP, 1f))
        if (sp.onA != null) head.addView(text("›", 18f, pal.onSurfaceVariant, bold = true))
        c.addView(head)
        val small = e.w == 1 && e.h == 1
        val big = e.w >= 2 && e.h >= 2
        val value = TextView(svc).apply {
            setTextColor(pal.onSurface)
            typeface = Look.font(svc, pixel, true)
            maxLines = 1
            if (small) setAutoSizeTextTypeUniformWithConfiguration(8, 26, 1, TypedValue.COMPLEX_UNIT_SP)
            else setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 34f else 19f)
        }
        val sub = text("", 11f, pal.onSurfaceVariant)
        var bar: GaugeBar? = null
        var ring: RingView? = null
        var graph: GraphView? = null
        if (big && e.id == "fps") {
            c.addView(value)
            c.addView(sub)
            graph = GraphView(svc, pal.primary, 50f).styled(pal, pixel).apply { setColors(GaugeColors.graph(pal, gp, 50f) { GaugeColors.frameTime(it) }) }
            c.addView(graph, lp(MATCH, 0, 1f).apply { topMargin = 6 * d })
        } else if (big && sp.fraction != null) {
            ring = RingView(svc, pal, "", pal.primary, pixel)
            c.addView(ring, lp(MATCH, 0, 1f))
            c.addView(sub.apply { gravity = Gravity.CENTER })
            if (e.id == "battery") {
                // large battery: power draw and temperature too
                val more = text("", 11f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER }
                c.addView(more)
                live { s -> more.text = listOfNotNull("%.1f W".format(s.watts), s.batTemp.takeIf { it > 0 }?.let { "${it.toInt()} °C" }).joinToString(" · ") }
            }
        } else {
            c.addView(View(svc), lp(1, 0, 1f))
            c.addView(value, if (small) lp(MATCH, 0, 1.4f) else lp(MATCH, WRAP))
            if (!small) c.addView(sub)
            if (sp.fraction != null) {
                bar = GaugeBar(svc, pal, pixel)
                c.addView(bar, lp(MATCH, 5 * d).apply { topMargin = 6 * d })
            }
        }
        live { s ->
            val f = sp.fraction?.invoke(s)
            val color = if (f != null) GaugeColors.of(pal, gp, f) else pal.primary
            value.text = sp.value(s)
            value.setTextColor(if (sp.colorValue && gp != GaugePalette.MONO && f != null) color else pal.onSurface)
            sub.text = sp.sub(s)
            if (f != null) bar?.set(f, color)
            ring?.let { r ->
                r.setColor(color)
                r.set(f ?: 0f, sp.value(s), "")
            }
            graph?.let { g -> s.frameTimesMs.takeLast(20).forEach { g.push(it) } }
        }
        sp.onA?.let { a -> c.setOnClickListener { if (!editing) a() } }
        return c to Item(c, sp.onA)
    }


    /** One column: blocks top to bottom; neighbouring small blocks share a row. */
    private fun column(ids: List<String>): View {
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val blocks = ids.mapNotNull { CcBlock.of(it) }
        var i = 0
        var first = true
        fun add(v: View, params: LinearLayout.LayoutParams) {
            if (!first) params.topMargin = 10 * d
            first = false
            col.addView(v, params)
        }
        while (i < blocks.size) {
            val b = blocks[i]
            if (b.small) {
                val row = LinearLayout(svc)
                var n = 0
                val kind = blocks[i].gauge
                while (i < blocks.size && blocks[i].small && blocks[i].gauge == kind && n < 4) {
                    row.addView(smallBlock(blocks[i]), lp(0, MATCH, 1f).apply { if (n > 0) marginStart = 10 * d })
                    i++
                    n++
                }
                val ids2 = blocks.subList(i - n, i).map { it.id }
                add(row, lp(MATCH, 0, ids2.maxOf { size(it) }))
                continue
            }
            when (b) {
                CcBlock.TILES -> add(tilesBlock(), lp(MATCH, 0, 2.2f * size("tiles")))
                CcBlock.FPS -> add(fpsBlock(), lp(MATCH, 0, 1.3f * size("fps")))
                CcBlock.BRIGHTNESS -> add(slider(IconView.Kind.SUN, tr("Helligkeit"), 255, Device.brightness(svc)) { Device.setBrightness(svc, it) }, lp(MATCH, (50 * d * size("brightness")).toInt()))
                CcBlock.VOLUME -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    add(slider(IconView.Kind.SPEAKER, tr("Lautstärke"), max, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                    }, lp(MATCH, (50 * d * size("volume")).toInt()))
                }
                else -> {}
            }
            i++
        }
        return col
    }

    private fun slider(icon: IconView.Kind, label: String, max: Int, value: Int, set: (Int) -> Unit): View {
        val v = BigSlider(svc, pal, icon, label, max, value, pixel, false, set)
        sliderItems += Item(v, null, { dir -> v.step(5 * dir) })
        return v
    }

    private fun card() = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = tileFill(false, 22f * d)
        setPadding(14 * d, 10 * d, 14 * d, 10 * d)
    }

    private fun fpsBlock(): View {
        val fps = card().apply { gravity = Gravity.CENTER }
        val modeChip = text("", 13f, pal.onPrimaryContainer, bold = true).apply {
            background = shape(pal.primaryContainer, 14f * d)
            setPadding(14 * d, 4 * d, 14 * d, 4 * d)
        }
        val row = LinearLayout(svc).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        val value = text("–", 50f, pal.onSurface, bold = true)
        row.addView(value)
        row.addView(text(" FPS", 18f, pal.onSurfaceVariant, bold = true).apply { setPadding(0, 0, 0, 10 * d) })
        fps.addView(modeChip)
        fps.addView(row)
        fps.addView(text(tr("Bildrate des Spiels · A: Leistungs-Overlay"), 12f, pal.onSurfaceVariant))
        fps.setOnClickListener { overlayMenu() }
        cardItems += Item(fps, { overlayMenu() })
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        graphUpdates += { s ->
            value.text = if (s.fps >= 0) "${s.fps}" else "–"
            modeChip.text = t("${Math.round(display?.refreshRate ?: 60f)} Hz Modus", "${Math.round(display?.refreshRate ?: 60f)} Hz mode")
        }
        return fps
    }

    private fun smallBlock(b: CcBlock): View {
        val gp = svc.prefs.gaugePalette
        fun ring(label: String, color: Int, update: RingView.(Metrics.Sample) -> Unit): View {
            val c = card().apply { setPadding(4 * d, 4 * d, 4 * d, 4 * d) }
            val r = RingView(svc, pal, label, color, pixel)
            c.addView(r, lp(MATCH, MATCH))
            graphUpdates += { s -> r.update(s) }
            return c
        }
        fun info(icon: IconView.Kind, title: String, onA: (() -> Unit)? = null, bar: GaugeBar? = null, update: (TextView, TextView, Metrics.Sample) -> Unit): View {
            val c = card()
            if (onA != null) {
                c.setOnClickListener { onA() }
                cardItems += Item(c, onA)
            }
            val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(IconView(svc, icon, 18).apply { setColor(pal.onSurfaceVariant) })
            head.addView(text(title, 12f, pal.onSurfaceVariant, bold = true).apply { setPadding(8 * d, 0, 0, 0) })
            val big = text("–", 24f, pal.onSurface, bold = true)
            val sub = text("", 11f, pal.onSurfaceVariant)
            c.addView(head)
            c.addView(View(svc), lp(1, 0, 1f))
            val line = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(big, lp(0, WRAP, 1f))
            if (onA != null) line.addView(text("›", 22f, pal.onSurfaceVariant, bold = true))
            c.addView(line)
            c.addView(sub)
            bar?.let { c.addView(it, lp(MATCH, 6 * d).apply { topMargin = 6 * d }) }
            graphUpdates += { s -> update(big, sub, s) }
            return c
        }
        fun gauge(title: String, icon: IconView.Kind, value: (Metrics.Sample) -> Triple<Float, String, String>, ringLabel: String): View {
            if (!svc.prefs.gaugeBars) return ring(ringLabel, pal.primary) { s ->
                val (f, v, u) = value(s)
                setColor(GaugeColors.of(pal, gp, f))
                set(f, v, u)
            }
            val bar = GaugeBar(svc, pal, pixel)
            return info(icon, title, bar = bar) { big, sub, s ->
                val (f, v, u) = value(s)
                big.text = v
                sub.text = u
                bar.set(f, GaugeColors.of(pal, gp, f))
            }
        }
        return when (b) {
            CcBlock.TEMP -> info(IconView.Kind.THERMO, tr("TEMPERATUR")) { big, sub, s ->
                big.text = if (s.cpuTemp > 0) "${s.cpuTemp.toInt()} °C" else "–"
                big.setTextColor(if (s.cpuTemp > 0 && gp != GaugePalette.MONO) GaugeColors.of(pal, gp, (s.cpuTemp - 40f) / 45f) else pal.onSurface)
                sub.text = listOfNotNull(
                    s.gpuTemp.takeIf { it > 0 }?.let { "GPU ${it.toInt()} °C" },
                    s.batTemp.takeIf { it > 0 }?.let { t("Akku ${it.toInt()} °C", "Battery ${it.toInt()} °C") },
                ).joinToString(" · ")
            }
            CcBlock.PERF -> info(IconView.Kind.BOLT, tr("LEISTUNG"), onA = {
                profileMenu(tr("Leistungsprofil"), "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } }
            }) { big, sub, s ->
                big.text = Device.perfLabel(svc)
                sub.text = t("CPU bis %.2f GHz", "CPU up to %.2f GHz").format(s.cpuMhz / 1000f)
            }
            CcBlock.FAN -> info(IconView.Kind.FAN, tr("LÜFTER"), onA = {
                profileMenu(tr("Lüfterprofil"), "fan_mode", Device.fanProfiles) { v -> Device.setFan(v) { refreshAll() } }
            }) { big, sub, _ ->
                big.text = Device.fanLabel(svc)
                sub.text = Device.fanSpeed(svc)?.let { t("Drehzahl $it", "Speed $it") } ?: ""
            }
            CcBlock.BATTERY -> info(IconView.Kind.PLUG, tr("AKKU")) { big, sub, s ->
                big.text = "${s.batPct} %"
                big.setTextColor(if (gp != GaugePalette.MONO) GaugeColors.of(pal, gp, 1f - s.batPct / 100f) else pal.onSurface)
                sub.text = "%.1f W".format(s.watts) + if (s.charging) tr(" · lädt") else ""
            }
            CcBlock.CPU -> gauge("CPU", IconView.Kind.BOLT, { s ->
                Triple(s.cpuPct / 100f, "${s.cpuPct.toInt()} %", "%.2f GHz".format(s.cpuMhz / 1000f))
            }, "CPU")
            CcBlock.GPU -> gauge("GPU", IconView.Kind.REFRESH, { s ->
                Triple(s.gpuPct / 100f, "${s.gpuPct.toInt()} %", "${s.gpuMhz} MHz")
            }, "GPU")
            CcBlock.POWER -> gauge(tr("LEISTUNGSAUFNAHME"), IconView.Kind.PLUG, { s ->
                Triple(s.watts / 15f, "%.1f W".format(s.watts), if (s.charging) tr("lädt") else tr("aus dem Akku"))
            }, "WATT")
            CcBlock.RAM -> gauge("RAM", IconView.Kind.SCREEN, { s ->
                val pct = if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f
                Triple(pct, "%.1f GB".format(s.ramUsedMb / 1024f), t("${(pct * 100).toInt()} % von ${Math.round(s.ramTotalMb / 1024f)} GB", "${(pct * 100).toInt()} % of ${Math.round(s.ramTotalMb / 1024f)} GB"))
            }, "RAM")
            else -> View(svc)
        }
    }

    // ---------- quick tiles ----------

    /** [menu]: submenu of the tile, opened with the west button (□); A/tap only switches. */
    private data class Tile(val title: String, val icon: IconView.Kind, val state: () -> String?, val on: () -> Boolean, val menu: (() -> Unit)? = null, val act: () -> Unit)

    private val tiles = mutableListOf<Tile>()
    private var page = 0
    private val perPage = 8
    private var tileGrid: LinearLayout? = null
    private var dots: LinearLayout? = null
    private val sliderItems = mutableListOf<Item>()

    private fun pageCount() = ((tiles.size + perPage - 1) / perPage).coerceAtLeast(1)

    private fun tileFor(id: String, rootOk: Boolean): Tile? = when (id) {
        "wifi" -> Tile(tr("WLAN"), IconView.Kind.WIFI, { if (Device.wifiOn(svc)) Device.wifiName(svc) ?: tr("An") else tr("Aus") }, { Device.wifiOn(svc) }, { wifiMenu(rootOk) }) {
            if (rootOk) Device.setWifi(!Device.wifiOn(svc)) { refreshAll() } else wifiMenu(false)
        }
        "bt" -> Tile("Bluetooth", IconView.Kind.BLUETOOTH, {
            if (!Device.btOn(svc)) tr("Aus") else Device.pairedDevices(svc).firstOrNull { it.second }?.first ?: tr("An")
        }, { Device.btOn(svc) }, { btMenu(rootOk) }) {
            if (rootOk) Device.setBt(!Device.btOn(svc)) { refreshAll() } else btMenu(false)
        }
        "perf" -> if (rootOk && Device.sys(svc, "performance_mode") != null) Tile(tr("Leistung"), IconView.Kind.BOLT, { Device.perfLabel(svc) }, { Device.performance(svc) }) {
            profileMenu(tr("Leistung"), "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } }
        } else null
        "fan" -> if (rootOk && Device.sys(svc, "fan_mode") != null) Tile(tr("Lüfter"), IconView.Kind.FAN, { Device.fanLabel(svc) }, { true }) {
            profileMenu(tr("Lüfter"), "fan_mode", Device.fanProfiles) { v -> chooseFan(v) }
        } else null
        "refresh" -> {
            val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (rootOk && display?.supportedModes?.any { it.refreshRate > 100f } == true)
                Tile(tr("Bildrate"), IconView.Kind.REFRESH, { if (Device.highRefresh(svc)) "120 Hz" else "60 Hz" }, { Device.highRefresh(svc) }) {
                    Device.setHighRefresh(!Device.highRefresh(svc)) { refreshAll() }
                } else null
        }
        "leds" -> if (rootOk && Device.hasStickLeds(svc)) Tile(if (Device.hasSideLights(svc)) tr("Beleuchtung") else tr("Stick-LEDs"), IconView.Kind.LED, { if (Device.lightsOn(svc)) tr("An") else tr("Aus") }, { Device.lightsOn(svc) }, { ledMenu() }) { toggleLeds() } else null
        "dnd" -> if (rootOk) Tile(tr("Nicht stören"), IconView.Kind.MOON, { if (Device.dnd(svc)) tr("An") else tr("Aus") }, { Device.dnd(svc) }) {
            Device.setDnd(!Device.dnd(svc)) { refreshAll() }
        } else null
        "airplane" -> if (rootOk) Tile(tr("Flugmodus"), IconView.Kind.AIRPLANE, { if (Device.airplane(svc)) tr("An") else tr("Aus") }, { Device.airplane(svc) }) {
            Device.setAirplane(!Device.airplane(svc)) { refreshAll() }
        } else null
        "rotation" -> Tile(tr("Drehung sperren"), IconView.Kind.ROTATE, { if (Device.rotationLocked(svc)) tr("Gesperrt") else tr("Automatisch") }, { Device.rotationLocked(svc) }) {
            Device.setRotationLocked(svc, !Device.rotationLocked(svc)) { refreshAll() }
        }
        "screen" -> if (rootOk && Device.sys(svc, "keep_screen_on") != null) Tile(tr("Bildschirm an"), IconView.Kind.SCREEN, { if (Device.keepScreenOn(svc)) tr("An") else tr("Aus") }, { Device.keepScreenOn(svc) }) {
            Device.setKeepScreenOn(!Device.keepScreenOn(svc)) { refreshAll() }
        } else null
        "clean" -> if (rootOk) Tile(tr("Speicher leeren"), IconView.Kind.BROOM, { null }, { false }) {
            Thread {
                val mb = Device.cleanMemory(svc)
                svc.ui.post { android.widget.Toast.makeText(svc, t("$mb MB freigegeben", "$mb MB freed"), android.widget.Toast.LENGTH_SHORT).show() }
            }.start()
        } else null
        "landscape" -> sysTile(rootOk, tr("Querformat erzwingen"), IconView.Kind.SCREEN, "force_landscape")
        "vibration" -> sysTile(rootOk, "Vibration", IconView.Kind.VIBRATE, "haptic_feedback_enabled")
        "keymapper" -> sysTile(rootOk, tr("Tastenzuordnung"), IconView.Kind.GAMEPAD, "key_adapter_enable")
        "mouse" -> sysTile(rootOk, tr("Mausmodus"), IconView.Kind.MOUSE, "global_gamepad_to_mouse_mode")
        "homelock" -> sysTile(rootOk, tr("Home-Sperre"), IconView.Kind.LOCK, "prevent_press_home_accidentally")
        "chargesep" -> sysTile(rootOk, tr("Direkte Stromversorgung"), IconView.Kind.PLUG, "is_charging_separation")
        "trigger" -> if (rootOk && (Device.sys(svc, "trigger_input_mode") != null || Device.lazySetting("trigger_input_mode"))) Tile(tr("Trigger-Modus"), IconView.Kind.GAMEPAD, {
            Device.profileLabel(svc, "trigger_input_mode", Device.triggerModes)
        }, { false }) {
            profileMenu(tr("Trigger-Modus"), "trigger_input_mode", Device.triggerModes) { v -> Device.setSys("trigger_input_mode", v) { refreshAll() } }
        } else null
        "controller" -> if (rootOk && Device.hasControllerSetting(svc)) Tile(tr("Tastenlayout"), IconView.Kind.GAMEPAD, {
            if (Device.builtinOff(svc)) t("Aus · externer Controller", "Off · external controller") else if (Glyphs.nintendoLayout()) "Retro" else "Xbox"
        }, { false }) {
            val off = Device.builtinOff(svc)
            val pad = Device.externalPad()
            showMenu(tr("Tastenlayout"), listOf(
                MenuEntry(tr("Xbox (A unten)"), checked = !off && !Glyphs.nintendoLayout()) { Device.setControllerLayout(false) { refreshAll() } },
                MenuEntry(t("Retro (A rechts)", "Retro (A on the right)"), checked = !off && Glyphs.nintendoLayout()) { Device.setControllerLayout(true) { refreshAll() } },
                // like Retroid's quick setting: the built-in controls off while an external controller is used;
                // without one connected yet, they come back on by themselves if none arrives within 60 s
                MenuEntry(t("Eingebaute Steuerung aus", "Built-in controls off"), checked = off,
                    hint = if (off) t("A schaltet sie wieder ein", "A turns them back on") else if (pad) t("Für einen externen Controller", "For an external controller") else t("Controller innerhalb 30 s verbinden", "Connect a controller within 30 s"),
                    run = { Device.setBuiltin(svc, off) { refreshAll(); if (!off && !pad) svc.guardBuiltinOff() } }),
            ))
        } else null
        "theme" -> Tile(tr("Farbschema"), IconView.Kind.PALETTE, { svc.prefs.theme.label }, { false }) {
            showMenu(tr("Farbschema"), Theme.values().map { t ->
                MenuEntry(t.label, checked = svc.prefs.theme == t) {
                    svc.prefs.theme = t
                    close()
                    svc.ui.postDelayed({ open() }, 60)
                }
            })
        }
        "media" -> {
            thread { Media.refresh(svc); refreshAll() }
            Tile(t("Musik", "Music"), IconView.Kind.MUSIC,
                { Media.title ?: if (Media.playing) t("Spielt", "Playing") else t("Nichts läuft", "Nothing playing") },
                { Media.playing }, menu = { mediaMenu() }) {
                Media.key(svc, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                svc.ui.postDelayed({ thread { Media.refresh(svc); refreshAll() } }, 500)
            }
        }
        // A: screenshot of the game (the Control Center closes first), or stops a running video; X: the menu
        "screenshot" -> Tile(t("Aufnahme", "Capture"), IconView.Kind.CAMERA, {
            when {
                Capture.recording -> "● " + Capture.elapsed()
                Capture.replayRunning -> t("Letzte ${svc.prefs.replaySec} s", "Last ${svc.prefs.replaySec} s")
                else -> "Screenshot"
            }
        }, { Capture.recording || Capture.replayRunning }, menu = { captureMenu() }) {
            close()
            if (Capture.recording) Capture.stopRecord(svc) else Capture.screenshot(svc)
        }
        // like the power button: the screen goes off and the game pauses until the device wakes up
        "sleep" -> Tile(t("Bildschirm aus", "Screen off"), IconView.Kind.POWER, { t("Ruhemodus", "Sleep") }, { false }) {
            close()
            svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }, 250)
        }
        // battery of connected Bluetooth devices (controllers, headphones) that report one
        "devbat" -> Tile(t("Geräte-Akku", "Device battery"), IconView.Kind.BATTERY, {
            val list = Bt.batteries(svc)
            when {
                list.isEmpty() -> t("Kein Gerät meldet Akku", "No device reports battery")
                list.size == 1 -> "${list[0].first} · ${list[0].second} %"
                else -> list.joinToString(" · ") { "${it.second} %" }
            }
        }, { Bt.batteries(svc).isNotEmpty() }) {
            val list = Bt.batteries(svc)
            showMenu(t("Geräte-Akku", "Device battery"),
                if (list.isEmpty()) listOf(MenuEntry(t("Kein verbundenes Gerät meldet seinen Akku", "No connected device reports its battery"), run = null))
                else list.map { (n, l) -> MenuEntry(n, hint = "$l %", run = null) })
        }
        "rpsettings" -> svc.packageManager.getLaunchIntentForPackage("com.rp.settings")?.let { i ->
            Tile(tr("Retroid-Einstellungen"), IconView.Kind.GAMEPAD, { null }, { false }) { close(); svc.startApp(i) }
        }
        "settings" -> Tile(tr("Android-Einstellungen"), IconView.Kind.GEAR, { null }, { false }) { openSystem(Settings.ACTION_SETTINGS) }
        "hugminn" -> Tile("Hugminn", IconView.Kind.GEAR, { null }, { false }) {
            close()
            svc.startApp(Intent(svc, MainActivity::class.java))
        }
        else -> null
    }

    /** On/off tile for a firmware system setting; hidden if this device does not have the setting. */
    private fun sysTile(rootOk: Boolean, title: String, icon: IconView.Kind, key: String): Tile? =
        if (!rootOk || Device.sys(svc, key) == null) null
        else Tile(title, icon, { if (Device.sysOn(svc, key)) tr("An") else tr("Aus") }, { Device.sysOn(svc, key) }) {
            Device.setSys(key, if (Device.sysOn(svc, key)) "0" else "1") { refreshAll() }
        }

    private fun loadTiles() {
        val rootOk = Root.available()
        tiles.clear()
        svc.prefs.ccTiles.forEach { id -> tileFor(id, rootOk)?.let { tiles += it } }
        page = 0
    }

    private fun tilesBlock(): View {
        loadTiles()
        val tilesCard = card().apply { setPadding(8 * d, 12 * d, 8 * d, 8 * d) }
        val grid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        tileGrid = grid
        tilesCard.addView(grid, lp(MATCH, 0, 1f))
        val dotRow = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        dots = dotRow
        tilesCard.addView(dotRow, lp(MATCH, 10 * d))
        return tilesCard
    }

    /** Builds the tiles of the current page (4 per row, 2 rows) and the item list for the D-pad. */
    private fun fillPage() {
        refreshers.clear()
        val tileItems = mutableListOf<Item>()
        tileGrid?.let { grid ->
            grid.removeAllViews()
            val shown = tiles.drop(page * perPage).take(perPage)
            shown.chunked(4).forEach { chunk ->
                val row = LinearLayout(svc)
                chunk.forEach { t ->
                    val (view, focusView) = tileView(t)
                    row.addView(view, lp(0, MATCH, 1f))
                    tileItems += Item(focusView, t.act)
                }
                repeat(4 - chunk.size) { row.addView(View(svc), lp(0, 1, 1f)) }
                grid.addView(row, lp(MATCH, 0, 1f))
            }
            if (shown.size <= 4) grid.addView(View(svc), lp(MATCH, 0, 1f))
            if (shown.isEmpty()) grid.addView(text(tr("Keine Kacheln – mit Y auswählen"), 14f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        }
        dots?.let { dr ->
            dr.removeAllViews()
            if (pageCount() > 1) repeat(pageCount()) { i ->
                dr.addView(View(svc).apply { background = shape(if (i == page) pal.primary else pal.outline, 4f * d) },
                    lp(if (i == page) 16 * d else 7 * d, 7 * d).apply { marginStart = 3 * d; marginEnd = 3 * d })
            }
        }
        items.clear()
        inside = null
        items += tileItems
        items += cardItems
        items += sliderItems
        gearItem?.let { items += it }
        focus = 0
        showFocus()
    }

    /** One tile: round (or square) icon button with the full name below (two lines, shrinks to fit). */
    private fun tileView(t: Tile): Pair<View, View> {
        val size = 54 * d
        val round = pal.dots
        val icon = IconView(svc, t.icon, 24)
        val button = FrameLayout(svc).apply {
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { t.act() }
        }
        val title = TextView(svc).apply {
            text = t.title
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(pal.onSurface)
            typeface = Look.font(svc, pixel, true)
            setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val state = text("", 11f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER }
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(size, size))
            addView(title, lp(MATCH, 30 * d).apply { topMargin = 4 * d })
            addView(state, lp(MATCH, WRAP))
            setPadding(2 * d, 0, 2 * d, 0)
        }
        val refresh = {
            val on = t.on()
            button.background = if (round) rounded(if (on) tileOn() else pal.surfaceHighest, size / 2f)
            else shape(if (on) tileOn() else pal.surfaceHighest, 16f * d)
            icon.setColor(if (on) tileOnFg() else pal.onSurface)
            val st = t.state()
            state.text = st ?: ""
            state.visibility = if (st == null) View.GONE else View.VISIBLE
        }
        refresh()
        refreshers += refresh
        return box to button
    }

    private fun changePage(dir: Int): Boolean {
        val n = pages() + if (editing) 1 else 0
        if (n < 2) return false
        gridPage = (gridPage + dir + n) % n
        renderGrid()
        return true
    }

    // ---------- sub menus ----------

    /** Fan profile: the firmware's ones directly, "custom" opens Hugminn's fan curve. */
    private fun chooseFan(v: String) {
        if (v == "6") { svc.ui.post { fanCurveEditor() }; return }
        FanCurve.stop(restoreSmart = false)
        svc.prefs.fanCustom = false
        Device.setFan(v) { refreshAll() }
    }

    /** Keys of a popup that handles the controller itself (fan curve). */
    private var popupKeys: ((Int) -> Unit)? = null

    /** Editor for the fan curve: ◀ ▶ choose a point, ▲ ▼ change its speed, A activates, B back. */
    private fun fanCurveEditor() {
        val r = root ?: return
        closeMenu()
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(22 * d, 16 * d, 22 * d, 14 * d)
            elevation = 12f * d
            isClickable = true
        }
        box.addView(text(tr("Eigene Lüfterkurve"), 18f, pal.onSurface, bold = true))
        val info = text("", 12f, pal.onSurfaceVariant).apply { setPadding(0, 4 * d, 0, 4 * d) }
        box.addView(info)
        val pts = svc.prefs.fanCurve.toMutableList()
        val view = FanCurveView(svc, pal).apply { points = pts.toList(); selected = 1 }
        box.addView(view, lp(560 * d, 240 * d))
        box.addView(text(tr("◀ ▶ Punkt · ▲ ▼ Drehzahl · A aktivieren · B zurück"), 12f, pal.onSurfaceVariant).apply { setPadding(0, 6 * d, 0, 0) })
        fun refreshInfo() {
            val t = FanCurve.currentTemp.takeIf { it > 0 } ?: 0f
            view.liveTemp = t
            info.text = if (FanCurve.isRunning()) t("Aktiv · jetzt ${t.toInt()} °C → ${FanCurve.currentPct} %", "Active · now ${t.toInt()} °C → ${FanCurve.currentPct} %") else
                t("Unter ${FanCurve.MIN} % geht es nie, ab ${FanCurve.HOT} °C läuft der Lüfter voll", "Never below ${FanCurve.MIN} %, from ${FanCurve.HOT} °C the fan runs at full speed")
        }
        refreshInfo()
        val tickInfo = object : Runnable { override fun run() { if (menuView === box) { refreshInfo(); svc.ui.postDelayed(this, 1000) } } }
        svc.ui.postDelayed(tickInfo, 1000)
        r.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        menuView = box
        popupKeys = { code ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> view.selected = (view.selected - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> view.selected = (view.selected + 1).coerceAtMost(pts.lastIndex)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val i = view.selected
                    pts[i] = (pts[i] + if (code == KeyEvent.KEYCODE_DPAD_UP) 5 else -5).coerceIn(FanCurve.MIN, 100)
                    // the curve never falls with rising temperature
                    for (j in 0 until i) pts[j] = minOf(pts[j], pts[i])
                    for (j in i + 1..pts.lastIndex) pts[j] = maxOf(pts[j], pts[i])
                    view.points = pts.toList()
                    if (FanCurve.isRunning()) svc.prefs.fanCurve = pts.toList()
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    svc.prefs.fanCurve = pts.toList()
                    Thread {
                        if (!FanCurve.available()) {
                            svc.ui.post { Toast.makeText(svc, tr("Dieses Gerät erlaubt keine eigene Lüftersteuerung"), Toast.LENGTH_LONG).show() }
                        } else {
                            svc.prefs.fanCustom = true
                            FanCurve.start(svc)
                            svc.ui.postDelayed({ refreshAll() }, 600)
                        }
                    }.start()
                    closeMenu()
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> closeMenu()
            }
        }
    }

    private fun profileMenu(title: String, key: String, table: Map<String, String>, sel: Int = -1, set: (String) -> Unit) {
        val cur = Device.sys(svc, key)
        val entries = table.map { (v, name) -> MenuEntry(name, checked = v == cur) { set(v) } }.toMutableList()
        if (cur != null && cur !in table) entries += MenuEntry(t("Profil $cur (unbekannt)", "Profile $cur (unknown)"), checked = true, run = null)
        // per app: remember the current profile for the app under the Control Center (e.g. an emulator)
        val app = svc.foregroundPkg
        val kind = if (key == "fan_mode") "fan" else "perf"
        // only for games and emulators (not for e.g. a file manager or Obtainium that happens to be open)
        if (app != null && !svc.homeInFront && Apps.isGameLike(svc, app) && Root.available() && (key == "fan_mode" || key == "performance_mode")) {
            val name = Apps.label(svc, app)
            val saved = AppProfiles.of(svc, app)?.let { if (kind == "fan") it.fan else it.perf }
            val now = if (kind == "fan" && svc.prefs.fanCustom) "6" else cur
            entries += MenuEntry(name, header = true, run = null)
            entries += MenuEntry(
                if (saved != null) t("Für $name gespeichert", "Saved for $name") else t("Für $name merken", "Remember for $name"),
                checked = saved != null,
                hint = if (saved != null) t("${table[saved] ?: saved} · A entfernt", "${table[saved] ?: saved} · A removes")
                else t("Startet $name immer mit „${table[now] ?: now}“", "$name always starts with “${table[now] ?: now}”"),
            ) {
                AppProfiles.set(svc, app, kind, if (saved != null) null else now)
                val at = entries.size - 1
                svc.ui.post { profileMenu(title, key, table, at, set) }
            }
            // per game: the emulator's running game, once it is known
            GameTitle.titles[app]?.takeIf { Apps.typeOf(app) != AppType.APP }?.let { game ->
                val gSaved = AppProfiles.ofGame(svc, app, game)?.let { if (kind == "fan") it.fan else it.perf }
                val idx = entries.size
                entries += MenuEntry(
                    if (gSaved != null) t("Für $game gespeichert", "Saved for $game") else t("Für $game merken", "Remember for $game"),
                    checked = gSaved != null,
                    hint = if (gSaved != null) t("${table[gSaved] ?: gSaved} · A entfernt", "${table[gSaved] ?: gSaved} · A removes")
                    else t("Nur dieses Spiel startet mit „${table[now] ?: now}“", "Only this game starts with “${table[now] ?: now}”"),
                ) {
                    AppProfiles.set(svc, AppProfiles.gameKey(app, game), kind, if (gSaved != null) null else now)
                    svc.ui.post { profileMenu(title, key, table, idx, set) }
                }
            }
        }
        showMenu(title, entries, sel)
    }

    /** Title of the menu on screen (lists that refresh themselves check it). */
    private var menuTitle = ""
    private var btScan: Bt.Scan? = null

    /** WLAN: switch, networks in range (saved ones connect at once, others ask for the password). */
    private fun wifiMenu(rootOk: Boolean, nets: List<Wifi.Net>? = null, saved: Map<String, String?>? = null, scanning: Boolean = true) {
        if (!rootOk) { openSystem(Settings.Panel.ACTION_WIFI); return }
        val on = Device.wifiOn(svc)
        val cur = Device.wifiName(svc)
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(if (on) tr("WLAN ausschalten") else tr("WLAN einschalten")) { Device.setWifi(!on) { refreshAll() } }
        if (on) {
            entries += MenuEntry(if (scanning) tr("Netzwerke – suche …") else tr("Netzwerke"), header = true, run = null)
            val list = nets.orEmpty()
            if (nets == null) entries += MenuEntry(tr("Wird geladen …"), run = null)
            else if (list.isEmpty()) entries += MenuEntry(tr("Keine Netzwerke gefunden"), run = null)
            list.take(14).forEach { n ->
                val connected = n.ssid == cur
                val known = saved?.containsKey(n.ssid) == true
                val lock = if (n.security == "open") tr("offen") else tr("gesichert")
                val hint = "${Wifi.bars(n.level)}  " + when {
                    connected -> tr("Verbunden")
                    known -> t("Gespeichert · $lock", "Saved · $lock")
                    else -> lock
                }
                entries += MenuEntry(n.ssid, checked = connected, hint = hint) {
                    when {
                        connected -> {}
                        n.security == "eap" || n.security == "wep" -> openSystem(Settings.Panel.ACTION_WIFI)
                        known || n.security == "open" || n.security == "owe" -> {
                            val pw = saved?.get(n.ssid)
                            Toast.makeText(svc, t("Verbinde mit ${n.ssid} …", "Connecting to ${n.ssid} …"), Toast.LENGTH_SHORT).show()
                            Thread { Wifi.connect(n.ssid, n.security, pw); svc.ui.postDelayed({ refreshAll() }, 2500) }.start()
                        }
                        else -> {
                            close()
                            svc.startApp(Intent(svc, WifiPasswordActivity::class.java).putExtra("ssid", n.ssid).putExtra("sec", n.security))
                        }
                    }
                }
            }
            entries += MenuEntry(tr("Weitere Einstellungen …")) { openSystem(Settings.Panel.ACTION_WIFI) }
        }
        val again = menuView != null && menuTitle == tr("WLAN")
        showMenu(tr("WLAN"), entries, if (again) menuSel else -1)
        menuTitle = tr("WLAN")
        if (on && nets == null) {
            // results we have now, then a fresh scan
            Thread {
                val sv = Wifi.saved()
                val first = Wifi.scanResults()
                svc.ui.post { if (menuView != null && menuTitle == tr("WLAN")) wifiMenu(true, first, sv, scanning = true) }
                Wifi.startScan()
                Thread.sleep(3500)
                val fresh = Wifi.scanResults()
                svc.ui.post { if (menuView != null && menuTitle == tr("WLAN")) wifiMenu(true, fresh, sv, scanning = false) }
            }.start()
        }
    }

    /** Bluetooth: switch, paired devices (connect/disconnect), search and pair new ones. */
    private fun btMenu(rootOk: Boolean, found: List<android.bluetooth.BluetoothDevice>? = null) {
        val on = Device.btOn(svc)
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(if (on) tr("Bluetooth ausschalten") else tr("Bluetooth einschalten")) {
            if (rootOk) Device.setBt(!on) { refreshAll() } else openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        if (on) {
            val paired = Bt.paired(svc).sortedByDescending { Bt.isConnected(it) }
            entries += MenuEntry(tr("Gekoppelte Geräte"), header = true, run = null)
            if (paired.isEmpty()) entries += MenuEntry(tr("Noch keine"), run = null)
            paired.forEach { d ->
                val connected = Bt.isConnected(d)
                val bat = if (connected) Bt.battery(d) else null
                val hint = if (!connected) tr("Gekoppelt · A verbindet")
                    else if (bat != null) t("Verbunden · Akku $bat % · A trennt", "Connected · battery $bat % · A disconnects")
                    else tr("Verbunden · A trennt")
                entries += MenuEntry(Bt.name(d), checked = connected, hint = hint) {
                    stopBtScan()
                    Toast.makeText(svc, if (connected) t("Trenne ${Bt.name(d)} …", "Disconnecting ${Bt.name(d)} …") else t("Verbinde ${Bt.name(d)} …", "Connecting ${Bt.name(d)} …"), Toast.LENGTH_SHORT).show()
                    Bt.toggle(svc, d, !connected) { svc.ui.postDelayed({ refreshAll() }, 1500) }
                }
            }
            entries += MenuEntry(if (found == null) tr("Neue Geräte") else tr("Neue Geräte – suche …"), header = true, run = null)
            if (found == null) entries += MenuEntry(tr("Suchen …"), hint = tr("Gerät in den Kopplungsmodus versetzen")) {
                svc.ui.post { startBtScan(rootOk) }
            } else {
                if (found.isEmpty()) entries += MenuEntry(tr("Noch nichts gefunden"), run = null)
                // devices with a name first; nameless ones (often phones, TVs, beacons nearby) only as a count
                // already paired devices (and their "LE_" twin that LE-Audio headphones announce too) are not new
                val pairedNames = paired.mapNotNull { runCatching { it.name }.getOrNull() }.toSet()
                val pairedAddr = paired.map { it.address }.toSet()
                val named = found.filter { f ->
                    val n = runCatching { f.name }.getOrNull() ?: return@filter false
                    f.address !in pairedAddr && n !in pairedNames && n.removePrefix("LE_") !in pairedNames
                }
                val nameless = found.count { runCatching { it.name }.getOrNull() == null }
                named.forEach { d ->
                    entries += MenuEntry(Bt.name(d), hint = tr("A koppelt")) {
                        stopBtScan()
                        Toast.makeText(svc, t("Kopple ${Bt.name(d)} …", "Pairing ${Bt.name(d)} …"), Toast.LENGTH_SHORT).show()
                        Bt.pair(svc, d)
                        Bt.confirmPairing(svc, Bt.name(d))
                    }
                }
                if (nameless > 0) entries += MenuEntry(t("$nameless weitere ohne Namen", "$nameless more without a name"), hint = tr("Meist Handys, Fernseher oder Sender in der Nähe"), run = null)
            }
        }
        val again = menuView != null && menuTitle == "Bluetooth"
        showMenu("Bluetooth", entries, if (again) menuSel else -1)
        menuTitle = "Bluetooth"
    }

    private fun startBtScan(rootOk: Boolean) {
        stopBtScan()
        val scan = Bt.Scan(svc) { list -> svc.ui.post { if (menuView != null && menuTitle == "Bluetooth") btMenu(rootOk, list) } }
        btScan = scan
        scan.start()
        btMenu(rootOk, emptyList())
        // stop searching after a while
        svc.ui.postDelayed({ if (btScan === scan) stopBtScan() }, 20_000)
    }

    private fun stopBtScan() {
        btScan?.stop()
        btScan = null
    }

    /** Which stick the LED colour menu changes: 0 both, 1 left, 2 right. */
    private var ledTarget = 0

    /**
     * Stick LEDs. With an LED app chosen in the settings (Bifrost, AmbiLuma …) the menu links to it;
     * otherwise Hugminn sets the colours itself, the same way Retroid's own LED window does.
     */
    private fun toggleLeds() {
        // everything at once: sticks and – on the Odin 2 – the side lights
        val on = !Device.lightsOn(svc)
        if (!on) LedFx.stop()
        Device.setAllLights(svc, on) {
            if (on) LedFx.resume(svc)
            refreshAll()
        }
    }

    private fun ledMenu(select: Int = -1) {
        val on = Device.lightsOn(svc)
        val pkg = svc.prefs.ledApp.takeIf { it.isNotEmpty() }
        val intent = pkg?.let { svc.packageManager.getLaunchIntentForPackage(it) }
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(if (on) tr("LEDs ausschalten") else tr("LEDs einschalten")) { Device.setAllLights(svc, !on) { refreshAll() } }
        // each stick on its own (Retroid; the Odin 2 switches both sticks together)
        if (Device.hasSideLights(svc)) {
            val st = Device.stickLeds(svc)
            val stAt = entries.size
            entries += MenuEntry("Sticks: " + if (st) tr("An") else tr("Aus"), checked = st) { Device.setStickLeds(svc, !st) { refreshAll(); ledMenu(stAt) } }
        } else for (left in listOf(true, false)) {
            val st = Device.stickOn(svc, left)
            val name = if (left) t("Linker Stick", "Left stick") else t("Rechter Stick", "Right stick")
            val at = entries.size
            entries += MenuEntry("$name: " + if (st) tr("An") else tr("Aus"), checked = st) { Device.setStick(svc, left, !st) { refreshAll(); ledMenu(at) } }
        }
        // AYN Odin 2: the lights in the sides of the case, on their own
        if (Device.hasSideLights(svc)) {
            val side = Device.sideLights(svc)
            val sideAt = entries.size
            entries += MenuEntry(if (side) t("Seitenbeleuchtung ausschalten", "Side lights off") else t("Seitenbeleuchtung einschalten", "Side lights on")) {
                Device.setSideLights(!side) { refreshAll(); ledMenu(sideAt) }
            }
        }
        if (intent != null) {
            val label = runCatching { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            entries += MenuEntry(t("Farben & Effekte in $label", "Colours & effects in $label")) {
                close()
                svc.startApp(intent)
            }
            showMenu(tr("Stick-LEDs"), entries, select)
            return
        }
        ledFocus = ""
        ledPicker()
    }

    /** Focusable cells of the LED window: key (to keep the focus across rebuilds), view, action. */
    private class LedCell(val key: String, val view: View, val act: () -> Unit)
    private var ledCells: List<LedCell>? = null
    private var ledFocus = "wheel"

    /** Colour wheel that currently takes the D-pad (LED window or tile colour), with its callbacks. */
    private var activeWheel: ColorWheel? = null
    private var wheelDone: (Int) -> Unit = {}
    private var wheelCancel: () -> Unit = {}

    private fun wheelKey(code: Int): Boolean {
        val w = activeWheel ?: return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> w.move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> w.move(0, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> w.move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> w.move(1, 0)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                activeWheel = null
                w.active = false
                wheelDone(w.color)
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                activeWheel = null
                w.active = false
                wheelCancel()
            }
        }
        return true
    }

    /** LED window tab: 0 Gerät (elements), 1 Modus (effects and device info), 2 Farbe. */
    private var ledTab = 0
    /** Elements chosen for changes (state 2). */
    private fun ledSel(st: IntArray) = st.indices.filter { st[it] == 2 }

    /** Gives the chosen elements new colours ([pick] per element index); the others keep theirs. */
    private fun applyEls(pick: (Int) -> Int) {
        val st = Device.elStates(svc)
        val sel = ledSel(st)
        if (sel.isEmpty()) return
        val cols = Device.elColors(svc)
        sel.forEach { cols[it] = pick(it) }
        Device.setElColors(svc, cols) { svc.ui.post { refreshAll(); if (root != null && ledCells != null) ledPicker() } }
    }

    /** The light chosen in the Gerät tab gets [c]; Aus or Kombi switch to Farbe, an effect keeps running in it. */
    private fun applyLed(c: Int) {
        val i = ledEl.coerceIn(0, Device.ledElNames(svc).lastIndex)
        val cols = Device.elColors(svc).also { it[i] = c }
        val st = Device.elStates(svc)
        val redraw = { svc.ui.post { refreshAll(); if (root != null && ledCells != null) ledPicker() }; Unit }
        Device.setElColors(svc, cols) {
            if (st[i] == 0 || st[i] == 2) Device.setElStates(svc, st.copyOf().also { it[i] = 1 }, redraw) else redraw()
        }
    }

    /** Chips that wrap to the next line when the width runs out (16:9 and 4:3 alike). */
    private class Flow(ctx: android.content.Context, private val gapX: Int, private val gapY: Int) : android.view.ViewGroup(ctx) {
        override fun onMeasure(ws: Int, hs: Int) {
            val maxW = MeasureSpec.getSize(ws).let { if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else it }
            var x = 0; var y = 0; var lineH = 0; var widest = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = c.layoutParams
                c.measure(if (lp.width > 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    if (lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gapY; lineH = 0 }
                x += c.measuredWidth + gapX
                widest = maxOf(widest, x - gapX)
                lineH = maxOf(lineH, c.measuredHeight)
            }
            setMeasuredDimension(if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) maxW else widest, y + lineH)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l
            var x = 0; var y = 0; var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gapY; lineH = 0 }
                c.layout(x, y + (lineH.coerceAtLeast(c.measuredHeight) - c.measuredHeight) / 2, x + c.measuredWidth, y + (lineH.coerceAtLeast(c.measuredHeight) - c.measuredHeight) / 2 + c.measuredHeight)
                x += c.measuredWidth + gapX
                lineH = maxOf(lineH, c.measuredHeight)
            }
        }
    }

    /** Scrolls when the window is taller than the screen (4:3); never taller than [maxH]. */
    private class MaxScroll(ctx: android.content.Context, private val maxH: Int) : android.widget.ScrollView(ctx) {
        override fun onMeasure(ws: Int, hs: Int) = super.onMeasure(ws, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }

    private var ledScroll: android.widget.ScrollView? = null

    /** LED window: the combination shown in the colour tab (D-pad left/right). */
    private var ledCombo = 0

    private val ledCombos get() = listOf(
        "Duo Rot/Blau" to (0xFFFF0000.toInt() to 0xFF0000FF.toInt()),
        tr("Feuer") to (0xFFFF0000.toInt() to 0xFFFF6600.toInt()),
        tr("Eisblau") to (0xFF00FFFF.toInt() to 0xFFFFFFFF.toInt()),
        tr("Ozean") to (0xFF0000FF.toInt() to 0xFF00FF99.toInt()),
        tr("Wald") to (0xFF00FF00.toInt() to 0xFF99FF00.toInt()),
        "Retro " + tr("Lila") to (0xFF8800FF.toInt() to 0xFFFF00FF.toInt()),
        tr("Sonnenuntergang") to (0xFFFF6600.toInt() to 0xFFFF00FF.toInt()),
        "Neon" to (0xFF99FF00.toInt() to 0xFFFF00FF.toInt()),
    )

    /** LED window state: the element in the Gerät tab, the effect shown in Modus, the wheel view in Farbe. */
    private var ledEl = 0
    private var ledFxIdx = -1
    private var ledWheelView = false
    /** Gerät tab: what the bar sets – 0 brightness, 1 saturation. */
    private var ledParam = 0
    /** The field that takes left/right right now (entered with A, like the sliders of the Control Center). */
    private var ledEditing: String? = null
    /** The north face button: Y in the modern layout, X in the retro layout. */
    private fun northKey() = if (Glyphs.nintendoLayout()) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y

    /** Came to a setting with the north button: choosing or cancelling goes back to "Zeigt". */
    private var ledViaNorth = false

    /** Fixed D-pad ways between the fields of the Gerät tab: field → (direction → field). */
    private var ledNav: Map<String, Map<Int, String>> = emptyMap()
    /** Left/right on a cell of the LED window (element, status, bars, combination, effect, tempo). */
    private var ledSide: Map<String, (Int) -> Unit> = emptyMap()

    private val ledColours15 = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFFFF4400.toInt(), 0xFFFF8800.toInt(), 0xFFFFDD00.toInt(),
        0xFFCCFF00.toInt(), 0xFF66FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF99.toInt(), 0xFF00FFFF.toInt(),
        0xFF0088FF.toInt(), 0xFF0000FF.toInt(), 0xFF8800FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0077.toInt(),
    )

    /** Effects of the Modus tab: effect, name, description, info (device display) or not. */
    private class LedFxItem(val e: LedEffect, val name: String, val desc: String, val info: Boolean = false)
    private val ledFxItems get() = listOf(
        LedFxItem(LedEffect.NONE, tr("Statisch"), tr("Leuchtet ruhig in der gewählten Farbe.")),
        LedFxItem(LedEffect.BREATHE, tr("Atmen"), tr("Wird langsam heller und dunkler, wie ruhiges Atmen.")),
        LedFxItem(LedEffect.PULSE, tr("Pulsieren"), tr("Kurzer Doppelschlag, dann Pause – wie ein Herzschlag.")),
        LedFxItem(LedEffect.RING, tr("Regenbogen"), tr("Links und rechts laufen versetzt durch alle Farben.")),
        LedFxItem(LedEffect.RAINBOW, tr("Farbwechsel"), tr("Alle Lichter wechseln gemeinsam durch alle Farben.")),
        LedFxItem(LedEffect.BATTERY, tr("Akku"), tr("Die Farbe zeigt den Ladestand – ein Blick genügt."), true),
        LedFxItem(LedEffect.TEMP, tr("Temperatur"), tr("Die Farbe zeigt, wie warm das Gerät gerade ist."), true),
    )

    /**
     * LED window with three tabs (L1/R1), always the same size, content in one centred column:
     * Gerät – one element at a time (◀ ▶): status Aus · An · Benutzerdefiniert, brightness and
     * saturation as bars, then the combinations; Farbe – 15 colours and the wheel as the 16th tile
     * (its own view); Modus – effects and device displays one at a time with tempo or colour scale.
     * Farbe, Modus and the combinations change only the elements on Benutzerdefiniert.
     */
    private fun ledPicker() {
        val r = root ?: return
        val reuse = (menuView as? LinearLayout)?.takeIf { it.tag == "led" }
        if (reuse == null) { closeMenu(); ledWheelView = false; ledFxIdx = -1; ledEditing = null; ledViaNorth = false; ledCombo = svc.prefs.ledComboSel }
        if (ledTab > 2) ledTab = 0
        val names = Device.ledElNames(svc)
        val n = names.size
        ledEl = ((ledEl % n) + n) % n
        val st = Device.elStates(svc)
        val cols = Device.elColors(svc)
        val kb = IntArray(n) { Device.elBright(svc, it) }
        val sat = IntArray(n) { i -> (FloatArray(3).also { android.graphics.Color.colorToHSV(cols[i], it) }[1] * 20).roundToInt() * 5 }
        // Modus and Farbe set the light chosen in the Gerät tab
        val sel = listOf(ledEl)
        val fx = IntArray(n) { Device.elFx(svc, it).ordinal }
        val commonFx = sel.map { fx[it] }.distinct().singleOrNull()?.let { LedEffect.values()[it] }
        val cells = mutableListOf<LedCell>()
        val side = mutableMapOf<String, (Int) -> Unit>()
        val dm = svc.resources.displayMetrics
        val boxW = (dm.widthPixels * 0.9f).toInt().coerceAtMost(1100 * d)
        // one height for every tab: the window never jumps
        val bodyH = (dm.heightPixels - 180 * d).coerceIn(160 * d, 440 * d)
        // 16:9: the Gerät tab in two columns (element + status | brightness + saturation), the
        // combinations below; 4:3 or narrow: one column that scrolls
        val twoCol = ledTab == 0 && boxW - 44 * d >= 600 * d
        val colW = if (twoCol) minOf(boxW - 44 * d, 760 * d) else minOf(boxW - 44 * d, 360 * d)
        val halfW = (colW - 16 * d) / 2
        val box = reuse?.apply { removeAllViews() } ?: LinearLayout(svc).apply {
            tag = "led"
            orientation = LinearLayout.VERTICAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(22 * d, 12 * d, 22 * d, 14 * d)
            elevation = 12f * d
            isClickable = true
        }
        fun again(key: String) = svc.ui.post { if (root != null) { ledFocus = key; ledPicker() } }
        fun chip(label: String, on: Boolean) = text(label, 13f, if (on) pal.onPrimaryContainer else pal.onSurfaceVariant, bold = on).apply {
            gravity = Gravity.CENTER
            setPadding(12 * d, 0, 12 * d, 0)
            background = shape(if (on) pal.primaryContainer else pal.surfaceHighest, 16f * d)
        }
        fun desc(t: String, color: Int = pal.onSurfaceVariant) = text(t, 11f, color).apply { gravity = Gravity.CENTER; setPadding(4 * d, 3 * d, 4 * d, 6 * d) }
        fun header(t: String) = text(t, 12f, pal.primary, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, 8 * d, 0, 2 * d) }
        fun scaled(c: Int, k: Int) = android.graphics.Color.rgb(android.graphics.Color.red(c) * k / 100, android.graphics.Color.green(c) * k / 100, android.graphics.Color.blue(c) * k / 100)
        /** No element on Benutzerdefiniert: to the Gerät tab, where it is set. */
        fun needSel(): Boolean {
            if (sel.isNotEmpty()) return false
            ledTab = 0
            again("st")
            return true
        }
        /** ◀ card ▶ – left/right (or the arrows) step through, A on the card runs [act]. */
        fun carousel(key: String, content: View, step: (Int) -> Unit, width: Int = minOf(colW, 360 * d), act: () -> Unit): View {
            val car = LinearLayout(svc).apply { gravity = Gravity.CENTER }
            fun arrow(sym: String, dir: Int) = text(sym, 18f, pal.onSurfaceVariant, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { step(dir) }
            }
            car.addView(arrow("◀", -1), lp(36 * d, 40 * d))
            content.setOnClickListener { act() }
            car.addView(content, lp(width - 72 * d - 40 * d, 40 * d))
            car.addView(arrow("▶", 1), lp(36 * d, 40 * d))
            cells += LedCell(key, content, act)
            side[key] = step
            return car
        }
        fun card(vararg views: View) = LinearLayout(svc).apply {
            gravity = Gravity.CENTER
            setPadding(12 * d, 0, 12 * d, 0)
            background = shape(pal.surfaceHighest, 18f * d)
            views.forEach { addView(it) }
        }
        fun dot(c: Int, size: Int = 14) = View(svc).apply { background = shape(c, size / 2f * d, 1 * d, pal.outline) }
        /** A row like the mockup: title left, value right, the control, a description. */
        fun panel(title: String, value: String): Pair<LinearLayout, LinearLayout> {
            val p = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12 * d, 7 * d, 12 * d, 7 * d)
                background = shape(pal.surface, 16f * d, 1 * d, pal.outline)
            }
            val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(title, 12f, pal.onSurfaceVariant), lp(0, WRAP, 1f))
            head.addView(text(value, 12f, pal.onSurface, bold = true))
            p.addView(head, lp(MATCH, WRAP).apply { bottomMargin = 6 * d })
            return p to head
        }
        /** Bar in the design's gauge look; touch sets it, left/right on the cell change it by [step]. */
        fun barPanel(key: String, title: String, value: Int, unit: String, color: Int, dim: Boolean, desc: String, steps: Int, onSet: (Int) -> Unit): View {
            val (p, _) = panel(title, unit)
            val bar = GaugeBar(svc, pal, pixel).apply { set(value / steps.toFloat(), color); alpha = if (dim) 0.4f else 1f }
            val barBox = FrameLayout(svc)
            barBox.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 10 * d, Gravity.CENTER_VERTICAL))
            barBox.setOnTouchListener { vv, e ->
                val nv = ((e.x / vv.width).coerceIn(0f, 1f) * steps).roundToInt()
                when (e.action) {
                    MotionEvent.ACTION_MOVE -> bar.set(nv / steps.toFloat(), color)
                    MotionEvent.ACTION_UP -> { ledFocus = key; onSet(nv) }
                }
                true
            }
            p.addView(barBox, lp(MATCH, 22 * d))
            p.addView(text(desc, 11f, pal.onSurfaceVariant).apply { setPadding(0, 2 * d, 0, 0) })
            cells += LedCell(key, p) {}
            side[key] = { dir -> onSet(value + dir) }
            return p
        }

        // --- top: title, tabs below it, both centred
        box.addView(text(tr("Beleuchtung"), 18f, pal.onSurface, bold = true).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        val tabs = LinearLayout(svc).apply { gravity = Gravity.CENTER; setPadding(0, 8 * d, 0, 6 * d) }
        tabs.addView(Glyphs.chip(svc, svc.prefs, pal, KeyEvent.KEYCODE_BUTTON_L1, 20))
        listOf(tr("Gerät"), tr("Modus"), tr("Farbe")).forEachIndexed { i, name ->
            val c = chip(name, i == ledTab).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            val act = { if (ledTab != i) { ledTab = i; ledWheelView = false; ledFocus = "tab$i"; ledPicker() } }
            c.setOnClickListener { act() }
            tabs.addView(c, lp(WRAP, 34 * d).apply { marginStart = 6 * d; marginEnd = 6 * d })
            // the tabs take no focus: L1/R1 switch them
        }
        tabs.addView(Glyphs.chip(svc, svc.prefs, pal, KeyEvent.KEYCODE_BUTTON_R1, 20))
        box.addView(tabs, lp(MATCH, WRAP))

        val scroll = android.widget.ScrollView(svc).apply { isVerticalScrollBarEnabled = false }
        ledScroll = scroll
        box.addView(scroll, lp(MATCH, bodyH))
        val outer = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(outer)
        val body = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(body, lp(colW, WRAP))
        fun gap(h: Int = 6) = body.addView(View(svc), lp(1, h * d))
        var autoSteer: (() -> Unit)? = null

        /** Which light the tab sets: the one chosen in the Gerät tab. */
        fun target(extra: String?) {
            body.addView(LinearLayout(svc).apply {
                gravity = Gravity.CENTER
                addView(dot(cols[ledEl]), lp(12 * d, 12 * d).apply { marginEnd = 8 * d })
                addView(text(tr("Für") + ": " + names[ledEl], 12f, pal.onSurface, bold = true))
            }, lp(MATCH, WRAP))
            body.addView(desc(extra ?: tr("Ein anderes Licht wählst du im Tab Gerät.")), lp(MATCH, WRAP))
        }

        when (ledTab) {
            0 -> {
                val i = ledEl
                val selW = if (twoCol) halfW else minOf(colW, 360 * d)
                /** One row: the choice on the left, what it shows on the right (stacked on 4:3). */
                fun pairRow(left: View, right: View) {
                    if (twoCol) {
                        val row = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
                        row.addView(left, lp(halfW, WRAP))
                        row.addView(View(svc), lp(16 * d, 1))
                        row.addView(right, lp(halfW, WRAP))
                        body.addView(row, lp(MATCH, WRAP).apply { bottomMargin = 8 * d })
                    } else {
                        body.addView(left, lp(MATCH, WRAP))
                        body.addView(right, lp(MATCH, WRAP).apply { bottomMargin = 8 * d })
                    }
                }
                fun chooser(key: String, content: View, hint: String, step: (Int) -> Unit, act: () -> Unit) = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(carousel(key, content, step, selW, act), lp(MATCH, WRAP))
                    addView(desc(hint), lp(MATCH, WRAP))
                }

                // row 1: which light | its status
                val elCard = card(dot(if (st[i] == 0) android.graphics.Color.BLACK else Device.elOutColor(svc, i, st[i])).also { it.layoutParams = lp(14 * d, 14 * d).apply { marginEnd = 10 * d } },
                    text(names[i], 14f, pal.onSurface, bold = true))
                val elSel = chooser("el", elCard, tr("Welches Licht du gerade einstellst"), { dir -> ledEl += dir; again("el") }) {}
                fun pick(k: Int) {
                    val ns = st.copyOf().also { it[i] = k.coerceIn(0, 3) }
                    Device.setElStates(svc, ns) { svc.ui.post { refreshAll() }; again("st") }
                }
                val modeNames = listOf(tr("Aus"), tr("Farbe"), tr("Kombi"), tr("Effekt"))
                val combos = Device.ledCombos
                val comboSel = combos[svc.prefs.ledComboSel.coerceIn(0, combos.lastIndex)]
                val comboC = if (i % 2 == 0) comboSel.second.first else comboSel.second.second
                val fxName = ledFxItems.firstOrNull { it.e == LedEffect.values()[fx[i]] }?.name ?: ""
                val (sp, _) = panel(tr("Zeigt"), modeNames[st[i]])
                val sw = TriRing(svc, pal.onSurfaceVariant, pal.surfaceHighest, 4).apply {
                    centers = intArrayOf(0xFF2A2A2A.toInt(), cols[i], comboC, pal.primary)
                    state = st[i]
                }
                sw.onPick = { k -> ledFocus = "st"; pick(k) }
                sp.addView(sw, lp(MATCH, 28 * d))
                val labels = LinearLayout(svc).apply { setPadding(0, 4 * d, 0, 0) }
                modeNames.forEachIndexed { k, l ->
                    labels.addView(text(l, 11f, if (k == st[i]) pal.onSurface else pal.onSurfaceVariant, bold = k == st[i]).apply {
                        gravity = when (k) { 0 -> Gravity.START; 3 -> Gravity.END; else -> Gravity.CENTER }
                        setOnClickListener { ledFocus = "st"; pick(k) }
                    }, lp(0, WRAP, 1f))
                }
                sp.addView(labels)
                val what = when (st[i]) {
                    0 -> tr("Bleibt dunkel")
                    1 -> tr("Leuchtet in seiner Farbe (Tab Farbe)")
                    2 -> tr("Kombi") + " " + comboSel.first
                    else -> tr("Effekt") + ": " + fxName + " " + tr("(Tab Modus)")
                }
                sp.addView(text(what, 11f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER; setPadding(0, 2 * d, 0, 0) }, lp(MATCH, WRAP))
                cells += LedCell("st", sp) {}
                side["st"] = { dir -> if (st[i] + dir in 0..3) pick(st[i] + dir) }
                pairRow(elSel, sp)

                // row 2: brightness or saturation | the bar (5 % steps)
                val pName = if (ledParam == 0) tr("Helligkeit") else tr("Sättigung")
                val pSel = chooser("param", card(text(pName, 14f, pal.onSurface, bold = true)), tr("Was der Balken rechts einstellt"), { ledParam = 1 - ledParam; again("param") }) {}
                val bar = if (ledParam == 0) barPanel("bar", tr("Helligkeit"), kb[i] / 5, "${kb[i]} %", pal.primary, st[i] == 0, tr("Leuchtstärke der LED"), 20) { v ->
                    Device.setElBright(svc, i, (v * 5).coerceIn(5, 100)) { again("bar") }
                } else barPanel("bar", tr("Sättigung"), sat[i] / 5, "${sat[i]} %", cols[i], st[i] == 0, tr("Farbkraft der LED"), 20) { v ->
                    val h = FloatArray(3).also { android.graphics.Color.colorToHSV(cols[i], it) }
                    h[1] = (v * 5).coerceIn(0, 100) / 100f
                    if (h[2] < 0.05f) h[2] = 1f
                    Device.setElColors(svc, cols.copyOf().also { it[i] = android.graphics.Color.HSVToColor(h) }) { svc.ui.post { refreshAll() }; again("bar") }
                }
                pairRow(pSel, bar)

                // row 3: the combination | its two colours
                val list = Device.ledCombos
                ledCombo = ((ledCombo % list.size) + list.size) % list.size
                val (cname, pair) = list[ledCombo]
                val cc = card(text(cname, 14f, pal.onSurface, bold = true))
                // left elements (left stick, left side) the first colour, right ones the second
                val cSel = chooser("combo", cc, tr("Kombi wählen, A übernimmt"), { dir -> ledCombo += dir; again("combo") }) {
                    // the combination for every light that is on
                    svc.prefs.ledComboSel = ledCombo
                    val ns = st.copyOf().also { a -> for (k in a.indices) if (a[k] > 0) a[k] = 2 }
                    val back = ledViaNorth
                    ledViaNorth = false
                    Device.setElStates(svc, ns) { svc.ui.post { refreshAll() }; again(if (back) "st" else "combo") }
                }
                val (cp, _) = panel(tr("Kombi"), cname)
                val sw2 = LinearLayout(svc)
                listOf(tr("Links") to pair.first, tr("Rechts") to pair.second).forEachIndexed { k, (l, c) ->
                    sw2.addView(LinearLayout(svc).apply {
                        orientation = LinearLayout.VERTICAL
                        if (k == 0) setPadding(0, 0, 4 * d, 0) else setPadding(4 * d, 0, 0, 0)
                        addView(View(svc).apply { background = shape(c, 5f * d) }, lp(MATCH, 10 * d))
                        addView(text(l, 11f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER; setPadding(0, 3 * d, 0, 0) }, lp(MATCH, WRAP))
                    }, lp(0, WRAP, 1f))
                }
                cp.addView(sw2, lp(MATCH, WRAP))
                cp.addView(text(tr("A schaltet alle eingeschalteten Lichter auf diese Kombi: links die erste, rechts die zweite Farbe"), 11f, pal.onSurfaceVariant).apply { setPadding(0, 3 * d, 0, 0) })
                pairRow(cSel, cp)
            }
            2 -> {
                if (!ledWheelView) {
                    target(tr("Andere Lichter behalten ihre Farbe."))
                    // 15 colours, the wheel as the 16th tile
                    val gapC = 8 * d
                    val cell = ((bodyH - 110 * d - 3 * gapC) / 4).coerceIn(30 * d, 50 * d)
                    val grid = android.widget.GridLayout(svc).apply { columnCount = 4 }
                    val selCols = sel.map { cols[it] or 0xFF000000.toInt() }.distinct()
                    fun place(j: Int, v: View) = grid.addView(v, android.widget.GridLayout.LayoutParams().apply {
                        width = cell; height = cell
                        setMargins(0, 0, if (j % 4 < 3) gapC else 0, if (j < 12) gapC else 0)
                    })
                    ledColours15.forEachIndexed { j, c ->
                        val v = FrameLayout(svc).apply {
                            background = shape(c, 10f * d)
                            if (selCols.singleOrNull() == c) addView(text("✓", 16f, onColor(c), bold = true).apply { gravity = Gravity.CENTER },
                                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        }
                        // colour chosen: back to the Gerät tab, ready for the next light
                        val act = { if (!needSel()) { ledTab = 0; ledFocus = if (ledViaNorth) "st" else "el"; ledViaNorth = false; applyLed(c) } }
                        v.setOnClickListener { act() }
                        place(j, v)
                        cells += LedCell("c$j", v, act)
                    }
                    val wt = FrameLayout(svc).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
                            colors = intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt())
                            cornerRadius = 10f * d
                        }
                        contentDescription = tr("Farbrad")
                        addView(text("+", 20f, android.graphics.Color.WHITE, bold = true).apply { gravity = Gravity.CENTER },
                            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }
                    val wAct = { if (!needSel()) { ledWheelView = true; again("wheel") } }
                    wt.setOnClickListener { wAct() }
                    place(15, wt)
                    cells += LedCell("c15", wt, wAct)
                    body.addView(LinearLayout(svc).apply { gravity = Gravity.CENTER; addView(grid) }, lp(MATCH, WRAP))
                    body.addView(desc(tr("Feste Farbe antippen oder mit A übernehmen. Nichts dabei? Die letzte Kachel öffnet das Farbrad.")).apply { setPadding(4 * d, 8 * d, 4 * d, 0) }, lp(MATCH, WRAP))
                } else {
                    target(null)
                    // the wheel on its own: A to steer it (live on the LEDs), A applies, B goes back
                    val wheelSize = (bodyH - 120 * d).coerceIn(120 * d, 240 * d)
                    val wheel = ColorWheel(svc, pal.onSurface).apply { select(sel.firstOrNull()?.let { cols[it] } ?: android.graphics.Color.RED) }
                    val wheelBox = FrameLayout(svc).apply { setPadding(6 * d, 6 * d, 6 * d, 6 * d) }
                    wheelBox.addView(wheel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    body.addView(LinearLayout(svc).apply { gravity = Gravity.CENTER; addView(wheelBox, lp(wheelSize, wheelSize)) }, lp(MATCH, WRAP))
                    fun previewWheel(c: Int) {
                        val z = IntArray(n) { if (it == ledEl) scaled(c, kb[it]) else if (st[it] == 0) 0 else scaled(Device.elOutColor(svc, it, st[it]), kb[it]) }
                        LedFx.previewEls(z, 1f)
                    }
                    val done: (Int) -> Unit = { c -> ledWheelView = false; ledTab = 0; ledFocus = if (ledViaNorth) "st" else "el"; ledViaNorth = false; applyLed(c) }
                    wheel.onChange = { c -> previewWheel(c) }
                    wheel.onPick = done
                    val steer = {
                        activeWheel = wheel
                        wheel.active = true
                        previewWheel(wheel.color)
                        wheelDone = done
                        // B: nothing changes, back to the Gerät tab
                        wheelCancel = { LedFx.resume(svc); ledWheelView = false; ledTab = 0; again("el") }
                        showLedSel()
                        renderFooter()
                    }
                    cells += LedCell("wheel", wheelBox, steer)
                    // the wheel takes the D-pad at once: no extra A to start
                    autoSteer = steer
                    body.addView(desc(tr("Mit dem Steuerkreuz eine Farbe suchen – die LED zeigt sie sofort. A übernimmt, B bricht ab.")), lp(MATCH, WRAP))
                    val back = chip(tr("Zurück zu den Farben"), false)
                    val backAct: () -> Unit = { ledWheelView = false; LedFx.resume(svc); again("c15") }
                    back.setOnClickListener { backAct() }
                    body.addView(LinearLayout(svc).apply { gravity = Gravity.CENTER; addView(back, lp(WRAP, 34 * d)) }, lp(MATCH, WRAP))
                    cells += LedCell("back", back, backAct)
                }
            }
            else -> {
                target(null)
                val tgt = listOf(ledEl)
                val tFx = LedEffect.values()[fx[ledEl]]
                val items = ledFxItems
                if (ledFxIdx !in items.indices) ledFxIdx = items.indexOfFirst { it.e == tFx }.coerceAtLeast(0)
                val it0 = items[ledFxIdx]
                val active = tFx == it0.e && st[ledEl] == 3
                val tag = text(if (it0.info) tr("Info") else tr("Effekt"), 10f, pal.primary).apply {
                    setPadding(6 * d, 0, 6 * d, 0)
                    background = shape(0, 8f * d, 1 * d, pal.primary)
                }
                val nameV = text(it0.name, 14f, pal.onSurface, bold = true).apply { setPadding(0, 0, 8 * d, 0) }
                val fc = card(nameV, tag)
                body.addView(carousel("fx", fc, { dir -> ledFxIdx = (ledFxIdx + dir + items.size) % items.size; again("fx") }) {
                    // the effect for this light, and it shows it
                    LedFx.setEls(svc, tgt, it0.e)
                    val back = ledViaNorth
                    ledViaNorth = false
                    if (back) ledTab = 0
                    Device.setElStates(svc, st.copyOf().also { it[ledEl] = 3 }) { svc.ui.post { refreshAll() }; again(if (back) "st" else "fx") }
                }, lp(MATCH, WRAP))
                body.addView(desc(it0.desc), lp(MATCH, WRAP))
                when (it0.e) {
                    LedEffect.BATTERY, LedEffect.TEMP -> {
                        val (p, _) = panel(tr("Farben"), "")
                        val lg = LinearLayout(svc)
                        val labels = if (it0.e == LedEffect.BATTERY) listOf(tr("über 50 %"), "20–50 %", tr("unter 20 %")) else listOf(tr("unter 60 °C"), "60–75 °C", tr("ab 75 °C"))
                        listOf(0xFF00E040.toInt(), 0xFFFFC000.toInt(), 0xFFFF1000.toInt()).forEachIndexed { k, c ->
                            lg.addView(LinearLayout(svc).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(3 * d, 0, 3 * d, 0)
                                addView(View(svc).apply { background = shape(c, 4f * d) }, lp(MATCH, 8 * d))
                                addView(text(labels[k], 11f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER; setPadding(0, 4 * d, 0, 0) }, lp(MATCH, WRAP))
                            }, lp(0, WRAP, 1f))
                        }
                        p.addView(lg)
                        body.addView(p, lp(MATCH, WRAP))
                    }
                    LedEffect.NONE -> {
                        val (p, _) = panel(tr("Tempo"), "–")
                        p.addView(text(tr("Kein Tempo nötig – die Farbe wählst du im Tab Farbe."), 11f, pal.onSurfaceVariant))
                        body.addView(p, lp(MATCH, WRAP))
                    }
                    else -> {
                        val sp = svc.prefs.ledSpeed
                        val si = LedFx.speeds.indexOfFirst { it >= sp }.coerceAtLeast(0)
                        val label = LedFx.speeds[si].let { if (it % 1f == 0f) it.toInt().toString() else it.toString().replace('.', ',') } + "×"
                        body.addView(barPanel("tempo", tr("Tempo"), si + 1, label, pal.primary, false, tr("Wie schnell der Effekt abläuft"), LedFx.speeds.size) { v ->
                            LedFx.setSpeed(svc, LedFx.speeds[(v - 1).coerceIn(0, LedFx.speeds.lastIndex)]); again("tempo")
                        }, lp(MATCH, WRAP))
                    }
                }
                // where in the list, and whether it is the one running
                val dots = LinearLayout(svc).apply { gravity = Gravity.CENTER; setPadding(0, 8 * d, 0, 2 * d) }
                items.indices.forEach { k -> dots.addView(View(svc).apply { background = shape(if (k == ledFxIdx) pal.primary else pal.surfaceHighest, 4f * d) }, lp(8 * d, 8 * d).apply { marginStart = 4 * d; marginEnd = 4 * d }) }
                body.addView(dots, lp(MATCH, WRAP))
                body.addView(desc("${ledFxIdx + 1} / ${items.size} · " + if (active) tr("✓ läuft gerade") else tr("A übernimmt den Effekt")), lp(MATCH, WRAP))
                // the same effect on every light that is on
                val all = chip(tr("Für alle eingeschalteten Lichter"), false)
                val allAct: () -> Unit = {
                    val on = (0 until n).filter { st[it] > 0 }.ifEmpty { listOf(ledEl) }
                    LedFx.setEls(svc, on, it0.e)
                    Device.setElStates(svc, st.copyOf().also { a -> on.forEach { a[it] = 3 } }) { svc.ui.post { refreshAll() }; again("fxall") }
                }
                all.setOnClickListener { allAct() }
                body.addView(LinearLayout(svc).apply { gravity = Gravity.CENTER; setPadding(0, 4 * d, 0, 0); addView(all, lp(WRAP, 34 * d)) }, lp(MATCH, WRAP))
                cells += LedCell("fxall", all, allAct)
            }
        }

        ledCells = cells
        ledSide = side
        autoSteer?.let { svc.ui.post { if (root != null && activeWheel == null && ledWheelView) it() } }
        // Gerät: the five fields are reached the way the eye goes – no detours
        val U = KeyEvent.KEYCODE_DPAD_UP; val D = KeyEvent.KEYCODE_DPAD_DOWN; val L = KeyEvent.KEYCODE_DPAD_LEFT; val Rt = KeyEvent.KEYCODE_DPAD_RIGHT
        ledNav = if (ledTab != 0) emptyMap() else if (twoCol) mapOf(
            // left: the choices, right: what they show
            "el" to mapOf(Rt to "st", D to "param"),
            "param" to mapOf(U to "el", Rt to "bar", D to "combo"),
            "combo" to mapOf(U to "param", Rt to "bar"),
            "st" to mapOf(L to "el", D to "bar"),
            "bar" to mapOf(U to "st", L to "param", D to "combo"),
        ) else mapOf(
            "el" to mapOf(D to "st"),
            "st" to mapOf(U to "el", D to "param"),
            "param" to mapOf(U to "st", D to "bar"),
            "bar" to mapOf(U to "param", D to "combo"),
            "combo" to mapOf(U to "bar"),
        )
        if (cells.none { it.key == ledFocus }) ledFocus = cells.firstOrNull { !it.key.startsWith("tab") }?.key ?: "tab$ledTab"
        if (reuse == null) r.addView(box, FrameLayout.LayoutParams(boxW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        menuView = box
        showLedSel()
    }

    private fun showLedSel() {
        ledCells?.forEach { c ->
            val sel = c.key == ledFocus && activeWheel == null
            val editing = sel && c.key == ledEditing
            c.view.foreground = if (sel) shape(0, if (c.key == "wheel") 24f * d else 12f * d, 3 * d, if (editing) pal.primary else pal.onSurface) else null
        }
        renderFooter()
        // keep the chosen field in view when the window scrolls (4:3)
        val sc = ledScroll
        val v = ledCells?.firstOrNull { it.key == ledFocus }?.view
        if (sc != null && v != null && v.isAttachedToWindow) sc.post {
            runCatching {
                val rr = android.graphics.Rect()
                v.getDrawingRect(rr)
                sc.offsetDescendantRectToMyCoords(v, rr)
                if (rr.top < sc.scrollY || rr.bottom > sc.scrollY + sc.height) sc.smoothScrollTo(0, (rr.top - sc.height / 3).coerceAtLeast(0))
            }
        }
    }

    /** D-pad in the LED window: to the nearest cell in that direction (by position on screen). */
    private fun ledKey(code: Int) {
        val cells = ledCells ?: return
        val cur = cells.firstOrNull { it.key == ledFocus } ?: cells.first()
        // a field taken with A: left/right change it, A confirms, B leaves it (like the sliders)
        val ed = ledEditing
        if (ed != null && ed == cur.key) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> ledSide[ed]?.invoke(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> ledSide[ed]?.invoke(1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { ledEditing = null; cur.act(); showLedSel() }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    ledEditing = null
                    // came here with the north button: back to "Zeigt"
                    if (ledViaNorth && (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK)) {
                        ledViaNorth = false
                        if (ledTab != 0) { ledTab = 0; ledFocus = "st"; ledPicker() } else { ledFocus = "st"; showLedSel() }
                    } else showLedSel()
                }
            }
            return
        }
        ledEditing = null
        // north button on "Zeigt": straight to the setting of what the light shows
        if (code == northKey()) {
            if (cur.key == "st" && ledTab == 0) when (Device.elState(svc, ledEl)) {
                1 -> { ledViaNorth = true; ledTab = 2; ledWheelView = false; ledFocus = "c0"; ledPicker() }
                2 -> { ledViaNorth = true; ledEditing = "combo"; ledFocus = "combo"; showLedSel() }
                3 -> { ledViaNorth = true; ledTab = 1; ledFocus = "fx"; ledEditing = "fx"; ledPicker() }
            }
            return
        }
        val (dx, dy) = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            // fields with a value (light, status, bar, combination, effect, tempo): A takes them first
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (ledSide.containsKey(cur.key)) { ledEditing = cur.key; showLedSel() } else cur.act()
                return
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                // Farbe and Modus: B goes back to the Gerät tab; there it closes the window
                if (ledTab != 0) { if (ledWheelView) LedFx.resume(svc); ledWheelView = false; ledTab = 0; ledFocus = if (ledViaNorth) "st" else "el"; ledViaNorth = false; ledPicker(); return }
                closeMenu()
                renderFooter()
                return
            }
            // L1/R1: Gerät · Modus · Farbe
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                ledTab = (ledTab + (if (code == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1) + 3) % 3; ledWheelView = false; ledFocus = "tab$ledTab"; ledPicker()
                return
            }
            else -> return
        }
        ledNav[cur.key]?.let { m ->
            m[code]?.let { ledFocus = it; showLedSel() }
            return
        }
        fun center(v: View): Pair<Float, Float> {
            val p = IntArray(2).also(v::getLocationOnScreen)
            return (p[0] + v.width / 2f) to (p[1] + v.height / 2f)
        }
        val (cx, cy) = center(cur.view)
        var best: LedCell? = null
        var bestScore = Float.MAX_VALUE
        for (c in cells) {
            if (c === cur) continue
            val (x, y) = center(c.view)
            val along = (x - cx) * dx + (y - cy) * dy
            if (along <= 4f) continue
            val across = abs((x - cx) * dy - (y - cy) * dx)
            val score = along + across * 2f
            if (score < bestScore) { bestScore = score; best = c }
        }
        best?.let { ledFocus = it.key; showLedSel() }
    }

    /** Own tile colour from the colour wheel. */
    /** [back]: from the edit menu, choosing or cancelling returns there instead of closing. */
    private fun tileColorWheel(back: (() -> Unit)? = null) {
        val r = root ?: return
        closeMenu()
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(22 * d, 16 * d, 22 * d, 14 * d)
            elevation = 12f * d
            isClickable = true
        }
        box.addView(text(tr("Eigene Kachelfarbe"), 18f, pal.onSurface, bold = true).apply { setPadding(0, 0, 0, 8 * d) })
        val wheel = ColorWheel(svc, pal.onSurface).apply {
            select(svc.prefs.tileColor.takeIf { it != 0 } ?: pal.primary)
            active = true
        }
        val sample = View(svc).apply { background = shape(wheel.color, 12f * d) }
        wheel.onChange = { c -> sample.background = shape(c, 12f * d) }
        val done: (Int) -> Unit = { c ->
            activeWheel = null
            svc.prefs.tileColor = c or 0xFF000000.toInt()
            if (back != null) back() else reopen()
        }
        wheel.onPick = done
        box.addView(wheel, lp(240 * d, 240 * d))
        box.addView(sample, lp(240 * d, 14 * d).apply { topMargin = 10 * d })
        box.addView(text(tr("Steuerkreuz wählen · A übernehmen · B zurück"), 12f, pal.onSurfaceVariant).apply { setPadding(0, 10 * d, 0, 0) })
        r.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        menuView = box
        activeWheel = wheel
        wheelDone = done
        wheelCancel = { if (back != null) back() else closeMenu() }
    }

    private fun openSystem(action: String) {
        close()
        svc.startApp(Intent(action))
    }

    private fun showMenu(title: String, entries: List<MenuEntry>, select: Int = -1, forceTall: Boolean = false) {
        val r = root ?: return
        // another menu replaces the Bluetooth list: its search ends (a fresh search starts with menuTitle empty)
        if (menuTitle.isNotEmpty() && title != menuTitle) { menuTitle = ""; stopBtScan() }
        // the same menu again (a list that updates itself): no open animation, keep the scroll position
        val refresh = menuView != null && title == menuTitle
        val keepScroll = if (refresh) menuScroll?.scrollY ?: 0 else 0
        removeMenuView()
        menuEntries = entries
        menuRows.clear()
        menuSel = select.takeIf { it in entries.indices && entries[it].run != null } ?: entries.indexOfFirst { it.run != null }.coerceAtLeast(0)
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(10 * d, 16 * d, 10 * d, 12 * d)
            elevation = 12f * d
            isClickable = true
        }
        box.addView(text(title, 18f, pal.onSurface, bold = true).apply { setPadding(14 * d, 0, 14 * d, 10 * d) })
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        menuList = list
        fillMenuRows(list, entries)
        val tall = forceTall || entries.size > 6 || title == "Bluetooth" || title == tr("WLAN")
        val sc = android.widget.ScrollView(svc).apply { addView(list) }
        menuScroll = sc
        box.addView(sc, if (tall) lp(MATCH, 0, 1f) else lp(MATCH, WRAP))
        // the menus with categories get more room; on 4:3 screens (Pocket Nova) even more
        val fourThree = r.width.toFloat() / r.height.coerceAtLeast(1) < 1.5f
        // the edit menu sits at the side as a panel, so its changes can be watched on the Control Center next to it
        val side = title == tr("Bearbeiten")
        val w = (r.width * when {
            side -> if (fourThree) 0.5f else 0.38f
            title == t("Aufnahme", "Capture") -> if (fourThree) 0.75f else 0.6f
            else -> 0.42f
        }).toInt().coerceAtLeast(320 * d)
        r.addView(box, FrameLayout.LayoutParams(w, if (tall) (r.height * (if (side) 0.92f else 0.86f)).toInt() else FrameLayout.LayoutParams.WRAP_CONTENT,
            if (side) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER).apply { if (side) marginEnd = 12 * d })
        menuView = box
        if (refresh) {
            sc.post { sc.scrollTo(0, keepScroll) }
        } else {
            box.scaleX = 0.96f
            box.scaleY = 0.96f
            box.alpha = 0f
            box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
        }
        showMenuSel()
    }

    /** The list inside the open menu (a tab change refills it in place). */
    private var menuList: LinearLayout? = null

    /** Rows of a menu: one per entry, headers in the accent colour. */
    private fun fillMenuRows(list: LinearLayout, entries: List<MenuEntry>) {
        entries.forEachIndexed { i, e ->
            val row = LinearLayout(svc).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14 * d, 0, 14 * d, 0)
                minimumHeight = 46 * d
                setOnClickListener { runMenu(i) }
            }
            if (entries.any { it.checked }) row.addView(text(if (e.checked) "●" else "○", 14f, if (e.checked) pal.primary else pal.outline).apply {
                setPadding(0, 0, 12 * d, 0)
                visibility = if (e.run == null && !e.checked) View.INVISIBLE else View.VISIBLE
            })
            if (e.swatch != 0) row.addView(View(svc).apply { background = shape(e.swatch, 12f * d, 1 * d, pal.outline) },
                lp(24 * d, 24 * d).apply { marginEnd = 12 * d })
            if (e.header) {
                row.minimumHeight = 34 * d
                row.addView(text(e.label, 13f, pal.primary, bold = true))
                list.addView(row, lp(MATCH, WRAP))
                menuRows += row
                return@forEachIndexed
            }
            val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(e.label, 15f, if (e.run == null) pal.onSurfaceVariant else pal.onSurface, bold = e.checked))
            e.hint?.let { col.addView(text(it, 12f, pal.onSurfaceVariant).apply { tag = "hint" }) }
            row.addView(col, lp(0, WRAP, 1f))
            list.addView(row, lp(MATCH, WRAP))
            menuRows += row
        }
    }

    private var menuScroll: android.widget.ScrollView? = null

    /** Changes only the small text under a menu entry (no new menu, nothing flickers). */
    private fun setMenuHint(i: Int, hint: String) {
        menuRows.getOrNull(i)?.findViewWithTag<android.widget.TextView>("hint")?.text = hint
    }

    /** The Capture tile's menu in tabs: take something, video settings, last seconds. Values change with ◀ ▶. */
    private fun captureMenu(tab: Int = 0, select: Int = -1) {
        val p = svc.prefs
        val title = t("Aufnahme", "Capture")
        val res = listOf(1080, 720)
        val fpsList = listOf(60, 30)
        fun q() = Capture.qualities.getOrElse(p.recQuality) { Capture.qualities[0] }
        fun setQ(r: Int, f: Int) { p.recQuality = Capture.qualities.indexOf(r to f).coerceAtLeast(0) }
        fun arrows(v: String) = "◀  $v  ▶"
        fun again(i: Int) = { captureMenu(curTab, i) }
        // a value that changes with ◀ ▶ (and A steps forward)
        fun value(label: String, show: () -> String, step: (Int) -> Unit): MenuEntry {
            val side: (Int) -> Unit = { dir -> step(dir); setMenuHint(menuSel, arrows(show())) }
            return MenuEntry(label, hint = arrows(show()), side = side) { side(1) }
        }

        val take = mutableListOf<MenuEntry>()
        take += MenuEntry("Screenshot", hint = t("Auch mit A auf der Kachel", "Also A on the tile")) { close(); Capture.screenshot(svc) }
        take += MenuEntry(t("Screenshot in 3 s", "Screenshot in 3 s")) { close(); Capture.screenshot(svc, 3) }
        if (Capture.recording) take += MenuEntry(t("Video stoppen", "Stop video"), hint = Capture.elapsed()) { close(); Capture.stopRecord(svc) }
        else {
            take += MenuEntry(t("Video aufnehmen", "Record video"), hint = t("A auf der Kachel stoppt", "A on the tile stops")) { close(); Capture.startRecord(svc) }
            take += MenuEntry(t("Video in 3 s", "Video in 3 s")) { close(); Capture.startRecordIn(svc, 3) }
        }
        if (Capture.replayRunning) take += MenuEntry(t("Letzte ${p.replaySec} s speichern", "Save the last ${p.replaySec} s")) { close(); Capture.saveReplay(svc) }
        if (Capture.lastUri != null) take += MenuEntry(t("Letzte Aufnahme ansehen", "View last capture")) { close(); Capture.openLast(svc) }

        val video = mutableListOf<MenuEntry>()
        video += MenuEntry(t("Bild", "Picture"), header = true, run = null)
        video += value(t("Auflösung", "Resolution"), { "${q().first}p" }) { dir ->
            val i = (res.indexOf(q().first) + dir).coerceIn(0, res.lastIndex); setQ(res[i], q().second) }
        video += value(t("Bildrate", "Frame rate"), { "${q().second} FPS" }) { dir ->
            val i = (fpsList.indexOf(q().second) - dir).coerceIn(0, fpsList.lastIndex); setQ(q().first, fpsList[i]) }
        video += value(t("Maximale Länge", "Maximum length"), { Capture.limitLabel(p.recLimit) }) { dir ->
            val l = Capture.limits; p.recLimit = l[(l.indexOf(p.recLimit).coerceAtLeast(0) + dir).coerceIn(0, l.lastIndex)] }
        video += MenuEntry(t("Ton", "Sound"), header = true, run = null)
        video += value(t("Aufnehmen", "Record"), { Capture.Audio.values().getOrElse(p.recAudio) { Capture.Audio.GAME }.label }) { dir ->
            p.recAudio = (p.recAudio + dir).coerceIn(0, Capture.Audio.values().size - 1) }
        video += MenuEntry(t("Während der Aufnahme", "While recording"), header = true, run = null)
        video += value(t("Anzeige", "Indicator"), { Capture.showLabels[p.recShow.coerceIn(0, 3)] }) { dir ->
            p.recShow = (p.recShow + dir).coerceIn(0, 3) }
        val iSleep = video.size
        video += MenuEntry(t("Bildschirm aus speichert das Video", "Screen off saves the video"), checked = p.recStopOnSleep) { p.recStopOnSleep = !p.recStopOnSleep; again(iSleep)() }

        // the last seconds: a category of the video settings; saving sits under "Take"
        video += MenuEntry(t("Letzte Sekunden", "Last seconds"), header = true, run = null)
        val iClip = video.size
        video += MenuEntry(t("Mitschneiden", "Keep recording"), checked = p.replayOn,
            hint = t("Läuft im Hintergrund, braucht etwas Akku", "Runs in the background, uses some battery")) {
            Capture.setReplay(svc, !p.replayOn); svc.ui.postDelayed({ if (isOpen) captureMenu(curTab, iClip) }, 300)
        }
        video += value(t("Länge des Clips", "Clip length"), { "${p.replaySec} s" }) { dir ->
            val l = Capture.replayLengths
            p.replaySec = l[(l.indexOf(p.replaySec).coerceAtLeast(0) + dir).coerceIn(0, l.lastIndex)]
            Capture.recorder?.let { if (it.replaySec > 0) it.replaySec = p.replaySec }
        }

        val keep = addTab
        tabMenu(title, listOf(t("Aufnehmen", "Take") to take, t("Einstellungen", "Settings") to video), tab)
        addTab = keep
        if (select >= 0 && menuEntries.getOrNull(select)?.run != null) { menuSel = select; showMenuSel() }
    }

    private fun showMenuSel() {
        menuRows.forEachIndexed { i, v ->
            v.background = if (i == menuSel) shape(pal.secondaryContainer, 16f * d) else null
        }
        val v = menuRows.getOrNull(menuSel) ?: return
        menuScroll?.post {
            val sc = menuScroll ?: return@post
            if (v.top < sc.scrollY) sc.scrollTo(0, v.top) else if (v.bottom > sc.scrollY + sc.height) sc.scrollTo(0, v.bottom - sc.height)
        }
    }

    /** Takes the menu off the screen (a new menu follows right away). */
    private fun removeMenuView() {
        menuView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        menuView = null
        popupKeys = null
        ledCells = null
        activeWheel = null
    }

    private fun closeMenu() {
        removeMenuView()
        menuTitle = ""
        stopBtScan()
    }

    private fun runMenu(i: Int) {
        val e = menuEntries.getOrNull(i) ?: return
        val run = e.run ?: return
        closeMenu()
        run()
    }

    private fun moveMenu(dir: Int) {
        var i = menuSel
        repeat(menuEntries.size) {
            i = (i + dir).coerceIn(0, menuEntries.lastIndex)
            if (menuEntries[i].run != null) {
                menuSel = i
                showMenuSel()
                return
            }
        }
    }

    // ---------- controller focus ----------

    /** Ring around the selected line of the list card the D-pad is inside of (none on the others). */
    private fun markRows() {
        val r = inside
        if (r != null && items.getOrNull(focus)?.rows !== r) inside = null
        val rows = inside
        if (rows != null && rows.list.isEmpty()) inside = null
        var v: View? = null
        if (rows != null && inside != null) {
            insideKey?.let { k -> rows.keys.indexOf(k).takeIf { it >= 0 }?.let { insideSel = it } }
            insideSel = insideSel.coerceIn(0, rows.list.lastIndex)
            insideKey = rows.keys.getOrNull(insideSel)
            v = rows.list[insideSel].first
        }
        // clear the other lines (the selected one keeps its mark, so it does not settle in again)
        items.forEach { it.rows?.list?.forEach { (row, _) -> if (row !== v) row.foreground = null } }
        if (v == null) { if (rows != null && rows.list.isEmpty()) renderFooter(); return }
        val fill = (v.background as? android.graphics.drawable.GradientDrawable)?.color?.defaultColor ?: 0
        val light = android.graphics.Color.alpha(fill) > 128 && android.graphics.Color.luminance(fill) > 0.6f
        v.foreground = focusMark(v, 14f * d, (if (pal.focusCorners) 4 else 3) * d, if (light) 0xFF101010.toInt() else if (pal.cocoon) pal.primary else pal.onSurface)
    }

    /** Leaves the list card again (B, moving away, re-drawing). */
    private fun leaveRows() {
        if (inside == null) return
        inside = null
        markRows()
        showFocus()
    }

    private fun showFocus() {
        if (editing) return
        markRows()
        val round = pal.dots
        renderFooter()
        if (!focusVisible) {
            items.forEach { it.view.foreground = null }
            return
        }
        items.forEachIndexed { i, it ->
            // Same corners as the element itself: round only for the round 1×1 switches of the dot style.
            val v = it.view
            val circle = round && it.round && v.width > 0
            val r = if (circle) v.width / 2f else 22f * d
            val adj = adjusting && i == focus && it.onAdjust != null
            if (pal.cocoon) {
                // Cocoon: a thick accent ring and the selected element lifts a little
                v.foreground = if (i == focus) focusMark(v, r, (if (adj) 6 else 4) * d, pal.primary) else null
                val sc = if (i == focus && !adj && v.width < 420 * d) 1.04f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            } else
            v.foreground = if (i == focus) {
                // the same ring as on dark tiles; on a light (active) tile it is drawn dark instead
                val fill = (v.background as? android.graphics.drawable.GradientDrawable)?.color?.defaultColor ?: 0
                val light = android.graphics.Color.alpha(fill) > 128 && android.graphics.Color.luminance(fill) > 0.6f
                val ring = when { adj -> tileOn(); light -> 0xFF101010.toInt(); else -> pal.onSurface }
                focusMark(v, r, (if (adj) 5 else if (pal.focusCorners) 4 else 3) * d, ring)
            } else null
        }
    }

    private fun move(dx: Int, dy: Int) {
        val best = nearest(dx, dy)
        if (best >= 0) {
            focus = best
            showFocus()
        } else if (dx != 0 && items.getOrNull(focus)?.onAdjust == null) {
            // Past the last element of a page: next/previous page.
            changePage(dx)
        }
    }

    /** Index of the item next to the focused one in a direction, or -1. */
    private fun nearest(dx: Int, dy: Int): Int {
        val cur = items.getOrNull(focus)?.view ?: return -1
        val a = IntArray(2).also(cur::getLocationOnScreen)
        val ax = a[0] + cur.width / 2
        val ay = a[1] + cur.height / 2
        var best = -1
        var bestScore = Float.MAX_VALUE
        items.forEachIndexed { i, it ->
            if (i == focus) return@forEachIndexed
            val b = IntArray(2).also(it.view::getLocationOnScreen)
            val vx = b[0] + it.view.width / 2 - ax
            val vy = b[1] + it.view.height / 2 - ay
            val along = vx * dx + vy * dy
            if (along <= 0) return@forEachIndexed
            val score = along + (abs(vx * dy) + abs(vy * dx)) * 2f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    // ---------- arranging the grid (Y → Anordnen) ----------

    private fun startEdit() {
        closeMenu()
        // Freeze every element at the place it has now
        placed = GridLayout.place(svc.prefs.ccGrid, computeCols())
        entries = placed.map { it.entry.copy(x = it.x, y = it.y, page = it.page) }.toMutableList()
        editing = true
        holding = false
        cursorX = 0
        cursorY = 0
        renderGrid()
    }

    /** Long press on an element: edit mode starts with this element (the finger can keep dragging it). */
    private fun beginEditAt(idx: Int) {
        val p = placed.getOrNull(idx) ?: return
        if (!editing) {
            entries = placed.map { it.entry.copy(x = it.x, y = it.y, page = it.page) }.toMutableList()
            editing = true
            holding = false
            closeMenu()
        }
        cursorX = p.x
        cursorY = p.y
        // the grid is re-drawn when the finger lets go (re-drawing now would end the drag)
    }

    /** Drag & drop: put element [idx] at cell x/y if it fits there. */
    private fun dropAt(idx: Int, x: Int, y: Int) {
        val p = placed.getOrNull(idx) ?: return renderGrid()
        if (fitsAt(p.entry, x, y, gridPage, idx)) {
            entries[idx] = p.entry.copy(x = x, y = y, page = gridPage)
            cursorX = x
            cursorY = y
        }
        renderGrid()
    }

    private fun finishEdit() {
        svc.prefs.ccGrid = placed.map { it.entry.copy(x = it.x, y = it.y, page = it.page) }
        editing = false
        holding = false
        renderGrid()
    }

    private fun editKey(code: Int) {
        val el = elementAt(cursorX, cursorY)
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val dx = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                val dy = when (code) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
                if (holding && el >= 0) {
                    // Move the held element one cell
                    val p = placed[el]
                    val nx = p.x + dx
                    val ny = p.y + dy
                    if (!fitsAt(p.entry, nx, ny, gridPage, el)) return
                    entries[el] = p.entry.copy(x = nx, y = ny, page = gridPage)
                    cursorX += dx
                    cursorY += dy
                    renderGrid()
                    return
                }
                // Cursor: jump over the element it is on
                var nx = cursorX + dx
                var ny = cursorY + dy
                if (el >= 0) {
                    val p = placed[el]
                    if (dx > 0) nx = p.x + p.entry.w
                    if (dx < 0) nx = p.x - 1
                    if (dy > 0) ny = p.y + p.entry.h
                    if (dy < 0) ny = p.y - 1
                }
                if (nx < 0 || nx >= gridCols) { changePage(if (nx < 0) -1 else 1); cursorX = if (nx < 0) gridCols - 1 else 0; renderGrid(); return }
                if (ny < 0 || ny >= GridLayout.ROWS) return
                cursorX = nx
                cursorY = ny
                renderGrid()
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                holding -> { holding = false; renderGrid() }
                el >= 0 -> elementMenu(el)
                else -> addMenu()
            }
            KeyEvent.KEYCODE_BUTTON_X -> if (el >= 0) {
                entries.removeAt(el)
                holding = false
                renderGrid()
            }
            KeyEvent.KEYCODE_BUTTON_Y -> if (el < 0) addMenu() else elementMenu(el)
            KeyEvent.KEYCODE_BUTTON_L1 -> changePage(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> changePage(1)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (holding) { holding = false; renderGrid() } else finishEdit()
        }
    }

    private fun sizeLabel(w: Int, h: Int) = "$w×$h" + when {
        w == 1 && h == 1 -> tr(" (klein)")
        h > w -> tr(" (hoch)")
        w > h -> tr(" (breit)")
        else -> tr(" (groß)")
    }

    /** A on an element: move, size, remove. */
    private fun elementMenu(el: Int) {
        val p = placed.getOrNull(el) ?: return
        val e = p.entry
        showMenu(GridLayout.name(e.id), listOf(
            MenuEntry(tr("Verschieben"), hint = tr("Mit dem Steuerkreuz, A legt ab")) { holding = true; cursorX = p.x; cursorY = p.y; renderGrid() },
            MenuEntry(tr("Größe"), hint = sizeLabel(e.w, e.h)) {
                val options = GridLayout.sizes(e.id, gridCols).filter { (w, h) -> fitsAt(e.copy(w = w, h = h), p.x, p.y, gridPage, el) }
                showMenu(tr("Größe"), options.map { (w, h) ->
                    MenuEntry(sizeLabel(w, h), checked = w == e.w && h == e.h) {
                        entries[el] = e.copy(w = w, h = h, x = p.x, y = p.y, page = gridPage)
                        renderGrid()
                    }
                })
            },
            MenuEntry(tr("Entfernen")) {
                entries.removeAt(el)
                renderGrid()
            },
        ))
    }

    /** A on a free cell: choose what goes there, sorted by type. */
    private fun addMenu() {
        val rootOk = Root.available()
        // everything may be added more than once (e.g. a small and a large music tile)
        val present = entries.map { it.id }.toSet()
        fun entryFor(id: String): MenuEntry = MenuEntry(GridLayout.name(id),
            hint = if (id in present) t("Schon im Raster · noch einmal hinzufügen", "Already in the grid · add another") else null) { placeAtCursor(id) }
        val switches = TileCatalog.all.keys.filter { tileFor(it, rootOk) != null }
        val infos = GridLayout.widgetNames.keys.filter { GridLayout.isInfo(it) }
        val sliders = GridLayout.widgetNames.keys.filter { GridLayout.isSlider(it) }
        // categories side by side: ◀ ▶ switches between them instead of scrolling through one long list
        val tabs = listOf(
            tr("Anzeigen") to infos.map { entryFor(it) },
            tr("Regler") to sliders.map { entryFor(it) },
            tr("Schalter") to switches.map { entryFor(it) },
        ).filter { it.second.isNotEmpty() }
        tabMenu(tr("Hinzufügen"), tabs, addTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    }

    /** Last category of the "add" picker (it opens there again). */
    private var addTab = 0

    /** Menu with categories as tabs: ◀ ▶ (or L1/R1, or a tap) changes the category. */
    private fun tabMenu(title: String, tabs: List<Pair<String, List<MenuEntry>>>, tab: Int) {
        if (tabs.isEmpty()) { showMenu(title, listOf(MenuEntry(tr("Alles ist schon im Raster"), run = null))); return }
        addTab = tab
        curTab = tab
        // already open: only the tabs and the list change, the window stays (no flicker)
        val openList = menuList
        val openBar = tabBar
        if (menuView != null && openList != null && openBar != null && openBar.parent === menuView) {
            menuEntries = tabs[tab].second
            menuRows.clear()
            openList.removeAllViews()
            fillMenuRows(openList, menuEntries)
            menuSel = menuEntries.indexOfFirst { it.run != null }.coerceAtLeast(0)
            menuScroll?.scrollTo(0, 0)
            styleTabs(openBar, tabs, tab, title)
            showMenuSel()
            setTabKeys(title, tabs, tab)
            return
        }
        showMenu(title, tabs[tab].second, forceTall = true)
        val box = menuView as? LinearLayout ?: return
        val bar = LinearLayout(svc).apply { setPadding(10 * d, 0, 10 * d, 10 * d); gravity = Gravity.CENTER_VERTICAL }
        tabBar = bar
        styleTabs(bar, tabs, tab, title)
        box.addView(bar, 1)
        setTabKeys(title, tabs, tab)
    }

    private var tabBar: LinearLayout? = null
    /** The tab shown last in a tab menu. */
    private var curTab = 0

    /** Draws the tab row: ◀ tabs ▶, the chosen one filled. */
    private fun styleTabs(bar: LinearLayout, tabs: List<Pair<String, List<MenuEntry>>>, tab: Int, title: String) {
        bar.removeAllViews()
        bar.addView(text("◀", 13f, pal.onSurfaceVariant, bold = true).apply { setPadding(0, 0, 8 * d, 0) })
        tabs.forEachIndexed { i, (name, _) ->
            val sel = i == tab
            bar.addView(text(name, 14f, if (sel) tileOnFg() else pal.onSurface, bold = sel).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                background = shape(if (sel) tileOn() else pal.surfaceHighest, 14f * d)
                setOnClickListener { tabMenu(title, tabs, i) }
            }, lp(0, 36 * d, 1f).apply { marginEnd = 6 * d })
        }
        bar.addView(text("▶", 13f, pal.onSurfaceVariant, bold = true).apply { setPadding(2 * d, 0, 0, 0) })
    }

    private fun setTabKeys(title: String, tabs: List<Pair<String, List<MenuEntry>>>, tab: Int) {
        popupKeys = { code ->
            when (code) {
                // in the Capture menu ◀ ▶ only change values; the tabs there go with L1/R1
                KeyEvent.KEYCODE_DPAD_LEFT -> menuEntries.getOrNull(menuSel)?.side?.invoke(-1) ?: if (title != t("Aufnahme", "Capture") && title != tr("Bearbeiten")) tabMenu(title, tabs, (tab - 1 + tabs.size) % tabs.size) else Unit
                KeyEvent.KEYCODE_DPAD_RIGHT -> menuEntries.getOrNull(menuSel)?.side?.invoke(1) ?: if (title != t("Aufnahme", "Capture") && title != tr("Bearbeiten")) tabMenu(title, tabs, (tab + 1) % tabs.size) else Unit
                KeyEvent.KEYCODE_BUTTON_L1 -> tabMenu(title, tabs, (tab - 1 + tabs.size) % tabs.size)
                KeyEvent.KEYCODE_BUTTON_R1 -> tabMenu(title, tabs, (tab + 1) % tabs.size)
                KeyEvent.KEYCODE_DPAD_UP -> moveMenu(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveMenu(1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> runMenu(menuSel)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> closeMenu()
            }
        }
    }


    /** Puts [id] at the cursor in the largest preferred size that fits there. */
    private fun placeAtCursor(id: String) {
        if (!editing) entries = svc.prefs.ccGrid.toMutableList()
        val preferred = when {
            GridLayout.isSlider(id) -> listOf(1 to 2, 2 to 1, 1 to 3, 3 to 1)
            id == "system" -> listOf(4 to 2, 4 to 4)
            id == "fps" -> listOf(2 to 2, 2 to 1)
            id == "perf" || id == "fan" -> listOf(2 to 1, 2 to 2, 3 to 2)
            GridLayout.isInfo(id) -> listOf(2 to 1, 1 to 1, 2 to 2)
            else -> listOf(1 to 1, 2 to 1)
        }
        val size = preferred.firstOrNull { (w, h) -> fitsAt(GridEntry(id, w, h), cursorX, cursorY, gridPage, -1) } ?: run {
            // too big for this spot: the first free place (a new page if needed)
            val (w, h) = preferred.first()
            entries.add(GridEntry(id, w.coerceAtMost(gridCols), h.coerceAtMost(GridLayout.ROWS)))
            added(id)
            return
        }
        entries.add(GridEntry(id, size.first, size.second, cursorX, cursorY, gridPage))
        added(id)
    }

    /** Saves the new element (outside the arrange mode) and shows the page it landed on. */
    private fun added(id: String) {
        if (!editing) svc.prefs.ccGrid = entries.toList()
        renderGrid()
        val p = placed.lastOrNull { it.entry.id == id } ?: return
        if (p.page != gridPage) { gridPage = p.page; renderGrid() }
        Notice.show(svc, t("${GridLayout.name(id)} hinzugefügt", "${GridLayout.name(id)} added") + if (pages() > 1) t(" · Seite ${p.page + 1}", " · page ${p.page + 1}") else "", corner = true)
    }

    // ---------- editing (Y): layout, tiles, colours, style ----------

    private fun reopen() {
        close()
        svc.ui.postDelayed({ open() }, 60)
    }

    /**
     * The edit menu (Y) in four categories, switched with L1/R1: layout, style, background, overlay.
     * Values change in their line with ◀ ▶ and show right away behind the menu.
     */
    private fun editMenu(tab: Int = 0, select: Int = -1) {
        val p = svc.prefs
        val title = tr("Bearbeiten")
        fun arrows(v: String) = "◀  $v  ▶"
        fun <T> step(list: List<T>, cur: T, dir: Int): T = list[(list.indexOf(cur).coerceAtLeast(0) + dir).mod(list.size)]
        // shows the change: the panel is rebuilt with fresh colours, the menu keeps its place
        fun refresh(at: Int) { pal = Palette(svc); rebuildPanel(); editMenu(curTab, at) }
        // style and colour scheme change the menu itself too: the Control Center opens anew, the menu with it
        fun restyle(at: Int) {
            val tb = curTab
            pal = Palette(svc)
            rebuildPanel()
            // the menu window itself takes the new look too: drawn anew in place, the category stays
            removeMenuView()
            tabBar = null
            editMenu(tb, at)
        }
        fun value(label: String, show: () -> String, hint: String? = null, rebuild: Boolean = true, onA: (() -> Unit)? = null, change: (Int) -> Unit): MenuEntry {
            val side: (Int) -> Unit = { dir ->
                change(dir)
                if (rebuild) refresh(menuSel) else setMenuHint(menuSel, arrows(show()))
            }
            return MenuEntry(if (hint != null) "$label · $hint" else label, hint = arrows(show()), side = side) { onA?.invoke() ?: side(1) }
        }

        // ---- layout ----
        val layout = mutableListOf<MenuEntry>()
        layout += MenuEntry(tr("Anordnen & Größe"), hint = tr("Elemente verschieben, vergrößern, entfernen")) { startEdit() }
        layout += MenuEntry(tr("Element hinzufügen"), hint = tr("Schalter, Anzeigen und Regler")) { addMenu() }
        layout += MenuEntry(t("Raster", "Grid"), header = true, run = null)
        layout += value(t("Zeilen", "Rows"), { "${p.gridRows}" }, rebuild = false) { dir ->
            p.gridRows = (p.gridRows + dir).coerceIn(3, 6); renderGrid()
        }
        layout += value(t("Spalten", "Columns"), { if (p.gridCols == 0) t("Automatisch ($gridCols)", "Automatic ($gridCols)") else "${p.gridCols}" }, rebuild = false) { dir ->
            p.gridCols = step(listOf(0, 4, 5, 6, 7, 8, 9, 10), p.gridCols, dir); renderGrid()
        }
        layout += MenuEntry(t("Zurücksetzen", "Reset"), header = true, run = null)
        layout += MenuEntry(tr("Standard wiederherstellen"), hint = tr("Layout, Kacheln, Größen und Farben zurücksetzen")) {
            showMenu(tr("Wirklich zurücksetzen?"), listOf(
                MenuEntry(tr("Ja, Standard wiederherstellen")) { svc.prefs.resetControlCenter(); reopen() },
                MenuEntry(tr("Abbrechen")) {},
            ))
        }

        // ---- style ----
        val style = mutableListOf<MenuEntry>()
        style += value(tr("Stil"), { p.uiStyle.label }, rebuild = false) { dir ->
            p.uiStyle = step(UiStyle.values().toList(), p.uiStyle, dir); restyle(menuSel)
            val at = menuSel
            Backdrop.prepareGlass(svc) { if (isOpen && menuView != null) restyle(at) }
        }
        style += value(tr("Farbschema"), { p.theme.label }, rebuild = false) { dir ->
            p.theme = step(Theme.values().toList(), p.theme, dir); restyle(menuSel)
        }
        val iColor = style.size
        style += value(tr("Kachelfarbe"), { TILE_COLORS[p.tileColor] ?: tr("Eigene") }, hint = t("A: Farbrad", "A: colour wheel"), rebuild = false,
            onA = { tileColorWheel { restyle(iColor) } }) { dir ->
            p.tileColor = step(TILE_COLORS.keys.toList(), p.tileColor, dir); restyle(menuSel)
        }
        style += value(tr("Farben der Anzeigen"), { p.gaugePalette.label }) { dir ->
            p.gaugePalette = step(GaugePalette.values().toList(), p.gaugePalette, dir)
        }

        // ---- background & tiles ----
        val bg = mutableListOf<MenuEntry>()
        bg += MenuEntry(t("Hintergrund", "Background"), header = true, run = null)
        val sources = BackdropSource.values().filter { it != BackdropSource.WALLPAPER || Root.available() }
        val srcHint = when {
            p.backdrop == BackdropSource.IMAGE -> if (Backdrop.imageFile(svc).exists()) t("A: anderes Bild", "A: other picture") else t("A: Bild wählen", "A: choose picture")
            p.backdrop == BackdropSource.WALLPAPER -> t("A: neu laden", "A: reload")
            else -> null
        }
        bg += value(t("Bild", "Picture"), {
            val used = Backdrop.source(svc)
            if (used != p.backdrop) "${used.label} (Liquid Glass)" else p.backdrop.label
        }, hint = srcHint, onA = {
            when (p.backdrop) {
                BackdropSource.IMAGE -> {
                    // picking needs an app screen: Hugminn's settings open the picture picker
                    close()
                    svc.startApp(Intent(svc, MainActivity::class.java).putExtra(MainActivity.EXTRA_PICK_BACKDROP, true))
                }
                BackdropSource.WALLPAPER -> Backdrop.fetchWallpaper(svc) { ok ->
                    if (!ok) Notice.show(svc, t("Hintergrundbild konnte nicht gelesen werden", "Couldn't read the wallpaper"), corner = true)
                    if (isOpen && menuView != null) refresh(menuSel)
                }
                BackdropSource.STYLE -> { p.backdrop = step(sources, p.backdrop, 1); refresh(menuSel) }
            }
        }) { dir ->
            p.backdrop = step(sources, p.backdrop, dir)
            if (p.backdrop == BackdropSource.WALLPAPER && !Backdrop.wallFile(svc).exists()) Backdrop.fetchWallpaper(svc) { if (isOpen && menuView != null) refresh(menuSel) }
        }
        if (Backdrop.source(svc) != BackdropSource.STYLE) {
            bg += value(if (pal.dark) t("Abdunkeln", "Darken") else t("Aufhellen", "Lighten"), { "${p.backdropDim} %" }) { dir ->
                p.backdropDim = (p.backdropDim + 10 * dir).coerceIn(0, 80)
            }
            val blurNames = listOf(tr("Aus"), t("Leicht", "Light"), t("Mittel", "Medium"), t("Stark", "Strong"))
            bg += value(t("Weichzeichnen", "Blur"), { blurNames[p.backdropBlur.coerceIn(0, 3)] }) { dir ->
                p.backdropBlur = (p.backdropBlur + dir).coerceIn(0, 3); Backdrop.forget()
            }
        }
        bg += MenuEntry(t("Kacheln", "Tiles"), header = true, run = null)
        bg += value(t("Kacheln", "Tiles"), { p.tileStyle.label },
            hint = when {
                p.uiStyle == UiStyle.GLASS -> t("Stil Liquid Glass: immer Glas", "Liquid Glass style: always glass")
                p.tileStyle == TileStyle.OLED && !pal.dark -> t("nur im dunklen Farbschema", "dark colour schemes only")
                else -> null
            }) { dir ->
            p.tileStyle = step(TileStyle.values().toList(), p.tileStyle, dir)
        }
        if (pal.tileStyle == TileStyle.GLASS) {
            val names = listOf(t("klar", "clear"), t("leicht", "light"), t("mittel", "medium"), t("kräftig", "strong"), t("milchig", "frosted"))
            bg += value(t("Glas-Stärke", "Glass strength"), { "${p.glassLevel}/5 · ${names[p.glassLevel - 1]}" }) { dir ->
                p.glassLevel = p.glassLevel + dir
            }
        }
        bg += MenuEntry(t("Auswahl", "Selection"), header = true, run = null)
        bg += value(t("Markierung", "Mark"), { p.focusStyle.label }, rebuild = false,
            hint = if (p.focusStyle == FocusStyle.AUTO) t("Ecken bei Cocoon, sonst Rahmen", "corners in Cocoon, else frame") else null) { dir ->
            p.focusStyle = step(FocusStyle.values().toList(), p.focusStyle, dir)
            pal = Palette(svc)
            focusVisible = true
            showFocus()
        }

        // ---- performance overlay ----
        val ov = mutableListOf<MenuEntry>()
        val iOn = ov.size
        ov += MenuEntry(tr("Leistungs-Overlay"), checked = p.overlayOn, hint = tr("Kleine Anzeige über dem Spiel, wie bei Steam")) {
            p.overlayOn = !p.overlayOn
            svc.overlay.refresh()
            editMenu(curTab, iOn)
        }
        ov += MenuEntry(tr("Angezeigte Werte"), hint = p.overlayItems.mapNotNull { OverlayItem.of(it)?.label }.joinToString(", ")) {
            openEditor(tr("Overlay-Werte"), listOf(
                EditSection(tr("Angezeigt"), p.overlayItems.toMutableList()),
                EditSection(tr("Ausgeblendet"), OverlayItem.values().map { it.id }.filter { it !in p.overlayItems }.toMutableList()),
            ), enableTo = 0, label = { OverlayItem.of(it)?.label ?: it }, note = { "" }) {
                p.overlayItems = editSections[0].ids.toList()
                if (p.overlayOn) svc.overlay.show()
            }
        }
        ov += value("Position", { p.overlayPos.label }, rebuild = false) { dir ->
            p.overlayPos = step(OverlayPos.values().toList(), p.overlayPos, dir); if (p.overlayOn) svc.overlay.show()
        }
        ov += value(tr("Anordnung"), { if (p.overlayRow) tr("Nebeneinander") else tr("Untereinander") }, rebuild = false) { _ ->
            p.overlayRow = !p.overlayRow; if (p.overlayOn) svc.overlay.show()
        }
        ov += value(tr("Größe"), { "${Math.round(p.overlayScale * 100)} %" }, rebuild = false) { dir ->
            val sizes = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
            p.overlayScale = sizes[(sizes.indexOfFirst { it >= p.overlayScale - 0.01f }.coerceAtLeast(0) + dir).coerceIn(0, sizes.lastIndex)]
            if (p.overlayOn) svc.overlay.show()
        }

        // the selection mark stays shown on the Control Center, so mark and tile changes can be seen
        focusVisible = true
        svc.ui.post { if (isOpen) showFocus() }
        val keep = addTab
        tabMenu(title, listOf(
            "Layout" to layout,
            tr("Stil") to style,
            t("Hintergrund", "Background") to bg,
            "Overlay" to ov,
        ), tab)
        addTab = keep
        if (select >= 0 && menuEntries.getOrNull(select)?.run != null) { menuSel = select; showMenuSel() }
    }

    private fun gridHint() = t("${GridLayout.ROWS} Zeilen · ", "${GridLayout.ROWS} rows · ") +
        if (svc.prefs.gridCols > 0) t("${svc.prefs.gridCols} Spalten", "${svc.prefs.gridCols} columns") else t("Spalten automatisch ($gridCols)", "columns automatic ($gridCols)")

    /** Rows and columns of the grid, right here; the grid changes at once behind the menu. */
    private fun gridMenu(select: Int = -1) {
        val list = mutableListOf<MenuEntry>()
        list += MenuEntry(t("Zeilen", "Rows"), header = true, run = null)
        (3..6).forEach { r ->
            val at = list.size
            list += MenuEntry("$r", checked = svc.prefs.gridRows == r) { svc.prefs.gridRows = r; renderGrid(); gridMenu(at) }
        }
        list += MenuEntry(t("Spalten", "Columns"), header = true, run = null)
        val autoAt = list.size
        list += MenuEntry(t("Automatisch", "Automatic"), checked = svc.prefs.gridCols == 0,
            hint = t("So viele quadratische Felder, wie passen", "As many square cells as fit")) { svc.prefs.gridCols = 0; renderGrid(); gridMenu(autoAt) }
        (4..10).forEach { c ->
            val at = list.size
            list += MenuEntry("$c", checked = svc.prefs.gridCols == c) { svc.prefs.gridCols = c; renderGrid(); gridMenu(at) }
        }
        showMenu(t("Raster", "Grid"), list, select)
    }

    private fun overlayMenu(select: Int = -1) {
        val p = svc.prefs
        showMenu(tr("Leistungs-Overlay"), listOf(
            MenuEntry(if (p.overlayOn) tr("Overlay ausschalten") else tr("Overlay einschalten"), hint = tr("Kleine Anzeige über dem Spiel, wie bei Steam")) {
                p.overlayOn = !p.overlayOn
                svc.overlay.refresh()
            },
            MenuEntry(tr("Angezeigte Werte"), hint = p.overlayItems.mapNotNull { OverlayItem.of(it)?.label }.joinToString(", ")) {
                openEditor(tr("Overlay-Werte"), listOf(
                    EditSection(tr("Angezeigt"), p.overlayItems.toMutableList()),
                    EditSection(tr("Ausgeblendet"), OverlayItem.values().map { it.id }.filter { it !in p.overlayItems }.toMutableList()),
                ), enableTo = 0, label = { OverlayItem.of(it)?.label ?: it }, note = { "" }) {
                    p.overlayItems = editSections[0].ids.toList()
                    if (p.overlayOn) svc.overlay.show()
                }
            },
            // position and layout change with ◀ ▶ like the size (A steps on too)
            run {
                val all = OverlayPos.values()
                val side: (Int) -> Unit = { dir ->
                    p.overlayPos = all[((p.overlayPos.ordinal + dir) % all.size + all.size) % all.size]
                    if (p.overlayOn) svc.overlay.show()
                    setMenuHint(menuSel, "◀  ${p.overlayPos.label}  ▶")
                }
                MenuEntry("Position", hint = "◀  ${p.overlayPos.label}  ▶", side = side) { side(1) }
            },
            run {
                fun lbl() = if (p.overlayRow) tr("Nebeneinander") else tr("Untereinander")
                val side: (Int) -> Unit = { _ ->
                    p.overlayRow = !p.overlayRow
                    if (p.overlayOn) svc.overlay.show()
                    setMenuHint(menuSel, "◀  ${lbl()}  ▶")
                }
                MenuEntry(tr("Anordnung"), hint = "◀  ${lbl()}  ▶", side = side) { side(1) }
            },
            MenuEntry(tr("Größe"), hint = "◀  ${Math.round(p.overlayScale * 100)} %  ▶", side = { dir ->
                val sizes = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                val i = (sizes.indexOfFirst { it >= p.overlayScale - 0.01f }.coerceAtLeast(0) + dir).coerceIn(0, sizes.lastIndex)
                if (sizes[i] != p.overlayScale) {
                    p.overlayScale = sizes[i]
                    if (p.overlayOn) svc.overlay.show()
                    setMenuHint(menuSel, "◀  ${Math.round(p.overlayScale * 100)} %  ▶")
                }
            }) {},
        ), select)
    }


    private fun colorMenu() {
        val own = svc.prefs.tileColor.takeIf { it != 0 && it !in TILE_COLORS }
        showMenu(tr("Kachelfarbe"), TILE_COLORS.map { (c, name) ->
            MenuEntry(name, checked = svc.prefs.tileColor == c, swatch = if (c == 0) pal.primary else c) {
                svc.prefs.tileColor = c
                reopen()
            }
        } + MenuEntry(tr("Eigene Farbe …"), checked = own != null, hint = tr("Aus dem Farbrad wählen"), swatch = own ?: 0) { svc.ui.post { tileColorWheel() } })
    }

    /** A list split into sections (e.g. left column / right column / hidden); items move across sections. */
    private class EditSection(val title: String, val ids: MutableList<String>)

    private var editView: View? = null
    private var editScroll: android.widget.ScrollView? = null
    private var editListView: LinearLayout? = null
    private var editSections: List<EditSection> = emptyList()
    private var editLabel: (String) -> String = { it }
    private var editNote: (String) -> String = { "" }
    private var editEnableTo = 0
    private var editDone: () -> Unit = {}
    private var editAdjust: ((String, Int) -> Unit)? = null
    private var editLive: (() -> Unit)? = null
    private var editSel = 0

    private fun flat() = editSections.flatMapIndexed { si, sec -> sec.ids.indices.map { si to it } }

    private fun openEditor(
        title: String, sections: List<EditSection>, enableTo: Int, label: (String) -> String, note: (String) -> String,
        adjust: ((String, Int) -> Unit)? = null, live: (() -> Unit)? = null, done: () -> Unit,
    ) {
        val r = root ?: return
        closeMenu()
        editAdjust = adjust
        editLive = live
        editSections = sections
        editLabel = label
        editNote = note
        editEnableTo = enableTo
        editDone = done
        editSel = 0
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(12 * d, 16 * d, 12 * d, 12 * d)
            elevation = 12f * d
            isClickable = true
        }
        box.addView(text(title, 18f, pal.onSurface, bold = true).apply { setPadding(12 * d, 0, 12 * d, 4 * d) })
        val help = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12 * d, 0, 12 * d, 10 * d)
        }
        var helpRow: LinearLayout? = null
        fun h(k: Int, t: String) {
            val r0 = helpRow?.takeIf { it.childCount < 4 } ?: LinearLayout(svc).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 2 * d, 0, 2 * d)
                help.addView(this)
                helpRow = this
            }
            r0.addView(Glyphs.chip(svc, svc.prefs, pal, k, 20))
            r0.addView(text(t, 12f, pal.onSurfaceVariant).apply { setPadding(5 * d, 0, 14 * d, 0) })
        }
        h(KeyEvent.KEYCODE_BUTTON_A, tr("ein/aus"))
        if (adjust != null) h(KeyEvent.KEYCODE_DPAD_LEFT, tr("Größe −/+"))
        h(KeyEvent.KEYCODE_BUTTON_L1, tr("nach oben"))
        h(KeyEvent.KEYCODE_BUTTON_R1, tr("nach unten"))
        h(KeyEvent.KEYCODE_BUTTON_B, "fertig")
        box.addView(help)
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        editListView = list
        val sc = android.widget.ScrollView(svc).apply { addView(list) }
        editScroll = sc
        box.addView(sc, lp(MATCH, 0, 1f))
        // With live preview the editor sits on the right so the Control Center stays visible.
        val w = (r.width * (if (live != null) 0.38f else 0.5f)).toInt().coerceAtLeast(340 * d)
        r.addView(box, FrameLayout.LayoutParams(w, (r.height * 0.9f).toInt(), if (live != null) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER).apply {
            if (live != null) marginEnd = 12 * d
        })
        editView = box
        renderEditor()
    }

    private fun renderEditor() {
        val list = editListView ?: return
        list.removeAllViews()
        var flatIndex = 0
        var selView: View? = null
        editSections.forEachIndexed { si, sec ->
            list.addView(text(sec.title, 13f, pal.primary, bold = true).apply { setPadding(12 * d, if (si == 0) 0 else 12 * d, 0, 4 * d) })
            if (sec.ids.isEmpty()) list.addView(text("—", 13f, pal.outline).apply { setPadding(36 * d, 4 * d, 0, 4 * d) })
            sec.ids.forEachIndexed { i, id ->
                val hidden = si == editSections.lastIndex
                val idx = flatIndex++
                val row = LinearLayout(svc).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12 * d, 0, 12 * d, 0)
                    minimumHeight = 42 * d
                    background = if (idx == editSel) shape(pal.secondaryContainer, 16f * d) else null
                    setOnClickListener { editSel = idx; toggleEdit() }
                }
                row.addView(text(if (hidden) "○" else "●", 15f, if (hidden) pal.outline else pal.primary).apply { setPadding(0, 0, 12 * d, 0) })
                row.addView(text(editLabel(id), 15f, pal.onSurface, bold = !hidden), lp(0, WRAP, 1f))
                row.addView(text(editNote(id).ifEmpty { if (hidden) "" else "${i + 1}" }, 12f, pal.onSurfaceVariant))
                list.addView(row, lp(MATCH, WRAP))
                if (idx == editSel) selView = row
            }
        }
        list.post {
            val v = selView ?: return@post
            editScroll?.let { sc ->
                if (v.top < sc.scrollY) sc.scrollTo(0, v.top - 30 * d)
                else if (v.bottom > sc.scrollY + sc.height) sc.scrollTo(0, v.bottom - sc.height)
            }
        }
    }

    private fun toggleEdit() {
        val (si, i) = flat().getOrNull(editSel) ?: return
        val id = editSections[si].ids.removeAt(i)
        val last = editSections.lastIndex
        if (si == last) editSections[editEnableTo].ids.add(id) else editSections[last].ids.add(0, id)
        editSel = flat().indexOfFirst { (s2, j) -> editSections[s2].ids[j] == id }.coerceAtLeast(0)
        renderEditor()
        editLive?.invoke()
    }

    /** Moves the selected item up/down; past the end of a section it moves into the next one. */
    private fun moveEdit(dir: Int) {
        val (si, i) = flat().getOrNull(editSel) ?: return
        val sec = editSections[si].ids
        val id = sec[i]
        if (dir < 0) {
            if (i > 0) { sec[i] = sec[i - 1]; sec[i - 1] = id }
            else if (si > 0) { sec.removeAt(i); editSections[si - 1].ids.add(id) } else return
        } else {
            if (i < sec.lastIndex) { sec[i] = sec[i + 1]; sec[i + 1] = id }
            else if (si < editSections.lastIndex) { sec.removeAt(i); editSections[si + 1].ids.add(0, id) } else return
        }
        editSel = flat().indexOfFirst { (s2, j) -> editSections[s2].ids[j] == id }.coerceAtLeast(0)
        renderEditor()
        editLive?.invoke()
    }

    private fun adjustEdit(dir: Int) {
        val (si, i) = flat().getOrNull(editSel) ?: return
        editAdjust?.invoke(editSections[si].ids[i], dir) ?: return
        renderEditor()
        editLive?.invoke()
    }

    private fun closeEditor() {
        editView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        editView = null
        editDone()
    }

    private fun layoutEditor() {
        val (left, right) = svc.prefs.ccLayout
        val hidden = CcBlock.values().map { it.id }.filter { it !in left && it !in right }
        openEditor(tr("Layout anpassen"), listOf(
            EditSection(tr("Linke Spalte"), left.toMutableList()),
            EditSection(tr("Rechte Spalte"), right.toMutableList()),
            EditSection(tr("Ausgeblendet"), hidden.toMutableList()),
        ), enableTo = 1, label = { CcBlock.of(it)?.label ?: it },
            note = { id -> t("Größe ${Math.round(size(id) * 100)} %", "Size ${Math.round(size(id) * 100)} %") },
            adjust = { id, dir ->
                val m = svc.prefs.ccSizes.toMutableMap()
                m[id] = ((m[id] ?: 1f) + 0.2f * dir).coerceIn(0.6f, 2.0f)
                svc.prefs.ccSizes = m
            },
            live = {
                svc.prefs.ccLayout = editSections[0].ids.toList() to editSections[1].ids.toList()
                rebuildPanel()
                editView?.bringToFront()
            },
        ) {
            svc.prefs.ccLayout = editSections[0].ids.toList() to editSections[1].ids.toList()
            rebuildPanel()
        }
    }

    // ---------- tile editor like stock Android: grid of shown tiles, grid of tiles to add ----------

    private var gridEdit: View? = null
    private val geActive = mutableListOf<String>()
    private val geAvail = mutableListOf<String>()
    private var geSel = 0
    private var geMoving = false
    private var geActiveGrid: LinearLayout? = null
    private var geAvailGrid: LinearLayout? = null
    private var geScroll: android.widget.ScrollView? = null
    private var geTitle: TextView? = null
    private val geCols = 6

    private fun tileEditor() {
        val r = root ?: return
        closeMenu()
        val rootOk = Root.available()
        geActive.clear()
        geActive += svc.prefs.ccTiles.filter { tileFor(it, rootOk) != null }
        geAvail.clear()
        geAvail += TileCatalog.all.keys.filter { it !in geActive && tileFor(it, rootOk) != null }
        geSel = 0
        geMoving = false
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(18 * d, 16 * d, 18 * d, 12 * d)
            elevation = 12f * d
            isClickable = true
        }
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(tr("Kacheln bearbeiten"), 18f, pal.onSurface, bold = true), lp(0, WRAP, 1f))
        fun h(k: Int, t: String) {
            head.addView(Glyphs.chip(svc, svc.prefs, pal, k, 20))
            head.addView(text(t, 12f, pal.onSurfaceVariant).apply { setPadding(5 * d, 0, 12 * d, 0) })
        }
        h(KeyEvent.KEYCODE_BUTTON_A, tr("Hinzufügen / Verschieben"))
        h(KeyEvent.KEYCODE_BUTTON_X, tr("Entfernen"))
        h(KeyEvent.KEYCODE_BUTTON_B, tr("Fertig"))
        box.addView(head)
        val content = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val t1 = text("", 13f, pal.primary, bold = true).apply { setPadding(4 * d, 12 * d, 0, 6 * d) }
        geTitle = t1
        content.addView(t1)
        val g1 = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        geActiveGrid = g1
        content.addView(g1)
        content.addView(View(svc).apply { setBackgroundColor(pal.outline) }, lp(MATCH, 1 * d).apply { topMargin = 12 * d; bottomMargin = 4 * d })
        content.addView(text(tr("Zum Hinzufügen auswählen"), 13f, pal.primary, bold = true).apply { setPadding(4 * d, 8 * d, 0, 6 * d) })
        val g2 = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        geAvailGrid = g2
        content.addView(g2)
        val sc = android.widget.ScrollView(svc).apply { addView(content) }
        geScroll = sc
        box.addView(sc, lp(MATCH, 0, 1f))
        r.addView(box, FrameLayout.LayoutParams((r.width * 0.9f).toInt(), (r.height * 0.9f).toInt(), Gravity.CENTER))
        gridEdit = box
        renderTileGrid()
    }

    private fun renderTileGrid() {
        val rootOk = Root.available()
        geTitle?.text = t("Angezeigt (${geActive.size}) · Reihenfolge wie im Control Center", "Shown (${geActive.size}) · same order as in the Control Center")
        var selView: View? = null
        fun fill(grid: LinearLayout?, ids: List<String>, offset: Int) {
            grid ?: return
            grid.removeAllViews()
            if (ids.isEmpty()) grid.addView(text("—", 14f, pal.outline).apply { setPadding(8 * d, 8 * d, 0, 8 * d) })
            ids.chunked(geCols).forEachIndexed { ri, chunk ->
                val row = LinearLayout(svc)
                chunk.forEachIndexed { ci, id ->
                    val idx = offset + ri * geCols + ci
                    val t = tileFor(id, rootOk) ?: return@forEachIndexed
                    val selected = idx == geSel
                    val moving = selected && geMoving
                    val icon = IconView(svc, t.icon, 24).apply { setColor(if (moving) tileOnFg() else pal.onSurface) }
                    val circle = FrameLayout(svc).apply {
                        background = if (pal.dots) rounded(if (moving) tileOn() else pal.surfaceHighest, 26f * d)
                        else shape(if (moving) tileOn() else pal.surfaceHighest, 14f * d)
                        foreground = if (selected) shape(0, if (pal.dots) 26f * d else 14f * d, 3 * d, pal.onSurface) else null
                        addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    }
                    val cell = LinearLayout(svc).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(2 * d, 4 * d, 2 * d, 4 * d)
                        addView(circle, LinearLayout.LayoutParams(52 * d, 52 * d))
                        addView(TextView(svc).apply {
                            text = t.title
                            gravity = Gravity.CENTER
                            maxLines = 2
                            setTextColor(pal.onSurface)
                            typeface = Look.font(svc, pixel, selected)
                            setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                        }, lp(MATCH, 30 * d).apply { topMargin = 4 * d })
                        setOnClickListener { geSel = idx; gridA() }
                    }
                    row.addView(cell, lp(0, WRAP, 1f))
                    if (selected) selView = cell
                }
                repeat(geCols - chunk.size) { row.addView(View(svc), lp(0, 1, 1f)) }
                grid.addView(row, lp(MATCH, WRAP))
            }
        }
        fill(geActiveGrid, geActive, 0)
        fill(geAvailGrid, geAvail, geActive.size)
        geScroll?.post {
            val v = selView ?: return@post
            val sc = geScroll ?: return@post
            val loc = IntArray(2).also(v::getLocationInWindow)
            val sloc = IntArray(2).also(sc::getLocationInWindow)
            val top = loc[1] - sloc[1]
            if (top < 0) sc.smoothScrollBy(0, top - 10 * d) else if (top + v.height > sc.height) sc.smoothScrollBy(0, top + v.height - sc.height + 10 * d)
        }
    }

    private fun gridMove(dx: Int, dy: Int) {
        val total = geActive.size + geAvail.size
        if (total == 0) return
        if (geMoving && geSel < geActive.size) {
            // Move the held tile within the shown tiles
            val to = (geSel + dx + dy * geCols).coerceIn(0, geActive.lastIndex)
            if (to != geSel) {
                val id = geActive.removeAt(geSel)
                geActive.add(to, id)
                geSel = to
            }
        } else {
            val inActive = geSel < geActive.size
            var to = geSel + dx + dy * geCols
            // Crossing between the two grids keeps the column where possible
            if (dy > 0 && inActive && to >= geActive.size) to = geActive.size + (geSel % geCols).coerceAtMost((geAvail.size - 1).coerceAtLeast(0))
            if (dy < 0 && !inActive && to < geActive.size) {
                val col = (geSel - geActive.size) % geCols
                val lastRowStart = (geActive.size - 1).coerceAtLeast(0) / geCols * geCols
                to = (lastRowStart + col).coerceAtMost(geActive.size - 1)
            }
            geSel = to.coerceIn(0, total - 1)
        }
        renderTileGrid()
    }

    private fun gridA() {
        if (geSel < geActive.size) {
            geMoving = !geMoving
        } else {
            val i = geSel - geActive.size
            val id = geAvail.removeAt(i)
            geActive += id
            // stay on the next tile to add
            geSel = (geActive.size + i).coerceAtMost(geActive.size + geAvail.size - 1)
        }
        renderTileGrid()
    }

    private fun gridRemove() {
        if (geSel >= geActive.size) return
        val id = geActive.removeAt(geSel)
        geAvail.add(0, id)
        geMoving = false
        geSel = geSel.coerceAtMost((geActive.size + geAvail.size - 1).coerceAtLeast(0))
        renderTileGrid()
    }

    private fun gridDone() {
        if (geMoving) {
            geMoving = false
            renderTileGrid()
            return
        }
        svc.prefs.ccTiles = geActive.toList()
        gridEdit?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        gridEdit = null
        rebuildPanel()
    }

    private var aDownAt = 0L
    private var aArmed = false
    private var aLong = false
    private val aLongPress = Runnable {
        if (!isOpen || editing || menuView != null || !focusVisible) return@Runnable
        aLong = true
        val idx = itemEntry.getOrNull(focus) ?: return@Runnable
        beginEditAt(idx)
        renderGrid()
    }

    /** Music without leaving the grid: Start plays/pauses, L2/R2 previous/next track. */
    private fun mediaKey(code: Int) {
        val k = when (code) {
            KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            KeyEvent.KEYCODE_BUTTON_L2 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            KeyEvent.KEYCODE_BUTTON_R2 -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return
        }
        Media.key(svc, k)
        svc.ui.postDelayed({ thread { Media.refresh(svc); refreshAll() } }, 400)
    }

    // analog triggers (when the handheld sends L2/R2 as axes, not as buttons)
    private var trigL = false
    private var trigR = false

    private fun triggerMotion(e: MotionEvent): Boolean {
        if (menuView != null || editing || editView != null || gridEdit != null) return false
        val l = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        var used = false
        if (l > 0.6f && !trigL) { trigL = true; mediaKey(KeyEvent.KEYCODE_BUTTON_L2); used = true } else if (l < 0.3f) trigL = false
        if (r > 0.6f && !trigR) { trigR = true; mediaKey(KeyEvent.KEYCODE_BUTTON_R2); used = true } else if (r < 0.3f) trigR = false
        return used
    }

    fun onKey(e: KeyEvent): Boolean {
        if ((e.keyCode == KeyEvent.KEYCODE_BUTTON_START || e.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || e.keyCode == KeyEvent.KEYCODE_BUTTON_R2) &&
            menuView == null && !editing && editView == null && gridEdit == null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) mediaKey(e.keyCode)
            return true
        }
        // A: short press acts on release, holding it (½ s) opens the edit mode on that element
        val isA = e.keyCode == KeyEvent.KEYCODE_BUTTON_A || e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (isA && !editing && menuView == null && editView == null && gridEdit == null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                aDownAt = SystemClock.uptimeMillis()
                aLong = false
                aArmed = true
                svc.ui.postDelayed(aLongPress, 500)
            } else if (e.action == KeyEvent.ACTION_UP) {
                svc.ui.removeCallbacks(aLongPress)
                // Only a press that started here counts: the release of an A that chose something in a
                // menu (which closed the menu) must not switch the tile below as well.
                val armed = aArmed
                aArmed = false
                if (!aLong && armed) {
                    val it = items.getOrNull(focus)
                    when {
                        // first press only shows where the cursor is
                        !focusVisible -> { focusVisible = true; showFocus() }
                        // sliders: A starts/ends changing the value with the D-pad
                        it?.onAdjust != null -> { adjusting = !adjusting; showFocus() }
                        // inside a list card: connect/disconnect the selected line
                        inside != null && it?.rows === inside -> inside!!.list.getOrNull(insideSel)?.second?.invoke()
                        // a list card with lines: A steps in, the D-pad then walks the lines
                        it?.rows != null && it.rows.list.isNotEmpty() -> { inside = it.rows; insideSel = 0; insideKey = null; showFocus() }
                        else -> it?.onA?.invoke()
                    }
                }
                aLong = false
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (gridEdit != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> gridMove(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> gridMove(1, 0)
                KeyEvent.KEYCODE_DPAD_UP -> gridMove(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> gridMove(0, 1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> gridA()
                KeyEvent.KEYCODE_BUTTON_X -> gridRemove()
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_Y -> gridDone()
            }
            return true
        }
        if (editView != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { editSel = (editSel - 1).coerceAtLeast(0); renderEditor() }
                KeyEvent.KEYCODE_DPAD_DOWN -> { editSel = (editSel + 1).coerceAtMost((flat().size - 1).coerceAtLeast(0)); renderEditor() }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> toggleEdit()
                KeyEvent.KEYCODE_BUTTON_L1 -> moveEdit(-1)
                KeyEvent.KEYCODE_BUTTON_R1 -> moveEdit(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (editAdjust != null) adjustEdit(-1) else moveEdit(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (editAdjust != null) adjustEdit(1) else moveEdit(1)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_Y -> closeEditor()
            }
            return true
        }
        popupKeys?.let { k -> if (menuView != null) { k(e.keyCode); return true } }
        if (activeWheel != null && menuView != null) {
            wheelKey(e.keyCode)
            return true
        }
        if (ledCells != null && menuView != null) {
            ledKey(e.keyCode)
            return true
        }
        if (menuView != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveMenu(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveMenu(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> menuEntries.getOrNull(menuSel)?.side?.invoke(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> menuEntries.getOrNull(menuSel)?.side?.invoke(1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> runMenu(menuSel)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> closeMenu()
            }
            return true
        }
        if (editing) {
            editKey(e.keyCode)
            return true
        }
        val item = items.getOrNull(focus)
        val dpad = e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (dpad && !focusVisible) {
            // Navigation starts with the first press; it only reveals the cursor.
            focusVisible = true
            showFocus()
            return true
        }
        val adj = if (adjusting) item?.onAdjust else null
        if (adjusting && (adj == null || e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BACK)) {
            adjusting = false
            showFocus()
            if (adj != null) return true
        }
        if (inside != null && item?.rows !== inside) inside = null
        inside?.let { rows ->
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { insideSel = (insideSel - 1).coerceAtLeast(0); insideKey = null; markRows(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { insideSel = (insideSel + 1).coerceAtMost(rows.list.lastIndex.coerceAtLeast(0)); insideKey = null; markRows(); return true }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { leaveRows(); return true }
                // sideways (and pages, edit, menu) leave the card first
                else -> { inside = null; markRows() }
            }
        }
        val vert = item?.vertical == true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (adj != null && vert) adj(1) else { adjusting = false; gridNav(0, -1) }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (adj != null && vert) adj(-1) else { adjusting = false; gridNav(0, 1) }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (adj != null && !vert) adj(-1) else { adjusting = false; gridNav(-1, 0) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (adj != null && !vert) adj(1) else { adjusting = false; gridNav(1, 0) }
            KeyEvent.KEYCODE_BUTTON_L1 -> changePage(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> changePage(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> item?.onA?.invoke()
            KeyEvent.KEYCODE_BUTTON_X -> if (focusVisible) item?.onY?.invoke()
            KeyEvent.KEYCODE_BUTTON_Y -> editMenu()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> close()
        }
        return true
    }
}

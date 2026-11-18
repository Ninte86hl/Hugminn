package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.widget.Toast
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
    private class Item(val view: View, val onA: (() -> Unit)?, val onAdjust: ((Int) -> Unit)? = null, val vertical: Boolean = false, val onY: (() -> Unit)? = null, val round: Boolean = false)
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
    private var metrics: Metrics? = null
    private val refreshers = mutableListOf<() -> Unit>()
    private val graphUpdates = mutableListOf<(Metrics.Sample) -> Unit>()

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
                svc.ui.post { if (isOpen) graphUpdates.forEach { it(s) } }
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
        isOpen = true
        pal = Palette(svc)
        items.clear()
        refreshers.clear()
        graphUpdates.clear()
        focus = 0
        focusVisible = false
        adjusting = false
        metrics = Metrics(svc) { svc.foregroundPkg }
        // Full window over everything; the status bar is covered unless the user wants to keep it.
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val statusH = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        // The window takes input focus: the D-pad of these handhelds is a joystick hat, which Android
        // only turns into D-pad keys inside the focused window (an accessibility service never sees it).
        val r = object : FrameLayout(svc) {
            // The D-pad is a joystick hat: read it directly (Android would only fake keys from it).
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                hat.onMotion(event) || super.dispatchGenericMotionEvent(event)

            override fun dispatchKeyEvent(event: KeyEvent): Boolean = try {
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = sysTop
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        r.alpha = 0f
        wm.addView(r, lp)
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
        root?.let { runCatching { wm.removeView(it) } }
        root = null
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
        val p = build()
        p.setPadding(p.paddingLeft, p.paddingTop + padTop(), p.paddingRight, p.paddingBottom)
        panelView?.let { r.removeView(it) }
        r.addView(p, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panelView = p
        r.post { showFocus() }
    }

    private fun size(id: String) = svc.prefs.ccSizes[id] ?: 1f

    private fun build(): View {
        pixel = svc.prefs.uiStyle == UiStyle.PIXEL
        val panel = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            setPadding(28 * d, 14 * d, 28 * d, 12 * d)
            isClickable = true
        }

        // Header with a permanent button for Android's own settings
        val header = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Huginn", 20f, pal.primary, bold = true))
        header.addView(text("  ·  Control Center", 15f, pal.onSurfaceVariant), lp(0, WRAP, 1f))
        val battery = text("", 14f, pal.onSurfaceVariant).apply { setPadding(0, 0, 14 * d, 0) }
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
        panel.addView(footer, lp(MATCH, 34 * d))
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
        gridCols = computeCols()
        if (!editing) entries = svc.prefs.ccGrid.toMutableList()
        placed = GridLayout.place(entries, gridCols)
        val pageCount = pages() + if (editing) 1 else 0 // one empty page to add more while editing
        gridPage = gridPage.coerceIn(0, pageCount - 1)
        graphUpdates.removeAll { it is GridUpdate }
        refreshers.clear()
        sliderItems.clear()
        cardItems.clear()
        items.clear()
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
        if (!editing && placed.isEmpty()) body.addView(text("Leer – Y drücken und „Anordnen“ wählen", 15f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER },
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
        fun hint(k: Int, s: String) {
            footer.addView(Glyphs.chip(svc, svc.prefs, pal, k, 22))
            footer.addView(text(s, 13f, pal.onSurfaceVariant).apply { setPadding(6 * d, 0, 16 * d, 0) })
        }
        val pc = pages() + if (editing) 1 else 0
        if (pc > 1) footer.addView(text("Seite ${gridPage + 1}/$pc", 13f, pal.onSurfaceVariant).apply { setPadding(0, 0, 16 * d, 0) })
        if (editing) {
            footer.addView(text(if (holding) "Verschieben: Steuerkreuz" else "Bearbeiten", 13f, pal.primary, bold = true).apply { setPadding(0, 0, 16 * d, 0) }, lp(0, WRAP, 1f))
            if (holding) hint(KeyEvent.KEYCODE_BUTTON_A, "Ablegen") else {
                hint(KeyEvent.KEYCODE_BUTTON_A, if (elementAt(cursorX, cursorY) >= 0) "Optionen" else "Hinzufügen")
                hint(KeyEvent.KEYCODE_BUTTON_X, "Entfernen")
                hint(KeyEvent.KEYCODE_BUTTON_R1, "Seite")
            }
            hint(KeyEvent.KEYCODE_BUTTON_B, "Fertig")
        } else {
            hint(KeyEvent.KEYCODE_BUTTON_SELECT, "Screenshot")
            if (pages() > 1) hint(KeyEvent.KEYCODE_BUTTON_R1, "Seite")
            val menuHere = focusVisible && items.getOrNull(focus)?.onY != null
            if (menuHere) hint(KeyEvent.KEYCODE_BUTTON_X, "Optionen")
            hint(KeyEvent.KEYCODE_BUTTON_Y, "Bearbeiten")
            hint(KeyEvent.KEYCODE_BUTTON_A, if (adjusting) "Fertig" else "Auswählen")
            hint(KeyEvent.KEYCODE_BUTTON_B, "Zurück")
        }
    }

    /** Marker for live updates that belong to the grid (cleared on every re-render). */
    private class GridUpdate(val f: (Metrics.Sample) -> Unit) : (Metrics.Sample) -> Unit {
        override fun invoke(s: Metrics.Sample) = f(s)
    }

    private fun live(f: (Metrics.Sample) -> Unit) {
        graphUpdates += GridUpdate(f)
    }

    /** View for one grid element at its size, plus what A does on it. */
    private fun element(e: GridEntry, rootOk: Boolean): Pair<View, Item?> {
        val id = e.id
        if (GridLayout.isSlider(id)) {
            val vertical = e.h > e.w
            val v = if (id == "brightness") BigSlider(svc, pal, IconView.Kind.SUN, "Helligkeit", 255, Device.brightness(svc), pixel, vertical) { Device.setBrightness(svc, it) }
            else {
                // Android has only 16 media steps: Hugminn keeps its own 0-100 % (5 % steps) and sets the nearest one
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val idx = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val saved = svc.prefs.volumePct
                val pct = if (saved >= 0 && Math.round(saved * max / 100f) == idx) saved else Math.round(idx * 100f / max)
                BigSlider(svc, pal, IconView.Kind.SPEAKER, "Lautstärke", 100, pct, pixel, vertical) { p ->
                    svc.prefs.volumePct = p
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p * max / 100f), 0)
                }
            }
            v.setAccent(tileOn(), tileOnFg())
            val wrap = FrameLayout(svc)
            wrap.addView(v, if (vertical) FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            else FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 56 * d, Gravity.CENTER_VERTICAL))
            return wrap to Item(v, null, { dir -> v.step(5 * dir) }, vertical)
        }
        if (GridLayout.isInfo(id)) return infoElement(e)
        val t = tileFor(id, rootOk) ?: return View(svc).let { it to Item(it, null) }
        val v = toggleElement(t, e)
        return v to Item(v, t.act, onY = t.menu, round = e.w == 1 && e.h == 1)
    }

    /** Quick toggle: 1×1 only the icon, larger with name and state. */
    private fun toggleElement(t: Tile, e: GridEntry): View {
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
                setPadding(16 * d, 0, 12 * d, 0)
                addView(icon)
                val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
                title = text(t.title, 15f, pal.onSurface, bold = true)
                state = text("", 12f, pal.onSurfaceVariant)
                col.addView(title)
                col.addView(state)
                addView(col, lp(0, WRAP, 1f).apply { marginStart = 12 * d })
            }
        }
        v.setOnClickListener { if (!editing) t.act() }
        val refresh = {
            val on = t.on()
            v.background = if (round && e.w == 1 && e.h == 1) rounded(if (on) tileOn() else pal.surfaceHigh, 999f) else shape(if (on) tileOn() else pal.surfaceHigh, 22f * d)
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
                { _ -> "${Math.round(display?.refreshRate ?: 60f)} Hz Modus" }, onA = { overlayMenu() })
            "perf" -> InfoSpec(IconView.Kind.BOLT, "LEISTUNG", { _ -> Device.perfLabel(svc) }, { s -> "CPU bis %.2f GHz".format(s.cpuMhz / 1000f) },
                onA = { profileMenu("Leistungsprofil", "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } } })
            "fan" -> InfoSpec(IconView.Kind.FAN, "LÜFTER", { _ -> Device.fanLabel(svc) },
                { _ -> if (FanCurve.isRunning()) "${FanCurve.currentPct} % bei ${FanCurve.currentTemp.toInt()} °C" else "Profil" },
                onA = { profileMenu("Lüfterprofil", "fan_mode", Device.fanProfiles) { v -> chooseFan(v) } })
            "temp" -> InfoSpec(IconView.Kind.THERMO, "TEMP", { s -> if (s.cpuTemp > 0) "${s.cpuTemp.toInt()}°" else "–" },
                { s -> listOfNotNull(s.gpuTemp.takeIf { it > 0 }?.let { "GPU ${it.toInt()}°" }, s.batTemp.takeIf { it > 0 }?.let { "Akku ${it.toInt()}°" }).joinToString(" · ") },
                { s -> (s.cpuTemp - 40f) / 45f }, colorValue = true)
            "battery" -> InfoSpec(IconView.Kind.PLUG, "AKKU", { s -> "${s.batPct} %" }, { s -> "%.1f W".format(s.watts) + if (s.charging) " · lädt" else "" },
                { s -> 1f - s.batPct / 100f }, colorValue = true)
            "cpu" -> InfoSpec(IconView.Kind.BOLT, "CPU", { s -> "${s.cpuPct.toInt()} %" }, { s -> "%.2f GHz".format(s.cpuMhz / 1000f) }, { s -> s.cpuPct / 100f })
            "gpu" -> InfoSpec(IconView.Kind.REFRESH, "GPU", { s -> "${s.gpuPct.toInt()} %" }, { s -> "${s.gpuMhz} MHz" }, { s -> s.gpuPct / 100f })
            "ram" -> InfoSpec(IconView.Kind.SCREEN, "RAM", { s -> "%.1f GB".format(s.ramUsedMb / 1024f) },
                { s -> "von ${Device.ramInstalledGb(s.ramTotalMb)} GB" }, { s -> if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f })
            "power" -> InfoSpec(IconView.Kind.PLUG, "WATT", { s -> "%.1f W".format(s.watts) }, { s -> if (s.charging) "lädt" else "aus dem Akku" }, { s -> s.watts / 15f })
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
            if (small) setAutoSizeTextTypeUniformWithConfiguration(12, 26, 1, TypedValue.COMPLEX_UNIT_SP)
            else setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 34f else 19f)
        }
        val sub = text("", 11f, pal.onSurfaceVariant)
        var bar: GaugeBar? = null
        var ring: RingView? = null
        var graph: GraphView? = null
        if (big && e.id == "fps") {
            c.addView(value)
            c.addView(sub)
            graph = GraphView(svc, pal.primary, 50f)
            c.addView(graph, lp(MATCH, 0, 1f).apply { topMargin = 6 * d })
        } else if (big && sp.fraction != null) {
            ring = RingView(svc, pal, "", pal.primary, pixel)
            c.addView(ring, lp(MATCH, 0, 1f))
            c.addView(sub.apply { gravity = Gravity.CENTER })
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
                CcBlock.BRIGHTNESS -> add(slider(IconView.Kind.SUN, "Helligkeit", 255, Device.brightness(svc)) { Device.setBrightness(svc, it) }, lp(MATCH, (50 * d * size("brightness")).toInt()))
                CcBlock.VOLUME -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    add(slider(IconView.Kind.SPEAKER, "Lautstärke", max, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
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
        background = shape(pal.surfaceHigh, 22f * d)
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
        fps.addView(text("Bildrate des Spiels · A: Leistungs-Overlay", 12f, pal.onSurfaceVariant))
        fps.setOnClickListener { overlayMenu() }
        cardItems += Item(fps, { overlayMenu() })
        val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        graphUpdates += { s ->
            value.text = if (s.fps >= 0) "${s.fps}" else "–"
            modeChip.text = "${Math.round(display?.refreshRate ?: 60f)} Hz Modus"
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
            CcBlock.TEMP -> info(IconView.Kind.THERMO, "TEMPERATUR") { big, sub, s ->
                big.text = if (s.cpuTemp > 0) "${s.cpuTemp.toInt()} °C" else "–"
                big.setTextColor(if (s.cpuTemp > 0 && gp != GaugePalette.MONO) GaugeColors.of(pal, gp, (s.cpuTemp - 40f) / 45f) else pal.onSurface)
                sub.text = listOfNotNull(
                    s.gpuTemp.takeIf { it > 0 }?.let { "GPU ${it.toInt()} °C" },
                    s.batTemp.takeIf { it > 0 }?.let { "Akku ${it.toInt()} °C" },
                ).joinToString(" · ")
            }
            CcBlock.PERF -> info(IconView.Kind.BOLT, "LEISTUNG", onA = {
                profileMenu("Leistungsprofil", "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } }
            }) { big, sub, s ->
                big.text = Device.perfLabel(svc)
                sub.text = "CPU bis %.2f GHz".format(s.cpuMhz / 1000f)
            }
            CcBlock.FAN -> info(IconView.Kind.FAN, "LÜFTER", onA = {
                profileMenu("Lüfterprofil", "fan_mode", Device.fanProfiles) { v -> Device.setFan(v) { refreshAll() } }
            }) { big, sub, _ ->
                big.text = Device.fanLabel(svc)
                sub.text = Device.fanSpeed(svc)?.let { "Drehzahl $it" } ?: ""
            }
            CcBlock.BATTERY -> info(IconView.Kind.PLUG, "AKKU") { big, sub, s ->
                big.text = "${s.batPct} %"
                big.setTextColor(if (gp != GaugePalette.MONO) GaugeColors.of(pal, gp, 1f - s.batPct / 100f) else pal.onSurface)
                sub.text = "%.1f W".format(s.watts) + if (s.charging) " · lädt" else ""
            }
            CcBlock.CPU -> gauge("CPU", IconView.Kind.BOLT, { s ->
                Triple(s.cpuPct / 100f, "${s.cpuPct.toInt()} %", "%.2f GHz".format(s.cpuMhz / 1000f))
            }, "CPU")
            CcBlock.GPU -> gauge("GPU", IconView.Kind.REFRESH, { s ->
                Triple(s.gpuPct / 100f, "${s.gpuPct.toInt()} %", "${s.gpuMhz} MHz")
            }, "GPU")
            CcBlock.POWER -> gauge("LEISTUNGSAUFNAHME", IconView.Kind.PLUG, { s ->
                Triple(s.watts / 15f, "%.1f W".format(s.watts), if (s.charging) "lädt" else "aus dem Akku")
            }, "WATT")
            CcBlock.RAM -> gauge("RAM", IconView.Kind.SCREEN, { s ->
                val pct = if (s.ramTotalMb > 0) s.ramUsedMb.toFloat() / s.ramTotalMb else 0f
                Triple(pct, "%.1f GB".format(s.ramUsedMb / 1024f), "${(pct * 100).toInt()} % von ${Math.round(s.ramTotalMb / 1024f)} GB")
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
        "wifi" -> Tile("WLAN", IconView.Kind.WIFI, { if (Device.wifiOn(svc)) Device.wifiName(svc) ?: "An" else "Aus" }, { Device.wifiOn(svc) }, { wifiMenu(rootOk) }) {
            if (rootOk) Device.setWifi(!Device.wifiOn(svc)) { refreshAll() } else wifiMenu(false)
        }
        "bt" -> Tile("Bluetooth", IconView.Kind.BLUETOOTH, {
            if (!Device.btOn(svc)) "Aus" else Device.pairedDevices(svc).firstOrNull { it.second }?.first ?: "An"
        }, { Device.btOn(svc) }, { btMenu(rootOk) }) {
            if (rootOk) Device.setBt(!Device.btOn(svc)) { refreshAll() } else btMenu(false)
        }
        "perf" -> if (rootOk && Device.sys(svc, "performance_mode") != null) Tile("Leistung", IconView.Kind.BOLT, { Device.perfLabel(svc) }, { Device.performance(svc) }) {
            profileMenu("Leistung", "performance_mode", Device.perfProfiles) { v -> Device.setPerf(v) { refreshAll() } }
        } else null
        "fan" -> if (rootOk && Device.sys(svc, "fan_mode") != null) Tile("Lüfter", IconView.Kind.FAN, { Device.fanLabel(svc) }, { true }) {
            profileMenu("Lüfter", "fan_mode", Device.fanProfiles) { v -> chooseFan(v) }
        } else null
        "refresh" -> {
            val display = svc.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (rootOk && display?.supportedModes?.any { it.refreshRate > 100f } == true)
                Tile("Bildrate", IconView.Kind.REFRESH, { if (Device.highRefresh(svc)) "120 Hz" else "60 Hz" }, { Device.highRefresh(svc) }) {
                    Device.setHighRefresh(!Device.highRefresh(svc)) { refreshAll() }
                } else null
        }
        "leds" -> if (rootOk && Device.sys(svc, "joystick_light_enabled") != null) Tile("Stick-LEDs", IconView.Kind.LED, { if (Device.stickLeds(svc)) "An" else "Aus" }, { Device.stickLeds(svc) }, { ledMenu() }) { toggleLeds() } else null
        "dnd" -> if (rootOk) Tile("Nicht stören", IconView.Kind.MOON, { if (Device.dnd(svc)) "An" else "Aus" }, { Device.dnd(svc) }) {
            Device.setDnd(!Device.dnd(svc)) { refreshAll() }
        } else null
        "airplane" -> if (rootOk) Tile("Flugmodus", IconView.Kind.AIRPLANE, { if (Device.airplane(svc)) "An" else "Aus" }, { Device.airplane(svc) }) {
            Device.setAirplane(!Device.airplane(svc)) { refreshAll() }
        } else null
        "rotation" -> Tile("Drehung sperren", IconView.Kind.ROTATE, { if (Device.rotationLocked(svc)) "Gesperrt" else "Automatisch" }, { Device.rotationLocked(svc) }) {
            Device.setRotationLocked(svc, !Device.rotationLocked(svc)) { refreshAll() }
        }
        "screen" -> if (rootOk && Device.sys(svc, "keep_screen_on") != null) Tile("Bildschirm an", IconView.Kind.SCREEN, { if (Device.keepScreenOn(svc)) "An" else "Aus" }, { Device.keepScreenOn(svc) }) {
            Device.setKeepScreenOn(!Device.keepScreenOn(svc)) { refreshAll() }
        } else null
        "clean" -> if (rootOk) Tile("Speicher leeren", IconView.Kind.BROOM, { null }, { false }) {
            Thread {
                val mb = Device.cleanMemory(svc)
                svc.ui.post { android.widget.Toast.makeText(svc, "$mb MB freigegeben", android.widget.Toast.LENGTH_SHORT).show() }
            }.start()
        } else null
        "landscape" -> sysTile(rootOk, "Querformat erzwingen", IconView.Kind.SCREEN, "force_landscape")
        "vibration" -> sysTile(rootOk, "Vibration", IconView.Kind.VIBRATE, "haptic_feedback_enabled")
        "keymapper" -> sysTile(rootOk, "Tastenzuordnung", IconView.Kind.GAMEPAD, "key_adapter_enable")
        "mouse" -> sysTile(rootOk, "Mausmodus", IconView.Kind.MOUSE, "global_gamepad_to_mouse_mode")
        "homelock" -> sysTile(rootOk, "Home-Sperre", IconView.Kind.LOCK, "prevent_press_home_accidentally")
        "chargesep" -> sysTile(rootOk, "Direkte Stromversorgung", IconView.Kind.PLUG, "is_charging_separation")
        "trigger" -> if (rootOk && Device.sys(svc, "trigger_input_mode") != null) Tile("Trigger-Modus", IconView.Kind.GAMEPAD, {
            Device.profileLabel(svc, "trigger_input_mode", Device.triggerModes)
        }, { false }) {
            profileMenu("Trigger-Modus", "trigger_input_mode", Device.triggerModes) { v -> Device.setSys("trigger_input_mode", v) { refreshAll() } }
        } else null
        "controller" -> if (rootOk && Device.sys(svc, "temp_abxy_layout_mode") != null) Tile("Tastenlayout", IconView.Kind.GAMEPAD, {
            if (Glyphs.nintendoLayout()) "Retroid" else "Xbox"
        }, { false }) {
            showMenu("Tastenlayout", listOf(
                MenuEntry("Xbox (A unten)", checked = !Glyphs.nintendoLayout()) { Device.setControllerLayout(false) { refreshAll() } },
                MenuEntry("Retroid / Nintendo (A rechts)", checked = Glyphs.nintendoLayout()) { Device.setControllerLayout(true) { refreshAll() } },
            ))
        } else null
        "theme" -> Tile("Farbschema", IconView.Kind.PALETTE, { svc.prefs.theme.label }, { false }) {
            showMenu("Farbschema", Theme.values().map { t ->
                MenuEntry(t.label, checked = svc.prefs.theme == t) {
                    svc.prefs.theme = t
                    close()
                    svc.ui.postDelayed({ open() }, 60)
                }
            })
        }
        "screenshot" -> Tile("Screenshot", IconView.Kind.CAMERA, { null }, { false }) {
            close()
            svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 300)
        }
        "rpsettings" -> svc.packageManager.getLaunchIntentForPackage("com.rp.settings")?.let { i ->
            Tile("Retroid-Einstellungen", IconView.Kind.GAMEPAD, { null }, { false }) { close(); svc.startApp(i) }
        }
        "settings" -> Tile("Android-Einstellungen", IconView.Kind.GEAR, { null }, { false }) { openSystem(Settings.ACTION_SETTINGS) }
        "hugminn" -> Tile("Hugminn", IconView.Kind.GEAR, { null }, { false }) {
            close()
            svc.startApp(Intent(svc, MainActivity::class.java))
        }
        else -> null
    }

    /** On/off tile for a firmware system setting; hidden if this device does not have the setting. */
    private fun sysTile(rootOk: Boolean, title: String, icon: IconView.Kind, key: String): Tile? =
        if (!rootOk || Device.sys(svc, key) == null) null
        else Tile(title, icon, { if (Device.sysOn(svc, key)) "An" else "Aus" }, { Device.sysOn(svc, key) }) {
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
            if (shown.isEmpty()) grid.addView(text("Keine Kacheln – mit Y auswählen", 14f, pal.onSurfaceVariant).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        }
        dots?.let { dr ->
            dr.removeAllViews()
            if (pageCount() > 1) repeat(pageCount()) { i ->
                dr.addView(View(svc).apply { background = shape(if (i == page) pal.primary else pal.outline, 4f * d) },
                    lp(if (i == page) 16 * d else 7 * d, 7 * d).apply { marginStart = 3 * d; marginEnd = 3 * d })
            }
        }
        items.clear()
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
        box.addView(text("Eigene Lüfterkurve", 18f, pal.onSurface, bold = true))
        val info = text("", 12f, pal.onSurfaceVariant).apply { setPadding(0, 4 * d, 0, 4 * d) }
        box.addView(info)
        val pts = svc.prefs.fanCurve.toMutableList()
        val view = FanCurveView(svc, pal).apply { points = pts.toList(); selected = 1 }
        box.addView(view, lp(560 * d, 240 * d))
        box.addView(text("◀ ▶ Punkt · ▲ ▼ Drehzahl · A aktivieren · B zurück", 12f, pal.onSurfaceVariant).apply { setPadding(0, 6 * d, 0, 0) })
        fun refreshInfo() {
            val t = FanCurve.currentTemp.takeIf { it > 0 } ?: 0f
            view.liveTemp = t
            info.text = if (FanCurve.isRunning()) "Aktiv · jetzt ${t.toInt()} °C → ${FanCurve.currentPct} %" else
                "Unter ${FanCurve.MIN} % geht es nie, ab ${FanCurve.HOT} °C läuft der Lüfter voll"
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
                            svc.ui.post { Toast.makeText(svc, "Dieses Gerät erlaubt keine eigene Lüftersteuerung", Toast.LENGTH_LONG).show() }
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

    private fun profileMenu(title: String, key: String, table: Map<String, String>, set: (String) -> Unit) {
        val cur = Device.sys(svc, key)
        val entries = table.map { (v, name) -> MenuEntry(name, checked = v == cur) { set(v) } }.toMutableList()
        if (cur != null && cur !in table) entries += MenuEntry("Profil $cur (unbekannt)", checked = true, run = null)
        showMenu(title, entries)
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
        entries += MenuEntry(if (on) "WLAN ausschalten" else "WLAN einschalten") { Device.setWifi(!on) { refreshAll() } }
        if (on) {
            entries += MenuEntry(if (scanning) "Netzwerke – suche …" else "Netzwerke", header = true, run = null)
            val list = nets.orEmpty()
            if (nets == null) entries += MenuEntry("Wird geladen …", run = null)
            else if (list.isEmpty()) entries += MenuEntry("Keine Netzwerke gefunden", run = null)
            list.take(14).forEach { n ->
                val connected = n.ssid == cur
                val known = saved?.containsKey(n.ssid) == true
                val lock = if (n.security == "open") "offen" else "gesichert"
                val hint = "${Wifi.bars(n.level)}  " + when {
                    connected -> "Verbunden"
                    known -> "Gespeichert · $lock"
                    else -> lock
                }
                entries += MenuEntry(n.ssid, checked = connected, hint = hint) {
                    when {
                        connected -> {}
                        n.security == "eap" || n.security == "wep" -> openSystem(Settings.Panel.ACTION_WIFI)
                        known || n.security == "open" || n.security == "owe" -> {
                            val pw = saved?.get(n.ssid)
                            Toast.makeText(svc, "Verbinde mit ${n.ssid} …", Toast.LENGTH_SHORT).show()
                            Thread { Wifi.connect(n.ssid, n.security, pw); svc.ui.postDelayed({ refreshAll() }, 2500) }.start()
                        }
                        else -> {
                            close()
                            svc.startApp(Intent(svc, WifiPasswordActivity::class.java).putExtra("ssid", n.ssid).putExtra("sec", n.security))
                        }
                    }
                }
            }
            entries += MenuEntry("Weitere Einstellungen …") { openSystem(Settings.Panel.ACTION_WIFI) }
        }
        val again = menuView != null && menuTitle == "WLAN"
        showMenu("WLAN", entries, if (again) menuSel else -1)
        menuTitle = "WLAN"
        if (on && nets == null) {
            // results we have now, then a fresh scan
            Thread {
                val sv = Wifi.saved()
                val first = Wifi.scanResults()
                svc.ui.post { if (menuView != null && menuTitle == "WLAN") wifiMenu(true, first, sv, scanning = true) }
                Wifi.startScan()
                Thread.sleep(3500)
                val fresh = Wifi.scanResults()
                svc.ui.post { if (menuView != null && menuTitle == "WLAN") wifiMenu(true, fresh, sv, scanning = false) }
            }.start()
        }
    }

    /** Bluetooth: switch, paired devices (connect/disconnect), search and pair new ones. */
    private fun btMenu(rootOk: Boolean, found: List<android.bluetooth.BluetoothDevice>? = null) {
        val on = Device.btOn(svc)
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(if (on) "Bluetooth ausschalten" else "Bluetooth einschalten") {
            if (rootOk) Device.setBt(!on) { refreshAll() } else openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        if (on) {
            val paired = Bt.paired(svc).sortedByDescending { Bt.isConnected(it) }
            entries += MenuEntry("Gekoppelte Geräte", header = true, run = null)
            if (paired.isEmpty()) entries += MenuEntry("Noch keine", run = null)
            paired.forEach { d ->
                val connected = Bt.isConnected(d)
                entries += MenuEntry(Bt.name(d), checked = connected, hint = if (connected) "Verbunden · A trennt" else "Gekoppelt · A verbindet") {
                    stopBtScan()
                    Toast.makeText(svc, if (connected) "Trenne ${Bt.name(d)} …" else "Verbinde ${Bt.name(d)} …", Toast.LENGTH_SHORT).show()
                    Bt.toggle(svc, d, !connected) { svc.ui.postDelayed({ refreshAll() }, 1500) }
                }
            }
            entries += MenuEntry(if (found == null) "Neue Geräte" else "Neue Geräte – suche …", header = true, run = null)
            if (found == null) entries += MenuEntry("Suchen …", hint = "Gerät in den Kopplungsmodus versetzen") {
                svc.ui.post { startBtScan(rootOk) }
            } else {
                if (found.isEmpty()) entries += MenuEntry("Noch nichts gefunden", run = null)
                // devices with a name first; nameless ones (often phones, TVs, beacons nearby) only as a count
                val named = found.filter { runCatching { it.name }.getOrNull() != null }
                val nameless = found.size - named.size
                named.forEach { d ->
                    entries += MenuEntry(Bt.name(d), hint = "A koppelt") {
                        stopBtScan()
                        Toast.makeText(svc, "Kopple ${Bt.name(d)} …", Toast.LENGTH_SHORT).show()
                        Bt.pair(svc, d)
                        Bt.confirmPairing(svc, Bt.name(d))
                    }
                }
                if (nameless > 0) entries += MenuEntry("$nameless weitere ohne Namen", hint = "Meist Handys, Fernseher oder Sender in der Nähe", run = null)
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
        val on = !Device.stickLeds(svc)
        if (!on) LedFx.stop()
        Device.setStickLeds(on) {
            if (on) LedFx.resume(svc)
            refreshAll()
        }
    }

    private fun ledMenu(select: Int = -1) {
        val on = Device.stickLeds(svc)
        val pkg = svc.prefs.ledApp.takeIf { it.isNotEmpty() }
        val intent = pkg?.let { svc.packageManager.getLaunchIntentForPackage(it) }
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(if (on) "LEDs ausschalten" else "LEDs einschalten") { Device.setStickLeds(!on) { refreshAll() } }
        if (intent != null) {
            val label = runCatching { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            entries += MenuEntry("Farben & Effekte in $label") {
                close()
                svc.startApp(intent)
            }
            showMenu("Stick-LEDs", entries, select)
            return
        }
        ledFocus = "wheel"
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

    /** Sets the colour of the chosen stick(s); breathing/pulsing keep running in the new colour. */
    private fun applyLed(c: Int) {
        val on = Device.stickLeds(svc)
        val (left, right) = Device.ledColors(svc) ?: (android.graphics.Color.WHITE to android.graphics.Color.WHITE)
        val l = if (ledTarget == 2) left else c
        val r = if (ledTarget == 1) right else c
        val keep = svc.prefs.ledEffect == LedEffect.BREATHE || svc.prefs.ledEffect == LedEffect.PULSE
        Device.setLedColors(l, r, turnOn = !on) {
            if (keep) LedFx.resume(svc) else LedFx.set(svc, LedEffect.NONE)
            // the window stays open (with the new colour ticked) for further changes
            svc.ui.post {
                refreshAll()
                if (root != null && ledCells != null) ledPicker()
            }
        }
    }

    private fun ledPicker() {
        val r = root ?: return
        closeMenu()
        val on = Device.stickLeds(svc)
        val (left, right) = Device.ledColors(svc) ?: (android.graphics.Color.WHITE to android.graphics.Color.WHITE)
        val current = if (ledTarget == 2) right else left
        val cells = mutableListOf<LedCell>()
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(pal.surfaceHigh, 28f * d, 1 * d, pal.outline)
            setPadding(22 * d, 16 * d, 22 * d, 16 * d)
            elevation = 12f * d
            isClickable = true
        }
        box.addView(text("Stick-LEDs", 18f, pal.onSurface, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 10 * d) }, lp(MATCH, WRAP))
        val cols = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        box.addView(cols)

        // left: colour wheel (A to steer it with the D-pad, A again to apply, B to cancel)
        val wheel = ColorWheel(svc, pal.onSurface).apply { select(current) }
        val wheelBox = FrameLayout(svc).apply { setPadding(6 * d, 6 * d, 6 * d, 6 * d) }
        wheelBox.addView(wheel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        cols.addView(wheelBox, lp(230 * d, 230 * d))
        fun preview(c: Int) {
            val k = Device.ledBrightness(svc)
            val l = if (ledTarget == 2) left else c
            val rr = if (ledTarget == 1) right else c
            LedFx.preview(l, rr, k)
        }
        wheel.onChange = { c -> LedFx.stop(); preview(c) }
        wheel.onPick = { c -> applyLed(c) }
        cells += LedCell("wheel", wheelBox) {
            activeWheel = wheel
            wheel.active = true
            LedFx.stop()
            preview(wheel.color)
            wheelDone = { c -> applyLed(c) }
            wheelCancel = {
                if (svc.prefs.ledEffect != LedEffect.NONE) LedFx.resume(svc) else LedFx.restoreStatic(svc)
                showLedSel()
            }
            showLedSel()
            renderFooter()
        }

        // right: target, quick colours, effects, brightness and speed
        val side = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; setPadding(18 * d, 0, 0, 0) }
        cols.addView(side)
        fun header(t: String) = text(t, 12f, pal.primary, bold = true).apply { setPadding(2 * d, 8 * d, 0, 5 * d) }
        fun chip(label: String, sel: Boolean) = text(label, 13f, if (sel) pal.onPrimaryContainer else pal.onSurfaceVariant, bold = sel).apply {
            gravity = Gravity.CENTER
            setPadding(12 * d, 0, 12 * d, 0)
            background = shape(if (sel) pal.primaryContainer else pal.surfaceHighest, 16f * d)
        }

        side.addView(header("Stick"))
        val targetRow = LinearLayout(svc)
        listOf(1 to "Links", 2 to "Rechts", 0 to "Beide").forEach { (i, label) ->
            val c = chip(label, i == ledTarget)
            val act = { ledTarget = i; ledFocus = "t$i"; ledPicker() }
            c.setOnClickListener { act() }
            targetRow.addView(c, lp(72 * d, 34 * d).apply { marginEnd = 6 * d })
            cells += LedCell("t$i", c, act)
        }
        side.addView(targetRow)

        side.addView(header("Farben"))
        Device.ledPresets.chunked(6).forEachIndexed { line, list ->
            val row = LinearLayout(svc)
            list.forEachIndexed { j, (name, c) ->
                val chosen = LedFx.current() == LedEffect.NONE && (current or 0xFF000000.toInt()) == c && (ledTarget != 0 || left == right)
                val cell = FrameLayout(svc).apply {
                    background = shape(c, 10f * d)
                    contentDescription = name
                    if (chosen) addView(text("✓", 16f, onColor(c), bold = true).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                cell.setOnClickListener { applyLed(c) }
                row.addView(cell, lp(36 * d, 36 * d).apply { setMargins(0, 0, 8 * d, 8 * d) })
                cells += LedCell("p${line * 6 + j}", cell) { applyLed(c) }
            }
            side.addView(row)
        }

        side.addView(header("Effekt"))
        val fxRow = LinearLayout(svc)
        LedEffect.values().forEach { ef ->
            val c = chip(ef.label, svc.prefs.ledEffect == ef)
            val act: () -> Unit = {
                // the window stays open for further adjustments
                val again = { svc.ui.post { if (root != null) { ledFocus = "fx${ef.ordinal}"; ledPicker() } } }
                if (!on && ef != LedEffect.NONE) Device.setStickLeds(true) { LedFx.set(svc, ef); refreshAll(); again() }
                else { LedFx.set(svc, ef); again() }
            }
            c.setOnClickListener { act() }
            fxRow.addView(c, lp(WRAP, 34 * d).apply { marginEnd = 6 * d })
            cells += LedCell("fx${ef.ordinal}", c, act)
        }
        side.addView(fxRow)

        val tune = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 12 * d, 0, 0) }
        fun stepper(key: String, label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
            fun btn(sym: String, run: () -> Unit) = text(sym, 18f, pal.onSurface, bold = true).apply {
                gravity = Gravity.CENTER
                background = shape(pal.surfaceHighest, 14f * d)
                setOnClickListener { run() }
            }
            tune.addView(text(label, 13f, pal.onSurfaceVariant).apply { setPadding(2 * d, 0, 8 * d, 0) })
            val m = btn("−", minus)
            tune.addView(m, lp(38 * d, 34 * d))
            tune.addView(text(value, 14f, pal.onSurface, bold = true).apply { gravity = Gravity.CENTER }, lp(56 * d, WRAP))
            val p = btn("+", plus)
            tune.addView(p, lp(38 * d, 34 * d))
            cells += LedCell("${key}-", m, minus)
            cells += LedCell("${key}+", p, plus)
        }
        val bright = (Device.ledBrightness(svc) * 100).roundToInt()
        val sp = svc.prefs.ledSpeed
        val si = LedFx.speeds.indexOfFirst { it >= sp }.coerceAtLeast(0)
        fun again(key: String) { ledFocus = key; ledPicker() }
        stepper("b", "Helligkeit", "$bright %",
            { LedFx.setBrightness(svc, ((bright + 9) / 10 * 10 - 10).coerceAtLeast(10)) { svc.ui.post { again("b-") } } },
            { LedFx.setBrightness(svc, (bright / 10 * 10 + 10).coerceAtMost(100)) { svc.ui.post { again("b+") } } })
        tune.addView(View(svc), lp(14 * d, 1))
        stepper("s", "Tempo", "${LedFx.speeds[si].let { if (it % 1f == 0f) it.toInt().toString() else it.toString().replace('.', ',') }}×",
            { LedFx.setSpeed(svc, LedFx.speeds[(si - 1).coerceAtLeast(0)]); again("s-") },
            { LedFx.setSpeed(svc, LedFx.speeds[(si + 1).coerceAtMost(LedFx.speeds.lastIndex)]); again("s+") })
        side.addView(tune)

        ledCells = cells
        if (cells.none { it.key == ledFocus }) ledFocus = "wheel"
        r.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        menuView = box
        showLedSel()
    }

    private fun showLedSel() {
        ledCells?.forEach { c ->
            val sel = c.key == ledFocus && activeWheel == null
            c.view.foreground = if (sel) shape(0, if (c.key == "wheel") 24f * d else 12f * d, 3 * d, pal.onSurface) else null
        }
    }

    /** D-pad in the LED window: to the nearest cell in that direction (by position on screen). */
    private fun ledKey(code: Int) {
        val cells = ledCells ?: return
        val cur = cells.firstOrNull { it.key == ledFocus } ?: cells.first()
        val (dx, dy) = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { cur.act(); return }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { closeMenu(); return }
            else -> return
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
    private fun tileColorWheel() {
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
        box.addView(text("Eigene Kachelfarbe", 18f, pal.onSurface, bold = true).apply { setPadding(0, 0, 0, 8 * d) })
        val wheel = ColorWheel(svc, pal.onSurface).apply {
            select(svc.prefs.tileColor.takeIf { it != 0 } ?: pal.primary)
            active = true
        }
        val sample = View(svc).apply { background = shape(wheel.color, 12f * d) }
        wheel.onChange = { c -> sample.background = shape(c, 12f * d) }
        val done: (Int) -> Unit = { c ->
            activeWheel = null
            svc.prefs.tileColor = c or 0xFF000000.toInt()
            reopen()
        }
        wheel.onPick = done
        box.addView(wheel, lp(240 * d, 240 * d))
        box.addView(sample, lp(240 * d, 14 * d).apply { topMargin = 10 * d })
        box.addView(text("Steuerkreuz wählen · A übernehmen · B zurück", 12f, pal.onSurfaceVariant).apply { setPadding(0, 10 * d, 0, 0) })
        r.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        menuView = box
        activeWheel = wheel
        wheelDone = done
        wheelCancel = { closeMenu() }
    }

    private fun openSystem(action: String) {
        close()
        svc.startApp(Intent(action))
    }

    private fun showMenu(title: String, entries: List<MenuEntry>, select: Int = -1) {
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
        val tall = entries.size > 6 || title == "Bluetooth" || title == "WLAN"
        val sc = android.widget.ScrollView(svc).apply { addView(list) }
        menuScroll = sc
        box.addView(sc, if (tall) lp(MATCH, 0, 1f) else lp(MATCH, WRAP))
        val w = (r.width * 0.42f).toInt().coerceAtLeast(320 * d)
        r.addView(box, FrameLayout.LayoutParams(w, if (tall) (r.height * 0.86f).toInt() else FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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

    private var menuScroll: android.widget.ScrollView? = null

    /** Changes only the small text under a menu entry (no new menu, nothing flickers). */
    private fun setMenuHint(i: Int, hint: String) {
        menuRows.getOrNull(i)?.findViewWithTag<android.widget.TextView>("hint")?.text = hint
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

    private fun showFocus() {
        if (editing) return
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
            v.foreground = if (i == focus) shape(0, r, (if (adj) 5 else 3) * d, if (adj) tileOn() else pal.onSurface) else null
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
        w == 1 && h == 1 -> " (klein)"
        h > w -> " (hoch)"
        w > h -> " (breit)"
        else -> " (groß)"
    }

    /** A on an element: move, size, remove. */
    private fun elementMenu(el: Int) {
        val p = placed.getOrNull(el) ?: return
        val e = p.entry
        showMenu(GridLayout.name(e.id), listOf(
            MenuEntry("Verschieben", hint = "Mit dem Steuerkreuz, A legt ab") { holding = true; cursorX = p.x; cursorY = p.y; renderGrid() },
            MenuEntry("Größe", hint = sizeLabel(e.w, e.h)) {
                val options = GridLayout.sizes(e.id, gridCols).filter { (w, h) -> fitsAt(e.copy(w = w, h = h), p.x, p.y, gridPage, el) }
                showMenu("Größe", options.map { (w, h) ->
                    MenuEntry(sizeLabel(w, h), checked = w == e.w && h == e.h) {
                        entries[el] = e.copy(w = w, h = h, x = p.x, y = p.y, page = gridPage)
                        renderGrid()
                    }
                })
            },
            MenuEntry("Entfernen") {
                entries.removeAt(el)
                renderGrid()
            },
        ))
    }

    /** A on a free cell: choose what goes there, sorted by type. */
    private fun addMenu() {
        val rootOk = Root.available()
        val present = entries.map { it.id }.toSet()
        fun entryFor(id: String): MenuEntry = MenuEntry(GridLayout.name(id)) { placeAtCursor(id) }
        val switches = TileCatalog.all.keys.filter { it !in present && tileFor(it, rootOk) != null }
        val infos = GridLayout.widgetNames.keys.filter { GridLayout.isInfo(it) && it !in present }
        val sliders = GridLayout.widgetNames.keys.filter { GridLayout.isSlider(it) && it !in present }
        val list = mutableListOf<MenuEntry>()
        if (infos.isNotEmpty()) { list += MenuEntry("Anzeigen", header = true, run = null); infos.forEach { list += entryFor(it) } }
        if (sliders.isNotEmpty()) { list += MenuEntry("Regler", header = true, run = null); sliders.forEach { list += entryFor(it) } }
        if (switches.isNotEmpty()) { list += MenuEntry("Schalter", header = true, run = null); switches.forEach { list += entryFor(it) } }
        if (list.isEmpty()) list += MenuEntry("Alles ist schon im Raster", run = null)
        showMenu("Hinzufügen", list)
    }

    /** Puts [id] at the cursor in the largest preferred size that fits there. */
    private fun placeAtCursor(id: String) {
        val preferred = when {
            GridLayout.isSlider(id) -> listOf(1 to 2, 2 to 1, 1 to 3, 3 to 1)
            id == "fps" -> listOf(2 to 2, 2 to 1, 1 to 1)
            GridLayout.isInfo(id) -> listOf(2 to 1, 1 to 1, 2 to 2)
            else -> listOf(1 to 1, 2 to 1)
        }
        val size = preferred.firstOrNull { (w, h) -> fitsAt(GridEntry(id, w, h), cursorX, cursorY, gridPage, -1) } ?: return
        entries.add(GridEntry(id, size.first, size.second, cursorX, cursorY, gridPage))
        renderGrid()
    }

    // ---------- editing (Y): layout, tiles, colours, style ----------

    private fun reopen() {
        close()
        svc.ui.postDelayed({ open() }, 60)
    }

    private fun editMenu() {
        showMenu("Bearbeiten", listOf(
            MenuEntry("Anordnen & Größe", hint = "Elemente verschieben, vergrößern, entfernen") { startEdit() },
            MenuEntry("Element hinzufügen", hint = "Schalter, Anzeigen und Regler") { addMenu() },
            MenuEntry("Kachelfarbe", hint = TILE_COLORS[svc.prefs.tileColor] ?: "Eigene") { colorMenu() },
            MenuEntry("Stil", hint = svc.prefs.uiStyle.label) {
                showMenu("Stil", UiStyle.values().map { st ->
                    MenuEntry(when (st) {
                        UiStyle.PIXEL -> "Pixel (Retroid-Pocket-Classic-Look)"
                        UiStyle.DOTS -> "Punktmatrix (schwarz-weiß, rot als Akzent)"
                        else -> "Modern (Material You)"
                    }, checked = svc.prefs.uiStyle == st) {
                        svc.prefs.uiStyle = st
                        reopen()
                    }
                })
            },
            MenuEntry("Leistungs-Overlay", hint = if (svc.prefs.overlayOn) "An" else "Aus") { overlayMenu() },
            MenuEntry("Farben der Anzeigen", hint = svc.prefs.gaugePalette.label) {
                showMenu("Farben der Anzeigen", GaugePalette.values().map { g ->
                    MenuEntry(g.label, checked = svc.prefs.gaugePalette == g) { svc.prefs.gaugePalette = g; reopen() }
                })
            },
            MenuEntry("Farbschema", hint = svc.prefs.theme.label) {
                showMenu("Farbschema", Theme.values().map { t ->
                    MenuEntry(t.label, checked = svc.prefs.theme == t) { svc.prefs.theme = t; reopen() }
                })
            },
            MenuEntry("Standard wiederherstellen", hint = "Layout, Kacheln, Größen und Farben zurücksetzen") {
                showMenu("Wirklich zurücksetzen?", listOf(
                    MenuEntry("Ja, Standard wiederherstellen") { svc.prefs.resetControlCenter(); reopen() },
                    MenuEntry("Abbrechen") {},
                ))
            },
        ))
    }

    private fun overlayMenu(select: Int = -1) {
        val p = svc.prefs
        showMenu("Leistungs-Overlay", listOf(
            MenuEntry(if (p.overlayOn) "Overlay ausschalten" else "Overlay einschalten", hint = "Kleine Anzeige über dem Spiel, wie bei Steam") {
                p.overlayOn = !p.overlayOn
                svc.overlay.refresh()
            },
            MenuEntry("Angezeigte Werte", hint = p.overlayItems.mapNotNull { OverlayItem.of(it)?.label }.joinToString(", ")) {
                openEditor("Overlay-Werte", listOf(
                    EditSection("Angezeigt", p.overlayItems.toMutableList()),
                    EditSection("Ausgeblendet", OverlayItem.values().map { it.id }.filter { it !in p.overlayItems }.toMutableList()),
                ), enableTo = 0, label = { OverlayItem.of(it)?.label ?: it }, note = { "" }) {
                    p.overlayItems = editSections[0].ids.toList()
                    if (p.overlayOn) svc.overlay.show()
                }
            },
            MenuEntry("Position", hint = p.overlayPos.label) {
                showMenu("Position", OverlayPos.values().map { pos ->
                    MenuEntry(pos.label, checked = p.overlayPos == pos) { p.overlayPos = pos; if (p.overlayOn) svc.overlay.show() }
                })
            },
            MenuEntry("Anordnung", hint = if (p.overlayRow) "Nebeneinander" else "Untereinander") {
                p.overlayRow = !p.overlayRow
                if (p.overlayOn) svc.overlay.show()
            },
            MenuEntry("Größe", hint = "◀  ${Math.round(p.overlayScale * 100)} %  ▶", side = { dir ->
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
        showMenu("Kachelfarbe", TILE_COLORS.map { (c, name) ->
            MenuEntry(name, checked = svc.prefs.tileColor == c, swatch = if (c == 0) pal.primary else c) {
                svc.prefs.tileColor = c
                reopen()
            }
        } + MenuEntry("Eigene Farbe …", checked = own != null, hint = "Aus dem Farbrad wählen", swatch = own ?: 0) { svc.ui.post { tileColorWheel() } })
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
        h(KeyEvent.KEYCODE_BUTTON_A, "ein/aus")
        if (adjust != null) h(KeyEvent.KEYCODE_DPAD_LEFT, "Größe −/+")
        h(KeyEvent.KEYCODE_BUTTON_L1, "nach oben")
        h(KeyEvent.KEYCODE_BUTTON_R1, "nach unten")
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
        openEditor("Layout anpassen", listOf(
            EditSection("Linke Spalte", left.toMutableList()),
            EditSection("Rechte Spalte", right.toMutableList()),
            EditSection("Ausgeblendet", hidden.toMutableList()),
        ), enableTo = 1, label = { CcBlock.of(it)?.label ?: it },
            note = { id -> "Größe ${Math.round(size(id) * 100)} %" },
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
        head.addView(text("Kacheln bearbeiten", 18f, pal.onSurface, bold = true), lp(0, WRAP, 1f))
        fun h(k: Int, t: String) {
            head.addView(Glyphs.chip(svc, svc.prefs, pal, k, 20))
            head.addView(text(t, 12f, pal.onSurfaceVariant).apply { setPadding(5 * d, 0, 12 * d, 0) })
        }
        h(KeyEvent.KEYCODE_BUTTON_A, "Hinzufügen / Verschieben")
        h(KeyEvent.KEYCODE_BUTTON_X, "Entfernen")
        h(KeyEvent.KEYCODE_BUTTON_B, "Fertig")
        box.addView(head)
        val content = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val t1 = text("", 13f, pal.primary, bold = true).apply { setPadding(4 * d, 12 * d, 0, 6 * d) }
        geTitle = t1
        content.addView(t1)
        val g1 = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        geActiveGrid = g1
        content.addView(g1)
        content.addView(View(svc).apply { setBackgroundColor(pal.outline) }, lp(MATCH, 1 * d).apply { topMargin = 12 * d; bottomMargin = 4 * d })
        content.addView(text("Zum Hinzufügen auswählen", 13f, pal.primary, bold = true).apply { setPadding(4 * d, 8 * d, 0, 6 * d) })
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
        geTitle?.text = "Angezeigt (${geActive.size}) · Reihenfolge wie im Control Center"
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

    fun onKey(e: KeyEvent): Boolean {
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

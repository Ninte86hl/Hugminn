package io.github.ninte86hl.hugminn

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * One element of the Control Center grid: what it is, how many cells it covers and where it sits
 * (x/y in cells, page). x < 0 means "no fixed place yet" (placed into the first free spot).
 */
data class GridEntry(val id: String, val w: Int, val h: Int, val x: Int = -1, val y: Int = -1, val page: Int = 0)

/** Placed element: position in cells on a page. */
data class Placed(val entry: GridEntry, val page: Int, val x: Int, val y: Int)

object GridLayout {
    /** Rows of the grid (set from the settings before each layout). */
    @Volatile var ROWS = 4

    private val infoIds = setOf("system", "fps", "perf", "fan", "temp", "battery", "cpu", "gpu", "ram", "power", "battime", "clock", "stats", "statsgraph", "statsfps")
    private val sliderIds = setOf("brightness", "volume", "brightness2", "volume2", "brightnessAll", "volumeAll")
    /** Two sliders in one element: top and bottom screen. */
    private val doubleIds = setOf("brightnessDuo", "volumeDuo")

    val widgetNames get() = linkedMapOf(
        "system" to t("System-Übersicht", "System overview"),
        "fps" to tr("Bildrate (FPS)"),
        "perf" to tr("Leistungsprofil"),
        "fan" to tr("Lüfter"),
        "temp" to tr("Temperatur"),
        "battery" to tr("Akku"),
        "cpu" to "CPU",
        "gpu" to "GPU",
        "ram" to "RAM",
        "power" to tr("Leistungsaufnahme"),
        "battime" to t("Akku-Spielzeit", "Battery playtime"),
        "clock" to t("Uhr", "Clock"),
        "stats" to t("CPU · GPU · RAM", "CPU · GPU · RAM"),
        "statsgraph" to t("Verlauf CPU / GPU", "CPU / GPU history"),
        "statsfps" to t("FPS + CPU · GPU · RAM", "FPS + CPU · GPU · RAM"),
        "brightness" to tr("Helligkeit"),
        "volume" to tr("Lautstärke"),
    ).apply {
        // Pocket Duo: the lower screen has its own brightness and volume
        if (Device.isDual) { put("brightness2", t("Helligkeit unten", "Brightness below")); put("brightnessDuo", t("Helligkeit oben + unten", "Brightness top + bottom")) }
        if (Device.isDuo) { put("volume2", t("Lautstärke unten", "Volume below")); put("volumeDuo", t("Lautstärke oben + unten", "Volume top + bottom")) }
    }

    fun name(id: String) = widgetNames[id] ?: TileCatalog.all[id] ?: id
    fun isSlider(id: String) = id in sliderIds
    fun isDouble(id: String) = id in doubleIds
    fun isInfo(id: String) = id in infoIds

    /** Sizes an element can have, smallest first. */
    fun sizes(id: String, cols: Int): List<Pair<Int, Int>> = when {
        // standing (1×2, 1×3) or lying (2×1, full row 4×1)
        isSlider(id) -> listOf(1 to 2, 1 to 3, 2 to 1, 4 to 1).filter { it.first <= cols && it.second <= ROWS }
        isDouble(id) -> listOf(2 to 2, 4 to 2).filter { it.first <= cols && it.second <= ROWS }
        // music: also large, with cover and buttons
        id == "media" -> listOf(1 to 1, 2 to 1, 2 to 2, 4 to 2).filter { it.first <= cols && it.second <= ROWS }
        // all live values in one large card
        id == "system" -> listOf(4 to 2).filter { it.first <= cols && it.second <= ROWS }.ifEmpty { listOf(cols to 2) }
        // Wi-Fi and Bluetooth with their lists, profiles with their choices, battery with all details
        id == "wifi" || id == "bt" -> listOf(1 to 1, 2 to 1, 2 to 2).filter { it.first <= cols && it.second <= ROWS }
        id == "perf" || id == "fan" -> listOf(2 to 1, 2 to 2, 3 to 2).filter { it.first <= cols && it.second <= ROWS }
        id == "battery" -> listOf(1 to 1, 2 to 1, 2 to 2)
        id == "fps" -> listOf(2 to 1, 2 to 2)
        id == "stats" -> listOf(2 to 1, 4 to 1, 2 to 2, 2 to 3).filter { it.first <= cols && it.second <= ROWS }
        id == "statsfps" -> listOf(2 to 2, 4 to 2, 2 to 3).filter { it.first <= cols && it.second <= ROWS }
        id == "statsgraph" -> listOf(2 to 2, 4 to 2).filter { it.first <= cols && it.second <= ROWS }
        id == "clock" -> listOf(1 to 1, 2 to 1, 2 to 2).filter { it.first <= cols && it.second <= ROWS }.filter { it.first <= cols && it.second <= ROWS }
        isInfo(id) -> listOf(1 to 1, 2 to 1)
        else -> listOf(1 to 1, 2 to 1)
    }

    /** Default for 8 columns (RP6); on narrower screens elements that do not fit are placed automatically. */
    /** Columns of the Control Center as last laid out (picks the default layout for the screen's shape). */
    @Volatile var lastCols = 8

    /** The default layout fitting the screen: wide (16:9), 4:3 (Pocket Duo, Thor below) or nearly square (Classic). */
    val defaults: List<GridEntry> get() = when {
        lastCols >= 8 -> wide
        lastCols >= 6 -> listOf(
            GridEntry("fps", 2, 2, 0, 0), GridEntry("perf", 2, 1, 2, 0), GridEntry("fan", 2, 1, 4, 0),
            GridEntry("temp", 1, 1, 2, 1), GridEntry("battery", 1, 1, 3, 1), GridEntry("stats", 2, 1, 4, 1),
            GridEntry("wifi", 1, 1, 0, 2), GridEntry("bt", 1, 1, 1, 2), GridEntry("dnd", 1, 1, 2, 2), GridEntry("refresh", 1, 1, 3, 2),
            GridEntry("brightness", 1, 2, 4, 2), GridEntry("volume", 1, 2, 5, 2),
            GridEntry("rotation", 1, 1, 0, 3), GridEntry("screenshot", 1, 1, 1, 3), GridEntry("power", 2, 1, 2, 3),
            GridEntry("leds", 1, 1), GridEntry("airplane", 1, 1), GridEntry("clean", 1, 1),
        )
        else -> listOf(
            GridEntry("fps", 2, 2, 0, 0), GridEntry("temp", 1, 1, 2, 0), GridEntry("battery", 1, 1, 3, 0),
            GridEntry("perf", 2, 1, 2, 1),
            GridEntry("wifi", 1, 1, 0, 2), GridEntry("bt", 1, 1, 1, 2), GridEntry("brightness", 1, 2, 2, 2), GridEntry("volume", 1, 2, 3, 2),
            GridEntry("dnd", 1, 1, 0, 3), GridEntry("refresh", 1, 1, 1, 3),
            GridEntry("stats", 2, 1), GridEntry("fan", 2, 1), GridEntry("rotation", 1, 1), GridEntry("screenshot", 1, 1),
            GridEntry("leds", 1, 1), GridEntry("airplane", 1, 1), GridEntry("power", 2, 1), GridEntry("clean", 1, 1),
        )
    }

    private val wide = listOf(
        GridEntry("fps", 2, 2, 0, 0), GridEntry("perf", 2, 1, 2, 0), GridEntry("fan", 2, 1, 4, 0),
        GridEntry("temp", 1, 1, 6, 0), GridEntry("battery", 1, 1, 7, 0),
        GridEntry("cpu", 2, 1, 2, 1), GridEntry("gpu", 2, 1, 4, 1), GridEntry("ram", 2, 1, 6, 1),
        GridEntry("wifi", 1, 1, 0, 2), GridEntry("bt", 1, 1, 1, 2), GridEntry("dnd", 1, 1, 2, 2), GridEntry("airplane", 1, 1, 3, 2),
        GridEntry("refresh", 1, 1, 4, 2), GridEntry("leds", 1, 1, 5, 2),
        GridEntry("rotation", 1, 1, 0, 3), GridEntry("screenshot", 1, 1, 1, 3), GridEntry("power", 2, 1, 2, 3), GridEntry("clean", 1, 1, 4, 3),
        GridEntry("brightness", 1, 2, 6, 2), GridEntry("volume", 1, 2, 7, 2),
    )

    fun parse(s: String?): List<GridEntry>? = s?.split(',')?.mapNotNull { e ->
        val p = e.split(':')
        when (p.size) {
            3 -> GridEntry(p[0], p[1].toIntOrNull() ?: 1, p[2].toIntOrNull() ?: 1)
            6 -> GridEntry(p[0], p[1].toIntOrNull() ?: 1, p[2].toIntOrNull() ?: 1, p[3].toIntOrNull() ?: -1, p[4].toIntOrNull() ?: -1, p[5].toIntOrNull() ?: 0)
            else -> null
        }
    }?.let { compactPages(it) }

    /** Pages left empty (everything moved off page 1, say) disappear: the others move up. */
    fun compactPages(list: List<GridEntry>): List<GridEntry> {
        val used = list.map { it.page.coerceAtLeast(0) }.distinct().sorted()
        if (used.isEmpty() || used == used.indices.toList()) return list
        return list.map { it.copy(page = used.indexOf(it.page.coerceAtLeast(0))) }
    }

    fun format(list: List<GridEntry>) = list.joinToString(",") { "${it.id}:${it.w}:${it.h}:${it.x}:${it.y}:${it.page}" }

    /** Elements at their fixed places; those without a (valid, free) place go into the first free spot. */
    /** Sizes that are no longer offered become the nearest one (performance and fan: no 1×1). */
    private fun upgrade(e: GridEntry, cols: Int): GridEntry {
        val ok = sizes(e.id, cols)
        if (ok.isEmpty() || (e.w to e.h) in ok) return e
        // the nearest size that is offered (ties: the larger one)
        val (w, h) = ok.minWith(compareBy<Pair<Int, Int>>({ Math.abs(it.first - e.w) + Math.abs(it.second - e.h) }, { -(it.first * it.second) }))
        return e.copy(w = w, h = h)
    }

    fun place(rawList: List<GridEntry>, cols: Int): List<Placed> {
        val list = rawList.map { upgrade(it, cols) }
        val used = HashMap<Int, Array<BooleanArray>>()
        fun grid(p: Int) = used.getOrPut(p) { Array(ROWS) { BooleanArray(cols) } }
        fun fits(e: GridEntry, x: Int, y: Int, p: Int): Boolean {
            if (x < 0 || y < 0 || x + e.w > cols || y + e.h > ROWS) return false
            val g = grid(p)
            for (yy in y until y + e.h) for (xx in x until x + e.w) if (g[yy][xx]) return false
            return true
        }
        fun mark(e: GridEntry, x: Int, y: Int, p: Int) {
            val g = grid(p)
            for (yy in y until y + e.h) for (xx in x until x + e.w) g[yy][xx] = true
        }
        val out = arrayOfNulls<Placed>(list.size)
        val later = mutableListOf<Int>()
        list.forEachIndexed { i, raw ->
            val e = raw.copy(w = raw.w.coerceIn(1, cols), h = raw.h.coerceIn(1, ROWS))
            if (fits(e, e.x, e.y, e.page)) {
                mark(e, e.x, e.y, e.page)
                out[i] = Placed(e, e.page, e.x, e.y)
            } else later += i
        }
        for (i in later) {
            val e = list[i].copy(w = list[i].w.coerceIn(1, cols), h = list[i].h.coerceIn(1, ROWS))
            var p = 0
            while (out[i] == null) {
                loop@ for (y in 0..ROWS - e.h) for (x in 0..cols - e.w) if (fits(e, x, y, p)) {
                    mark(e, x, y, p)
                    out[i] = Placed(e.copy(x = x, y = y, page = p), p, x, y)
                    break@loop
                }
                p++
            }
        }
        return out.map { it!! }
    }

    /** First-fit packing in reading order; elements that no longer fit start the next page. */
    fun pack(list: List<GridEntry>, cols: Int): List<Placed> {
        val out = mutableListOf<Placed>()
        var page = 0
        var used = Array(ROWS) { BooleanArray(cols) }
        for (raw in list) {
            val e = raw.copy(w = raw.w.coerceIn(1, cols), h = raw.h.coerceIn(1, ROWS))
            var spot: Pair<Int, Int>? = null
            for (attempt in 0..1) {
                spot = find(used, e, cols)
                if (spot != null) break
                page++
                used = Array(ROWS) { BooleanArray(cols) }
            }
            val (x, y) = spot ?: continue
            for (yy in y until y + e.h) for (xx in x until x + e.w) used[yy][xx] = true
            out += Placed(e, page, x, y)
        }
        return out
    }

    private fun find(used: Array<BooleanArray>, e: GridEntry, cols: Int): Pair<Int, Int>? {
        for (y in 0..ROWS - e.h) for (x in 0..cols - e.w) {
            var free = true
            loop@ for (yy in y until y + e.h) for (xx in x until x + e.w) if (used[yy][xx]) { free = false; break@loop }
            if (free) return x to y
        }
        return null
    }
}

/** Places its children on a grid of square cells (positions set via [place]). */
class GridBoard(ctx: Context, private val cols: Int, private val gap: Int) : ViewGroup(ctx) {
    private val pos = HashMap<View, IntArray>()
    private val draggable = HashSet<View>()

    /** Whether elements can be dragged right away (edit mode). */
    var editing: () -> Boolean = { false }
    /** Long press on an element (outside edit mode). */
    var onLongPress: ((View) -> Unit)? = null
    /** Element dropped; x/y = cell of its top-left corner. */
    var onDrop: ((View, Int, Int) -> Unit)? = null

    fun place(v: View, x: Int, y: Int, w: Int, h: Int, drag: Boolean = false) {
        pos[v] = intArrayOf(x, y, w, h)
        if (drag) draggable += v
        addView(v)
    }

    // ---- drag & drop ----
    private var dragView: View? = null
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var dragging = false
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    /** How long a tile must be held to move it by touch (long enough not to catch a slider by mistake). */
    private val HOLD_MS = 800L
    private val longPress = Runnable {
        val v = dragView ?: return@Runnable
        longFired = true
        buzz()
        // the element must not also be "clicked"
        val cancel = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        v.dispatchTouchEvent(cancel)
        cancel.recycle()
        onLongPress?.invoke(v)
        lift(v)
    }

    private fun hasSlider(v: View): Boolean =
        v is BigSlider || (v is android.view.ViewGroup && (0 until v.childCount).any { hasSlider(v.getChildAt(it)) })

    /** A short, clear buzz when moving starts (also with touch vibration off in Android). */
    private fun buzz() {
        runCatching {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)
            vib?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
        }.onFailure { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
    }

    /**
     * A slider (brightness, volume) wants the finger for itself: then the hold must not start moving
     * the tile – before, holding the slider still for a moment picked up the whole tile.
     */
    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        if (disallow && !longFired) removeCallbacks(longPress)
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    private fun lift(v: View) {
        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(90).start()
        v.elevation = 16f
        v.bringToFront()
    }

    private fun childAt(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c in draggable && x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c
        }
        return null
    }

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longFired = false
                dragging = false
                dragView = childAt(e.x, e.y)
                // tiles are moved by touch only in edit mode (Y / "Bearbeiten" → Anordnen): outside of
                // it a touch only uses the tile – sliders, menus – and never picks it up
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val far = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop
                if (far && !longFired) removeCallbacks(longPress)
                if (far && dragView != null && (editing() || longFired) && !dragging) {
                    dragging = true
                    if (!longFired) lift(dragView!!)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return dragging || longFired
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        // outside edit mode a touch that no tile took (a value card) is nothing – no drop, no re-draw
        if (!editing() && !dragging && !longFired) { dragView = null; return false }
        val v = dragView ?: return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) dragging = true
                if (dragging) {
                    v.translationX = e.x - downX
                    v.translationY = e.y - downY
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val c = cell()
                val cx = Math.round((v.left + v.translationX - originX(c)) / (c + gap).toFloat())
                val cy = Math.round((v.top + v.translationY - originY(c)) / (c + gap).toFloat())
                v.translationX = 0f
                v.translationY = 0f
                v.scaleX = 1f
                v.scaleY = 1f
                v.elevation = 0f
                dragView = null
                dragging = false
                longFired = false
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP) onDrop?.invoke(v, cx, cy)
            }
        }
        return true
    }

    private fun cell(): Int {
        val byW = (width - gap * (cols - 1)) / cols
        val byH = (height - gap * (GridLayout.ROWS - 1)) / GridLayout.ROWS
        return minOf(byW, byH).coerceAtLeast(1)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        val c = minOf((w - gap * (cols - 1)) / cols, (h - gap * (GridLayout.ROWS - 1)) / GridLayout.ROWS).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val p = pos[v] ?: continue
            v.measure(
                MeasureSpec.makeMeasureSpec(c * p[2] + gap * (p[2] - 1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(c * p[3] + gap * (p[3] - 1), MeasureSpec.EXACTLY),
            )
        }
    }

    /** Elements sit exactly where they are in edit mode: the whole grid is centred, not only the used cells. */
    private fun originX(c: Int): Int = (width - (c * cols + gap * (cols - 1))) / 2

    private fun originY(c: Int): Int = ((height - (c * GridLayout.ROWS + gap * (GridLayout.ROWS - 1))) / 2).coerceAtLeast(0)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = cell()
        val ox = originX(c)
        val oy = originY(c)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val p = pos[v] ?: continue
            val x = ox + p[0] * (c + gap)
            val y = oy + p[1] * (c + gap)
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
    }
}

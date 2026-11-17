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
    const val ROWS = 4

    private val infoIds = setOf("fps", "perf", "fan", "temp", "battery", "cpu", "gpu", "ram", "power")
    private val sliderIds = setOf("brightness", "volume")

    val widgetNames = linkedMapOf(
        "fps" to "Bildrate (FPS)",
        "perf" to "Leistungsprofil",
        "fan" to "Lüfter",
        "temp" to "Temperatur",
        "battery" to "Akku",
        "cpu" to "CPU",
        "gpu" to "GPU",
        "ram" to "RAM",
        "power" to "Leistungsaufnahme",
        "brightness" to "Helligkeit",
        "volume" to "Lautstärke",
    )

    fun name(id: String) = widgetNames[id] ?: TileCatalog.all[id] ?: id
    fun isSlider(id: String) = id in sliderIds
    fun isInfo(id: String) = id in infoIds

    /** Sizes an element can have, smallest first. */
    fun sizes(id: String, cols: Int): List<Pair<Int, Int>> = when {
        // vertical (1×2 … 1×4) or horizontal (2×1 … full width)
        isSlider(id) -> (2..ROWS).map { 1 to it } + (2..cols).map { it to 1 }
        isInfo(id) -> listOf(1 to 1, 2 to 1, 2 to 2)
        else -> listOf(1 to 1, 2 to 1)
    }

    /** Default for 8 columns (RP6); on narrower screens elements that do not fit are placed automatically. */
    val defaults = listOf(
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
    }

    fun format(list: List<GridEntry>) = list.joinToString(",") { "${it.id}:${it.w}:${it.h}:${it.x}:${it.y}:${it.page}" }

    /** Elements at their fixed places; those without a (valid, free) place go into the first free spot. */
    fun place(list: List<GridEntry>, cols: Int): List<Placed> {
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
    private val longPress = Runnable {
        val v = dragView ?: return@Runnable
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        // the element must not also be "clicked"
        val cancel = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        v.dispatchTouchEvent(cancel)
        cancel.recycle()
        onLongPress?.invoke(v)
        lift(v)
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
                if (dragView != null && !editing()) postDelayed(longPress, 450)
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
                // Outside edit mode the board is centred on the columns actually in use (no empty strip on the right).
        val used = if (editing()) cols else (pos.values.maxOfOrNull { it[0] + it[2] } ?: cols).coerceIn(1, cols)
        val totalW = c * used + gap * (used - 1)
                val ox = (width - totalW) / 2
                val cx = Math.round((v.left + v.translationX - ox) / (c + gap).toFloat())
                val cy = Math.round((v.top + v.translationY) / (c + gap).toFloat())
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = cell()
        val totalW = c * cols + gap * (cols - 1)
        val ox = (width - totalW) / 2
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val p = pos[v] ?: continue
            val x = ox + p[0] * (c + gap)
            val y = p[1] * (c + gap)
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
    }
}

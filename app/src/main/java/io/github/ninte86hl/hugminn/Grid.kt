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

    fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
        pos[v] = intArrayOf(x, y, w, h)
        addView(v)
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

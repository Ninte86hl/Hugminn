package io.github.ninte86hl.hugminn

/**
 * Frame rate of the game in front, measured from SurfaceFlinger's present times of the layer the
 * game draws into (`dumpsys SurfaceFlinger --latency <layer>`, root). Which layer that is differs per
 * app (SurfaceView, its BLAST child, the window …), so every layer of the app is tried once and the
 * one that really receives pictures is kept. Only simple shell commands are used; counting is done here.
 */
object FrameRate {
    class Result(val fps: Int, val frameTimesMs: List<Float>)

    /** What the last measurement saw (for feedback reports). */
    @Volatile var diag = ""

    private var layerPkg: String? = null
    private var layer: String? = null
    private var layerAt = 0L
    private var emptyRuns = 0
    private val recent = ArrayDeque<Float>()
    private var flipsPrev = -1L
    private var flipsAt = 0L
    private var flipRaw = ""
    private var rawInfo = ""

    /**
     * Fallback when no layer of the game reports present times (e.g. Dolphin): SurfaceFlinger's count of
     * pictures shown on the display. Counts everything on screen, but while a game runs that is the game.
     */
    private fun displayFps(): Int? {
        val out = (Root.exec("service call SurfaceFlinger 1013") ?: Root.execLong("service call SurfaceFlinger 1013", 3_000))
        flipRaw = out?.replace('\n', ' ')?.take(120) ?: "keine Antwort"
        if (out == null) return null
        val v = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(out)?.groupValues?.get(1)?.toLongOrNull(16) ?: return null
        val now = System.nanoTime()
        val prev = flipsPrev; val at = flipsAt
        flipsPrev = v; flipsAt = now
        if (prev < 0 || v < prev || now - at > 5_000_000_000L || now - at < 200_000_000L) return null
        return ((v - prev) * 1_000_000_000.0 / (now - at)).toInt()
    }

    /** Present times (ns) of a layer, newest 130; the command form that is known to work through the root service. */
    private fun presents(layer: String): List<Long>? {
        val out = Root.execLong("dumpsys SurfaceFlinger --latency ${Root.quote(layer)} | awk 'NR>1 { print 0, \$2 }' | tail -n 130", 3_000) ?: return null
        val ls = out.lines().filter { it.isNotBlank() }
        rawInfo = "${ls.size} Zeilen, z.B. ${ls.getOrNull(ls.size / 2)?.take(40)}"
        return out.lines().mapNotNull { it.trim().split(' ').getOrNull(1)?.toLongOrNull() }
            .filter { it in 1 until 9_000_000_000_000_000_000L }.sorted()
    }

    /** Frames within the last second before the newest one, and that newest time. */
    private fun count(p: List<Long>): Pair<Int, Long> {
        val last = p.lastOrNull() ?: return 0 to 0L
        return (p.count { last - it <= 1_000_000_000L } - 1).coerceAtLeast(0) to last
    }

    /** Tries every layer of [pkg] and keeps the one with the most recent pictures. */
    private fun findLayer(pkg: String): String? {
        val now = System.currentTimeMillis()
        if (pkg == layerPkg && now - layerAt < 15_000) return layer
        val list = Root.execLong("dumpsys SurfaceFlinger --list | grep -F ${Root.quote(pkg)}", 4_000)
        if (list == null) { diag = "Ebenenliste: keine Antwort"; return null }
        val names = list.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("ActivityRecord") && !it.contains("InputSink") }
            .sortedBy { if (it.contains("(BLAST)")) 0 else if (it.startsWith("SurfaceView")) 1 else 2 }.take(14)
        val lines = HashMap<String, Int>(); var raw = ""
        val rows = names.map { n -> val p = presents(n); lines[n] = p?.size ?: -1; if (raw.isEmpty() && n.contains("(BLAST)")) raw = rawInfo; val c = p?.let { count(it) } ?: (0 to 0L); Triple(c.first, c.second, n) }
        val best = rows.maxWithOrNull(compareBy({ it.first }, { it.second }))
        diag = "Ebenen: " + (if (rows.isEmpty()) "keine (Liste: ${list.replace('\n', ' ').take(200)})" else rows.joinToString { "${it.third.takeLast(60)}=${it.first}/${lines[it.third]}" } + " · BLAST roh: $raw")
        layerPkg = pkg
        layer = best?.takeIf { it.first > 0 || it.second > 0 }?.third
        layerAt = now
        return layer
    }

    fun sample(pkg: String?): Result? {
        if (pkg == null || !Root.available()) return null
        val l = findLayer(pkg)
        if (l == null) {
            val f = displayFps()
            diag = diag.substringBefore(" · Messung") + " · Messung: Bildwechsel-Zähler → ${f ?: "?"} ($flipRaw)"
            val shown = (f ?: 0).coerceIn(0, 240)
            if (shown > 0) { recent.addLast(1000f / shown); while (recent.size > 60) recent.removeFirst() }
            return Result(shown, recent.toList())
        }
        flipsPrev = -1
        val (fps, lastNs) = count(presents(l) ?: return null)
        val last = lastNs.toDouble()
        // no new picture for a while: the game is paused (or drawing elsewhere now)
        val stale = last <= 0 || System.nanoTime() - last > 1_500_000_000.0
        if (stale || fps == 0) {
            if (++emptyRuns >= 3) layerAt = 0 // look for the layer again
        } else emptyRuns = 0
        val shown = if (stale) 0 else fps.coerceIn(0, 240)
        if (shown > 0) { recent.addLast(1000f / shown); while (recent.size > 60) recent.removeFirst() }
        diag = diag.substringBefore(" · Messung") + " · Messung: ${l.take(70)} → $fps (${if (stale) "steht" else "läuft"})"
        return Result(shown, recent.toList())
    }
}

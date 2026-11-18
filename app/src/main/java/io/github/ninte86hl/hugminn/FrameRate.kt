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

    /** Present times (ns) of a layer, newest 130; the command form that is known to work through the root service. */
    private fun presents(layer: String): List<Long>? {
        val out = Root.execLong("dumpsys SurfaceFlinger --latency ${Root.quote(layer)} | awk 'NR>1 { print 0, \$2 }' | tail -n 130", 3_000) ?: return null
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
        if (pkg == layerPkg && layer != null && now - layerAt < 15_000) return layer
        val list = Root.execLong("dumpsys SurfaceFlinger --list | grep -F ${Root.quote(pkg)}", 4_000)
        if (list == null) { diag = "Ebenenliste: keine Antwort"; return null }
        val names = list.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("ActivityRecord") && !it.contains("InputSink") }.take(10)
        val rows = names.map { n -> val c = presents(n)?.let { count(it) } ?: (0 to 0L); Triple(c.first, c.second, n) }
        val best = rows.maxWithOrNull(compareBy({ it.first }, { it.second }))
        diag = "Ebenen: " + (if (rows.isEmpty()) "keine (Liste: ${list.replace('\n', ' ').take(200)})" else rows.joinToString { "${it.third.take(70)}=${it.first}" })
        layerPkg = pkg
        layer = best?.takeIf { it.first > 0 || it.second > 0 }?.third
        layerAt = now
        return layer
    }

    fun sample(pkg: String?): Result? {
        if (pkg == null || !Root.available()) return null
        val l = findLayer(pkg) ?: return Result(0, emptyList())
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

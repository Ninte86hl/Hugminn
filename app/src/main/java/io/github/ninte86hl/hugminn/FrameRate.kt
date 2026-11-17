package io.github.ninte86hl.hugminn

/**
 * Frame rate of the app in the foreground, measured like dumpsys does: SurfaceFlinger's present
 * timestamps for that app's layer (needs the root service). Falls back to the display's frame counter.
 */
object FrameRate {
    class Result(val fps: Int, val frameTimesMs: List<Float>)

    private var layerPkg: String? = null
    private var layer: String? = null
    private var layerAt = 0L

    private fun findLayer(pkg: String): String? {
        val now = System.currentTimeMillis()
        if (pkg == layerPkg && now - layerAt < 8_000) return layer
        val list = Root.execLong("dumpsys SurfaceFlinger --list", 6_000) ?: return null
        val candidates = list.lines().map { it.trim() }.filter { it.contains(pkg) && !it.startsWith("ActivityRecord") && !it.contains("ActivityRecordInputSink") }
        // Games draw into a SurfaceView; otherwise the app window itself.
        val found = candidates.firstOrNull { it.startsWith("SurfaceView") && !it.contains("BLAST") && !it.contains("Background") }
            ?: candidates.firstOrNull { it.startsWith("SurfaceView") }
            ?: candidates.firstOrNull { !it.startsWith("Task") }
        layerPkg = pkg
        layer = found
        layerAt = now
        return found
    }

    fun sample(pkg: String?): Result? {
        if (pkg == null || !Root.available()) return null
        val l = findLayer(pkg) ?: return null
        val out = Root.execLong("dumpsys SurfaceFlinger --latency ${Root.quote(l)}", 4_000) ?: return null
        val presents = out.lines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\\\s+"))
            if (p.size < 3) null else p[1].toLongOrNull()
        }.filter { it in 1 until Long.MAX_VALUE }.sorted()
        if (presents.size < 2) {
            layerAt = 0 // layer may have changed
            return Result(0, emptyList())
        }
        val last = presents.last()
        val window = presents.filter { last - it <= 1_000_000_000L }
        val times = presents.zipWithNext { a, b -> (b - a) / 1_000_000f }.takeLast(60)
        // No new frame for a while: the app is paused
        val stale = (System.nanoTime() - last) > 1_500_000_000L
        return Result(if (stale) 0 else (window.size - 1).coerceAtLeast(0), times)
    }
}

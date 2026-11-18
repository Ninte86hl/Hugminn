package io.github.ninte86hl.hugminn

/**
 * Frame rate of the game in front, measured from SurfaceFlinger's present times of the layer the
 * game draws into (`dumpsys SurfaceFlinger --latency <layer>`, root). Which layer that is differs per
 * app (SurfaceView, its BLAST child, the window …), so every layer of the app is tried once and the
 * one that really receives pictures is kept. All counting happens in the shell, so the root service
 * only has to hand back one short line.
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

    /** awk: frames presented within the last second before the newest one, and that newest time. */
    private const val COUNT =
        "awk 'NR>1 && \$2>0 && \$2<9000000000000000000 { t[++n]=\$2; if (\$2>m) m=\$2 } " +
            "END { c=0; for (i=1;i<=n;i++) if (m-t[i] <= 1000000000) c++; printf \"%d %.0f\\n\", (c>0?c-1:0), m }'"

    /** Tries every layer of [pkg] and keeps the one with the most recent pictures. */
    private fun findLayer(pkg: String): String? {
        val now = System.currentTimeMillis()
        if (pkg == layerPkg && layer != null && now - layerAt < 15_000) return layer
        // the layer names go to a file first: dumpsys inside a "while read" loop would eat its input
        val list = "/data/local/tmp/hugminn_layers"
        val script = "dumpsys SurfaceFlinger --list | grep -F ${Root.quote(pkg)} | grep -v ActivityRecord | grep -v InputSink | head -n 12 > $list; " +
            "while IFS= read -r L; do n=\$(dumpsys SurfaceFlinger --latency \"\$L\" </dev/null | $COUNT); echo \"\$n|\$L\"; done < $list; rm -f $list"
        val out = Root.execLong(script, 6_000)
        if (out == null) { diag = "Ebenensuche: keine Antwort"; return null }
        val rows = out.lines().mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val nums = parts[0].trim().split(' ')
            Triple(nums.getOrNull(0)?.toIntOrNull() ?: 0, nums.getOrNull(1)?.toDoubleOrNull() ?: 0.0, parts[1])
        }
        val best = rows.maxWithOrNull(compareBy({ it.first }, { it.second }))
        diag = "Ebenen: " + (if (rows.isEmpty()) "keine lesbar, Antwort: ${out.replace('\n', ' ').take(300)}" else rows.joinToString { "${it.third.take(70)}=${it.first}" })
        layerPkg = pkg
        layer = best?.takeIf { it.first > 0 || it.second > 0 }?.third
        layerAt = now
        return layer
    }

    fun sample(pkg: String?): Result? {
        if (pkg == null || !Root.available()) return null
        val l = findLayer(pkg) ?: return Result(0, emptyList())
        val out = Root.execLong("dumpsys SurfaceFlinger --latency ${Root.quote(l)} | $COUNT", 3_000)
        val nums = out?.trim()?.split(' ')
        val fps = nums?.getOrNull(0)?.toIntOrNull() ?: return null
        val last = nums.getOrNull(1)?.toDoubleOrNull() ?: 0.0
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

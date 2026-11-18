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
        val list = Root.execLong("dumpsys SurfaceFlinger --list | grep -F ${Root.quote(pkg)}", 6_000)
        if (list == null) { diag += " · Ebenenliste: keine Antwort"; return null }
        val candidates = list.lines().map { it.trim() }.filter { it.contains(pkg) && !it.startsWith("ActivityRecord") && !it.contains("ActivityRecordInputSink") }
        diag += " · Ebenen: ${candidates.size}" + (candidates.firstOrNull()?.let { " (z. B. ${it.take(90)})" } ?: "")
        // Games draw into a SurfaceView; on Android 13 its pictures arrive on the "(BLAST)" layer.
        // Names may start with a hash ("35eaec7 com.foo/…", part of the name), so look for the words anywhere.
        val names = candidates
        val found = names.firstOrNull { it.contains("SurfaceView") && it.contains("BLAST") && !it.contains("Background") }
            ?: names.firstOrNull { it.contains("SurfaceView") && !it.contains("Background") }
            ?: names.firstOrNull { it.contains("BLAST") && !it.startsWith("Task") }
            ?: names.firstOrNull { !it.startsWith("Task") && !it.startsWith("Splash") }
        layerPkg = pkg
        layer = found
        layerAt = now
        return found
    }

    /** What the last measurement saw (for feedback reports). */
    @Volatile var diag = ""
    @Volatile private var statsOn = false
    @Volatile private var probed = false
    @Volatile private var probe = ""
    private var lastGood = -1
    private var lastGoodAt = 0L
    private val recent = ArrayDeque<Float>()

    /**
     * Frame rate as the screen really shows it: SurfaceFlinger's own frame statistics (TimeStats),
     * i.e. how often a new picture was put on screen since the last sample. The answer of the root
     * service has to stay one short line, so the statistics are boiled down in the shell.
     */
    private fun timeStats(): Int? {
        if (!statsOn) {
            Root.exec("sh -c " + Root.quote("dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1; dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1; echo ok"))
            statsOn = true
            return null
        }
        val script = "dumpsys SurfaceFlinger --timestats -dump | awk '" +
            "/layerName =/ { exit } " +
            "\$1==\"displayRefreshRate\" { rr=\$3 } " +
            "/presentToPresent histogram/ { h=1; next } " +
            "h==1 { for (i=1;i<=NF;i++) { if (split(\$i,kv,\"=\")==2) { ms=kv[1]+0; c=kv[2]+0; if (c>0 && ms<100) { n+=c; t+=ms*c } } } h=0 } " +
            "END { f=(t>0)?1000*n/t:0; if (rr>0 && f>rr) f=rr; printf \"HFPS %d %d\\n\", f+0.5, n }'; " +
            "dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1"
        // through a scratch file: the root service's direct answer is only one line and may come back empty
        if (!probed) {
            probed = true
            probe = Root.execLong("command -v awk; dumpsys SurfaceFlinger --timestats -dump 2>&1 | head -c 160", 8_000)?.replace('\n', ' ') ?: "keine Antwort"
        }
        val raw = Root.execLong(script, 10_000)
        val out = raw?.lines()?.lastOrNull { it.isNotBlank() }
        diag = "TimeStats: ${raw?.replace('\n', ' ')?.take(240) ?: "keine Antwort"} · Probe: ${probe.take(220)}"
        val m = out?.let { Regex("""HFPS (\d+) (\d+)""").find(it) } ?: return null
        val fps = m.groupValues[1].toInt()
        val frames = m.groupValues[2].toInt()
        // no frames at all in the statistics: this firmware doesn't fill them, use the app's own layer
        if (frames == 0 && lastGoodAt == 0L) return null
        val now = System.currentTimeMillis()
        if (frames >= 5 && fps in 1..240) {
            lastGood = fps
            lastGoodAt = now
            return fps
        }
        // a loading screen or a short pause: keep the last value for a moment, then 0
        return if (now - lastGoodAt < 3_000 && lastGood > 0) lastGood else 0
    }

    fun sample(pkg: String?): Result? {
        if (pkg == null || !Root.available()) return null
        timeStats()?.let { fps ->
            if (fps > 0) { recent.addLast(1000f / fps); while (recent.size > 60) recent.removeFirst() }
            return Result(fps, recent.toList())
        }
        val l = findLayer(pkg) ?: return null
        val out = Root.execLong("dumpsys SurfaceFlinger --latency ${Root.quote(l)}", 6_000)
        diag += " · Ebene: $l (${out?.lines()?.size ?: "keine Antwort"} Zeilen${out?.lines()?.getOrNull(1)?.let { ", z. B. $it" } ?: ""})"
        if (out == null) return null
        val presents = out.lines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
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

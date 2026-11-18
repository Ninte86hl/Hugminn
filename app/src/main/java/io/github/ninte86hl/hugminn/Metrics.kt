package io.github.ninte86hl.hugminn

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Live values for the Control Center graphs. All read without root. */
class Metrics(private val ctx: Context, private val foreground: () -> String? = { null }) {
    data class Sample(
        val cpuPct: Float, val cpuMhz: Int,
        val gpuPct: Float, val gpuMhz: Int,
        val ramUsedMb: Long, val ramTotalMb: Long,
        val cpuTemp: Float, val gpuTemp: Float, val batTemp: Float,
        val batPct: Int, val watts: Float, val charging: Boolean,
        /** Frames per second of the app in the foreground (needs root), -1 if unknown. */
        val fps: Int,
        val frameTimesMs: List<Float> = emptyList(),
    )

    private var lastFrames = -1L
    private var lastFrameTime = 0L

    /** SurfaceFlinger's frame counter (transaction 1013) via the root service. */
    private fun fps(): Int {
        val out = Root.exec("service call SurfaceFlinger 1013", 1500) ?: return -1
        val frames = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(out)?.groupValues?.get(1)?.toLongOrNull(16) ?: return -1
        val now = android.os.SystemClock.elapsedRealtime()
        val r = if (lastFrames >= 0 && now > lastFrameTime) ((frames - lastFrames) * 1000f / (now - lastFrameTime)).toInt() else -1
        lastFrames = frames
        lastFrameTime = now
        return r.coerceIn(-1, 240)
    }

    private val cpuFreqFiles = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }
        ?.map { File(it, "scaling_cur_freq") } ?: emptyList()
    private val gpuDir = File("/sys/class/kgsl/kgsl-3d0")

    // Thermal zones by kind; modem and charger sensors are ignored (the RP6 has no modem).
    private val cpuZones = mutableListOf<File>()
    private val gpuZones = mutableListOf<File>()

    init {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { z ->
            val type = read(File(z, "type")) ?: return@forEach
            val temp = File(z, "temp")
            when {
                type.startsWith("cpu-") || type.startsWith("cpuss-") -> cpuZones += temp
                type.startsWith("gpuss-") || type == "gpu" -> gpuZones += temp
            }
        }
    }

    private fun read(f: File): String? = try {
        f.readText().trim()
    } catch (t: Throwable) {
        null
    }

    private fun maxTemp(zones: List<File>): Float =
        zones.mapNotNull { read(it)?.toFloatOrNull() }.filter { it in 1f..150_000f }.maxOrNull()?.let { if (it > 1000) it / 1000f else it } ?: 0f

    companion object {
        /** One measurement serves everyone (overlay and Control Center at the same time). */
        private val lock = Any()
        @Volatile private var shared: Sample? = null
        @Volatile private var sharedAt = 0L
        // CPU deltas belong to the shared measurement, not to one Metrics instance
        private var lastIdle = 0L
        private var lastTotal = 0L

        // The FPS measurement needs root calls that can take a while, so it runs on its own thread and
        // the quick values (CPU, RAM, temperatures …) never wait for it.
        @Volatile private var fpsPkg: String? = null
        @Volatile private var fpsWanted = 0L
        @Volatile private var fpsLatest: FrameRate.Result? = null
        @Volatile private var fpsLatestAt = 0L
        private var fpsThread: Thread? = null

        private fun wantFps(pkg: String?) {
            fpsPkg = pkg
            fpsWanted = System.currentTimeMillis()
            synchronized(this) {
                if (fpsThread?.isAlive == true) return
                fpsThread = Thread {
                    while (System.currentTimeMillis() - fpsWanted < 3_000) {
                        val t = System.currentTimeMillis()
                        val r = try { FrameRate.sample(fpsPkg) } catch (e: Throwable) { null }
                        fpsLatest = r; fpsLatestAt = System.currentTimeMillis()
                        val rest = 500 - (System.currentTimeMillis() - t)
                        if (rest > 0) Thread.sleep(rest)
                    }
                    FrameRate.stop()
                }.apply { isDaemon = true; name = "hugminn-fps"; start() }
            }
        }
    }

    fun sample(): Sample {
        shared?.takeIf { System.currentTimeMillis() - sharedAt < 450 }?.let { return it }
        return synchronized(lock) {
            shared?.takeIf { System.currentTimeMillis() - sharedAt < 450 } ?: measure().also {
                shared = it
                sharedAt = System.currentTimeMillis()
            }
        }
    }

    private fun measure(): Sample {
        wantFps(foreground())
        val frame = fpsLatest?.takeIf { System.currentTimeMillis() - fpsLatestAt < 3_000 }
        // CPU load from /proc/stat deltas.
        var cpuPct = 0f
        read(File("/proc/stat"))?.lineSequence()?.firstOrNull()?.let { line ->
            val v = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size >= 4) {
                val idle = v[3] + (v.getOrNull(4) ?: 0)
                val total = v.sum()
                if (lastTotal > 0 && total > lastTotal) cpuPct = 100f * (1f - (idle - lastIdle).toFloat() / (total - lastTotal))
                lastIdle = idle
                lastTotal = total
            }
        }
        val cpuMhz = cpuFreqFiles.mapNotNull { read(it)?.toIntOrNull() }.maxOrNull()?.div(1000) ?: 0
        val gpuPct = read(File(gpuDir, "gpu_busy_percentage"))?.filter { it.isDigit() }?.toFloatOrNull() ?: 0f
        val gpuMhz = (read(File(gpuDir, "gpuclk"))?.toLongOrNull() ?: 0L).div(1_000_000).toInt()

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = mi.totalMem / 1_048_576
        val usedMb = (mi.totalMem - mi.availMem) / 1_048_576

        val bat = File("/sys/class/power_supply/battery")
        val batTemp = (read(File(bat, "temp"))?.toFloatOrNull() ?: 0f) / 10f
        val batPct = read(File(bat, "capacity"))?.toIntOrNull() ?: 0
        val ua = read(File(bat, "current_now"))?.toLongOrNull() ?: 0L
        val uv = read(File(bat, "voltage_now"))?.toLongOrNull() ?: 0L
        val watts = kotlin.math.abs(ua.toDouble() * uv / 1e12).toFloat()
        val charging = read(File(bat, "status")) == "Charging"

        return Sample(
            cpuPct.coerceIn(0f, 100f), cpuMhz, gpuPct.coerceIn(0f, 100f), gpuMhz,
            usedMb, totalMb, maxTemp(cpuZones), maxTemp(gpuZones), batTemp, batPct, watts, charging,
            frame?.fps ?: -1,
            frame?.frameTimesMs ?: emptyList(),
        )
    }
}

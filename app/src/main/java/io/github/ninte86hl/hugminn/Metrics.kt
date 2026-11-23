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
        /** Minutes until empty (on battery) or full (charging), -1 while unknown. */
        val batMinutes: Int = -1,
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
                // Unisoc (Anbernic): soc-, big7-, mid5-, lit0-, apcpu0-thmzone … and gpu-thmzone
                type == "gpu-thmzone" -> gpuZones += temp
                type.endsWith("-thmzone") && Regex("^(soc|big|mid|lit|apcpu)").containsMatchIn(type) -> cpuZones += temp
            }
        }
    }

    /** Mali/Unisoc GPU: its devfreq node (clock, and the load where the driver reports one). */
    private val gpuFreq: File? = File("/sys/class/devfreq").listFiles()?.firstOrNull { it.name.endsWith(".gpu") || it.name.endsWith("gpu") }

    private fun devfreqGpu(): Pair<Float, Int>? {
        val d = gpuFreq ?: return null
        val mhz = read(File(d, "cur_freq"))?.trim()?.toLongOrNull()?.let { (if (it > 100_000) it / 1_000_000 else it).toInt() } ?: return null
        // "load" is "45@680000000Hz" on many drivers
        val pct = read(File(d, "load"))?.substringBefore('@')?.trim()?.toFloatOrNull()
            ?: read(File(d, "device/utilisation"))?.trim()?.toFloatOrNull() ?: -1f
        return pct to mhz
    }

    @Volatile private var gpuRoot: Pair<Float, Int>? = null
    @Volatile private var gpuRootAt = 0L
    @Volatile private var gpuRootBusy = false

    /** GPU load and clock through root (last values; refreshed in the background every 2 s). */
    private fun rootGpu(): Pair<Float, Int>? {
        if (!Root.available()) return null
        val now = android.os.SystemClock.uptimeMillis()
        if (!gpuRootBusy && now - gpuRootAt > 2000) {
            gpuRootBusy = true
            gpuRootAt = now
            Thread {
                runCatching {
                    val out = Root.execLong("cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpuclk", 1500)?.lines().orEmpty()
                    val p = out.getOrNull(0)?.filter { it.isDigit() }?.toFloatOrNull() ?: 0f
                    val m = (out.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L).div(1_000_000).toInt()
                    gpuRoot = p to m
                }
                gpuRootBusy = false
            }.start()
        }
        return gpuRoot
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
        // average power for the battery estimate (shared like the CPU deltas)
        @Volatile private var avgWatts = -1f
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
        var gpuPct = read(File(gpuDir, "gpu_busy_percentage"))?.filter { it.isDigit() }?.toFloatOrNull() ?: -1f
        var gpuMhz = (read(File(gpuDir, "gpuclk"))?.toLongOrNull() ?: -1L).let { if (it < 0) -1 else (it / 1_000_000).toInt() }
        // some firmwares (AYN Odin 2) do not let apps read the GPU values: ask root, at most every 2 s
        if (gpuPct < 0 || gpuMhz < 0) devfreqGpu()?.let { (p, m) -> if (gpuPct < 0 && p >= 0) gpuPct = p; if (gpuMhz < 0) gpuMhz = m }
        if (gpuPct < 0 || gpuMhz < 0) rootGpu()?.let { (p, m) -> if (gpuPct < 0) gpuPct = p; if (gpuMhz < 0) gpuMhz = m }
        if (gpuPct < 0) gpuPct = 0f
        if (gpuMhz < 0) gpuMhz = 0

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = mi.totalMem / 1_048_576
        val usedMb = (mi.totalMem - mi.availMem) / 1_048_576

        val bat = File("/sys/class/power_supply/battery")
        // Android's own battery values where the firmware hides the files (AYN Odin 2)
        val sticky = runCatching { ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val batTemp = ((read(File(bat, "temp"))?.toFloatOrNull()
            ?: sticky?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)?.toFloat() ?: 0f)) / 10f
        val batPct = read(File(bat, "capacity"))?.toIntOrNull()
            ?: bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 0
        val ua = read(File(bat, "current_now"))?.toLongOrNull()
            ?: bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toLong()?.takeIf { it != Int.MIN_VALUE.toLong() } ?: 0L
        val uv = read(File(bat, "voltage_now"))?.toLongOrNull()
            ?: sticky?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0)?.toLong()?.times(1000) ?: 0L
        val watts = kotlin.math.abs(ua.toDouble() * uv / 1e12).toFloat()
        val charging = read(File(bat, "status"))?.let { it == "Charging" }
            ?: (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) == android.os.BatteryManager.BATTERY_STATUS_CHARGING)
        val batMinutes = estimate(watts, uv, charging)

        var cpuT = maxTemp(cpuZones)
        var gpuT = maxTemp(gpuZones)
        // Anbernic: the helper reads what Android hides from apps
        if (Device.isAnbernic && (cpuT <= 0f || gpuT <= 0f || gpuMhz <= 0)) AnbernicHelper.stats()?.let { st ->
            fun t(v: Float?) = v?.takeIf { it > 0 }?.let { if (it > 1000) it / 1000f else it }
            if (cpuT <= 0f) t(st["cpu"])?.let { cpuT = it }
            if (gpuT <= 0f) t(st["gpu"])?.let { gpuT = it }
            if (gpuMhz <= 0) st["gpumhz"]?.takeIf { it > 0 }?.let { gpuMhz = (if (it > 100_000) it / 1_000_000 else it).toInt() }
            if (gpuPct <= 0f) st["gpuload"]?.let { gpuPct = it }
        }
        return Sample(
            cpuPct.coerceIn(0f, 100f), cpuMhz, gpuPct.coerceIn(0f, 100f), gpuMhz,
            usedMb, totalMb, cpuT, gpuT, batTemp, batPct, watts, charging,
            frame?.fps ?: -1,
            frame?.frameTimesMs ?: emptyList(),
            batMinutes,
        )
    }

    /**
     * Battery time: remaining charge (µAh, from Android) × voltage ÷ the average power of the last
     * minutes. While charging Android's own estimate to full is used.
     */
    private fun estimate(watts: Float, uv: Long, charging: Boolean): Int {
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java) ?: return -1
        if (charging) {
            avgWatts = -1f
            val ms = runCatching { bm.computeChargeTimeRemaining() }.getOrDefault(-1L)
            return if (ms > 0) (ms / 60_000).toInt() else -1
        }
        if (watts <= 0.1f) return -1
        // smooth over roughly two minutes, so a loading screen does not swing the estimate
        avgWatts = if (avgWatts < 0f) watts else avgWatts + (watts - avgWatts) * 0.01f
        val uah = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
        if (uah <= 0 || uv <= 0) return -1
        val wh = uah / 1e6 * (uv / 1e6)
        return (wh / avgWatts * 60).toInt().takeIf { it in 1..(48 * 60) } ?: -1
    }
}

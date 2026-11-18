package io.github.ninte86hl.hugminn

/**
 * Performance and fan profile per app (usually per emulator). When such an app comes to the
 * front its profiles are set; when the user leaves it for an app without profiles or the home
 * screen, the settings from before come back.
 */
object AppProfiles {
    class Profile(val perf: String?, val fan: String?)

    /** App currently running with its own profiles, and what was set before it. */
    @Volatile private var active: String? = null
    private var basePerf: String? = null
    private var baseFan: String? = null
    private var baseCurve = false

    fun all(svc: HugminnService): Map<String, Profile> = parse(svc.prefs.appProfiles)

    fun of(svc: HugminnService, pkg: String?): Profile? = pkg?.let { all(svc)[it] }

    /** Stores ([value] = null removes) the performance ("perf") or fan ("fan") profile for [pkg]. */
    fun set(svc: HugminnService, pkg: String, kind: String, value: String?) {
        val m = all(svc).toMutableMap()
        val old = m[pkg]
        val p = if (kind == "perf") Profile(value, old?.fan) else Profile(old?.perf, value)
        if (p.perf == null && p.fan == null) m.remove(pkg) else m[pkg] = p
        svc.prefs.appProfiles = m.entries.joinToString(";") { (k, v) -> "$k=${v.perf ?: ""}|${v.fan ?: ""}" }
        // stored while that app is in front: it is the active one now (its settings are already set)
        if (pkg == svc.foregroundPkg && active == null && m.containsKey(pkg)) remember(svc).also { active = pkg }
        if (!m.containsKey(pkg) && active == pkg) active = null
    }

    private fun parse(s: String?): Map<String, Profile> = s.orEmpty().split(';').mapNotNull { e ->
        val pkg = e.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val v = e.substringAfter('=')
        pkg to Profile(v.substringBefore('|').ifBlank { null }, v.substringAfter('|', "").ifBlank { null })
    }.toMap()

    private fun remember(svc: HugminnService) {
        basePerf = Device.sys(svc, "performance_mode")
        baseFan = Device.sys(svc, "fan_mode")
        baseCurve = svc.prefs.fanCustom
    }

    /** Called when an app comes to the front ([pkg] = null: home screen). */
    fun onForeground(svc: HugminnService, pkg: String?) {
        if (!Root.available()) return
        val p = of(svc, pkg)
        if (p != null && pkg != null) {
            if (active == pkg) return
            if (active == null) remember(svc)
            active = pkg
            apply(svc, p.perf ?: basePerf, p.fan ?: baseFan, if (p.fan == null) baseCurve else p.fan == "6")
        } else if (active != null) {
            active = null
            apply(svc, basePerf, baseFan, baseCurve)
        }
    }

    private fun apply(svc: HugminnService, perf: String?, fan: String?, curve: Boolean) = Thread {
        if (perf != null && perf != Device.sys(svc, "performance_mode")) Device.setPerf(perf) {}
        if (curve && FanCurve.available()) {
            if (!FanCurve.isRunning()) FanCurve.start(svc)
        } else if (fan != null) {
            FanCurve.stop(restoreSmart = false)
            if (fan != Device.sys(svc, "fan_mode")) Device.setFan(fan) {}
        }
    }.start()
}

package io.github.ninte86hl.hugminn

/**
 * Performance and fan profile per app (usually per emulator) and per game. When such an app comes to
 * the front its profiles are set; when the user leaves it for an app without profiles or the home
 * screen, the settings from before come back.
 *
 * Keys: "package" for an app, "package#Game title" for one game in that emulator. A game profile beats
 * the emulator's; what it leaves open (e.g. only the fan) comes from the emulator's profile. The game
 * is recognised a few seconds after it starts, so the emulator's profile applies first.
 */
object AppProfiles {
    class Profile(val perf: String?, val fan: String?)

    /** Key of the profile currently applied, and what was set before it. */
    @Volatile private var activeKey: String? = null
    private var basePerf: String? = null
    private var baseFan: String? = null
    private var baseCurve = false
    @Volatile private var noticeText: String? = null
    @Volatile private var noticeSvc: HugminnService? = null
    private val notice = Runnable {
        val svc = noticeSvc ?: return@Runnable
        val txt = noticeText ?: return@Runnable
        // only if that profile is still the one in use
        if (activeKey != null) Notice.show(svc, txt, corner = true)
    }

    /** Emulator whose running game is being watched. */
    @Volatile private var watchPkg: String? = null

    fun all(svc: HugminnService): Map<String, Profile> = parse(svc.prefs.appProfiles)

    fun of(svc: HugminnService, pkg: String?): Profile? = pkg?.let { all(svc)[it] }

    /** Key for one game of an emulator. */
    fun gameKey(pkg: String, title: String) = pkg + "#" + title.replace(Regex("[;=|#]"), " ").trim()

    /** Package of a key. */
    fun pkgOf(key: String) = key.substringBefore('#')

    /** Game title of a key, null for an app profile. */
    fun titleOf(key: String) = key.substringAfter('#', "").ifBlank { null }

    /** Profile stored for one game (exactly this game, without the emulator's). */
    fun ofGame(svc: HugminnService, pkg: String, title: String): Profile? {
        val k = gameKey(pkg, title)
        return all(svc).entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
    }

    /** Stores ([value] = null removes) the performance ("perf") or fan ("fan") profile under [key]. */
    @Synchronized
    fun set(svc: HugminnService, key: String, kind: String, value: String?) {
        val m = all(svc).toMutableMap()
        val existing = m.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: key
        val old = m[existing]
        val p = if (kind == "perf") Profile(value, old?.fan) else Profile(old?.perf, value)
        m.remove(existing)
        if (p.perf != null || p.fan != null) m[key] = p
        svc.prefs.appProfiles = m.entries.joinToString(";") { (k, v) -> "$k=${v.perf ?: ""}|${v.fan ?: ""}" }
        // stored while that app is in front: its settings are already set, it is the active one now
        val pkg = pkgOf(key)
        if (pkg == svc.foregroundPkg) {
            val hit = find(m, pkg, GameTitle.titles[pkg])
            if (hit != null) { if (activeKey == null) remember(svc); activeKey = hit.first } else activeKey = null
            if (hasGames(m, pkg)) watch(svc, pkg)
        }
    }

    private fun parse(s: String?): Map<String, Profile> = s.orEmpty().split(';').mapNotNull { e ->
        val k = e.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val v = e.substringAfter('=')
        k to Profile(v.substringBefore('|').ifBlank { null }, v.substringAfter('|', "").ifBlank { null })
    }.toMap()

    private fun hasGames(m: Map<String, Profile>, pkg: String) = m.keys.any { it.startsWith("$pkg#") }

    /** The profile that applies to [pkg] running [title]: key and merged values. */
    private fun find(m: Map<String, Profile>, pkg: String, title: String?): Pair<String, Profile>? {
        val app = m[pkg]
        val game = title?.let { t -> gameKey(pkg, t).let { k -> m.entries.firstOrNull { it.key.equals(k, ignoreCase = true) } } }
        return when {
            game != null -> game.key to Profile(game.value.perf ?: app?.perf, game.value.fan ?: app?.fan)
            app != null -> pkg to app
            else -> null
        }
    }

    private fun remember(svc: HugminnService) {
        basePerf = Device.sys(svc, "performance_mode")
        baseFan = Device.sys(svc, "fan_mode")
        baseCurve = svc.prefs.fanCustom
    }

    /** Called when an app comes to the front ([pkg] = null: home screen). */
    fun onForeground(svc: HugminnService, pkg: String?) {
        if (!Root.available()) return
        if (pkg != watchPkg) watchPkg = null
        evaluate(svc, pkg, pkg?.let { GameTitle.titles[it] })
        if (pkg != null && hasGames(all(svc), pkg)) watch(svc, pkg)
    }

    @Synchronized
    private fun evaluate(svc: HugminnService, pkg: String?, title: String?) {
        val hit = pkg?.let { find(all(svc), it, title) }
        if (hit != null) {
            if (activeKey == hit.first) return
            if (activeKey == null) remember(svc)
            activeKey = hit.first
            val p = hit.second
            apply(svc, p.perf ?: basePerf, p.fan ?: baseFan, if (p.fan == null) baseCurve else p.fan == "6")
            // a short notice says which profile took over (game or emulator)
            val who = titleOf(hit.first) ?: Apps.label(svc, pkgOf(hit.first))
            val what = listOfNotNull(p.perf?.let { Device.perfProfiles[it] ?: it }, p.fan?.let { t("Lüfter ", "Fan ") + (Device.fanProfiles[it] ?: it) }).joinToString(" · ")
            // one notice, once things have settled (the game's profile often follows the emulator's within seconds)
            svc.ui.removeCallbacks(notice)
            noticeText = if (what.isNotEmpty()) t("Profil für $who: $what", "Profile for $who: $what") else null
            noticeSvc = svc
            svc.ui.postDelayed(notice, 5_000)
        } else if (activeKey != null) {
            activeKey = null
            svc.ui.removeCallbacks(notice)
            apply(svc, basePerf, baseFan, baseCurve)
        }
    }

    /** While an emulator with game profiles is in front, checks every few seconds which game runs. */
    private fun watch(svc: HugminnService, pkg: String) {
        if (watchPkg == pkg) return
        watchPkg = pkg
        Thread {
            try {
                while (watchPkg == pkg && svc.foregroundPkg == pkg && !svc.homeInFront) {
                    val t = runCatching { GameTitle.current(pkg) }.getOrNull()
                    if (watchPkg != pkg || svc.foregroundPkg != pkg || svc.homeInFront) break
                    evaluate(svc, pkg, t)
                    Thread.sleep(8_000)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Crash.note(svc, t)
            }
            if (watchPkg == pkg) watchPkg = null
        }.apply { isDaemon = true; name = "hugminn-gameprofile" }.start()
    }

    /** One after another, in the order they were asked for: quick app switches can't overtake each other. */
    private val applier = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "hugminn-profile").apply { isDaemon = true } }

    private fun apply(svc: HugminnService, perf: String?, fan: String?, curve: Boolean) = applier.execute {
        if (perf != null && perf != Device.sys(svc, "performance_mode")) Device.setPerf(perf) {}
        if (curve && FanCurve.available()) {
            if (!FanCurve.isRunning()) FanCurve.start(svc)
        } else if (fan != null) {
            FanCurve.stop(restoreSmart = false)
            if (fan != Device.sys(svc, "fan_mode")) Device.setFan(fan) {}
        }
    }
}

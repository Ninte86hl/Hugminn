package io.github.ninte86hl.hugminn

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

enum class AppType(private val labelDe: String) { APP("App"), RETROARCH("RetroArch"), EMULATOR("Emulator");

    val label get() = tr(labelDe)
}

/** Resolves labels from the recents view to packages and tells apps, RetroArch and emulators apart. */
object Apps {
    /** Emulators, RetroArch and Android games (category or flag "game"): apps a profile makes sense for. */
    fun isGameLike(ctx: android.content.Context, pkg: String): Boolean {
        if (typeOf(pkg) != AppType.APP) return true
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        @Suppress("DEPRECATION")
        return ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME || ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
    }

    private val platforms = listOf(
        "com.github.stenzek.duckstation" to "PlayStation", "xyz.aethersx2" to "PlayStation 2", "xyz.nethersx2" to "PlayStation 2",
        "com.github.stenzek.aethersx2" to "PlayStation 2", "com.armsx2" to "PlayStation 2", "com.armsx3" to "PlayStation 3",
        "net.rpcs3" to "PlayStation 3", "org.ppsspp" to "PSP", "org.vita3k" to "PS Vita", "ru.vita3k" to "PS Vita",
        "org.dolphinemu" to "GameCube / Wii", "info.cemu" to "Wii U", "org.mupen64plusae" to "Nintendo 64",
        "me.magnum.melonds" to "Nintendo DS", "com.dsemu.drastic" to "Nintendo DS", "org.citra" to "Nintendo 3DS",
        "io.github.lime3ds" to "Nintendo 3DS", "org.azahar_emu" to "Nintendo 3DS", "io.github.borked3ds" to "Nintendo 3DS",
        "io.github.mandarine3ds" to "Nintendo 3DS", "org.yuzu" to "Switch", "dev.eden" to "Switch", "dev.legacy.eden" to "Switch",
        "org.sudachi" to "Switch", "skyline.emu" to "Switch", "io.recompiled.redream" to "Dreamcast", "com.flycast" to "Dreamcast",
        "it.dbtecno.pizzaboy" to "Game Boy", "com.retroarch" to "RetroArch",
    )

    /** The system an emulator plays (e.g. DuckStation → PlayStation), or null if unknown. */
    fun platform(pkg: String): String? = platforms.firstOrNull { pkg.startsWith(it.first) }?.second

    private val emulatorPackages = listOf(
        "org.dolphinemu", "org.ppsspp", "xyz.aethersx2", "xyz.nethersx2", "com.github.stenzek.duckstation",
        "me.magnum.melonds", "com.dsemu.drastic", "org.citra", "io.github.lime3ds", "org.azahar_emu",
        "io.github.borked3ds", "org.yuzu", "dev.eden", "dev.legacy.eden", "org.sudachi", "io.github.mandarine3ds",
        "org.mupen64plusae", "io.recompiled.redream", "com.flycast", "com.explusalpha", "com.fms.",
        "skyline.emu", "org.vita3k", "info.cemu", "com.swordfish.lemuroid", "org.scummvm", "com.winlator",
        "app.gamenative", "com.github.stenzek.aethersx2", "net.rpcs3", "ru.vita3k", "org.mozilla.dosbox",
        "com.armsx2", "com.armsx3", "com.rfandango", "it.dbtecno.pizzaboy", "com.retroarch",
    )

    fun typeOf(pkg: String?): AppType = when {
        pkg == null -> AppType.APP
        pkg.startsWith("com.retroarch") -> AppType.RETROARCH
        emulatorPackages.any { pkg.startsWith(it) } || Regex("emu(lator)?\\b|\\.emu").containsMatchIn(pkg) -> AppType.EMULATOR
        else -> AppType.APP
    }

    @Volatile private var homePkg: String? = null
    @Volatile private var homeAt = 0L

    /**
     * Launchers never count as apps to switch to: Android's own and the home app the user set
     * (Cocoon, ES-DE, Daijisho …).
     */
    fun isHome(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg.contains("launcher", true)) return true
        val now = System.currentTimeMillis()
        if (now - homeAt > 30_000) {
            homePkg = runCatching {
                ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName?.takeIf { it != "android" }
            }.getOrNull()
            homeAt = now
        }
        return pkg == homePkg
    }

    @Volatile private var labelCache: Map<String, String> = emptyMap()

    /** Launcher label → package for all launchable apps. */
    fun labels(ctx: Context): Map<String, String> {
        if (labelCache.isEmpty()) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            labelCache = pm.queryIntentActivities(i, 0).associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        }
        return labelCache
    }

    /** Installed apps that control the stick LEDs (Bifrost, AmbiLuma …): label to package, sorted by name. */
    fun ledApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val rx = Regex("bifrost|ambi ?luma|\\bled\\b", RegexOption.IGNORE_CASE)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { (label, pkg) -> pkg.startsWith("com.moonbench.bifrost") || rx.containsMatchIn(label) || rx.containsMatchIn(pkg) }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    /** Name of an app as the launcher shows it. */
    fun label(ctx: Context, pkg: String): String =
        labels(ctx).entries.firstOrNull { it.value == pkg }?.key
            ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun icon(ctx: Context, pkg: String?): Drawable? = pkg?.let {
        try {
            ctx.packageManager.getApplicationIcon(it)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Most recently resumed packages, newest first (needs usage access; empty otherwise). */
    fun recentPackages(ctx: Context): List<String> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - 6 * 3600_000L, now)
        } catch (t: Throwable) {
            return emptyList()
        }
        val order = LinkedHashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) order[e.packageName] = e.timeStamp
        }
        return order.entries.sortedByDescending { it.value }.map { it.key }
    }
}

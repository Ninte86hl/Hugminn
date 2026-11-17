package io.github.ninte86hl.hugminn

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

enum class AppType(val label: String) { APP("App"), RETROARCH("RetroArch"), EMULATOR("Emulator") }

/** Resolves labels from the recents view to packages and tells apps, RetroArch and emulators apart. */
object Apps {
    private val emulatorPackages = listOf(
        "org.dolphinemu", "org.ppsspp", "xyz.aethersx2", "xyz.nethersx2", "com.github.stenzek.duckstation",
        "me.magnum.melonds", "com.dsemu.drastic", "org.citra", "io.github.lime3ds", "org.azahar_emu",
        "io.github.borked3ds", "org.yuzu", "dev.eden", "dev.legacy.eden", "org.sudachi", "io.github.mandarine3ds",
        "org.mupen64plusae", "io.recompiled.redream", "com.flycast", "com.explusalpha", "com.fms.",
        "skyline.emu", "org.vita3k", "info.cemu", "com.swordfish.lemuroid", "org.scummvm", "com.winlator",
        "app.gamenative", "com.github.stenzek.aethersx2", "net.rpcs3", "ru.vita3k", "org.mozilla.dosbox",
    )

    fun typeOf(pkg: String?): AppType = when {
        pkg == null -> AppType.APP
        pkg.startsWith("com.retroarch") -> AppType.RETROARCH
        emulatorPackages.any { pkg.startsWith(it) } || Regex("emu(lator)?\\b|\\.emu").containsMatchIn(pkg) -> AppType.EMULATOR
        else -> AppType.APP
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

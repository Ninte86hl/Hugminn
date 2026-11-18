package io.github.ninte86hl.hugminn

import android.net.Uri

/**
 * The game an emulator is running, as far as Android lets us see it (root):
 * 1. the file the frontend handed over when starting the emulator (the task's start intent, "dat=…"),
 * 2. otherwise a game file the emulator has open right now (/proc/<pid>/fd).
 * The file name is cleaned up into a title: "Metroid Fusion (USA).gba" → "Metroid Fusion".
 */
object GameTitle {
    /** package → game title (only for apps where one was found). */
    @Volatile var titles: Map<String, String> = emptyMap()
        private set

    /** What the last search saw (test builds report it). */
    @Volatile var diag = ""

    private val romExt = setOf(
        "gba", "gb", "gbc", "nes", "fds", "sfc", "smc", "n64", "z64", "v64", "nds", "dsi", "3ds", "cia", "cci", "cxi",
        "iso", "cso", "chd", "pbp", "bin", "cue", "img", "ccd", "mds", "m3u", "gcm", "gcz", "rvz", "wbfs", "wia", "ciso", "wad",
        "xci", "nsp", "nca", "md", "gen", "smd", "sms", "gg", "32x", "pce", "sgx", "a26", "a78", "lnx", "ngp", "ngc", "ws", "wsc",
        "vb", "gdi", "cdi", "elf", "wua", "wud", "wux", "rpx", "pkg", "xex", "pup", "psvita", "vpk", "zip", "7z", "j64", "jag", "col", "int", "min", "pce", "sv", "wsx", "dsk", "adf",
    )

    /** Called with the task list (background thread). */
    fun update(svc: HugminnService, tasks: List<Pair<String, String?>>) {
        val res = HashMap<String, String>()
        val d = StringBuilder()
        for ((pkg, dat) in tasks) {
            if (res.containsKey(pkg)) continue
            if (Apps.typeOf(pkg) == AppType.APP) continue
            d.append(pkg).append(": dat=").append(dat?.take(160)).append('\n')
            val title = dat?.let { fromPath(it, folderOk = true) } ?: fromLog(pkg, d) ?: fromOpenFiles(pkg, d)
            d.append("  → ").append(title).append('\n')
            if (title != null) res[pkg] = title
        }
        titles = res
        diag = d.toString()
    }

    private fun fromOpenFiles(pkg: String, d: StringBuilder? = null): String? {
        val out = Root.execLong("ls -l /proc/\$(pidof -s ${Root.quote(pkg)})/fd 2>/dev/null", 3_000) ?: return null
        // the largest-looking game file wins; .zip only if nothing else is open
        // only files on the storage (games), not the app's own caches or the system
        val paths = out.lines().mapNotNull { it.substringAfter(" -> ", "").takeIf { p -> p.startsWith("/") } }
            .filter { p -> storage.any { p.startsWith(it) } && !p.contains("/Android/data/") }
        val games = paths.filter { ext(it) in romExt && ext(it) != "zip" && ext(it) != "7z" }
            .ifEmpty { paths.filter { ext(it) == "zip" || ext(it) == "7z" } }
        d?.append("  offene Spieldateien: ")?.append(paths.take(4).joinToString(" | "))?.append('\n')
        return games.firstOrNull()?.let { fromPath(it) }
    }

    private val storage = listOf("/storage/", "/sdcard/", "/mnt/media_rw/", "/mnt/user/")

    /**
     * Most emulators write the file they load into the system log ("Loading content file: …").
     * The newest such line of the app wins.
     */
    private fun fromLog(pkg: String, d: StringBuilder?): String? {
        val exts = romExt.joinToString("|")
        val out = Root.execLong("logcat -d -v brief -t 4000 --pid=\$(pidof -s ${Root.quote(pkg)}) 2>/dev/null | grep -iE '\\.($exts)([^a-z0-9]|\$)' | tail -n 5", 4_000)
            ?: return null
        val rx = Regex("""((?:/storage/|/sdcard/|/mnt/|content://)[^"'\n]*?\.(?:$exts))(?=[^A-Za-z0-9]|$)""", RegexOption.IGNORE_CASE)
        val hit = out.lines().asReversed().firstNotNullOfOrNull { l -> rx.findAll(l).lastOrNull()?.groupValues?.get(1) }
        d?.append("  Log: ")?.append(hit ?: out.lines().lastOrNull()?.take(160))?.append('\n')
        return hit?.let { fromPath(it) }
    }

    private fun ext(p: String) = p.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** "content://…/primary%3ARoms%2Fgba%2FMetroid%20Fusion%20(USA).gba" → "Metroid Fusion". */
    fun fromPath(raw: String, folderOk: Boolean = false): String? {
        var s = Uri.decode(raw.trim().trimEnd('}', ',')).substringBefore('?')
        s = s.trimEnd('/').substringAfterLast('/').substringAfterLast(':')
        val e = s.substringAfterLast('.', "").lowercase()
        // a game folder (Wii U, PS3 …) has no extension: its name is the title
        if (e in romExt) s = s.substringBeforeLast('.')
        else if (!folderOk || (e.isNotEmpty() && e.length <= 4 && e.all { it.isLetterOrDigit() })) return null
        s = s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "") // (USA), [!], (Rev 1) …
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return s.takeIf { it.length >= 2 }
    }
}

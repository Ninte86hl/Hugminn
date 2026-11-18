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
        "vb", "gdi", "cdi", "elf", "psvita", "vpk", "zip", "7z", "j64", "jag", "col", "int", "min", "pce", "sv", "wsx", "dsk", "adf",
    )

    /** Called with the task list (background thread). */
    fun update(svc: HugminnService, tasks: List<Pair<String, String?>>) {
        val res = HashMap<String, String>()
        val d = StringBuilder()
        for ((pkg, dat) in tasks) {
            if (res.containsKey(pkg)) continue
            if (Apps.typeOf(pkg) == AppType.APP) continue
            d.append(pkg).append(": dat=").append(dat?.take(160)).append('\n')
            val title = dat?.let { fromPath(it) } ?: fromOpenFiles(pkg, d)
            d.append("  → ").append(title).append('\n')
            if (title != null) res[pkg] = title
        }
        titles = res
        diag = d.toString()
    }

    private fun fromOpenFiles(pkg: String, d: StringBuilder? = null): String? {
        val out = Root.execLong("ls -l /proc/\$(pidof -s ${Root.quote(pkg)})/fd 2>/dev/null", 3_000) ?: return null
        // the largest-looking game file wins; .zip only if nothing else is open
        val paths = out.lines().mapNotNull { it.substringAfter(" -> ", "").takeIf { p -> p.startsWith("/") } }
            .filter { !it.contains("/Android/data/") || it.contains("/files/") }
        val games = paths.filter { ext(it) in romExt && ext(it) != "zip" && ext(it) != "7z" }
            .ifEmpty { paths.filter { ext(it) == "zip" || ext(it) == "7z" } }
        d?.append("  offene Dateien: ").append(paths.size).append(", z.B. ")
            ?.append(paths.filter { !it.startsWith("/dev") && !it.startsWith("/system") && !it.startsWith("/apex") }.take(6).joinToString(" | "))?.append('\n')
        return games.firstOrNull()?.let { fromPath(it) }
    }

    private fun ext(p: String) = p.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** "content://…/primary%3ARoms%2Fgba%2FMetroid%20Fusion%20(USA).gba" → "Metroid Fusion". */
    fun fromPath(raw: String): String? {
        var s = Uri.decode(raw.trim().trimEnd('}', ',')).substringBefore('?')
        s = s.substringAfterLast('/').substringAfterLast(':')
        val e = s.substringAfterLast('.', "").lowercase()
        if (e !in romExt) return null
        s = s.substringBeforeLast('.')
        s = s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "") // (USA), [!], (Rev 1) …
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return s.takeIf { it.length >= 2 }
    }
}

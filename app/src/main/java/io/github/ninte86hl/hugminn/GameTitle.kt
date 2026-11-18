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
            val title = dat?.let { fromPath(it, folderOk = true) } ?: fromOpenFiles(pkg, d) ?: fromRetroArchHistory(pkg, d) ?: fromLog(pkg, d)
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
        // long commands come back empty from the root service: the search runs as a small script
        val cmd = Root.script("gamelog", "logcat -d -v brief -t 6000 --pid=\$(pidof -s \"\$1\") 2>/dev/null | grep -iE '\\.($exts)([^a-z0-9]|\$)' | tail -n 5\n")
            ?: return null
        val out = Root.execLong("$cmd ${Root.quote(pkg)}", 4_000) ?: return null
        val rx = Regex("""((?:/storage/|/sdcard/|/mnt/|content://)[^"'\n]*?\.(?:$exts))(?=[^A-Za-z0-9]|$)""", RegexOption.IGNORE_CASE)
        val hit = out.lines().asReversed().firstNotNullOfOrNull { l -> rx.findAll(l).lastOrNull()?.groupValues?.get(1) }
        d?.append("  Log: ")?.append(hit ?: out.lines().lastOrNull()?.take(160))?.append('\n')
        return hit?.let { fromPath(it) }
    }

    /** RetroArch keeps a history of started games; the newest entry is the running one. */
    private fun fromRetroArchHistory(pkg: String, d: StringBuilder?): String? {
        if (!pkg.startsWith("com.retroarch")) return null
        for (f in listOf(
            "/storage/emulated/0/RetroArch/playlists/builtin/content_history.lpl",
            "/storage/emulated/0/Android/data/$pkg/files/playlists/builtin/content_history.lpl",
            "/data/data/$pkg/playlists/builtin/content_history.lpl",
            "/data/data/$pkg/files/playlists/builtin/content_history.lpl",
        )) {
            val out = Root.execLong("head -c 3000 ${Root.quote(f)} 2>/dev/null", 3_000) ?: continue
            if (!out.contains("\"path\"")) continue
            val path = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1)
            val label = Regex("\"label\"\\s*:\\s*\"([^\"]*)\"").find(out)?.groupValues?.get(1)
            d?.append("  RetroArch-Verlauf: ")?.append(label)?.append(" | ")?.append(path?.takeLast(120))?.append('\n')
            val fromLabel = label?.takeIf { it.isNotBlank() && it != "N/A" }?.let { clean(it) }
            return fromLabel ?: path?.substringBefore('#')?.let { fromPath(it) }
        }
        d?.append("  RetroArch-Verlauf: nicht gefunden\n")
        return null
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
        // a second extension like ".xiso" in "Game.xiso.iso"
        if (s.substringAfterLast('.', "").lowercase() in setOf("xiso", "nkit", "iso", "dec")) s = s.substringBeforeLast('.')
        return clean(s)
    }

    /** "Metroid Fusion (USA) [!]" → "Metroid Fusion". */
    private fun clean(name: String): String? {
        val s = name.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "") // (USA), [!], (Rev 1) …
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return fixCaps(s).takeIf { it.length >= 2 }
    }

    private val smallWords = setOf("OF", "A", "AN", "TO", "IN", "ON", "AT", "BY", "OR", "THE", "AND", "FOR")
    private val commonShort = setOf("THE", "AND", "FOR", "NOT", "ARE", "YOU", "ONE", "TWO", "NEW", "BIG", "ALL", "OUT", "WAR", "MAN", "SKY", "SEA", "DAY", "AGE", "RUN")
    private val roman = Regex("^[IVXLC]+$")

    /**
     * Words written in capitals get normal case ("THE LEGEND OF ZELDA" → "The Legend of Zelda"),
     * abbreviations and numerals stay (HD, DX, 3D, II, NBA).
     */
    private fun fixCaps(title: String): String {
        val words = title.split(' ')
        return words.mapIndexed { i, w ->
            val letters = w.filter { it.isLetter() }
            if (letters.isEmpty() || letters.any { it.isLowerCase() }) return@mapIndexed w
            val core = letters.uppercase()
            when {
                roman.matches(core) && core.length <= 4 -> w
                i > 0 && core in smallWords -> w.lowercase()
                core.length >= 4 || core in commonShort -> w.lowercase().replaceFirstChar { it.uppercase() }
                else -> w
            }
        }.joinToString(" ")
    }
}

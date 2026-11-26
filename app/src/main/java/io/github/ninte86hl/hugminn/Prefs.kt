package io.github.ninte86hl.hugminn

import android.content.Context
import android.content.SharedPreferences

/** What a Home gesture does. */
enum class Action(private val labelDe: String) {
    NONE("Nichts"),
    HOME("Startbildschirm"),
    SWITCHER("Game Switcher"),
    CONTROL_CENTER("Control Center"),
    RECENTS("Android-Übersicht"),
    LAST_APP("Letzte App"),
    SCREENSHOT("Screenshot"),
    RECORD("Video aufnehmen / stoppen"),
    REPLAY("Letzte Sekunden speichern"),
    /** Dual-screen handhelds: switcher and Control Center at once, one on each screen. */
    BOTH("Spielwechsler + Control Center"),
    /** Pocket Duo: the double press handed on to Retroid's firmware (its control center). */
    RETROID("Retroid-Kontrollcenter");

    val label get() = tr(labelDe)
}

/** What the switcher frame shows where the status bar is. */
enum class StatusBar(private val labelDe: String) {
    OVERLAY("Eigene Anzeige"),
    HIDDEN("Ausblenden"),
    SYSTEM("Android-Statusleiste");

    val label get() = tr(labelDe)
}

enum class Theme(private val labelDe: String) {
    SYSTEM("System"),
    LIGHT("Hell"),
    DARK("Dunkel"),
    OLED("OLED-Schwarz");

    val label get() = tr(labelDe)
}

/** Colours of the performance gauges. */
enum class GaugePalette(private val labelDe: String) {
    TRAFFIC("Ampel (grün → rot)"),
    COOL_WARM("Kalt → warm"),
    ACCENT("Akzentfarbe"),
    MONO("Einfarbig");

    val label get() = tr(labelDe)
}

/** What lies behind the Control Center and the switcher. */
enum class BackdropSource(private val labelDe: String) {
    STYLE("Stil-Farbe"),
    WALLPAPER("Android-Hintergrundbild"),
    IMAGE("Eigenes Bild");

    val label get() = tr(labelDe)
}

/** How tiles and cards are filled. */
enum class TileStyle(private val labelDe: String) {
    STANDARD("Standard"),
    OLED("OLED-Schwarz"),
    GLASS("Glas");

    val label get() = tr(labelDe)
}

/** How the selected element is marked when steering with the D-pad. */
enum class FocusStyle(private val labelDe: String) {
    AUTO("Automatisch"),
    CORNERS("Ecken"),
    FRAME("Rahmen");

    val label get() = tr(labelDe)
}

enum class TileShape(private val labelDe: String) {
    ROUND("Rund"),
    SQUARE("Abgerundetes Quadrat");

    val label get() = tr(labelDe)
}

enum class GlyphStyle(private val labelDe: String) {
    AUTO("Automatisch"),
    XBOX("Modern"),
    NINTENDO("Retro"),
    PLAYSTATION("Modern (alternativ)");

    val label get() = tr(labelDe)
}

class Prefs(private val ctx: Context) {
    companion object {
        @Volatile private var dual: Boolean? = null

        /** Dual-screen features (AYN Thor: switcher and player on the second screen) come with 1.15 – until then only in test builds. */
        fun dualReady(ctx: Context): Boolean = dual ?: runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName?.contains("test") == true
        }.getOrDefault(false).also { dual = it }
    }

    private val p: SharedPreferences = ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE)

    /** 4:3 screens (Pocket Nova) are taller for their width: five rows by default instead of four. */
    private val fourThree = ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels).toFloat() / minOf(it.widthPixels, it.heightPixels) < 1.5f }

    /**
     * Extra buttons (back paddles, M1/M2): every device sends them as C (left / M1) and Z (right / M2).
     * Not set unless the person sets them: a set one no longer reaches the game.
     */
    fun extraKey(code: Int, long: Boolean): Action = enumOr(p.getString("extra${code}_${if (long) "L" else "S"}", null), Action.NONE)
    /** Retroid's Back button held: free for an action (the Duo's double Home belongs to Retroid, so the switcher goes here). */
    var backLong: Action
        get() = enumOr(p.getString("backLong", null), Action.NONE).let { if (it == Action.RETROID) Action.NONE else it }
        set(v) = p.edit().putString("backLong", v.name).apply()

    fun setExtraKey(code: Int, long: Boolean, a: Action) = p.edit().putString("extra${code}_${if (long) "L" else "S"}", a.name).apply()

    var single: Action
        get() = enumOr(p.getString("single", null), Action.HOME)
        set(v) = p.edit().putString("single", v.name).apply()
    var double: Action
        // Pocket Duo: the firmware takes the double press itself (Retroid's control center) – Hugminn never sees it
        get() = enumOr(p.getString("double", null), Action.SWITCHER).let { if (it == Action.RETROID) Action.SWITCHER else it }
        set(v) = p.edit().putString("double", v.name).apply()
    var long: Action
        // Duo: switcher and Control Center together, one per screen (Hugminn offers what Retroid's own panel does)
        get() = enumOr(p.getString("long", null), if (Device.isDuo) Action.BOTH else Action.CONTROL_CENTER).let { if (it == Action.RETROID) Action.NONE else it }
        set(v) = p.edit().putString("long", v.name).apply()

    var statusBar: StatusBar
        get() = enumOr(p.getString("statusBar", null), StatusBar.OVERLAY)
        set(v) = p.edit().putString("statusBar", v.name).apply()

    /** Show the switcher frame also when Android's recents view is opened by gesture. */
    var autoSwitcher: Boolean
        get() = p.getBoolean("autoSwitcher", true)
        set(v) = p.edit().putBoolean("autoSwitcher", v).apply()

    /** Control Center covers the status bar (true) or leaves Android's status bar visible. */
    /** Control Center's top bar: own bar in the Hugminn style (default), none, or Android's status bar. */
    var ccStatusBar: StatusBar
        get() = enumOr(p.getString("ccStatusBar", null), StatusBar.OVERLAY)
        set(v) = p.edit().putString("ccStatusBar", v.name).apply()

    /** Last measured switcher geometry ("screenW,screenH|card l,t,r,b|snap l,t,r,b|pitch"). */
    var switcherGeometry: String?
        get() = p.getString("switcherGeometry", null)
        set(v) = p.edit().putString("switcherGeometry", v).apply()

    /** Quick tiles shown in the Control Center, in catalog order. */
    var ccTiles: List<String>
        get() = p.getString("ccTiles", null)?.split(',')?.filter { it in TileCatalog.all } ?: TileCatalog.defaults
        set(v) = p.edit().putString("ccTiles", v.filter { it in TileCatalog.all }.distinct().joinToString(",")).apply()

    /** Removes values of test versions (the old feedback token never stays on the device). */
    /**
     * Dot matrix became the default style for new installs. Who already used Hugminn without ever
     * choosing a style keeps Modern, so nothing changes under their hands. Runs once.
     */
    fun keepOldStyle() {
        if (p.contains("styleChecked")) return
        val e = p.edit()
        if (!p.contains("uiStyle") && p.all.isNotEmpty()) e.putString("uiStyle", UiStyle.MODERN.name)
        e.putBoolean("styleChecked", true).apply()
    }

    fun forgetOld() {
        p.edit().remove("pixelNf").apply()
        // Liquid Glass was a colour scheme in early 1.13 tests and became a style of its own
        if (p.getString("theme", null) == "GLASS") p.edit().putString("theme", Theme.DARK.name).putString("uiStyle", UiStyle.GLASS.name).apply()
    }

    /** Test build: GitHub token for uploading feedback to the private repository Hugminn-Feedback. */
    var githubToken: String?
        get() = p.getString("githubToken", null)
        set(v) = p.edit().putString("githubToken", v).apply()

    /** Performance/fan profile per app: "pkg=perf|fan;…" (empty part = not set). */
    var appProfiles: String?
        get() = p.getString("appProfiles", null)
        set(v) = p.edit().putString("appProfiles", v).apply()

    var language: Language
        get() = enumOr(p.getString("language", null), Language.AUTO)
        set(v) = p.edit().putString("language", v.name).apply()

    var uiStyle: UiStyle
        get() = enumOr(p.getString("uiStyle", null), UiStyle.DOTS)
        set(v) = p.edit().putString("uiStyle", v.name).apply()

    /** Colour of active tiles (0 = Material You accent). */
    var tileColor: Int
        get() = p.getInt("tileColor", 0)
        set(v) = p.edit().putInt("tileColor", v).apply()

    /** Background of the Control Center and the switcher. */
    var backdrop: BackdropSource
        get() = enumOr(p.getString("backdrop", null), BackdropSource.STYLE)
        set(v) = p.edit().putString("backdrop", v.name).apply()

    /** Darkening (dark themes) or lightening (light themes) over the background picture, in percent. */
    var backdropDim: Int
        get() = p.getInt("backdropDim", 50)
        set(v) = p.edit().putInt("backdropDim", v).apply()

    /** Blur of the background picture: 0 off, 1 light, 2 medium, 3 strong. */
    var backdropBlur: Int
        get() = p.getInt("backdropBlur", 2)
        set(v) = p.edit().putInt("backdropBlur", v).apply()

    var tileStyle: TileStyle
        get() = enumOr(p.getString("tileStyle", null), TileStyle.STANDARD)
        set(v) = p.edit().putString("tileStyle", v.name).apply()

    /** Strength of the glass tiles: 1 clear … 5 frosted. */
    var glassLevel: Int
        get() = p.getInt("glassLevel", 3).coerceIn(1, 5)
        set(v) = p.edit().putInt("glassLevel", v.coerceIn(1, 5)).apply()

    /** Dual-screen handhelds, "switcher + Control Center": the Control Center on the lower screen (else on the top one). */
    var ccLower: Boolean
        get() = p.getBoolean("ccLower", true)
        set(v) = p.edit().putBoolean("ccLower", v).apply()

    /** Dual-screen handhelds (AYN Thor): the switcher shows name, hints and shortcuts on the second screen. */
    var dualSwitcher: Boolean
        get() = dualReady(ctx) && p.getBoolean("dualSwitcher", true)
        set(v) = p.edit().putBoolean("dualSwitcher", v).apply()

    /** Dual-screen handhelds: music/video in front on the top screen → player on the second screen. */
    var dualMedia: Boolean
        get() = dualReady(ctx) && p.getBoolean("dualMedia", true)
        set(v) = p.edit().putBoolean("dualMedia", v).apply()

    /** Apps turned to landscape while in front (YouTube's portrait pages). */
    var landscapeOn: Boolean
        get() = p.getBoolean("landscapeOn", true)
        set(v) = p.edit().putBoolean("landscapeOn", v).apply()

    var landscapeApps: Set<String>
        get() = p.getStringSet("landscapeApps", null) ?: setOf("com.google.android.youtube", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient", "com.crunchyroll.crunchyroid", "com.netflix.mediaclient")
        set(v) = p.edit().putStringSet("landscapeApps", v).apply()

    /** Android's navigation bar hidden everywhere (root, policy_control). */
    var hideNav: Boolean
        get() = p.getBoolean("hideNav", false)
        set(v) = p.edit().putBoolean("hideNav", v).apply()

    /** Test builds: where the feedback button floats (offset from the middle of the right edge). */
    var bubbleY: Int
        get() = p.getInt("bubbleY", 0)
        set(v) = p.edit().putInt("bubbleY", v).apply()

    /** AYN: own colours per stick (0 = none, AYN's one colour applies) – AYN itself keeps only one. */
    var aynLedLeft: Int
        get() = p.getInt("aynLedLeft", 0)
        set(v) = p.edit().putInt("aynLedLeft", v).apply()
    var aynLedRight: Int
        get() = p.getInt("aynLedRight", 0)
        set(v) = p.edit().putInt("aynLedRight", v).apply()

    var focusStyle: FocusStyle
        get() = enumOr(p.getString("focusStyle", null), FocusStyle.AUTO)
        set(v) = p.edit().putString("focusStyle", v.name).apply()

    /** Control Center layout: "left blocks|right blocks", comma separated ids; missing blocks are hidden. */
    var ccLayout: Pair<List<String>, List<String>>
        get() {
            val raw = p.getString("ccLayout", null) ?: return CcBlock.defaultLeft to CcBlock.defaultRight
            val parts = raw.split('|')
            fun list(i: Int) = parts.getOrNull(i)?.split(',')?.filter { id -> CcBlock.values().any { it.id == id } } ?: emptyList()
            return list(0) to list(1)
        }
        set(v) = p.edit().putString("ccLayout", v.first.joinToString(",") + "|" + v.second.joinToString(",")).apply()

    /** Size factor per Control Center block (1.0 = normal). */
    var ccSizes: Map<String, Float>
        get() = p.getString("ccSizes", null)?.split(',')?.mapNotNull { e ->
            val kv = e.split(':')
            kv.getOrNull(1)?.toFloatOrNull()?.let { kv[0] to it }
        }?.toMap() ?: emptyMap()
        set(v) = p.edit().putString("ccSizes", v.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    /** Control Center grid: elements in order with their size in cells. */
    /**
     * The Control Center is open on the second screen (switcher + Control Center): its grid – tiles, rows,
     * columns – is kept apart from the main screen's, as the screens differ in shape. Starts as a copy.
     */
    @Volatile var ccOther = false
    private val gridSuffix get() = if (ccOther) "_2" else ""

    var ccGrid: List<GridEntry>
        get() = GridLayout.parse(p.getString("ccGrid$gridSuffix", null) ?: p.getString("ccGrid", null)) ?: GridLayout.defaults
        set(v) = p.edit().putString("ccGrid$gridSuffix", GridLayout.format(v)).apply()

    /** Back to the default Control Center (layout, tiles, sizes, colours). */
    fun resetControlCenter() = p.edit()
        .remove("ccGrid").remove("ccLayout").remove("ccTiles").remove("ccSizes").remove("tileColor").remove("tileShape").remove("gaugePalette")
        .apply()

    var overlayOn: Boolean
        get() = p.getBoolean("overlayOn", false)
        set(v) = p.edit().putBoolean("overlayOn", v).apply()
    var overlayItems: List<String>
        get() = p.getString("overlayItems", null)?.split(',')?.filter { OverlayItem.of(it) != null } ?: OverlayItem.defaults
        set(v) = p.edit().putString("overlayItems", v.joinToString(",")).apply()
    var overlayPos: OverlayPos
        get() = enumOr(p.getString("overlayPos", null), OverlayPos.TOP_LEFT)
        set(v) = p.edit().putString("overlayPos", v.name).apply()
    /** true: values side by side in one line, false: below each other. */
    /** Fan speed (%) at 40, 55, 70 and 85 °C for Hugminn's own fan curve. */
    var fanCurve: List<Int>
        get() = p.getString("fanCurve", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 } ?: listOf(25, 35, 60, 90)
        set(v) = p.edit().putString("fanCurve", v.joinToString(",")).apply()

    /** Hugminn's fan curve is in charge (custom fan mode). */
    var fanCustom: Boolean
        get() = p.getBoolean("fanCustom", false)
        set(v) = p.edit().putBoolean("fanCustom", v).apply()

    /** Size of the performance overlay (1 = normal). */
    var overlayScale: Float
        get() = p.getFloat("overlayScale", 1f)
        set(v) = p.edit().putFloat("overlayScale", v).apply()

    var overlayRow: Boolean
        get() = p.getBoolean("overlayRow", true)
        set(v) = p.edit().putBoolean("overlayRow", v).apply()

    /** Gauges as bars (true) or rings (false). */
    var gaugeBars: Boolean
        get() = p.getBoolean("gaugeBars", true)
        set(v) = p.edit().putBoolean("gaugeBars", v).apply()

    /** Volume as Hugminn shows it (0-100 % in 5 % steps), -1 = not set. */
    /** Duo: the way that brought up Retroid's control center last time. */
    var retroidWay: String
        get() = p.getString("retroidWay", "service") ?: "service"
        set(v) = p.edit().putString("retroidWay", v).apply()

    /** Duo: last brightness of the lower screen in %. */
    var bottomBrightness: Int
        get() = p.getInt("bottomBrightness", 50)
        set(v) = p.edit().putInt("bottomBrightness", v).apply()

    var volumePct: Int
        get() = p.getInt("volumePct", -1)
        set(v) = p.edit().putInt("volumePct", v).apply()

    /** Package of the app that sets the stick LED colours (empty: Hugminn sets the colours itself). */
    var ledApp: String
        get() = p.getString("ledApp", "") ?: ""
        set(v) = p.edit().putString("ledApp", v).apply()

    /** Hugminn's own switcher instead of an overlay on Android's recents view. */
    /** Pause the app in front (cover it) while the own game switcher is open. */
    /** The introduction was shown (or skipped). */
    /** The Home/Guide button of a Bluetooth or USB controller works like the handheld's Home button. */
    /** Capture: index into Capture.qualities, Capture.Audio, red dot while recording, ring buffer. */
    var recQuality: Int
        get() = p.getInt("recQuality", 0)
        set(v) = p.edit().putInt("recQuality", v).apply()
    var recAudio: Int
        get() = p.getInt("recAudio", 0)
        set(v) = p.edit().putInt("recAudio", v).apply()
    /** While recording: 0 = stick LEDs pulse red, 1 = red dot on screen, 2 = both, 3 = nothing. */
    var recShow: Int
        get() = p.getInt("recShow", 0)
        set(v) = p.edit().putInt("recShow", v).apply()
    /** Videos stop by themselves after this many seconds (0 = no limit). */
    var recLimit: Int
        get() = p.getInt("recLimit", 0)
        set(v) = p.edit().putInt("recLimit", v).apply()
    /** Screen off during a video: stop and save it. */
    var recStopOnSleep: Boolean
        get() = p.getBoolean("recStopOnSleep", true)
        set(v) = p.edit().putBoolean("recStopOnSleep", v).apply()
    var replayOn: Boolean
        get() = p.getBoolean("replayOn", false)
        set(v) = p.edit().putBoolean("replayOn", v).apply()
    var replaySec: Int
        get() = p.getInt("replaySec", 30)
        set(v) = p.edit().putInt("replaySec", v).apply()

    var padHome: Boolean
        get() = p.getBoolean("padHome", true)
        set(v) = p.edit().putBoolean("padHome", v).apply()

    /** New installs: permissions only as the person allows them in the setup (no silent granting). */
    /** Allowed in the setup: recording starts without Android asking each time (root). */
    var recNoAsk: Boolean
        get() = p.getBoolean("recNoAsk", false)
        set(v) = p.edit().putBoolean("recNoAsk", v).apply()

    var askPermissions: Boolean
        get() = p.getBoolean("askPermissions", false)
        set(v) = p.edit().putBoolean("askPermissions", v).apply()

    var setupDone: Boolean
        get() = p.getBoolean("setupDone", false)
        set(v) = p.edit().putBoolean("setupDone", v).apply()

    /** Control Center grid: rows (2–6) and columns (0 = as many as fit). */
    var gridRows: Int
        get() = p.getInt("gridRows$gridSuffix", if (ccOther) 5 else p.getInt("gridRows", if (fourThree) 5 else 4))
        set(v) = p.edit().putInt("gridRows$gridSuffix", v).apply()
    var gridCols: Int
        get() = p.getInt("gridCols$gridSuffix", if (ccOther) 0 else p.getInt("gridCols", 0))
        set(v) = p.edit().putInt("gridCols$gridSuffix", v).apply()

    /** Pause the app in front while the Control Center is open. */
    var pauseInCc: Boolean
        get() = p.getBoolean("pauseInCc", true)
        set(v) = p.edit().putBoolean("pauseInCc", v).apply()

    var pauseInSwitcher: Boolean
        get() = p.getBoolean("pauseInSwitcher", true)
        set(v) = p.edit().putBoolean("pauseInSwitcher", v).apply()

    var ownSwitcher: Boolean
        get() = p.getBoolean("ownSwitcher", true)
        set(v) = p.edit().putBoolean("ownSwitcher", v).apply()

    var ledSpeed: Float
        get() = p.getFloat("ledSpeed", 1f)
        set(v) = p.edit().putFloat("ledSpeed", v).apply()

    var ledEffect: LedEffect
        get() = enumOr(p.getString("ledEffect", null), LedEffect.NONE)
        set(v) = p.edit().putString("ledEffect", v.name).apply()

    /** Odin 2: effect of the side lights (the sticks use [ledEffect]). */
    var ledEffectSide: LedEffect
        get() = enumOr(p.getString("ledEffectSide", null), LedEffect.NONE)
        set(v) = p.edit().putString("ledEffectSide", v.name).apply()

    /** Odin 2: own colours of the sticks (0 = AYN's colour). */
    var aynStickLeft: Int
        get() = p.getInt("aynStickLeft", 0)
        set(v) = p.edit().putInt("aynStickLeft", v).apply()
    var aynStickRight: Int
        get() = p.getInt("aynStickRight", 0)
        set(v) = p.edit().putInt("aynStickRight", v).apply()

    /**
     * LED elements (Odin 2: left stick, right stick, left side, right side; Retroid: the two sticks):
     * state per element as one digit – 0 off, 1 on (keeps its look), 2 on and chosen for changes.
     */
    var ledElState: String
        get() = p.getString("ledElState", "") ?: ""
        set(v) = p.edit().putString("ledElState", v).apply()
    /** On the home screen: performance Normal and fan Quiet (AppProfiles). */
    /** Launcher chosen by the person ("" = Android's home app). */
    var launcherPkg: String
        get() = p.getString("launcherPkg", "") ?: ""
        set(v) { p.edit().putString("launcherPkg", v).apply(); Apps.chosenHome = v.ifBlank { null } }
    var launcherPerf: String
        get() = p.getString("launcherPerf", "0") ?: "0"
        set(v) = p.edit().putString("launcherPerf", v).apply()
    var launcherFan: String
        get() = p.getString("launcherFan", "1") ?: "1"
        set(v) = p.edit().putString("launcherFan", v).apply()

    /** Anbernic: Hugminn's own colour for the stick lights (0 = Anbernic's) and their brightness. */
    var anbLedColor: Int
        get() = p.getInt("anbLedColor", 0)
        set(v) = p.edit().putInt("anbLedColor", v).apply()
    /** Anbernic: after a restart Hugminn starts its helper itself through Anbernic's script management. */
    var anbAutostart: Boolean
        get() = p.getBoolean("anbAutostart", true)
        set(v) = p.edit().putBoolean("anbAutostart", v).apply()

    var anbLedLevel: Int
        get() = p.getInt("anbLedLevel", 50).let { if (it > 100) 50 else it }
        set(v) = p.edit().putInt("anbLedLevel", v).apply()

    var launcherProfile: Boolean
        get() = p.getBoolean("launcherProfile", true)
        set(v) = p.edit().putBoolean("launcherProfile", v).apply()

    /** Mode per element as one digit: 0 off, 1 colour, 2 combination, 3 effect. */
    var ledElMode: String
        get() = p.getString("ledElMode", "") ?: ""
        set(v) = p.edit().putString("ledElMode", v).apply()
    /** The chosen colour combination (index into Device.ledCombos). */
    var ledComboSel: Int
        get() = p.getInt("ledComboSel", 0)
        set(v) = p.edit().putInt("ledComboSel", v).apply()
    fun ledElColor(i: Int) = p.getInt("ledElC$i", 0)
    fun setLedElColor(i: Int, c: Int) = p.edit().putInt("ledElC$i", c).apply()
    fun ledElB(i: Int) = p.getInt("ledElB$i", -1)
    fun setLedElB(i: Int, v: Int) = p.edit().putInt("ledElB$i", v).apply()
    fun ledElFx(i: Int): LedEffect? = p.getString("ledElFx$i", null)?.let { enumOr(it, LedEffect.NONE) }
    fun setLedElFx(i: Int, e: LedEffect) = p.edit().putString("ledElFx$i", e.name).apply()

    var gaugePalette: GaugePalette
        get() = enumOr(p.getString("gaugePalette", null), GaugePalette.TRAFFIC)
        set(v) = p.edit().putString("gaugePalette", v.name).apply()

    var tileShape: TileShape
        get() = enumOr(p.getString("tileShape", null), TileShape.ROUND)
        set(v) = p.edit().putString("tileShape", v.name).apply()

    var theme: Theme
        get() = enumOr(p.getString("theme", null), Theme.SYSTEM)
        set(v) = p.edit().putString("theme", v.name).apply()

    var glyphStyle: GlyphStyle
        get() = enumOr(p.getString("glyphs", null), GlyphStyle.AUTO)
        set(v) = p.edit().putString("glyphs", v.name).apply()
    var glyphColored: Boolean
        get() = p.getBoolean("glyphColored", true)
        set(v) = p.edit().putBoolean("glyphColored", v).apply()


    /** Max gap between two presses for a double press, in ms. */
    var doubleMs: Int
        get() = p.getInt("doubleMs", 300)
        set(v) = p.edit().putInt("doubleMs", v).apply()
    var longMs: Int
        get() = p.getInt("longMs", 450)
        set(v) = p.edit().putInt("longMs", v).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def
}

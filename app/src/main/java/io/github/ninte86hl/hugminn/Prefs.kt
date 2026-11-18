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
    LAST_APP("Letzte App");

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

enum class TileShape(private val labelDe: String) {
    ROUND("Rund"),
    SQUARE("Abgerundetes Quadrat");

    val label get() = tr(labelDe)
}

enum class GlyphStyle(private val labelDe: String) {
    AUTO("Automatisch"),
    XBOX("Xbox"),
    NINTENDO("Nintendo"),
    PLAYSTATION("PlayStation");

    val label get() = tr(labelDe)
}

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE)

    var single: Action
        get() = enumOr(p.getString("single", null), Action.HOME)
        set(v) = p.edit().putString("single", v.name).apply()
    var double: Action
        get() = enumOr(p.getString("double", null), Action.SWITCHER)
        set(v) = p.edit().putString("double", v.name).apply()
    var long: Action
        get() = enumOr(p.getString("long", null), Action.CONTROL_CENTER)
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
    fun forgetOld() = p.edit().remove("githubToken").remove("pixelNf").apply()

    /** Performance/fan profile per app: "pkg=perf|fan;…" (empty part = not set). */
    var appProfiles: String?
        get() = p.getString("appProfiles", null)
        set(v) = p.edit().putString("appProfiles", v).apply()

    var language: Language
        get() = enumOr(p.getString("language", null), Language.AUTO)
        set(v) = p.edit().putString("language", v.name).apply()

    var uiStyle: UiStyle
        get() = enumOr(p.getString("uiStyle", null), UiStyle.MODERN)
        set(v) = p.edit().putString("uiStyle", v.name).apply()

    /** Colour of active tiles (0 = Material You accent). */
    var tileColor: Int
        get() = p.getInt("tileColor", 0)
        set(v) = p.edit().putInt("tileColor", v).apply()

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
    var ccGrid: List<GridEntry>
        get() = GridLayout.parse(p.getString("ccGrid", null)) ?: GridLayout.defaults
        set(v) = p.edit().putString("ccGrid", GridLayout.format(v)).apply()

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
    var volumePct: Int
        get() = p.getInt("volumePct", -1)
        set(v) = p.edit().putInt("volumePct", v).apply()

    /** Package of the app that sets the stick LED colours (empty: Hugminn sets the colours itself). */
    var ledApp: String
        get() = p.getString("ledApp", "") ?: ""
        set(v) = p.edit().putString("ledApp", v).apply()

    /** Hugminn's own switcher instead of an overlay on Android's recents view. */
    /** Pause the app in front (cover it) while the own game switcher is open. */
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

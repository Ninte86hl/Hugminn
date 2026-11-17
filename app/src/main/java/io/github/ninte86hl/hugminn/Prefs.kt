package io.github.ninte86hl.hugminn

import android.content.Context
import android.content.SharedPreferences

/** What a Home gesture does. */
enum class Action(val label: String) {
    NONE("Nichts"),
    HOME("Startbildschirm"),
    SWITCHER("Game Switcher"),
    CONTROL_CENTER("Control Center"),
    RECENTS("Android-Übersicht"),
    LAST_APP("Letzte App"),
}

/** What the switcher frame shows where the status bar is. */
enum class StatusBar(val label: String) {
    OVERLAY("Eigene Anzeige"),
    HIDDEN("Ausblenden"),
    SYSTEM("Android-Statusleiste"),
}

enum class Theme(val label: String) {
    SYSTEM("System"),
    LIGHT("Hell"),
    DARK("Dunkel"),
    OLED("OLED-Schwarz"),
}

enum class GlyphStyle(val label: String) {
    AUTO("Automatisch"),
    XBOX("Xbox"),
    NINTENDO("Nintendo"),
    PLAYSTATION("PlayStation"),
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
    var ccHideStatus: Boolean
        get() = p.getBoolean("ccHideStatus", true)
        set(v) = p.edit().putBoolean("ccHideStatus", v).apply()

    /** Last measured switcher geometry ("screenW,screenH|card l,t,r,b|snap l,t,r,b|pitch"). */
    var switcherGeometry: String?
        get() = p.getString("switcherGeometry", null)
        set(v) = p.edit().putString("switcherGeometry", v).apply()

    var theme: Theme
        get() = enumOr(p.getString("theme", null), Theme.SYSTEM)
        set(v) = p.edit().putString("theme", v.name).apply()

    var glyphStyle: GlyphStyle
        get() = enumOr(p.getString("glyphs", null), GlyphStyle.AUTO)
        set(v) = p.edit().putString("glyphs", v.name).apply()
    var glyphColored: Boolean
        get() = p.getBoolean("glyphColored", true)
        set(v) = p.edit().putBoolean("glyphColored", v).apply()

    /** GitHub token for uploading feedback screenshots (needs write access to the repository). */
    var githubToken: String?
        get() = p.getString("githubToken", null)
        set(v) = p.edit().putString("githubToken", v).apply()

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

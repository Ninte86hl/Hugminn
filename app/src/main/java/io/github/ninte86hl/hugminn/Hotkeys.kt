package io.github.ninte86hl.hugminn

import android.content.Context
import android.view.KeyEvent

/**
 * Emulator shortcuts shown in the game switcher (Y → Shortcuts). Hugminn doesn't change any emulator;
 * it shows the shortcuts as set up per emulator in Hugminn's settings: the common layout (Select as hotkey,
 * as on EmuDeck, ROCKNIX, Batocera or RetroPie), own ones, or none.
 */
object Hotkeys {
    enum class Act(private val de: String, private val en: String) {
        MENU("Emulator-Menü", "Emulator menu"),
        EXIT("Spiel beenden", "Quit game"),
        SAVE("Spielstand speichern", "Save state"),
        LOAD("Spielstand laden", "Load state"),
        SLOT("Speicherplatz wechseln", "Change save slot"),
        FAST("Vorspulen", "Fast forward"),
        REWIND("Zurückspulen", "Rewind"),
        PAUSE("Pause", "Pause");
        val label get() = t(de, en)
    }

    private const val SEL = KeyEvent.KEYCODE_BUTTON_SELECT
    private const val ST = KeyEvent.KEYCODE_BUTTON_START

    /** The combinations one can choose; index 0 = not set. */
    val combos: List<IntArray> = listOf(
        intArrayOf(),
        intArrayOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR),
        intArrayOf(SEL, ST),
        intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_R1), intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_L1),
        intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_R2), intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_L2),
        intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_A), intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_B),
        intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_X), intArrayOf(SEL, KeyEvent.KEYCODE_BUTTON_Y),
        intArrayOf(SEL, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT),
        intArrayOf(ST, KeyEvent.KEYCODE_BUTTON_R1), intArrayOf(ST, KeyEvent.KEYCODE_BUTTON_L1),
        intArrayOf(ST, KeyEvent.KEYCODE_BUTTON_R2), intArrayOf(ST, KeyEvent.KEYCODE_BUTTON_L2),
        intArrayOf(KeyEvent.KEYCODE_BUTTON_MODE),
    )

    /** The common layout (EmuDeck / ROCKNIX style). */
    private val common = mapOf(
        Act.MENU to 1, Act.EXIT to 2, Act.SAVE to 3, Act.LOAD to 4,
        Act.SLOT to 11, Act.FAST to 5, Act.REWIND to 6, Act.PAUSE to 7,
    )

    enum class Mode(private val de: String, private val en: String) {
        COMMON("Gängige Belegung", "Common layout"), OWN("Eigene", "Own"), OFF("Aus", "Off");
        val label get() = t(de, en)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hotkeys", Context.MODE_PRIVATE)

    fun mode(ctx: Context, pkg: String): Mode =
        Mode.values().getOrElse(prefs(ctx).getInt("mode_$pkg", 0)) { Mode.COMMON }

    fun setMode(ctx: Context, pkg: String, m: Mode) {
        // switching to own shortcuts starts from the common layout
        if (m == Mode.OWN && !prefs(ctx).contains("own_$pkg")) saveOwn(ctx, pkg, common)
        prefs(ctx).edit().putInt("mode_$pkg", m.ordinal).apply()
    }

    private fun own(ctx: Context, pkg: String): Map<Act, Int> =
        prefs(ctx).getString("own_$pkg", null)?.split(',')?.mapNotNull { e ->
            val (a, c) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            (Act.values().firstOrNull { it.name == a } ?: return@mapNotNull null) to (c.toIntOrNull() ?: 0)
        }?.toMap() ?: common

    private fun saveOwn(ctx: Context, pkg: String, m: Map<Act, Int>) =
        prefs(ctx).edit().putString("own_$pkg", m.entries.joinToString(",") { "${it.key.name}=${it.value}" }).apply()

    /** The shortcut set for one action in own mode (cycles through [combos]). */
    fun cycle(ctx: Context, pkg: String, act: Act, dir: Int) {
        val m = own(ctx, pkg).toMutableMap()
        m[act] = ((m[act] ?: 0) + dir + combos.size) % combos.size
        saveOwn(ctx, pkg, m)
    }

    fun comboOf(ctx: Context, pkg: String, act: Act): IntArray = when (mode(ctx, pkg)) {
        Mode.COMMON -> combos[common[act] ?: 0]
        Mode.OWN -> combos.getOrElse(own(ctx, pkg)[act] ?: 0) { combos[0] }
        Mode.OFF -> combos[0]
    }

    /** What the switcher shows for [pkg]: action to keys, only the ones that are set. */
    fun list(ctx: Context, pkg: String): List<Pair<Act, IntArray>> =
        if (mode(ctx, pkg) == Mode.OFF) emptyList()
        else Act.values().map { it to comboOf(ctx, pkg, it) }.filter { it.second.isNotEmpty() }

    /** Text form, e.g. "Select + R1", for settings and reports. */
    fun text(keys: IntArray): String = if (keys.isEmpty()) t("Nicht belegt", "Not set") else keys.joinToString(" + ") { name(it) }

    fun name(k: Int) = when (k) {
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"; KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"; KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"; KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_A -> "A"; KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"; KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        SEL -> "Select"; ST -> "Start"; KeyEvent.KEYCODE_BUTTON_MODE -> "Home"
        KeyEvent.KEYCODE_DPAD_LEFT -> "◀"; KeyEvent.KEYCODE_DPAD_RIGHT -> "▶"
        else -> "?"
    }

    // ---- apps kept open ----

    fun pinned(ctx: Context): Set<String> = prefs(ctx).getStringSet("pinned", emptySet()) ?: emptySet()
    fun isPinned(ctx: Context, pkg: String) = pkg in pinned(ctx)
    fun setPinned(ctx: Context, pkg: String, on: Boolean) =
        prefs(ctx).edit().putStringSet("pinned", pinned(ctx).toMutableSet().apply { if (on) add(pkg) else remove(pkg) }).apply()
}

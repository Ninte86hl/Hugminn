package io.github.ninte86hl.hugminn

/** All quick tiles the Control Center can show (id → name), in display order. */
object TileCatalog {
    val all = linkedMapOf(
        "wifi" to "WLAN",
        "bt" to "Bluetooth",
        "perf" to "Leistung",
        "fan" to "Lüfter",
        "refresh" to "Bildrate",
        "leds" to "Stick-LEDs",
        "dnd" to "Nicht stören",
        "airplane" to "Flugmodus",
        "rotation" to "Drehung sperren",
        "landscape" to "Querformat erzwingen",
        "vibration" to "Vibration",
        "controller" to "Tastenlayout",
        "trigger" to "Trigger-Modus",
        "keymapper" to "Tastenzuordnung",
        "mouse" to "Mausmodus",
        "homelock" to "Home-Sperre",
        "chargesep" to "Direkte Stromversorgung",
        "theme" to "Farbschema",
        "screen" to "Bildschirm an",
        "clean" to "Speicher leeren",
        "screenshot" to "Screenshot",
        "rpsettings" to "Retroid-Einstellungen",
        "settings" to "Android-Einstellungen",
        "hugminn" to "Hugminn",
    )
    val defaults = listOf("wifi", "bt", "refresh", "leds", "dnd", "rotation", "clean", "screenshot")
}

/** Building blocks of the Control Center; "small" ones sit side by side in a row. */
enum class CcBlock(val id: String, val label: String, val small: Boolean) {
    TILES("tiles", "Kacheln", false),
    BRIGHTNESS("brightness", "Helligkeit", false),
    VOLUME("volume", "Lautstärke", false),
    FPS("fps", "Bildrate", false),
    TEMP("temp", "Temperatur", true),
    PERF("perf", "Leistungsprofil", true),
    FAN("fan", "Lüfter", true),
    BATTERY("battery", "Akku", true),
    CPU("cpu", "CPU-Anzeige", true),
    GPU("gpu", "GPU-Anzeige", true),
    POWER("power", "Leistungsaufnahme", true),
    RAM("ram", "RAM-Anzeige", true);

    /** Gauges (CPU, GPU, RAM, power) and info cards are not mixed in one row. */
    val gauge get() = id in setOf("cpu", "gpu", "ram", "power")

    companion object {
        val defaultLeft = listOf("fps", "temp", "perf", "fan", "cpu", "gpu", "ram", "power")
        val defaultRight = listOf("tiles", "brightness", "volume")
        fun of(id: String) = values().firstOrNull { it.id == id }
    }
}

/** Colours offered for active tiles (0 = accent colour of the system). */
val TILE_COLORS = linkedMapOf(
    0 to "Systemfarbe (Material You)",
    0xFFE53935.toInt() to "Rot",
    0xFFFB8C00.toInt() to "Orange",
    0xFFFDD835.toInt() to "Gelb",
    0xFF43A047.toInt() to "Grün",
    0xFF00ACC1.toInt() to "Türkis",
    0xFF1E88E5.toInt() to "Blau",
    0xFF8E24AA.toInt() to "Lila",
    0xFFD81B60.toInt() to "Pink",
    0xFFEEEEEE.toInt() to "Weiß",
)

package io.github.ninte86hl.hugminn

/** All quick tiles the Control Center can show (id → name), in display order. */
object TileCatalog {
    val all get() = linkedMapOf(
        "wifi" to tr("WLAN"),
        "bt" to "Bluetooth",
        "perf" to tr("Leistung"),
        "fan" to tr("Lüfter"),
        "refresh" to tr("Bildrate"),
        "leds" to tr("Stick-LEDs"),
        "dnd" to tr("Nicht stören"),
        "airplane" to tr("Flugmodus"),
        "rotation" to tr("Drehung sperren"),
        "landscape" to tr("Querformat erzwingen"),
        "vibration" to "Vibration",
        "controller" to tr("Tastenlayout"),
        "trigger" to tr("Trigger-Modus"),
        "keymapper" to tr("Tastenzuordnung"),
        "mouse" to tr("Mausmodus"),
        "homelock" to tr("Home-Sperre"),
        "chargesep" to tr("Direkte Stromversorgung"),
        "chargelimit" to t("Laden bis 80 %", "Charge to 80 %"),
        "focuslock" to t("Bildschirm-Fokus", "Screen focus"),
        "bottomscreen" to t("Unterer Bildschirm", "Lower screen"),
        "external" to t("Externer Bildschirm", "External display"),
        "equalizer" to "Equalizer",
        "theme" to tr("Farbschema"),
        "screen" to tr("Bildschirm an"),
        "clean" to tr("Speicher leeren"),
        "screenshot" to t("Aufnahme", "Capture"),
        "sleep" to t("Bildschirm aus", "Screen off"),
        "devbat" to t("Geräte-Akku", "Device battery"),
        "media" to t("Musik", "Music"),
        "rpsettings" to tr("Retroid-Einstellungen"),
        "settings" to tr("Android-Einstellungen"),
        "hugminn" to "Hugminn",
    )
    val defaults = listOf("wifi", "bt", "refresh", "leds", "dnd", "rotation", "clean", "screenshot")

    /** Groups for the "add" picker, so no list gets too long (name → tile ids, in order). */
    val groups get() = listOf(
        t("Verbindung", "Connections") to listOf("wifi", "bt", "airplane", "dnd", "devbat"),
        t("Bildschirm", "Display") to listOf("refresh", "rotation", "landscape", "screen", "sleep", "theme", "focuslock", "bottomscreen", "external"),
        t("Leistung", "Power") to listOf("perf", "fan", "chargesep", "chargelimit", "clean"),
        t("Steuerung", "Controls") to listOf("controller", "trigger", "keymapper", "mouse", "homelock", "vibration", "leds"),
        t("Medien & Apps", "Media & apps") to listOf("media", "equalizer", "screenshot", "rpsettings", "settings", "hugminn"),
    )
}

/** Building blocks of the Control Center; "small" ones sit side by side in a row. */
enum class CcBlock(val id: String, private val labelDe: String, val small: Boolean) {
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

    val label get() = tr(labelDe)


    /** Gauges (CPU, GPU, RAM, power) and info cards are not mixed in one row. */
    val gauge get() = id in setOf("cpu", "gpu", "ram", "power")

    companion object {
        val defaultLeft = listOf("fps", "temp", "perf", "fan", "cpu", "gpu", "ram", "power")
        val defaultRight = listOf("tiles", "brightness", "volume")
        fun of(id: String) = values().firstOrNull { it.id == id }
    }
}

/** Colours offered for active tiles (0 = accent colour of the system). */
val TILE_COLORS get() = linkedMapOf(
    0 to tr("Systemfarbe (Material You)"),
    0xFFE53935.toInt() to tr("Rot"),
    0xFFFB8C00.toInt() to "Orange",
    0xFFFDD835.toInt() to tr("Gelb"),
    0xFF43A047.toInt() to tr("Grün"),
    0xFF00ACC1.toInt() to tr("Türkis"),
    0xFF1E88E5.toInt() to tr("Blau"),
    0xFF8E24AA.toInt() to tr("Lila"),
    0xFFD81B60.toInt() to "Pink",
    0xFFEEEEEE.toInt() to tr("Weiß"),
)

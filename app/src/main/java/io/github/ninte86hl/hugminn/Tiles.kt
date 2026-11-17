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
        "screen" to "Bildschirm an",
        "clean" to "Speicher leeren",
        "screenshot" to "Screenshot",
        "rpsettings" to "Retroid-Einstellungen",
        "settings" to "Android-Einstellungen",
        "hugminn" to "Hugminn",
    )
    val defaults = listOf("wifi", "bt", "perf", "fan", "refresh", "leds", "dnd", "screenshot")
}

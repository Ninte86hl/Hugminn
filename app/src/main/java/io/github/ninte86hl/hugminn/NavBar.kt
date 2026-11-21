package io.github.ninte86hl.hugminn

import android.content.Context

/**
 * Hides Android's navigation bar (the gesture line at the bottom) everywhere – swipe up from the
 * edge to bring it back for a moment. Uses Android's "policy_control" setting, which AYN's firmware
 * still honours (its launcher uses it). What the firmware had there before is kept and comes back
 * when this is switched off. Needs root.
 */
object NavBar {
    @Volatile var diag = ""
    private const val OURS = "immersive.navigation=*"

    private fun read(): String? = Root.exec("settings get global policy_control")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun write(v: String?) {
        if (v.isNullOrEmpty()) Root.exec("settings delete global policy_control")
        else Root.exec("settings put global policy_control '$v'")
    }

    /** Switches it on or off (in the background). */
    fun set(ctx: Context, on: Boolean) {
        val prefs = Prefs(ctx)
        prefs.hideNav = on
        Thread { runCatching { apply(prefs) }.onFailure { diag = "Fehler: ${it.javaClass.simpleName}" } }.start()
    }

    /** Hugminn started: put the setting in place again if the firmware took it away. */
    fun resume(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.hideNav) return
        Thread { runCatching { apply(prefs) } }.start()
    }

    private fun apply(prefs: Prefs) {
        if (!Root.available()) { diag = "kein Root"; return }
        val cur = read()
        val others = cur?.split(':')?.filter { it.isNotBlank() && !it.startsWith("immersive.navigation=") }.orEmpty()
        val next = if (prefs.hideNav) (others + OURS).joinToString(":") else others.joinToString(":").ifEmpty { null }
        if (next != cur) write(next)
        diag = "policy_control: ${cur ?: "–"} → ${read() ?: "–"}"
    }
}

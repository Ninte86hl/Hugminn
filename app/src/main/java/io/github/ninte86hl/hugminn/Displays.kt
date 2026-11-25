package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * The screens of the handheld. Dual-screen devices (AYN Thor, Retroid Pocket Dual) have a second
 * built-in display; this collects what Hugminn needs to know about it before using it.
 */
object Displays {
    data class Info(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val density: Int,
        val rates: List<Int>,
        val state: Int,
        val flags: Int,
    ) {
        val isDefault get() = id == Display.DEFAULT_DISPLAY
        /** Private and virtual displays (screen recording, casting) are not a second screen. */
        val isPrivate get() = (flags and Display.FLAG_PRIVATE) != 0
        val stateName get() = when (state) {
            Display.STATE_ON -> "an"; Display.STATE_OFF -> "aus"; Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "doze"
            else -> "?$state"
        }
    }

    fun list(ctx: Context): List<Info> {
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return dm.displays.map { d ->
            @Suppress("DEPRECATION") val size = android.graphics.Point().also { d.getRealSize(it) }
            val dens = runCatching {
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") d.getRealMetrics(m)
                m.densityDpi
            }.getOrDefault(0)
            Info(
                d.displayId, d.name ?: "?", size.x, size.y, dens,
                runCatching { d.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted() }.getOrDefault(emptyList()),
                d.state, d.flags,
            )
        }
    }

    /** A built-in second screen, if there is one (not the main one, not a private/virtual one). */
    fun second(ctx: Context): Info? = list(ctx).firstOrNull { !it.isDefault && !it.isPrivate }

    /**
     * Hugminn's windows are laid out for the main screen. On the other screen (smaller, other shape) the
     * content keeps that layout and is scaled to fit, centred; the edges get [ground].
     * Returns the view to add to the window manager.
     */
    fun fitted(ctx: Context, content: android.view.View, mainW: Int, mainH: Int, wm: android.view.WindowManager, ground: Int): android.view.View {
        @Suppress("DEPRECATION") val size = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
        val b = android.graphics.Rect(0, 0, size.x, size.y)
        // the layout keeps the main screen's size on one side and takes the other screen's shape on the
        // other (4:3 below a 16:9 top: taller), so it fills the screen without black bars
        val s = if (b.height() * mainW >= b.width() * mainH) b.width() / mainW.toFloat() else b.height() / mainH.toFloat()
        val w = (b.width() / s).toInt()
        val h = (b.height() / s).toInt()
        return android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(ground)
            addView(content, android.widget.FrameLayout.LayoutParams(w, h))
            content.pivotX = 0f; content.pivotY = 0f
            content.scaleX = s; content.scaleY = s
        }
    }

    /** Window manager for overlays on a screen (the accessibility service's display context may add them there). */
    fun wmFor(ctx: Context, id: Int): android.view.WindowManager {
        if (id != android.view.Display.DEFAULT_DISPLAY) runCatching {
            ctx.getSystemService(DisplayManager::class.java)?.getDisplay(id)?.let { return ctx.createDisplayContext(it).getSystemService(android.view.WindowManager::class.java) }
        }
        return ctx.getSystemService(android.view.WindowManager::class.java)
    }

    @Volatile private var dual: Boolean? = null

    /**
     * Start options for Hugminn's own screens and apps opened from it: always on the top screen.
     * Android starts new screens on the screen that has the focus – after a touch on the Thor's lower
     * screen that would be the lower one. Null on single-screen handhelds (nothing changes there).
     */
    fun onTop(ctx: Context): android.os.Bundle? {
        val d = dual ?: (second(ctx) != null).also { dual = it }
        if (!d) return null
        return runCatching { android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle() }.getOrNull()
    }

    /** For feedback logs: every display, and with the service also the windows on each of them. */
    fun describe(ctx: Context, svc: AccessibilityService? = null): String = buildString {
        val all = list(ctx)
        appendLine("Bildschirme (${all.size}):")
        all.forEach { i ->
            appendLine("  #${i.id} \"${i.name}\" ${i.width}x${i.height} · ${i.density} dpi · ${i.rates.joinToString("/")} Hz · ${i.stateName}" +
                " · flags=0x${Integer.toHexString(i.flags)}${if (i.isDefault) " · Haupt" else ""}${if (i.isPrivate) " · privat" else ""}")
        }
        if (svc != null && android.os.Build.VERSION.SDK_INT >= 30) runCatching {
            val per = svc.windowsOnAllDisplays
            for (k in 0 until per.size()) {
                val id = per.keyAt(k)
                appendLine("  Fenster auf #$id:")
                per.valueAt(k).forEach { w ->
                    val b = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                    val pkg = runCatching { w.root?.packageName }.getOrNull()
                    appendLine("    type=${w.type} layer=${w.layer} aktiv=${w.isActive} ${pkg ?: "-"} \"${w.title ?: ""}\" ${b.toShortString()}")
                }
            }
        }.onFailure { appendLine("  Fenster: ${it.javaClass.simpleName}") }
        if (NavBar.diag.isNotEmpty()) appendLine("  Navigationsleiste: " + NavBar.diag)
        if (Landscape.diag.isNotEmpty()) appendLine("  Querformat: " + Landscape.diag)
        if (DualMedia.diag.isNotEmpty()) appendLine("  Player unten: " + DualMedia.diag)
        if (ledInfo.isNotEmpty()) { appendLine("  LED-Treiber:"); ledInfo.lines().forEach { appendLine("    $it") } }
        if (ledProbe.isNotEmpty()) { appendLine("  LED-Tester:"); ledProbe.trim().lines().forEach { appendLine("    $it") } }
        // the root dumps take seconds: never on the main thread (keys pass it – Hugminn froze and
        // Android stopped it); the picture taken in the background before opening has them
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) appendLine("  (Fensterliste: siehe „Vor dem Öffnen“)")
        else if (Root.available()) {
            // what really runs where: tasks per display and the visible windows (also presentations,
            // which the accessibility window list leaves out), plus the focus
            Root.execLong("dumpsys activity activities | grep -E 'Display #|\\* Task\\{|topResumedActivity|ResumedActivity:' | head -30", 4_000)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine("  Tasks je Bildschirm:")
                it.lines().forEach { l -> appendLine("    " + l.trim().take(200)) }
            }
            Root.execLong("dumpsys window windows | grep -E 'Window #|mDisplayId=|mCurrentFocus|mFocusedApp|isVisible=|mHasSurface' | grep -vE 'mHasSurface=false|isVisible=false' | head -90", 5_000)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine("  Fenster je Bildschirm (dumpsys):")
                it.lines().forEach { l -> appendLine("    " + l.trim().take(200)) }
            }
        }
    }

    /**
     * AYN: which light drivers exist (the Odin 2's sticks are not on the sn3112 driver – that one
     * lights the sides). Read once in the background at the start, for feedback reports.
     */
    @Volatile var ledInfo = ""
        private set

    /** Answers of the LED tester in the device test (Odin 2). */
    @Volatile var ledProbe = ""

    fun collectLeds() {
        if (!Device.isAyn) return
        Thread {
            runCatching {
                ledInfo = Root.execLong(
                    "ls /sys/class/leds/ 2>&1 | tr '\\n' ' '; echo; " +
                        "ls -d /sys/class/*led* /sys/class/sn31* /sys/class/aw* 2>/dev/null | tr '\\n' ' '; echo; " +
                        "for d in /sys/class/sn3112*/led /sys/class/leds/*; do echo \"\$d: \$(ls \$d 2>/dev/null | tr '\\n' ' ')\"; done 2>/dev/null | head -40; " +
                        "getprop | grep -iE 'led|light|joystick' | head -20",
                    6_000,
                )?.trim().orEmpty()
            }
        }.start()
    }

    /** What was on the screens right before Hugminn opened (only on handhelds with a second screen). */
    @Volatile var beforeOpen: String = ""
        private set

    /** Takes that picture in the background, so opening is not slowed down. */
    fun snapshot(ctx: Context, svc: AccessibilityService, where: String) {
        if (second(ctx) == null) return
        Thread {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            beforeOpen = "Vor dem Öffnen ($where, $t):\n" + runCatching { describe(ctx, svc) }.getOrDefault("")
        }.start()
    }
}

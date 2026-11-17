package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hugminn's core: takes over the handheld's Home key (it arrives here as KEYCODE_MOVE_HOME from the
 * built-in controller) and turns single, double and long presses into the configured actions.
 * While the Game Switcher or Control Center is open, all controller keys go to them.
 */
class HugminnService : AccessibilityService() {

    companion object {
        @Volatile var instance: HugminnService? = null
    }

    val ui = Handler(Looper.getMainLooper())
    lateinit var prefs: Prefs
        private set
    private lateinit var switcher: Switcher
    private lateinit var controlCenter: ControlCenter

    private var pendingSingle = false
    private var longFired = false
    private var ignoreUp = false
    private val longPress = Runnable {
        longFired = true
        perform(prefs.long)
    }
    private val singlePress = Runnable {
        pendingSingle = false
        perform(prefs.single)
    }

    override fun onServiceConnected() {
        Crash.install(this)
        prefs = Prefs(this)
        switcher = Switcher(this)
        controlCenter = ControlCenter(this)
        instance = this
        // Keep the firmware from killing Hugminn in the background.
        Thread {
            Device.ensureWhitelist(this)
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Root.exec("pm grant $packageName android.permission.POST_NOTIFICATIONS")
            }
        }.start()
        SettingsWatch.start(this)
        // A crash last time? Send the report with the system log (if feedback is set up).
        ui.postDelayed({ Feedback.sendErrorLogIfPending(this) }, 3000)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closeOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        SettingsWatch.stop(this)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (::switcher.isInitialized) try {
            switcher.onEvent(event)
        } catch (t: Throwable) {
            Crash.note(this, t)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::prefs.isInitialized) return false
        if (event.keyCode == KeyEvent.KEYCODE_MOVE_HOME || event.keyCode == KeyEvent.KEYCODE_HOME) return onHome(event)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT && (controlCenter.isOpen || switcher.isOpen)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Feedback.capture(this, if (controlCenter.isOpen) "controlcenter" else "switcher")
            }
            return true
        }
        return try {
            when {
                controlCenter.isOpen -> controlCenter.onKey(event)
                switcher.isOpen -> switcher.onKey(event)
                else -> false
            }
        } catch (t: Throwable) {
            Crash.note(this, t)
            runCatching { closeOverlays() }
            true
        }
    }

    private fun onHome(e: KeyEvent): Boolean {
        val p = prefs
        val overlayOpen = controlCenter.isOpen || switcher.isOpen
        // Untouched setup and nothing open: let Android handle Home itself.
        if (!overlayOpen && p.single == Action.HOME && p.double == Action.NONE && p.long == Action.NONE) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return true
                if (pendingSingle) {
                    ui.removeCallbacks(singlePress)
                    pendingSingle = false
                    ignoreUp = true
                    perform(p.double)
                    return true
                }
                longFired = false
                if (p.long != Action.NONE) ui.postDelayed(longPress, p.longMs.toLong())
            }
            KeyEvent.ACTION_UP -> {
                ui.removeCallbacks(longPress)
                when {
                    ignoreUp -> ignoreUp = false
                    longFired -> {}
                    p.double == Action.NONE -> perform(p.single)
                    else -> {
                        pendingSingle = true
                        ui.postDelayed(singlePress, p.doubleMs.toLong())
                    }
                }
            }
        }
        return true
    }

    fun perform(a: Action) {
        try {
            performUnsafe(a)
        } catch (t: Throwable) {
            // Never let one broken feature take the whole service (and the accessibility permission) down.
            Crash.note(this, t)
            runCatching { closeOverlays() }
        }
    }

    private fun performUnsafe(a: Action) {
        when (a) {
            Action.NONE -> {}
            Action.HOME -> {
                closeOverlays()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            Action.SWITCHER -> if (switcher.isOpen) switcher.close(goBack = true) else {
                controlCenter.close()
                switcher.open()
            }
            Action.CONTROL_CENTER -> if (controlCenter.isOpen) controlCenter.close() else controlCenter.open()
            Action.RECENTS -> {
                closeOverlays()
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            Action.LAST_APP -> {
                closeOverlays()
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                ui.postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 280)
            }
        }
    }

    fun closeOverlays() {
        if (::controlCenter.isInitialized) controlCenter.close()
        if (::switcher.isInitialized && switcher.isOpen) switcher.close(goBack = false)
    }

    /** Roots of all windows on screen (the recents view lives in the launcher's window). */
    fun roots(): List<AccessibilityNodeInfo> = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())

    fun startApp(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
        }
    }
}

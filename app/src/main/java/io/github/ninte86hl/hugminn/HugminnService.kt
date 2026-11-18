package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    lateinit var own: OwnSwitcher
    /** Last time the app in front showed any window activity (tells a swipe from an app apart). */
    @Volatile private var appEventAt = 0L
    fun appRecentlyInFront() = SystemClock.uptimeMillis() - appEventAt < 2500
    val anySwitcherOpen get() = (::switcher.isInitialized && switcher.isOpen) || (::own.isInitialized && own.isOpen)

    /** Opens Hugminn's own switcher for the swipe gesture (Android's recents view is underneath). */
    fun openOwnFromSwipe() {
        if (own.isOpen || controlCenter.isOpen) return
        own.open(swipe = true)
    }

    /** Keeps pictures of the app in front up to date (for the own switcher). */
    private val snapshotTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 7000)
            if (!prefs.ownSwitcher || anySwitcherOpen || controlCenter.isOpen) return
            val pkg = foregroundPkg ?: return
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isInteractive != true) return
            val front = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (front == pkg) Snapshots.capture(this@HugminnService, pkg)
        }
    }
    private lateinit var controlCenter: ControlCenter
    lateinit var overlay: PerfOverlay
        private set

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

    private var lastNight = -1

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (lastNight != -1 && night != lastNight) runCatching { overlay.onNightModeChanged() }
        lastNight = night
    }

    override fun onServiceConnected() {
        Root.filesDir = filesDir
        Crash.install(this)
        prefs = Prefs(this)
        prefs.forgetOld()
        Lang.init(this)
        watchScreenshots()
        switcher = Switcher(this)
        own = OwnSwitcher(this)
        own.refresh()
        ui.postDelayed(snapshotTick, 7000)
        controlCenter = ControlCenter(this)
        overlay = PerfOverlay(this)
        instance = this
        if (prefs.overlayOn) ui.post { runCatching { overlay.show() } }
        // Keep the firmware from killing Hugminn in the background.
        Thread {
            Device.ensureWhitelist(this)
            listOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ).forEach { perm ->
                if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) Root.exec("pm grant $packageName $perm")
            }
        }.start()
        Bt.listenBattery(this)
        Thread { runCatching { if (Root.available()) Media.enableListener(this) } }.start()
        // Low battery: the sticks pulse red
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0) Thread { runCatching { LedFx.battery(this@HugminnService, level * 100 / scale, charging) } }.start()
            }
        }, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // own fan curve chosen last time keeps running
        if (prefs.fanCustom) Thread { if (FanCurve.available()) FanCurve.start(this) }.start()
        // LED effect chosen last time keeps running
        Thread { runCatching { LedFx.resume(this) } }.start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closeOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.hide()
        LedFx.stop()
        FanCurve.stop(restoreSmart = true)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Package of the app in the foreground (game), for frame rate and overlay. */
    @Volatile var foregroundPkg: String? = null
    /** The home screen (launcher) is in front, not an app. */
    @Volatile var homeInFront = false
        private set
    /** Last opened windows (package/class), for finding firmware dialogs in feedback reports. */
    val windowLog: MutableList<String> = mutableListOf()
    // Retroid's game assistant (its overlay pops up over games) is not an app the user switches to
    private val notApps = setOf("com.android.systemui", "com.android.launcher3", "com.google.android.inputmethod.latin", "com.rp.gameassistant")

        override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName) {
                val entry = "$pkg/${event.className}"
                synchronized(windowLog) {
                    if (windowLog.firstOrNull()?.endsWith(entry) != true) {
                        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
                        windowLog.add(0, "$t  $entry")
                        while (windowLog.size > 25) windowLog.removeAt(windowLog.lastIndex)
                    }
                }
            }
            if (pkg != null && pkg != packageName && pkg !in notApps && !Apps.isHome(this, pkg) && !pkg.contains("inputmethod", true)) {
                if (foregroundPkg != pkg && ::own.isInitialized) own.touch(pkg)
                AppProfiles.onForeground(this, pkg)
                foregroundPkg = pkg
                homeInFront = false
            } else if (pkg != null && Apps.isHome(this, pkg)) {
                // back on the home screen: profiles of the last app end
                AppProfiles.onForeground(this, null)
                homeInFront = true
            }
        }
        if (event.packageName?.toString() == foregroundPkg) appEventAt = SystemClock.uptimeMillis()
        if (::switcher.isInitialized) try {
            switcher.onEvent(event)
        } catch (t: Throwable) {
            Crash.note(this, t)
        }
    }

    private var shotsAt = 0L

    /**
     * Android's own screenshot (Power + Volume down) shows no confirmation while the Control Center or
     * the switcher covers the screen, so Hugminn shows one when a new picture appears meanwhile.
     */
    private fun watchScreenshots() {
        val o = object : android.database.ContentObserver(ui) {
            override fun onChange(selfChange: Boolean, uris: Collection<android.net.Uri>, flags: Int) {
                if (flags and android.content.ContentResolver.NOTIFY_INSERT == 0) return
                if (!::controlCenter.isInitialized || (!controlCenter.isOpen && !anySwitcherOpen)) return
                val now = System.currentTimeMillis()
                if (now - Shot.savedAt < 4_000 || now - shotsAt < 3_000) return
                shotsAt = now
                Notice.show(this@HugminnService, tr("Screenshot gespeichert"))
            }
        }
        runCatching { contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o) }
    }

    private val systemKeys = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_CAMERA,
    )

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::prefs.isInitialized) return false
        if (event.keyCode == KeyEvent.KEYCODE_MOVE_HOME || event.keyCode == KeyEvent.KEYCODE_HOME) return onHome(event)
        // the device's own keys always reach Android (volume, power, screenshot with Power + Volume down)
        if (event.keyCode in systemKeys) return false
        // Select: screenshot with the overlays (Android's key combination cannot reach them)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT && (controlCenter.isOpen || anySwitcherOpen)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (prefs.githubToken.isNullOrBlank()) Shot.take(this)
                else Feedback.capture(this, if (controlCenter.isOpen) "controlcenter" else "switcher")
            }
            return true
        }
        return try {
            when {
                controlCenter.isOpen -> controlCenter.onKey(event)
                own.isOpen -> own.onKey(event)
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
        val overlayOpen = controlCenter.isOpen || anySwitcherOpen
        // Untouched setup and nothing open: let Android handle Home itself.
        if (!overlayOpen && p.single == Action.HOME && p.double == Action.NONE && p.long == Action.NONE) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return true
                // picture of the game right now, before anything covers it (own switcher)
                if (!overlayOpen && p.ownSwitcher && !pendingSingle) foregroundPkg?.let { fg ->
                    if (runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() == fg) Snapshots.capture(this, fg)
                }
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
            Action.SWITCHER -> when {
                own.isOpen -> own.close(back = true)
                switcher.isOpen -> switcher.close(goBack = true)
                else -> {
                    controlCenter.close()
                    if (prefs.ownSwitcher) own.open(swipe = false) else switcher.open()
                }
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
        if (::own.isInitialized && own.isOpen) own.close(back = false)
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

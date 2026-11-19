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
        /** Set by the device test while it waits for buttons (Home included). */
        @Volatile var keyProbe: ((Int) -> Unit)? = null
        /** The setup wizard waits for the switcher / Control Center to be opened ("switcher", "cc"). */
        @Volatile var overlayOpened: ((String) -> Unit)? = null
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
            // the app whose window came last (from the window events): asking Android for the active window
            // here would wait for that app, and keys wait meanwhile, since they pass this thread
            if (windowPkg == pkg) Snapshots.capture(this@HugminnService, pkg)
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
        prefs.keepOldStyle()
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
        if (Device.autoGrant(this)) Thread {
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
        // an external controller went away while the built-in controls are off: switch them back on
        runCatching {
            getSystemService(android.hardware.input.InputManager::class.java)?.registerInputDeviceListener(object : android.hardware.input.InputManager.InputDeviceListener {
                // only a real external controller counts: switching the built-in controls off removes
                // Retroid's own controller device, which must not switch them right back on
                val pads = android.view.InputDevice.getDeviceIds().filter { Device.isExternalPad(it) }.toMutableSet()
                override fun onInputDeviceAdded(id: Int) {
                    WakeDiag.note("Eingabegerät neu: ${android.view.InputDevice.getDevice(id)?.name} (id=$id)")
                    if (Device.isExternalPad(id)) pads += id
                }
                override fun onInputDeviceChanged(id: Int) { WakeDiag.note("Eingabegerät geändert: id=$id") }
                override fun onInputDeviceRemoved(id: Int) {
                    WakeDiag.note("Eingabegerät weg: id=$id")
                    if (!pads.remove(id)) return
                    ui.postDelayed({
                        if (Root.available() && !Device.externalPad() && Device.builtinOff(this@HugminnService)) Device.setBuiltin(this@HugminnService, true) {
                            Notice.show(this@HugminnService, t("Controller getrennt – eingebaute Steuerung wieder an", "Controller disconnected – built-in controls back on"))
                        }
                    }, 1500)
                }
            }, ui)
        }
        Thread { runCatching { if (Root.available() && Device.autoGrant(this)) Media.enableListener(this) } }.start()
        Capture.resume(this)
        // test builds: an error from the last run is sent once (only with a token set)
        Thread { runCatching { Feedback.sendErrorLogIfPending(this) } }.start()
        // screen off during a video: stop and save it cleanly
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                if (Capture.recording && prefs.recStopOnSleep) Capture.stopRecord(this@HugminnService)
                // nothing of Hugminn left on the lock screen when the screen comes back on
                runCatching { closeOverlays() }
            }
        }, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        // waking up: if the built-in controls are off and no external controller came back (e.g. it went
        // to sleep with the handheld), switch them on again, so the handheld can always be used
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                WakeDiag.start(this@HugminnService)
                ui.postDelayed({
                    Thread {
                        if (Root.available() && Device.builtinOff(this@HugminnService) && !Device.externalPad())
                            Device.setBuiltin(this@HugminnService, true) {
                                Notice.show(this@HugminnService, t("Kein Controller verbunden – eingebaute Steuerung wieder an", "No controller connected – built-in controls back on"))
                            }
                    }.start()
                }, 2500)
            }
        }, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
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
    /** The app whose window came to the front last (games, launchers, anything but Hugminn and the system bar). */
    @Volatile var windowPkg: String? = null
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
            if (pkg != null && pkg != packageName && pkg != "com.android.systemui") windowPkg = pkg
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

    /** Test build: the last key events with their device (to see what an external controller sends). */
    val keyLog = java.util.ArrayDeque<String>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!WakeDiag.active) return onKeyEventInner(event)
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = onKeyEventInner(event)
        WakeDiag.key(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime, android.os.SystemClock.uptimeMillis() - t0, r)
        return r
    }

    private fun onKeyEventInner(event: KeyEvent): Boolean {
        if (!::prefs.isInitialized) return false
        synchronized(keyLog) {
            val dev = event.device
            keyLog.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"}${if (event.repeatCount > 0) " r${event.repeatCount}" else ""} · ${dev?.name} (extern=${dev?.isExternal}, quelle=0x${Integer.toHexString(event.source)})")
            while (keyLog.size > 40) keyLog.removeFirst()
        }
        // the device test asks for the Home button: it goes there instead of opening anything
        keyProbe?.let { probe ->
            if (event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
                if (event.action == KeyEvent.ACTION_UP) ui.post { probe(KeyEvent.KEYCODE_HOME) }
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MOVE_HOME || event.keyCode == KeyEvent.KEYCODE_HOME) return onHome(event)
        // an external controller (e.g. 8BitDo over Bluetooth): its Home/Guide button counts as Home
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && prefs.padHome && event.device?.isExternal == true) return onHome(event, forced = true)
        // the power key closes everything of Hugminn first, so Android's power menu is never hidden behind it
        if (event.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) ui.post { runCatching { closeOverlays() } }
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

    private fun onHome(e: KeyEvent, forced: Boolean = false): Boolean {
        val p = prefs
        // locked (lock screen after waking up): Home stays Android's, nothing of Hugminn opens there
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) {
            closeOverlays()
            return false
        }
        val overlayOpen = controlCenter.isOpen || anySwitcherOpen
        // Untouched setup and nothing open: let Android handle Home itself (a controller's Guide button
        // has no meaning for Android, so Hugminn performs the single press itself then).
        if (!forced && !overlayOpen && p.single == Action.HOME && p.double == Action.NONE && p.long == Action.NONE) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return true
                // picture of the game right now, before anything covers it (own switcher)
                if (!overlayOpen && p.ownSwitcher && !pendingSingle) foregroundPkg?.let { fg ->
                    if (windowPkg == fg) Snapshots.capture(this, fg)
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
        // trying out the overlays in the setup: a single press must not leave the wizard
        if (overlayOpened != null && a == Action.HOME) return
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
            Action.SCREENSHOT -> { closeOverlays(); Capture.screenshot(this) }
            Action.RECORD -> { closeOverlays(); Capture.toggleRecord(this) }
            Action.REPLAY -> Capture.saveReplay(this)
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

    /** Built-in controls switched off with no external controller yet: a countdown in the middle of the
     *  screen; back on if none is connected within 60 s. The Control Center closes, since it can't be
     *  used without buttons anyway. */
    private var guardRun: Runnable? = null
    @Volatile var guardDiag = ""
    fun guardBuiltinOff() {
        guardRun?.let { ui.removeCallbacks(it) }
        if (::controlCenter.isInitialized) controlCenter.close()
        val end = SystemClock.elapsedRealtime() + 30_000
        val tick = object : Runnable {
            override fun run() {
                val left = ((end - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
                when {
                    Device.externalPad() -> {
                        guardRun = null; Notice.hideSticky(this@HugminnService)
                        guardDiag = "${30 - left} s: controller found · " + Device.padList()
                        Notice.show(this@HugminnService, t("Controller verbunden", "Controller connected"))
                    }
                    !Device.builtinOff(this@HugminnService) -> {
                        guardRun = null; Notice.hideSticky(this@HugminnService)
                        guardDiag = "${30 - left} s: built-in back on (temp_abxy_layout_mode=${Device.sys(this@HugminnService, "temp_abxy_layout_mode")}, no_create=${Device.sys(this@HugminnService, "no_create_gamepad_button_layout")})"
                        Notice.show(this@HugminnService, t("Eingebaute Steuerung ist wieder an", "Built-in controls are back on"))
                    }
                    left <= 0 -> {
                        guardRun = null; Notice.hideSticky(this@HugminnService)
                        if (Root.available()) Device.setBuiltin(this@HugminnService, true) {
                            Notice.show(this@HugminnService, t("Kein Controller verbunden – eingebaute Steuerung wieder an", "No controller connected – built-in controls back on"))
                        }
                    }
                    else -> {
                        Notice.sticky(this@HugminnService, t("Eingebaute Steuerung aus\nJetzt Controller verbinden · $left s", "Built-in controls off\nConnect a controller now · $left s"))
                        ui.postDelayed(this, 1000)
                    }
                }
            }
        }
        guardRun = tick
        tick.run()
    }

    fun controlCenterOpen() = ::controlCenter.isInitialized && controlCenter.isOpen
}

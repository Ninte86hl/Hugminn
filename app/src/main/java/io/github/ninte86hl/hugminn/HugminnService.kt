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
        /** Device test, extra buttons: every key goes here (a copy) and nowhere else – power aside. */
        @Volatile var keyTap: ((KeyEvent) -> Unit)? = null
        /** Sees every key (without taking it): the setup's button test works whichever window has the focus. */
        @Volatile var keyWatch: ((Int) -> Unit)? = null
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
            if (windowPkg == pkg && onTopScreen(pkg)) Snapshots.capture(this@HugminnService, pkg)
        }
    }
    private lateinit var controlCenter: ControlCenter
    private var dualMedia: DualMedia? = null
    private var landscape: Landscape? = null
    private val landscapeCheck = Runnable { landscape?.check() }
    private val lastTop = mutableMapOf<Int, String?>()

    @Volatile private var dualScreen: Boolean? = null

    /**
     * Dual screen: is [pkg] really what the top screen shows? (After Home the launcher is in front up
     * top, while the last app may still count as "in front" – its card would get the launcher's picture.)
     * Only the windows' titles are compared: asking the app itself could make keys wait.
     */
    private fun onTopScreen(pkg: String): Boolean {
        val dual = dualScreen ?: (Displays.second(this) != null).also { dualScreen = it }
        if (!dual || android.os.Build.VERSION.SDK_INT < 30) return true
        val w = runCatching { windowsOnAllDisplays.get(android.view.Display.DEFAULT_DISPLAY) }.getOrNull()
            ?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION } ?: return true
        val title = w.title?.toString() ?: return true
        return title == Apps.label(this, pkg)
    }

    /** The app in front on the top screen (null: home screen) – only display 0 counts, not the Thor's lower screen. */
    fun topScreenApp(): String? = topAppOn(android.view.Display.DEFAULT_DISPLAY)

    /** The app in front on screen [id] (null: its launcher, e.g. Cocoon on the Thor's lower screen). */
    fun topAppOn(id: Int): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return if (id != 0 || homeInFront) null else foregroundPkg
        val wins = runCatching { windowsOnAllDisplays.get(id) }.getOrNull() ?: return lastTop[id]
        val app = wins.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION } ?: return lastTop[id]
        val pkg = runCatching { app.root?.packageName?.toString() }.getOrNull() ?: return lastTop[id]
        // Hugminn's own pause screen (switcher) lies over the app: keep the app
        if (pkg == packageName) return lastTop[id]
        return (if (Apps.isHome(this, pkg) || pkg.contains("cocoon")) null else pkg).also { lastTop[id] = it }
    }

    lateinit var overlay: PerfOverlay
        private set

    private var pendingSingle = false
    /** When Home went down (uptime ms) – the double press is timed from here. */
    private var homeDownAt = 0L
    private var longFired = false
    private var ignoreUp = false
    private val longPress = Runnable {
        longFired = true
        perform(prefs.long)
    }
    private val singlePress = Runnable {
        pendingSingle = false
        // an extra press right after opening (the windows were still coming up) must not close them again
        if ((controlCenter.isOpen || anySwitcherOpen) && android.os.SystemClock.uptimeMillis() - openedAt < 1200) {
            Glyphs.note("home einzeln ignoriert (gerade geöffnet)")
            return@Runnable
        }
        perform(prefs.single)
    }
    /** When a double or long press last opened something (uptime ms). */
    private var openedAt = 0L

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
        Apps.chosenHome = prefs.launcherPkg.ifBlank { null }
        Device.app = applicationContext
        // Anbernic: after an update the new helper.sh goes down at once – the running helper picks it up itself
        if (Device.isAnbernic && android.os.Environment.isExternalStorageManager()) Thread { runCatching { AnbernicHelper.install(packageName) } }.start()
        // Anbernic after a restart: the helper isn't running – Hugminn starts it through Anbernic's script management
        if (Device.isAnbernic) ui.postDelayed({ AnbernicAutostart.afterStart(this) }, 15_000)
        prefs.duoHomeBack()
        if (Device.isDuo) Thread { runCatching { findRetroidNode() } }.start()
        prefs.forgetOld()
        // the Android wallpaper may have changed while Hugminn was off: copy it again in the background
        if (prefs.backdrop == BackdropSource.WALLPAPER) ui.postDelayed({ if (Root.available()) Backdrop.fetchWallpaper(this) { } }, 8_000)
        Lang.init(this)
        watchScreenshots()
        switcher = Switcher(this)
        own = OwnSwitcher(this)
        own.refresh()
        ui.postDelayed(snapshotTick, 7000)
        controlCenter = ControlCenter(this)
        dualMedia = DualMedia(this).also { it.start() }
        landscape = Landscape(this)
        NavBar.resume(this)
        Device.refreshLayout(this)
        if (Root.available()) Displays.collectLeds()
        FeedbackBubble.start(this)
        // the tablet view (test builds) is gone for good: a density it left behind goes back to the old value
        Thread {
            val sp = getSharedPreferences("hugminn", MODE_PRIVATE)
            sp.all.keys.filter { it.startsWith("wideRestore") }.forEach { k ->
                val back = sp.getInt(k, -1)
                val id = k.removePrefix("wideRestore").toIntOrNull() ?: 0
                if (back >= 0 && Root.available()) Root.exec(if (back > 0) "wm density $back -d $id" else "wm density reset -d $id")
                sp.edit().remove(k).apply()
            }
            listOf("wideOn", "wideApps", "wideDpi", "wideKeep", "wideOn2", "wideApps2").forEach { sp.edit().remove(it).apply() }
        }.start()
        overlay = PerfOverlay(this)
        instance = this
        if (prefs.overlayOn) ui.post { runCatching { overlay.show() } }
        // Keep the firmware from killing Hugminn in the background: its whitelist, on every start once
        // root answers (it was missing on a Pocket 5 that hadn't run the automatic grants)
        Thread {
            repeat(20) { if (!Root.available()) Thread.sleep(1000) }
            if (Root.available() && !Device.whitelisted(this)) Device.ensureWhitelist(this)
        }.start()
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
                // AYN with two stick colours: the firmware puts its one colour back on waking
                if (Device.aynSplit(this@HugminnService) && LedFx.current() == LedEffect.NONE && Device.stickLeds(this@HugminnService))
                    LedFx.restoreStatic(this@HugminnService, delayMs = 1500)
                // Anbernic: the controller's lights stay dark after standby – switch them fully on again
                if (Device.isAnbernic) ui.postDelayed({ AnbernicLed.wake(this@HugminnService) }, 1200)
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
        // monitor on USB-C (Retroid Connect TV): its tile only shows while one is connected
        if (packageManager.getLaunchIntentForPackage("com.rp.settings") != null) ExternalDisplay.watch(this)
        // own fan curve chosen last time keeps running
        if (prefs.fanCustom) Thread { if (FanCurve.available()) FanCurve.start(this) }.start()
        // LED effect chosen last time keeps running
        Thread { runCatching { LedFx.resume(this) } }.start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closeOverlays()
        dualMedia?.stop()
        landscape?.hide()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        dualMedia?.stop()
        landscape?.hide()
        FeedbackBubble.stop(this)
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            dualMedia?.kick()
            ui.removeCallbacks(landscapeCheck)
            ui.postDelayed(landscapeCheck, 150)
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
        keyWatch?.let { w -> if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { val c = event.keyCode; ui.post { w(c) } } }
        keyTap?.let { l ->
            if (event.keyCode != KeyEvent.KEYCODE_POWER) { val copy = KeyEvent(event); ui.post { l(copy) }; return true }
        }
        // Homes Hugminn sent on to the firmware itself (Retroid's double press)
        // the double tap Hugminn hands on to Retroid's firmware (sent through the controller itself, see RETROID)
        // A time window, not a count: some events arrive here twice, the count ran out early and the second tap's
        // release was held back – Retroid then took Home as held and switched the lower screen off.
        if (event.keyCode == KeyEvent.KEYCODE_HOME && android.os.SystemClock.uptimeMillis() < passHomeUntil) {
            Glyphs.note("pass HOME ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} dev=${event.deviceId} ${event.device?.name} t=${event.eventTime}")
            return false
        }
        // AYN's own button (Thor): it sends Home, but belongs to AYN – its menu, closing it, screen off
        if (Device.isAynKey(event)) return false
        // Anbernic (RG 406H): its Home button sends F10 from the built-in pad
        val anbernicHome = event.keyCode == KeyEvent.KEYCODE_F10 && event.device?.name == "retrogame_joypad"
        // the device test asks for the Home button: it goes there instead of opening anything
        keyProbe?.let { probe ->
            if (event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_MOVE_HOME || anbernicHome) {
                if (event.action == KeyEvent.ACTION_UP) ui.post { probe(KeyEvent.KEYCODE_HOME) }
                return true
            }
        }
        // Pocket Classic: the firmware tells single, double and long apart itself – a single press
        // arrives as Back, a double click as one Home (the Backs are swallowed), held as a long Back.
        // Back is on the face buttons and touch anyway, so the button becomes a full Home button.
        if (Device.noSticks && event.keyCode == KeyEvent.KEYCODE_BACK && event.device?.name?.contains("Retroid", true) == true) return onClassicKey(event, false)
        if (Device.noSticks && (event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_MOVE_HOME)) return onClassicKey(event, true)
        if (event.keyCode == KeyEvent.KEYCODE_MOVE_HOME || event.keyCode == KeyEvent.KEYCODE_HOME) return onHome(event)
        if (anbernicHome) return onHome(event, forced = true)
        // extra buttons (paddles, M1/M2) – only once the person gave them something to do
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_C || event.keyCode == KeyEvent.KEYCODE_BUTTON_Z) onExtraKey(event)?.let { return it }
        // Retroid's Back button held: an action of its own (switcher on the Duo); a short press stays Back
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !Device.noSticks && prefs.backLong != Action.NONE &&
            event.device?.name?.contains("Retroid", true) == true && !controlCenter.isOpen && !anySwitcherOpen) return onBackKey(event)
        // an external controller (e.g. 8BitDo over Bluetooth): its Home/Guide button counts as Home
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && prefs.padHome && event.device?.isExternal == true) return onHome(event, forced = true)
        // the power key closes everything of Hugminn first, so Android's power menu is never hidden behind it
        if (event.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) ui.post { runCatching { closeOverlays() } }
        // the device's own keys always reach Android (volume, power, screenshot with Power + Volume down)
        if (event.keyCode in systemKeys) return false
        // test builds: Select sends a feedback report (screenshot + log) from the overlays
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT && (controlCenter.isOpen || anySwitcherOpen)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (prefs.githubToken.isNullOrBlank()) Shot.take(this)
                else Feedback.capture(this, if (controlCenter.isOpen) "controlcenter" else "switcher")
            }
            return true
        }
        // the overlays go by button position (north, west …), the same on every layout
        val ev = Glyphs.logical(event)
        return try {
            when {
                comboMode -> comboKey(ev)
                controlCenter.isOpen -> controlCenter.onKey(ev)
                own.isOpen -> own.onKey(ev)
                switcher.isOpen -> switcher.onKey(ev)
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
                    if (windowPkg == fg && onTopScreen(fg)) Snapshots.capture(this, fg)
                }
                Glyphs.note("home down · wartet auf zweiten=$pendingSingle · zweimal=${p.double} lange=${p.long} (${p.doubleMs} ms)")
                if (pendingSingle) {
                    ui.removeCallbacks(singlePress)
                    pendingSingle = false
                    ignoreUp = true
                    perform(p.double)
                    return true
                }
                longFired = false
                homeDownAt = e.eventTime.takeIf { it > 0 } ?: android.os.SystemClock.uptimeMillis()
                if (p.long != Action.NONE) ui.postDelayed(longPress, p.longMs.toLong())
            }
            KeyEvent.ACTION_UP -> {
                ui.removeCallbacks(longPress)
                when {
                    ignoreUp -> { ignoreUp = false; afterHomeUp?.let { afterHomeUp = null; ui.postDelayed(it, 60) } }
                    longFired -> {}
                    p.double == Action.NONE -> perform(p.single)
                    else -> {
                        // The wait for a second press counts from pressing down, not from letting go: some
                        // firmwares (Pocket Duo) hand the release on late, which ate up the time for the second press.
                        val wait = (homeDownAt + p.doubleMs + 120 - android.os.SystemClock.uptimeMillis()).coerceAtLeast(80)
                        Glyphs.note("home up nach ${android.os.SystemClock.uptimeMillis() - homeDownAt} ms · warte noch $wait ms")
                        pendingSingle = true
                        ui.postDelayed(singlePress, wait)
                    }
                }
            }
        }
        return true
    }

    private var classicLong = false
    private val classicLongPress = Runnable { classicLong = true; perform(prefs.long) }

    private fun onClassicKey(e: KeyEvent, double: Boolean): Boolean {
        // lock screen: the button stays Android's
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0 && double) {
                // picture of the game right now, before the switcher covers it
                if (!controlCenter.isOpen && !anySwitcherOpen && prefs.ownSwitcher) foregroundPkg?.let { fg ->
                    if (windowPkg == fg && onTopScreen(fg)) Snapshots.capture(this, fg)
                }
                // right away on the press: the firmware's release comes ~80 ms later
                ui.post { perform(prefs.double) }
            } else if (e.repeatCount == 0) {
                classicLong = false
                if (prefs.long != Action.NONE) ui.postDelayed(classicLongPress, prefs.longMs.toLong())
            }
            KeyEvent.ACTION_UP -> {
                ui.removeCallbacks(classicLongPress)
                if (!double && !classicLong) ui.post { perform(prefs.single) }
                classicLong = false
            }
        }
        return true
    }

    private val extraLong = HashMap<Int, Boolean>()

    /** Short and long press of an extra button; null: nothing set, the key goes on to the game. */
    private fun onExtraKey(e: KeyEvent): Boolean? {
        val code = e.keyCode
        val short = prefs.extraKey(code, false)
        val long = prefs.extraKey(code, true)
        if (short == Action.NONE && long == Action.NONE) return null
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                extraLong[code] = false
                if (long != Action.NONE) extraLongPress(code)
            }
            KeyEvent.ACTION_UP -> {
                ui.removeCallbacksAndMessages(extraToken(code))
                if (extraLong[code] != true) perform(short)
                extraLong.remove(code)
            }
        }
        return true
    }

    private fun extraToken(code: Int) = "extra$code"

    private var passHomeUntil = 0L
    /** Runs when the Home press Hugminn holds back is let go (see RETROID). */
    private var afterHomeUp: Runnable? = null

    /**
     * Retroid's control center is the "floating view" of its Game Assistant. Ways to bring it up, tried in turn
     * until one of its windows shows (the first that works is remembered): its service started directly (root
     * may start it although it isn't open to other apps), the broadcast its widget sends, its quick-settings tile.
     */
    private fun openRetroidPanel() {
        val ways = listOf(
            "service" to "am startservice -n com.rp.gameassistant/com.ro.gameassistant.service.FloatingViewService 2>&1",
            "widget" to "am broadcast -a widget.provider.onclick.to.gameassistant 2>&1 | tail -1",
            "tile" to "cmd statusbar expand-settings; sleep 0.5; cmd statusbar click-tile $RETROID_TILE 2>&1; sleep 0.4; cmd statusbar collapse",
        )
        val first = ways.indexOfFirst { it.first == prefs.retroidWay }.coerceAtLeast(0)
        val order = ways.drop(first) + ways.take(first)
        fun shown(since: Long) = synchronized(windowLog) { windowLog.firstOrNull() }?.let { it.contains("com.rp.gameassistant") } == true &&
            System.currentTimeMillis() - since < 5_000
        Thread {
            for ((name, cmd) in order) {
                val t0 = System.currentTimeMillis()
                val out = Root.execLong(cmd, 5_000)
                Thread.sleep(900)
                val ok = shown(t0)
                Glyphs.note("retroid $name → ${if (ok) "OPEN" else "–"} ${out?.trim()?.take(120)}")
                if (ok) { prefs.retroidWay = name; return@Thread }
            }
        }.start()
    }

    private val RETROID_TILE = "com.rp.gameassistant/com.ro.gameassistant.service.FloatingWindowTileService"

    /** Home pressed / let go as raw input events (struct input_event, 64-bit: time 16 bytes, type, code, value) + sync. */
    private fun homeEventFiles(): Pair<String, String> {
        fun ev(type: Int, code: Int, value: Int) = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(0).putShort(type.toShort()).putShort(code.toShort()).putInt(value).array()
        fun file(name: String, value: Int) = java.io.File(filesDir, name).apply {
            writeBytes(ev(1, 102, value) + ev(0, 0, 0))
            setReadable(true, false)
        }.absolutePath
        return file("home_down.bin", 1) to file("home_up.bin", 0)
    }

    /** The Retroid controller's input device (eventN), found once – reading the list takes a moment. */
    @Volatile private var retroidNode: String? = null
    private fun findRetroidNode(): String? {
        val devs = Root.execLong("cat /proc/bus/input/devices", 4_000).orEmpty()
        return devs.split("\n\n").firstOrNull { it.contains("Retroid Pocket Controller") }
            ?.lineSequence()?.firstOrNull { it.startsWith("H:") }?.split(" ", "=")?.firstOrNull { it.startsWith("event") }
            ?.also { retroidNode = it }
    }

    /** Duo diagnosis: raw Home events (getevent) for 3 s after a press, the last three captures. */
    val homeTrace = java.util.concurrent.ConcurrentLinkedDeque<String>()
    @Volatile private var capturing = false
    private fun captureHome() {
        if (capturing || !Root.available()) return
        capturing = true
        val at = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
        Thread {
            val out = Root.execLong("timeout 3 getevent -lt 2>&1 | grep -E 'KEY_HOME|KEY_HOMEPAGE|BTN_MODE|0066|00ac' | head -40", 6_000)
            homeTrace.addLast("ab $at:\n${out?.trim().orEmpty()}")
            while (homeTrace.size > 3) homeTrace.pollFirst()
            capturing = false
        }.start()
    }
    private var backHeld = false
    private val backLongPress = Runnable { backHeld = true; perform(prefs.backLong) }

    private fun onBackKey(e: KeyEvent): Boolean {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) { backHeld = false; ui.postDelayed(backLongPress, prefs.longMs.toLong()) }
            KeyEvent.ACTION_UP -> {
                ui.removeCallbacks(backLongPress)
                if (!backHeld) performGlobalAction(GLOBAL_ACTION_BACK)
                backHeld = false
            }
        }
        return true
    }

    private fun extraLongPress(code: Int): Runnable = Runnable { extraLong[code] = true; perform(prefs.extraKey(code, true)) }.also { r ->
        // tagged, so the release can take it back
        ui.postAtTime(r, extraToken(code), android.os.SystemClock.uptimeMillis() + prefs.longMs)
    }

    fun perform(a: Action) {
        Glyphs.note("aktion $a · offen: combo=$comboMode cc=${::controlCenter.isInitialized && controlCenter.isOpen} switcher=$anySwitcherOpen")
        if (a == Action.BOTH || a == Action.SWITCHER || a == Action.CONTROL_CENTER) openedAt = android.os.SystemClock.uptimeMillis()
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
            // Hugminn holds the Home button, so Retroid's firmware never saw the double press: send it two Homes it may keep
            Action.RETROID -> {
                closeOverlays()
                openRetroidPanel()
            }
            Action.BOTH -> if (comboMode || controlCenter.isOpen || anySwitcherOpen) closeOverlays() else openCombo()
            Action.BOTTOM_SCREEN -> if (Device.isDuo && Root.available()) Thread {
                val on = !Device.bottomScreenOn(this)
                Device.setBottomScreen(on) {
                    Notice.show(this, if (on) t("Unterer Bildschirm an", "Lower screen on") else t("Unterer Bildschirm aus", "Lower screen off"), corner = true)
                }
            }.start()
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

    private var keymapPaused = false

    /**
     * An overlay opened or closed. Anbernic: its key mapper is paused meanwhile (as its own panel
     * does it), otherwise it turns B into Back and the like inside Hugminn's windows.
     */
    fun overlayState() {
        if (!Device.isAnbernic) return
        val open = (::controlCenter.isInitialized && controlCenter.isOpen) || anySwitcherOpen
        if (open == keymapPaused) return
        keymapPaused = open
        Thread {
            runCatching { android.provider.Settings.Global.putString(contentResolver, "keymap_paused_by_panel", if (open) "1" else "0") }
                .onFailure { AnbernicHelper.trace("keymap pause failed: ${it.message}") }
        }.start()
    }

    // ---------- switcher + Control Center on two screens ----------

    /** Both are open together, one per screen; the buttons go to one of them ([comboCc]). */
    var comboMode = false
        private set
    private var comboCc = false

    private fun openCombo() {
        val low = Displays.second(this)?.id ?: return perform(Action.SWITCHER)
        closeOverlays()
        comboMode = true
        comboCc = false
        val ccLow = prefs.ccLower
        comboCcDisplay = if (ccLow) low else android.view.Display.DEFAULT_DISPLAY
        own.open(swipe = false, display = if (ccLow) android.view.Display.DEFAULT_DISPLAY else low)
    }

    private var comboCcDisplay = android.view.Display.DEFAULT_DISPLAY

    /** The switcher's window is up (its list takes a moment): now the Control Center, so both appear together. */
    fun comboBuilt() {
        if (!comboMode) return
        if (!controlCenter.isOpen) controlCenter.open(display = comboCcDisplay)
        Glyphs.note("control center sichtbar")
        comboLook()
    }

    fun comboLook() {
        if (!comboMode) return
        controlCenter.look(comboCc)
        own.look(!comboCc)
    }

    /** A touch on one of the two windows: the buttons go there. */
    fun comboFocus(cc: Boolean) {
        if (!comboMode || comboCc == cc) return
        comboCc = cc
        comboLook()
        if (cc) controlCenter.activate()
    }

    /** A button while both are open: the D-pad crosses over to the other screen at the edge, everything else goes to the active one. */
    fun comboKey(e: KeyEvent): Boolean {
        val ccLow = prefs.ccLower
        if (e.action == KeyEvent.ACTION_DOWN) {
            val toCc = if (ccLow) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            val toSw = if (ccLow) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            if (!comboCc && e.keyCode == toCc) { Glyphs.note("combo → cc"); comboFocus(true); return true }
            if (comboCc && e.keyCode == toSw && controlCenter.atEdge(if (ccLow) -1 else 1)) { Glyphs.note("combo → switcher"); comboFocus(false); return true }
        }
        // in the switcher, L1/R1 turn the Control Center's pages on the other screen
        if (!comboCc && !own.menuOpen && (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || e.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
            if (e.action == KeyEvent.ACTION_DOWN) controlCenter.turnPage(if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1)
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) Glyphs.note("combo ${KeyEvent.keyCodeToString(e.keyCode)} → ${if (comboCc) "cc" else "switcher"}")
        if (comboCc) controlCenter.onKey(e) else own.onKey(e)
        return true
    }

    /** One of the two closed (game started, B, …): the other one goes too. */
    fun comboClosed() {
        if (!comboMode) return
        comboMode = false
        closeOverlays()
    }

    fun closeOverlays() {
        comboMode = false
        if (::controlCenter.isInitialized) controlCenter.close()
        if (::switcher.isInitialized && switcher.isOpen) switcher.close(goBack = false)
        if (::own.isInitialized && own.isOpen) own.close(back = false)
    }

    /** Roots of all windows on screen (the recents view lives in the launcher's window). */
    fun roots(): List<AccessibilityNodeInfo> = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())

    fun startApp(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Displays.onTop(this))
        } catch (t: Throwable) {
        }
    }

    /** Built-in controls switched off with no external controller yet: a countdown in the middle of the
     *  screen; back on if none is connected within 60 s. The Control Center closes, since it can't be
     *  used without buttons anyway. */
    private var guardRun: Runnable? = null
    @Volatile var guardDiag = ""
    fun guardBuiltinOff() {
        // called from a root callback too: the Control Center may only be closed on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { ui.post { guardBuiltinOff() }; return }
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

    /** Android's task of [pkg] (-1 if unknown), for bringing it back to the front. */
    fun taskOf(pkg: String): Int = if (::own.isInitialized) own.taskOf(pkg) else -1

    fun controlCenterOpen() = ::controlCenter.isInitialized && controlCenter.isOpen
}

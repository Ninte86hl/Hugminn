package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Screenshots, videos and "save the last seconds" (the Capture tile, Home button actions). */
object Capture {
    enum class Audio(private val de: String, private val en: String) {
        GAME("Spielton", "Game sound"),
        GAME_MIC("Spielton + Mikrofon", "Game sound + microphone"),
        MIC("Nur Mikrofon", "Microphone only"),
        NONE("Kein Ton", "No sound");
        val label get() = t(de, en)
    }

    /** Resolution (short side) and frame rate. */
    val qualities = listOf(1080 to 60, 1080 to 30, 720 to 60, 720 to 30)
    val replayLengths = listOf(15, 30, 60)
    val limits = listOf(0, 5, 10, 15, 30, 60, 180, 300, 600)
    fun limitLabel(s: Int) = when { s == 0 -> t("Unbegrenzt", "No limit"); s < 60 -> "$s s"; else -> "${s / 60} min" }
    private var limitRun: Runnable? = null

    @Volatile var recorder: Recorder? = null
    @Volatile var recordStart = 0L
    @Volatile var lastUri: Uri? = null
    @Volatile var lastIsVideo = false
    /** What to do once the screen capture is running. */
    @Volatile private var pending: String? = null

    val recording get() = recorder?.recordingFile == true
    val replayRunning get() = recorder?.let { it.replaySec > 0 } == true

    fun elapsed(): String {
        val s = ((SystemClock.elapsedRealtime() - recordStart) / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---- screenshot ----

    fun screenshot(svc: HugminnService, delaySec: Int = 0) {
        if (delaySec <= 0) {
            svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 300)
            return
        }
        var left = delaySec
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    Notice.sticky(svc, t("Screenshot in $left s", "Screenshot in $left s"))
                    left--
                    svc.ui.postDelayed(this, 1000)
                } else {
                    Notice.hideSticky(svc)
                    svc.ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 250)
                }
            }
        }
        tick.run()
    }

    // ---- video ----

    val showLabels get() = listOf(t("Stick-LEDs pulsieren rot", "Stick LEDs pulse red"), t("Roter Punkt", "Red dot"), t("LEDs und Punkt", "LEDs and dot"), t("Nichts", "Nothing"))

    /** Starts a video after a countdown in the middle of the screen. */
    fun startRecordIn(svc: HugminnService, sec: Int) {
        var left = sec
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) { Notice.sticky(svc, t("Aufnahme in $left s", "Recording in $left s")); left--; svc.ui.postDelayed(this, 1000) }
                else { Notice.hideSticky(svc); startRecord(svc) }
            }
        }
        tick.run()
    }

    fun toggleRecord(svc: HugminnService) = if (recording) stopRecord(svc) else startRecord(svc)

    fun startRecord(svc: HugminnService) {
        val r = recorder
        if (r != null) { beginFile(svc, r); return }
        pending = "record"
        requestProjection(svc)
    }

    private fun beginFile(svc: HugminnService, r: Recorder) {
        if (!r.startFile()) { Notice.show(svc, t("Aufnahme fehlgeschlagen", "Recording failed")); return }
        recordStart = SystemClock.elapsedRealtime()
        limitRun?.let { svc.ui.removeCallbacks(it) }
        svc.prefs.recLimit.takeIf { it > 0 }?.let { sec ->
            val start = recordStart
            val run = Runnable { if (recording && recordStart == start) stopRecord(svc) }
            limitRun = run
            svc.ui.postDelayed(run, sec * 1000L)
        }
        Indicator.show(svc)
        if (svc.prefs.recShow in listOf(0, 2)) Thread { runCatching { LedFx.setRecording(svc, true) } }.start()
        Notice.show(svc, t("Aufnahme läuft", "Recording"), corner = true)
    }

    fun stopRecord(svc: HugminnService) {
        val r = recorder ?: return
        Indicator.hide(svc)
        Thread { runCatching { LedFx.setRecording(svc, false) } }.start()
        Thread {
            val uri = r.stopFile()
            svc.ui.post {
                if (uri != null) { lastUri = uri; lastIsVideo = true }
                Notice.show(svc, if (uri != null) t("Video gespeichert · Filme/Hugminn", "Video saved · Movies/Hugminn") else t("Aufnahme fehlgeschlagen", "Recording failed"))
                if (!replayRunning) stopEngine(svc)
            }
        }.start()
    }

    // ---- the last seconds ----

    fun setReplay(svc: HugminnService, on: Boolean) {
        svc.prefs.replayOn = on
        val r = recorder
        if (on) {
            if (r != null) r.replaySec = svc.prefs.replaySec
            else { pending = "replay"; requestProjection(svc) }
        } else if (r != null) {
            r.replaySec = 0
            if (!r.recordingFile) stopEngine(svc)
        }
    }

    fun saveReplay(svc: HugminnService) {
        val r = recorder?.takeIf { it.replaySec > 0 }
        if (r == null) {
            Notice.show(svc, t("Erst „Letzte Sekunden“ in der Aufnahme-Kachel einschalten", "First switch on “Last seconds” in the Capture tile"))
            return
        }
        Notice.show(svc, t("Speichere die letzten ${r.replaySec} s …", "Saving the last ${r.replaySec} s …"), corner = true)
        Thread {
            val uri = r.saveReplay()
            svc.ui.post {
                if (uri != null) { lastUri = uri; lastIsVideo = true }
                Notice.show(svc, if (uri != null) t("Clip gespeichert · Filme/Hugminn", "Clip saved · Movies/Hugminn") else t("Noch nichts aufgenommen", "Nothing recorded yet"))
            }
        }.start()
    }

    /** After a restart: the ring buffer runs again if it was on. */
    fun resume(svc: HugminnService) {
        if (svc.prefs.replayOn && recorder == null) svc.ui.postDelayed({ if (recorder == null) setReplay(svc, true) }, 8000)
    }

    // ---- screen capture ----

    private fun requestProjection(svc: HugminnService) {
        Thread {
            // with root, Android stops asking "Start recording?" every time; the microphone and the
            // notification are allowed too
            if (Root.available() && (Device.autoGrant(svc) || svc.prefs.recNoAsk)) {
                Root.exec("appops set ${svc.packageName} PROJECT_MEDIA allow")
                Root.exec("pm grant ${svc.packageName} android.permission.RECORD_AUDIO")
                if (android.os.Build.VERSION.SDK_INT >= 33) Root.exec("pm grant ${svc.packageName} android.permission.POST_NOTIFICATIONS")
            }
            svc.ui.post {
                runCatching {
                    svc.startActivity(Intent(svc, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }.onFailure { Notice.show(svc, t("Aufnahme konnte nicht starten", "Couldn't start recording")) }
            }
        }.start()
    }

    /** Called by the service once the screen capture is allowed. */
    fun started(ctx: Context, projection: MediaProjection) {
        val svc = HugminnService.instance
        val prefs = Prefs(ctx)
        val (short, fps) = qualities.getOrElse(prefs.recQuality) { qualities[0] }
        val m = ctx.resources.displayMetrics
        val real = android.graphics.Point().also {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(WindowManager::class.java)).defaultDisplay.getRealSize(it)
        }
        val (rw, rh) = maxOf(real.x, real.y) to minOf(real.x, real.y)
        val scale = minOf(1f, short.toFloat() / rh)
        val w = ((rw * scale) / 16).toInt() * 16
        val h = ((rh * scale) / 16).toInt() * 16
        val landscape = real.x >= real.y
        val bitrate = when { short >= 1080 && fps >= 60 -> 16_000_000; short >= 1080 -> 10_000_000; fps >= 60 -> 8_000_000; else -> 5_000_000 }
        val r = runCatching {
            Recorder(ctx, projection, if (landscape) w else h, if (landscape) h else w, m.densityDpi, fps, bitrate,
                Audio.values().getOrElse(prefs.recAudio) { Audio.GAME }, if (prefs.replayOn) prefs.replaySec else 0)
        }.getOrNull()
        if (r == null) {
            runCatching { projection.stop() }
            ctx.stopService(Intent(ctx, CaptureService::class.java))
            svc?.let { Notice.show(it, t("Aufnahme fehlgeschlagen", "Recording failed")) }
            return
        }
        recorder = r
        val what = pending
        pending = null
        svc?.ui?.post {
            when (what) {
                "record" -> beginFile(svc, r)
                "replay" -> Notice.show(svc, t("Letzte ${prefs.replaySec} s werden mitgeschnitten", "Keeping the last ${prefs.replaySec} s"), corner = true)
            }
        }
    }

    fun failed(ctx: Context) {
        pending = null
        HugminnService.instance?.let { Notice.show(it, t("Bildschirmaufnahme nicht erlaubt", "Screen recording not allowed")) }
    }

    private fun stopEngine(ctx: Context) {
        val r = recorder ?: return
        recorder = null
        Thread { r.release() }.start()
        ctx.stopService(Intent(ctx, CaptureService::class.java))
    }

    /** The projection ended from outside (e.g. Android's cast icon): clean up. */
    fun stoppedFromOutside(ctx: Context) {
        val svc = HugminnService.instance
        if (recording) svc?.let { stopRecord(it) }
        svc?.let { Indicator.hide(it) }
        stopEngine(ctx)
    }

    fun openLast(ctx: Context) {
        val uri = lastUri ?: return
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (lastIsVideo) "video/mp4" else "image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    /** Small red dot with the recording time in the top right corner. */
    object Indicator {
        private var view: TextView? = null
        private val tick = object : Runnable {
            override fun run() {
                val v = view ?: return
                v.text = "● " + elapsed()
                v.postDelayed(this, 1000)
            }
        }

        fun show(svc: HugminnService) = svc.ui.post {
            if (view != null || svc.prefs.recShow !in listOf(1, 2)) return@post
            val wm = svc.getSystemService(WindowManager::class.java)
            val v = TextView(svc).apply {
                setTextColor(0xFFFF4D57.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(svc.dp(10), svc.dp(4), svc.dp(10), svc.dp(4))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xB0000000.toInt()); cornerRadius = svc.dp(12).toFloat() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; x = svc.dp(12); y = svc.dp(12) }
            if (runCatching { wm.addView(v, lp) }.isSuccess) { view = v; tick.run() }
        }

        fun hide(svc: HugminnService) = svc.ui.post {
            view?.let { v -> v.removeCallbacks(tick); runCatching { svc.getSystemService(WindowManager::class.java).removeViewImmediate(v) } }
            view = null
        }
    }
}

/** Asks Android for the screen capture (with root it no longer asks the user) and hands it to the service. */
class ProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // sound needs the microphone permission (also for the game sound); without root Android asks the person
        val wantsSound = Prefs(this).recAudio != Capture.Audio.NONE.ordinal
        if (wantsSound && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        else askCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED)
            HugminnService.instance?.let { Notice.show(it, t("Ohne Mikrofon-Erlaubnis: Video ohne Ton", "Without microphone permission: video without sound")) }
        askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(mpm.createScreenCaptureIntent(), 1) }.onFailure { Capture.failed(this); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java).putExtra("code", resultCode).putExtra("data", data)
            runCatching { startForegroundService(i) }.onFailure { Capture.failed(this) }
        } else Capture.failed(this)
        finish()
        overridePendingTransition(0, 0)
    }
}

/** Keeps the screen capture alive (Android requires a foreground service with a notification for it). */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", t("Aufnahme", "Capture"), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "capture")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(t("Hugminn nimmt auf", "Hugminn is recording"))
            .setContentText(t("Bildschirmaufnahme aktiv", "Screen capture active"))
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, n)
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || Capture.recorder != null) return START_NOT_STICKY
        val mp = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull()
        if (mp == null) { Capture.failed(this); stopSelf(); return START_NOT_STICKY }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (Capture.recorder != null) Capture.stoppedFromOutside(this@CaptureService) }
        }, android.os.Handler(mainLooper))
        Capture.started(this, mp)
        return START_NOT_STICKY
    }
}

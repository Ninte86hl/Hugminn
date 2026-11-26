package io.github.ninte86hl.hugminn

import android.content.Context
import android.content.Intent

/**
 * A monitor or TV on USB-C (Retroid "Connect TV"): whether one is connected, and the switches of
 * Retroid's own settings page. Retroid announces plugging in and out by broadcast.
 */
object ExternalDisplay {
    @Volatile var connected = false
        private set

    /** Retroid's own settings for video output (1 = on). */
    val switches get() = listOf(
        "no_auto_sleep_when_output_video" to t("Kein Standby", "No auto sleep"),
        "force_landscape_when_output_video" to t("Querformat erzwingen", "Force landscape"),
        "close_screen_when_output_video" to t("Handheld-Bildschirm aus", "Handheld screen off"),
    )

    /** Reads the state once (service start), then follows Retroid's broadcasts. */
    fun watch(svc: HugminnService) {
        Thread { refresh() }.start()
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.hasExtra("is_connected")) connected = i.getBooleanExtra("is_connected", false)
                else Thread { refresh() }.start()
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction("com.ro.action.HDMI_OR_DP_STATUS_CHANGED")
            addAction("com.ro.action.DISPLAY_PORT_STATUS_CHANGED")
            addAction("com.ro.action.HDMI_STATUS_CHANGED")
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) svc.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else svc.registerReceiver(r, f)
        }
    }

    fun refresh() {
        if (!Root.available()) return
        val out = Root.exec("getprop sys.dp.isconnect; getprop persist.sys.hdmiisconnect") ?: return
        connected = out.lines().any { it.trim() == "1" || it.trim().equals("true", true) }
    }

    /** For feedback logs: everything the video output depends on (to learn what Retroid writes for each resolution). */
    fun describe(): String {
        if (!Root.available()) return "Externer Bildschirm: kein Root"
        return "Externer Bildschirm (verbunden=$connected):\n" + (Root.execLong(
            "getprop | grep -iE 'dp|hdmi|videomode|mirror|rds|secondary_off'; " +
                "settings list system | grep -iE 'output|dp_|hdmi|extern'; " +
                "for f in /sys/class/drm/*/edid_modes /sys/class/drm_dp/edid_modes /sys/class/dp_control/dp_state/state; do [ -e \$f ] && echo \"\$f: \$(cat \$f | head -c 600)\"; done; " +
                "ls -d /sys/class/drm*/ 2>/dev/null | head -20"
        ) ?: "-")
    }

    /** Retroid's equalizer (Handheld Settings), if this device has it. */
    fun equalizerIntent(ctx: Context): Intent? = Intent("action_equalizer_config").takeIf {
        it.resolveActivity(ctx.packageManager) != null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Retroid's Connect TV page (resolution) – until Hugminn sets it itself. */
    fun settingsIntent(ctx: Context): Intent? = ctx.packageManager.getLaunchIntentForPackage("com.rp.settings")
}

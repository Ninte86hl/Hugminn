package io.github.ninte86hl.hugminn

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** App shortcuts (long press on the Hugminn icon, or placed on the home screen / in a frontend). */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cls = intent?.component?.className.orEmpty()
        val switcher = intent?.action?.endsWith("SWITCHER") == true || cls.endsWith("SwitcherApp") || cls.endsWith("SwitcherShortcut")
        // a launcher asks for a shortcut ("Add shortcut"): hand it over instead of opening anything
        if (intent?.action == Intent.ACTION_CREATE_SHORTCUT) {
            val info = OpenFromOutside.info(this, switcher)
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
            setResult(RESULT_OK, sm?.createShortcutResultIntent(info))
            finish()
            return
        }
        val action = if (switcher) Action.SWITCHER else Action.CONTROL_CENTER
        finish()
        overridePendingTransition(0, 0)
        OpenFromOutside.open(this, action, closeShade = false)
    }
}

object OpenFromOutside {
    /** Asks the launcher to place a shortcut on the home screen. */
    fun pin(ctx: android.content.Context, switcher: Boolean): Boolean {
        val sm = ctx.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        return runCatching { sm.requestPinShortcut(info(ctx, switcher), null) }.getOrDefault(false)
    }

    fun info(ctx: android.content.Context, switcher: Boolean): android.content.pm.ShortcutInfo =
        android.content.pm.ShortcutInfo.Builder(ctx, if (switcher) "muninn" else "huginn")
            .setShortLabel(ctx.getString(if (switcher) R.string.sc_switcher else R.string.sc_cc))
            .setIcon(icon(ctx, switcher))
            .setIntent(Intent("io.github.ninte86hl.hugminn." + if (switcher) "SWITCHER" else "CONTROL_CENTER").setClass(ctx, ShortcutActivity::class.java))
            .build()

    /** The red (Control Center) or blue (switcher) icon as a picture: some launchers such as Cocoon show
     *  the app's own icon for a shortcut whose icon is only a resource. */
    private fun icon(ctx: android.content.Context, switcher: Boolean): android.graphics.drawable.Icon {
        val res = if (switcher) R.mipmap.ic_shortcut_switcher else R.mipmap.ic_shortcut_cc
        val dr = ctx.getDrawable(res) as? android.graphics.drawable.AdaptiveIconDrawable
            ?: return android.graphics.drawable.Icon.createWithResource(ctx, res)
        val size = (108 * ctx.resources.displayMetrics.density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        listOfNotNull(dr.background, dr.foreground).forEach { it.setBounds(0, 0, size, size); it.draw(c) }
        return android.graphics.drawable.Icon.createWithAdaptiveBitmap(bmp)
    }

    /** The extra app icons "Hugminn Control Center" / "Hugminn Spielwechsler" for frontends that only list apps. */
    fun appIcons(ctx: android.content.Context): Boolean = ctx.packageManager.getComponentEnabledSetting(
        android.content.ComponentName(ctx, "io.github.ninte86hl.hugminn.CcApp")) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setAppIcons(ctx: android.content.Context, on: Boolean) {
        val st = if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (n in listOf("CcApp", "SwitcherApp"))
            ctx.packageManager.setComponentEnabledSetting(android.content.ComponentName(ctx, "io.github.ninte86hl.hugminn.$n"), st, android.content.pm.PackageManager.DONT_KILL_APP)
    }

    /** Opens an overlay from outside; without the running service, Hugminn's settings open instead. */
    fun open(ctx: android.content.Context, action: Action, closeShade: Boolean) {
        val svc = HugminnService.instance
        if (svc == null) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (closeShade && android.os.Build.VERSION.SDK_INT >= 31)
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        // let the shade or the shortcut's activity go away first, so the overlay gets the focus
        svc.ui.postDelayed({ if (action == Action.CONTROL_CENTER && svc.controlCenterOpen()) return@postDelayed; svc.perform(action) }, if (closeShade) 350 else 200)
    }
}

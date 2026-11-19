package io.github.ninte86hl.hugminn

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** App shortcuts (long press on the Hugminn icon, or placed on the home screen / in a frontend). */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = if (intent?.action?.endsWith("SWITCHER") == true) Action.SWITCHER else Action.CONTROL_CENTER
        finish()
        overridePendingTransition(0, 0)
        OpenFromOutside.open(this, action, closeShade = false)
    }
}

object OpenFromOutside {
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

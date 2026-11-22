package io.github.ninte86hl.hugminn

import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Landscape for apps that insist on portrait (YouTube's browse pages): while such an app is in front
 * on the top screen, a tiny invisible window of Hugminn asks Android for landscape – the way rotation
 * apps do it. Android then turns the whole app sideways, not only full-screen videos. Gone as soon as
 * another app is in front. No root, nothing else on the device changes.
 */
class Landscape(private val svc: HugminnService) {

    companion object {
        @Volatile var diag = ""
    }

    private var view: View? = null

    /** Called whenever the app in front may have changed. */
    fun check() {
        val pkg = svc.topScreenApp()
        val want = svc.prefs.landscapeOn && pkg != null && pkg in svc.prefs.landscapeApps
        if (want && view == null) show(pkg!!) else if (!want && view != null) hide()
    }

    private fun show(pkg: String) {
        val v = View(svc)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        runCatching {
            svc.getSystemService(WindowManager::class.java).addView(v, lp)
            view = v
            diag = "quer erzwungen für $pkg"
        }.onFailure { diag = "quer: ${it.javaClass.simpleName}"; Crash.note(svc, it) }
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { svc.getSystemService(WindowManager::class.java).removeViewImmediate(v) }
        diag = "quer aus"
    }
}

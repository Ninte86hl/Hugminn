package io.github.ninte86hl.hugminn

import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** A short message on top of everything, also above the Control Center (Android's toasts end up below it). */
object Notice {
    private var view: View? = null

    fun show(svc: HugminnService, text: String, corner: Boolean = false) = svc.ui.post {
        val wm = svc.getSystemService(WindowManager::class.java)
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        val pixel = Look.pixel(svc)
        val v = TextView(svc).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Look.font(svc, pixel, true)
            setPadding(svc.dp(20), svc.dp(10), svc.dp(20), svc.dp(10))
            background = Look.shape(svc, pixel, 0xE6202124.toInt(), svc.dp(22).toFloat())
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // corner: top right, out of the game's way; otherwise bottom centre
            if (corner) { gravity = Gravity.TOP or Gravity.END; x = svc.dp(20); y = svc.dp(20) }
            else { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = svc.dp(90) }
        }
        if (runCatching { wm.addView(v, lp) }.isFailure) return@post
        view = v
        v.animate().alpha(1f).setDuration(120).start()
        svc.ui.postDelayed({
            v.animate().alpha(0f).setDuration(200).withEndAction {
                runCatching { wm.removeViewImmediate(v) }
                if (view === v) view = null
            }.start()
        }, if (corner) 3000 else 1800)
    }
}

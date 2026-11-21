package io.github.ninte86hl.hugminn

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Test builds only: a small feedback button that always floats at the right edge. One tap sends a
 * report with screenshots, without a text. Drag it up or down if it is in the way. Release builds
 * have no Feedback class, so the button simply does not exist there.
 */
@SuppressLint("StaticFieldLeak")
object FeedbackBubble {
    private var view: View? = null

    private fun report(svc: HugminnService): (() -> Unit)? = runCatching {
        val c = Class.forName("io.github.ninte86hl.hugminn.Feedback")
        val inst = c.getField("INSTANCE").get(null)
        val m = c.getMethod("capture", HugminnService::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val go: () -> Unit = {
            // the window list in the background first, then the report
            Displays.snapshot(svc, svc, "Knopf")
            svc.ui.postDelayed({ runCatching { m.invoke(inst, svc, "Knopf", true) } }, 2500)
        }
        go
    }.getOrNull()

    @SuppressLint("ClickableViewAccessibility")
    fun start(svc: HugminnService) {
        if (view != null) return
        val go = report(svc) ?: return
        val pal = Palette(svc)
        val px = Look.pixel(svc)
        val size = svc.dp(44)
        val v = FrameLayout(svc).apply {
            background = Look.shape(svc, px, pal.surfaceHigh, size / 2f, svc.dp(1), pal.primary)
            alpha = 0.75f
            addView(IconView(svc, IconView.Kind.CAMERA, 20).styled(pal, px).apply { setColor(pal.onSurface) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = svc.dp(6)
            y = svc.prefs.bubbleY
        }
        val wm = svc.getSystemService(WindowManager::class.java)
        var downY = 0f
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > svc.dp(8)) moved = true
                    if (moved) { lp.y = startY + dy; runCatching { wm.updateViewLayout(v, lp) } }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) svc.prefs.bubbleY = lp.y
                    else {
                        v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
                        runCatching { go() }.onFailure { Crash.note(svc, it) }
                    }
                }
            }
            true
        }
        runCatching { wm.addView(v, lp); view = v }.onFailure { Crash.note(svc, it) }
    }

    fun stop(svc: HugminnService) {
        val v = view ?: return
        view = null
        runCatching { svc.getSystemService(WindowManager::class.java).removeViewImmediate(v) }
    }
}

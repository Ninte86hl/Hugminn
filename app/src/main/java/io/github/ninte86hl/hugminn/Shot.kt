package io.github.ninte86hl.hugminn

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.view.Display

/**
 * Screenshot with Hugminn's overlays (Select while the Control Center or the switcher is open).
 * Android's own key combination does not work while an overlay has the buttons, so Hugminn takes the
 * picture itself and saves it to Pictures/Screenshots.
 */
object Shot {
    @Volatile private var busy = false
    /** When Hugminn itself last saved a picture (so the system screenshot notice does not show twice). */
    @Volatile var savedAt = 0L

    fun take(svc: HugminnService) {
        if (busy) return
        busy = true
        savedAt = System.currentTimeMillis()
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bmp == null) { done(svc, false); return }
                Thread {
                    val ok = runCatching { save(svc, bmp) }.getOrDefault(false)
                    bmp.recycle()
                    svc.ui.post { done(svc, ok) }
                }.start()
            }

            override fun onFailure(errorCode: Int) = done(svc, false)
        })
    }

    private fun save(svc: HugminnService, bmp: Bitmap): Boolean {
        val name = "Hugminn_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = svc.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return true
    }

    private fun done(svc: HugminnService, ok: Boolean) {
        busy = false
        if (ok) savedAt = System.currentTimeMillis()
        Notice.show(svc, if (ok) "Screenshot gespeichert" else "Screenshot fehlgeschlagen")
    }
}

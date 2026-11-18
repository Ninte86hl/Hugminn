package io.github.ninte86hl.hugminn

import android.service.notification.NotificationListenerService

/**
 * Lets Android show Hugminn the media sessions (song, artist, cover, position) for the music tile.
 * Hugminn switches it on itself through the root service. Only the playback notification of the
 * music app with the active session is looked at, for players that publish nothing else.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    companion object {
        @Volatile var instance: MediaListener? = null

        /** Title, text and picture of [pkg]'s ongoing notification (its player), if any. */
        fun playerNotice(pkg: String): Triple<String?, String?, android.graphics.Bitmap?>? {
            val l = instance ?: return null
            val n = runCatching { l.activeNotifications }.getOrNull()?.firstOrNull { it.packageName == pkg && it.isOngoing } ?: return null
            val x = n.notification.extras
            val title = x.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            val text = x.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            val pic = runCatching {
                (x.getParcelable(android.app.Notification.EXTRA_LARGE_ICON_BIG) as? android.graphics.Bitmap)
                    ?: (n.notification.getLargeIcon()?.loadDrawable(l) as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }.getOrNull()
            return Triple(title, text, pic)
        }
    }
}

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

        /**
         * Media sessions that players attach to their notification (what Android's own media
         * notification shows); some players publish their song only there.
         */
        fun notificationSessions(ctx: android.content.Context): List<android.media.session.MediaController> {
            val l = instance ?: return emptyList()
            return runCatching { l.activeNotifications }.getOrNull().orEmpty().mapNotNull { n ->
                val token = runCatching {
                    n.notification.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)
                }.getOrNull() ?: return@mapNotNull null
                runCatching { android.media.session.MediaController(ctx, token) }.getOrNull()
            }
        }

        /** Title, text and picture of [pkg]'s player notification, if any. */
        fun playerNotice(pkg: String): Triple<String?, String?, android.graphics.Bitmap?>? {
            val l = instance ?: return null
            val all = runCatching { l.activeNotifications }.getOrNull().orEmpty().filter { it.packageName == pkg }
            val n = all.firstOrNull { it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) }
                ?: all.firstOrNull { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) != null } ?: return null
            val x = n.notification.extras
            val title = x.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            val text = x.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            val pic = runCatching {
                (x.getParcelable(android.app.Notification.EXTRA_LARGE_ICON_BIG) as? android.graphics.Bitmap)
                    ?: (n.notification.getLargeIcon()?.loadDrawable(l) as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }.getOrNull()
            if (title != null) return Triple(title, text, pic)
            // old players draw their own notification layout (no title in the extras): read its texts
            val rv = n.notification.bigContentView ?: n.notification.contentView ?: return Triple(null, null, pic)
            val texts = mutableListOf<String>()
            var img: android.graphics.Bitmap? = null
            val done = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val v = rv.apply(l, android.widget.FrameLayout(l))
                    fun walk(x: android.view.View) {
                        when (x) {
                            is android.widget.TextView -> x.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
                            is android.widget.ImageView -> if (img == null) (x.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                ?.takeIf { it.width >= 64 && it.height >= 64 }?.let { img = it }
                        }
                        if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
                    }
                    walk(v)
                }
                done.countDown()
            }
            done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            return Triple(texts.getOrNull(0), texts.getOrNull(1), pic ?: img)
        }
    }
}

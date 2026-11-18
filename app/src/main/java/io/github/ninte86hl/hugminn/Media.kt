package io.github.ninte86hl.hugminn

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Music in the background: play/pause, next, previous as media keys (works with every player),
 * and what is playing from Android's media sessions (needs the root service).
 */
object Media {
    @Volatile var title: String? = null
        private set
    @Volatile var app: String? = null
        private set
    @Volatile var playing = false
        private set
    /** Song and artist separately (only from media sessions, else null). */
    @Volatile var song: String? = null
        private set
    @Volatile var artist: String? = null
        private set
    @Volatile var art: android.graphics.Bitmap? = null
        private set
    /** Length and position in ms (-1 = unknown); position at [positionAt] (uptime), moving while playing. */
    @Volatile var duration = -1L
        private set
    @Volatile private var position = -1L
    @Volatile private var positionAt = 0L
    @Volatile private var speed = 1f

    /** Current position in ms, or -1. */
    fun positionNow(): Long = if (position < 0) -1 else
        (position + if (playing) ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong() else 0L).let { if (duration > 0) it.coerceAtMost(duration) else it }

    /** What the last refresh saw (test builds report it). */
    @Volatile var diag = ""

    private fun component(ctx: Context) = android.content.ComponentName(ctx, MediaListener::class.java)

    /** Lets Hugminn read the media sessions (once, through the root service). */
    fun enableListener(ctx: Context) {
        val c = component(ctx).flattenToString()
        val on = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        listenerInfo = if (on.contains(c)) "an" else "aus → allow_listener: " + (Root.exec("cmd notification allow_listener $c 2>&1")?.trim()?.ifEmpty { "ok" } ?: "keine Antwort")
    }

    /** State of the media listener when it was last checked (test builds report it). */
    @Volatile var listenerInfo = "nicht geprüft"

    /** From Android's media sessions (needs [MediaListener] enabled); false if not available. */
    private fun fromSessions(ctx: Context): Boolean {
        val msm = ctx.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return false
        val res = runCatching { msm.getActiveSessions(component(ctx)) }
        val active = res.getOrNull()
        if (active == null) { diag = "Sessions: nicht erlaubt (${res.exceptionOrNull()?.javaClass?.simpleName})"; return false }
        // the listener should be connected; if Android has not bound it yet, ask for it
        if (MediaListener.instance == null) runCatching { android.service.notification.NotificationListenerService.requestRebind(component(ctx)) }
        // sessions from player notifications first: they are the ones Android's media notification shows
        val fromNotes = MediaListener.notificationSessions(ctx)
        val list = (fromNotes + active).distinctBy { it.sessionToken }
        diag = "Listener verbunden=${MediaListener.instance != null} · aus Benachrichtigungen ${fromNotes.size}\n"
        diag += "Sessions: " + list.joinToString(" | ") { m ->
            "${m.packageName} state=${m.playbackState?.state} title=${m.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)} art=${m.metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) != null}"
        }.ifEmpty { "keine" }
        val mc = list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null } ?: list.firstOrNull()
        val md = mc?.metadata
        val st = mc?.playbackState
        playing = st?.state == android.media.session.PlaybackState.STATE_PLAYING
        app = mc?.packageName
        song = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        artist = (md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
        art = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
        duration = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1L
        position = st?.position?.takeIf { it >= 0 } ?: -1L
        positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        // players that only open a session (e.g. Android's own music app): sound and their notification
        if (mc != null && st == null) playing = ctx.getSystemService(AudioManager::class.java)?.isMusicActive == true
        if (mc != null && song == null) MediaListener.playerNotice(mc.packageName)?.let { (t, x, pic) ->
            song = t; artist = x; if (art == null) art = pic
            diag += " · Benachrichtigung: $t / $x / Bild=${pic != null}"
        } ?: run { if (mc != null && song == null) diag += " · keine Benachrichtigung gefunden" }
        title = listOfNotNull(song, artist).joinToString(" – ").ifBlank { null }
        return true
    }

    fun key(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Reads the media sessions; the one playing wins, else the newest with a title. */
    fun refresh(ctx: Context? = null) {
        if (ctx != null && fromSessions(ctx)) return
        if (!Root.available()) return
        // not allowed yet (e.g. right after an update): switch the listener on and try again
        if (ctx != null) {
            runCatching { enableListener(ctx) }
            Thread.sleep(300)
            if (fromSessions(ctx)) return
        }
        song = null; artist = null; art = null; duration = -1L; position = -1L
        val out = Root.execLong("dumpsys media_session | grep -E 'package=|state=PlaybackState|description='", 3_000) ?: return
        class S(val pkg: String) { var state = 0; var desc: String? = null }
        val list = mutableListOf<S>()
        out.lines().forEach { l ->
            val t = l.trim()
            when {
                t.startsWith("package=") -> list += S(t.removePrefix("package="))
                t.startsWith("state=PlaybackState") -> list.lastOrNull()?.state = Regex("state=(\\d+)").find(t.removePrefix("state=PlaybackState"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                t.contains("description=") -> list.lastOrNull()?.desc = t.substringAfter("description=").trim().takeIf { it.isNotEmpty() && it != "null, null, null" }
            }
        }
        diag += "\nDumpsys: " + list.joinToString(" | ") { "${it.pkg} state=${it.state} desc=${it.desc}" }.ifEmpty { "keine" }
        val s = list.firstOrNull { it.state == 3 } ?: list.firstOrNull { it.desc != null } ?: list.firstOrNull()
        playing = s?.state == 3
        app = s?.pkg
        // "Song, Artist, Album" → "Song – Artist"
        title = s?.desc?.split(", ")?.filter { it.isNotBlank() && it != "null" }?.take(2)?.joinToString(" – ")?.takeIf { it.isNotBlank() }
    }
}

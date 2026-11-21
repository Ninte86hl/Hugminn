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
    /** The player takes jumps (seekTo) – the progress bar on the second screen only works then. */
    @Volatile var canSeek = false
        private set
    @Volatile private var position = -1L
    @Volatile private var positionAt = 0L
    @Volatile private var speed = 1f

    /** Current position in ms, or -1. */
    fun positionNow(): Long = if (position < 0) -1 else
        (position + if (playing) ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong() else 0L).let { if (duration > 0) it.coerceAtMost(duration) else it }

    /** What the last refresh saw (test builds report it). */
    @Volatile var diag = ""

    private val noise = Regex(
        """\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv|hd|hq|4k|remaster(ed)?|explicit|clip officiel|offizielles?)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    private fun channel(a: String) = a.replace(Regex("""\s*(-\s*topic|vevo|official|offiziell)\s*$""", RegexOption.IGNORE_CASE), "").trim()

    private fun same(a: String, b: String) = a.lowercase().filter { it.isLetterOrDigit() } == b.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Video titles as songs: "Escape The Fate - "Paranoia" (Official Video)" by the channel
     * "Escape the Fate" becomes "Paranoia" by "Escape the Fate"; "Ankor Official" becomes "Ankor".
     */
    private fun tidy(pkg: String?) {
        var t = song ?: return
        var a = artist?.let { channel(it) }
        t = t.replace(noise, "").trim()
        val video = pkg?.contains("youtube") == true || pkg?.contains("newpipe") == true
        val dash = Regex("""\s+[-–—]\s+""").find(t)
        if (dash != null) {
            val left = t.substring(0, dash.range.first).trim()
            val right = t.substring(dash.range.last + 1).trim()
            if (right.isNotEmpty() && (a == null || same(left, a) || video)) {
                // "Artist - Song": the artist part goes to the artist line (a label channel gives way to it)
                if (a == null || !same(left, a)) a = left
                t = right
            }
        }
        // quotes around the whole song
        t = t.trim().removeSurrounding("\"").removeSurrounding("“", "”").removeSurrounding("„", "“").trim()
        if (t.isNotEmpty()) song = t
        artist = a?.ifBlank { null }
    }

    private var trimmedFrom: android.graphics.Bitmap? = null
    private var trimmed: android.graphics.Bitmap? = null

    /** Video thumbnails (YouTube) come with black bars above and below: cut them off. */
    private fun trimBars(b: android.graphics.Bitmap): android.graphics.Bitmap {
        if (b === trimmedFrom) return trimmed ?: b
        fun dark(y: Int): Boolean {
            val step = (b.width / 16).coerceAtLeast(1)
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                if (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) > 60) return false
                x += step
            }
            return true
        }
        val out = runCatching {
            var top = 0
            while (top < b.height / 3 && dark(top)) top++
            var bottom = b.height - 1
            while (bottom > b.height * 2 / 3 && dark(bottom)) bottom--
            if (top > 2 || bottom < b.height - 3) android.graphics.Bitmap.createBitmap(b, 0, top, b.width, bottom - top + 1) else b
        }.getOrDefault(b)
        trimmedFrom = b; trimmed = out
        return out
    }

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
    private fun fromSessions(ctx: Context, prefer: String? = null): Boolean {
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
        val mc = list.firstOrNull { prefer != null && it.packageName == prefer && it.metadata != null }
            ?: list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null } ?: list.firstOrNull()
        val md = mc?.metadata
        val st = mc?.playbackState
        playing = st?.state == android.media.session.PlaybackState.STATE_PLAYING
        canSeek = st != null && (st.actions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
        app = mc?.packageName
        song = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        artist = (md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
        art = (md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART))?.let { trimBars(it) }
        duration = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1L
        position = st?.position?.takeIf { it >= 0 } ?: -1L
        positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        // players that only open a session (e.g. Android's own music app): sound and their notification
        if (mc != null && st == null) playing = ctx.getSystemService(AudioManager::class.java)?.isMusicActive == true
        if (mc != null && song == null) MediaListener.playerNotice(mc.packageName)?.let { (t, x, pic) ->
            // file names as titles: "02 Tell Me When You've Had Enough.mp3" → "Tell Me When You've Had Enough"
            song = t?.replace(Regex("""\.(mp3|flac|ogg|opus|m4a|aac|wav|wma)$""", RegexOption.IGNORE_CASE), "")
                ?.replace(Regex("""^\d{1,3}\s*[-._]?\s+(?=\S)"""), "")?.trim()?.ifBlank { t }
            artist = x; if (art == null) art = pic
            diag += " · Benachrichtigung: $t / $x / Bild=${pic != null}"
        } ?: run { if (mc != null && song == null) diag += " · keine Benachrichtigung gefunden" }
        tidy(mc?.packageName)
        title = listOfNotNull(song, artist).joinToString(" – ").ifBlank { null }
        return true
    }

    private fun controller(ctx: Context, pkg: String): android.media.session.MediaController? {
        val msm = ctx.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return null
        return runCatching { (MediaListener.notificationSessions(ctx) + msm.getActiveSessions(component(ctx))).firstOrNull { it.packageName == pkg } }.getOrNull()
    }

    /** One entry of the player's queue (playlist). */
    class QItem(val id: Long, val title: String, val sub: String?, val icon: android.graphics.Bitmap?)

    /** The queue the player of [pkg] shares with Android, and the id of the entry playing (-1 unknown). */
    fun queue(ctx: Context, pkg: String): Pair<List<QItem>, Long> {
        val mc = controller(ctx, pkg) ?: return emptyList<QItem>() to -1L
        val q = runCatching { mc.queue }.getOrNull().orEmpty()
        val items = q.map { qi ->
            val d = qi.description
            QItem(qi.queueId, d.title?.toString() ?: "", d.subtitle?.toString()?.takeIf { it.isNotBlank() }, d.iconBitmap)
        }
        return items to (mc.playbackState?.activeQueueItemId ?: -1L)
    }

    fun skipTo(ctx: Context, pkg: String, id: Long): Boolean =
        runCatching { controller(ctx, pkg)?.transportControls?.skipToQueueItem(id) != null }.getOrDefault(false)

    /** Jumps to [ms] in the player of [pkg] (the progress bar on the second screen). */
    fun seek(ctx: Context, pkg: String, ms: Long): Boolean {
        val mc = controller(ctx, pkg) ?: return false
        return runCatching {
            mc.transportControls.seekTo(ms)
            position = ms
            positionAt = SystemClock.elapsedRealtime()
            true
        }.getOrDefault(false)
    }

    private val playerApps = Regex("youtube|newpipe|spotify|music|deezer|tidal|soundcloud|podcast|audible|vlc|netflix|twitch|plex|jellyfin|kodi|stremio|disney|prime|crunchyroll|player|radio", RegexOption.IGNORE_CASE)

    /**
     * Music or a video plays in [pkg] right now (Spotify, YouTube & co.): Hugminn's switcher and
     * Control Center then leave it running instead of pausing it – that would only break the flow.
     */
    fun playsIn(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        val sound = ctx.getSystemService(AudioManager::class.java)?.isMusicActive == true
        if (!sound) return false
        // the session read last, or a player by its name (sessions are not read on every handheld all the time)
        return (app == pkg && playing) || playerApps.containsMatchIn(pkg)
    }

    fun key(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Reads the media sessions; the one playing wins, else the newest with a title. */
    fun refresh(ctx: Context? = null, prefer: String? = null) {
        if (ctx != null && fromSessions(ctx, prefer)) return
        if (!Root.available()) return
        // not allowed yet (e.g. right after an update): switch the listener on and try again
        if (ctx != null && Device.autoGrant(ctx)) {
            runCatching { enableListener(ctx) }
            Thread.sleep(300)
            if (fromSessions(ctx)) return
        }
        song = null; artist = null; art = null; duration = -1L; position = -1L; canSeek = false
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

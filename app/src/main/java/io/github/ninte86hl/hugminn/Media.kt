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

    fun key(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Reads the media sessions; the one playing wins, else the newest with a title. */
    fun refresh() {
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
        val s = list.firstOrNull { it.state == 3 } ?: list.firstOrNull { it.desc != null }
        playing = s?.state == 3
        app = s?.pkg
        // "Song, Artist, Album" → "Song – Artist"
        title = s?.desc?.split(", ")?.filter { it.isNotBlank() && it != "null" }?.take(2)?.joinToString(" – ")?.takeIf { it.isNotBlank() }
    }
}

package io.github.ninte86hl.hugminn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle

/**
 * An empty black screen that Hugminn's own game switcher puts over the app in front while it is open.
 * Covering the app fully stops it (like Android's own app overview does), so games and emulators pause
 * and go quiet. Taking the audio focus also silences apps that only react to that.
 */
class PauseActivity : Activity() {
    private var focus: AudioFocusRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.BLACK)
        current = this
        if (!wanted) finishQuietly()
    }

    override fun onStart() {
        super.onStart()
        val am = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am.requestAudioFocus(focus!!) }
    }

    override fun onStop() {
        focus?.let { f -> runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(f) } }
        focus = null
        super.onStop()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* the switcher handles the buttons */ }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        @Volatile private var current: PauseActivity? = null
        /** Who wants the app paused right now ("switcher", "cc"); the cover stays while anyone does. */
        private val owners = java.util.Collections.synchronizedSet(HashSet<String>())
        private val wanted get() = owners.isNotEmpty()

        /** Covers (and so pauses) the app in front. */
        fun hold(svc: HugminnService, who: String) {
            owners += who
            if (current != null) return
            runCatching {
                svc.startActivity(Intent(svc, PauseActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ), Displays.onTop(svc))
            }
        }

        /** Uncovers again, after [delayMs] (time for another app to come to the front first). */
        fun release(svc: HugminnService, who: String, delayMs: Long = 0) {
            owners -= who
            val run = { if (!wanted) current?.finishQuietly() }
            if (delayMs <= 0) svc.ui.post(run) else svc.ui.postDelayed(run, delayMs)
        }
    }
}

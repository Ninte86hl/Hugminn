package io.github.ninte86hl.hugminn

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Remembers the last crash (or caught error) so it can be shown in the settings and sent with feedback. */
object Crash {
    private const val KEY = "lastCrash"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(val ctx: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            save(ctx, "Absturz im Thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    /** A caught error: remembered (shown in the settings and added to a bug report). */
    fun note(ctx: Context, e: Throwable) {
        save(ctx, "Fehler (abgefangen)", e)
    }

    private fun save(ctx: Context, what: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
        val text = "$stamp · $what\n" + sw.toString().lines().take(30).joinToString("\n")
        ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE).edit().putString(KEY, text).commit()
    }

    fun last(ctx: Context): String? = ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE).getString(KEY, null)

    /** True if the last error has not been sent yet. */
    fun pending(ctx: Context): Boolean {
        val last = last(ctx) ?: return false
        return ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE).getInt("crashSent", 0) != last.hashCode()
    }

    fun markSent(ctx: Context) {
        val last = last(ctx) ?: return
        ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE).edit().putInt("crashSent", last.hashCode()).apply()
    }

    fun clear(ctx: Context) = ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE).edit().remove(KEY).apply()
}

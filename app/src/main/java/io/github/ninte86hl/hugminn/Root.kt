package io.github.ninte86hl.hugminn

import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Client for the firmware root service "PServerBinder" on Retroid and AYN handhelds.
 * Transaction 0, data = string array [command, wait], reply = byte array with the first output line.
 * The service only accepts short commands, so Hugminn sends one small command per call.
 */
object Root {
    private val pool = Executors.newCachedThreadPool()

    private fun binder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "PServerBinder") as? IBinder
    } catch (t: Throwable) {
        null
    }

    fun available(): Boolean = binder() != null

    /** Runs [cmd] as root and returns the first line of its output (null without root or on timeout). */
    fun exec(cmd: String, timeoutMs: Long = 4_000): String? {
        val f = pool.submit<String?> { direct(cmd) }
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            null
        }
    }

    /** Fire and forget, for settings changes from the UI thread. */
    fun run(vararg cmds: String, then: (() -> Unit)? = null) {
        pool.execute {
            cmds.forEach { exec(it) }
            then?.invoke()
        }
    }

    private fun direct(cmd: String): String? {
        val b = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            b.transact(0, data, reply, 0)
            reply.createByteArray()?.toString(Charsets.UTF_8)?.trimEnd('\n', '\r')
        } catch (t: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun putSetting(table: String, key: String, value: String) = "settings put $table $key ${quote(value)}"

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

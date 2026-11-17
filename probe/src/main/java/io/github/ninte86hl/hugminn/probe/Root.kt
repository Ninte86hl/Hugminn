package io.github.ninte86hl.hugminn.probe

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import java.io.File

/**
 * Client for the firmware's root service "PServerBinder" found on AYN and Retroid handhelds.
 * Contract (as documented by other community projects): transaction code 0, no interface token,
 * data = string array [command, wait], reply = byte array with the command's output.
 * The reply may hold only the first line, so [exec] writes the full output to a file instead.
 */
object Root {
    private const val SERVICE = "PServerBinder"
    private const val DONE = "__HUGMINN_DONE__"

    @Volatile var lastError: String? = null

    fun binder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        null
    }

    fun available(): Boolean = binder() != null

    fun rawExec(cmd: String, wait: Boolean = true): String? {
        val b = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, if (wait) "1" else "0"))
            b.transact(0, data, reply, 0)
            reply.createByteArray()?.toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Runs [cmd] as root and returns its full output (stdout + stderr), or null without root. */
    fun exec(ctx: Context, cmd: String, timeoutMs: Long = 20_000): String? {
        if (binder() == null) return null
        val dir = File(ctx.filesDir, "root").apply { mkdirs() }
        val out = File(dir, "out_${System.nanoTime()}.txt")
        val path = out.absolutePath
        val script = "( $cmd ) > '$path' 2>&1; echo $DONE >> '$path'; chmod 666 '$path'"
        rawExec("sh -c " + quote(script), wait = true)
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (out.exists()) {
                val txt = runCatching { out.readText() }.getOrNull()
                if (txt != null && txt.contains(DONE)) {
                    out.delete()
                    return txt.substringBefore(DONE).trimEnd()
                }
            }
            Thread.sleep(100)
        }
        val partial = runCatching { out.readText() }.getOrNull()
        out.delete()
        return (partial ?: "") + "\n[Zeitüberschreitung nach ${timeoutMs / 1000} s]"
    }

    /** Copies a script from the app's assets and runs it as root with optional arguments. */
    fun runAsset(ctx: Context, asset: String, args: String = "", timeoutMs: Long = 30_000): String? {
        if (binder() == null) return null
        val dir = File(ctx.filesDir, "scripts").apply { mkdirs() }
        val file = File(dir, asset)
        ctx.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        return exec(ctx, "sh '${file.absolutePath}' $args", timeoutMs)
    }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

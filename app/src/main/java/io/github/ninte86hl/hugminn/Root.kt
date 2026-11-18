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

    private val counter = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Runs [cmd] as root and returns its whole output. The service only answers with one line, so the
     * output goes to a scratch file and is read back in base64 chunks.
     */
    private val longLock = Any()

    fun execLong(cmd: String, timeoutMs: Long = 20_000): String? = synchronized(longLock) { execLongLocked(cmd, timeoutMs) }

    private fun execLongLocked(cmd: String, timeoutMs: Long): String? {
        if (!available()) return null
        val f = "/data/local/tmp/hugminn_${android.os.Process.myPid()}_${counter.incrementAndGet()}"
        val mark = "@@HUGMINN_END@@"
        exec("sh -c " + quote("($cmd)>$f 2>&1;echo $mark>>$f"), timeoutMs)
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (exec("tail -c 40 $f 2>/dev/null | tr -d '\\n'")?.contains(mark) == true) break
            Thread.sleep(80)
        }
        val size = exec("wc -c < $f 2>/dev/null")?.trim()?.toLongOrNull() ?: 0L
        val out = java.io.ByteArrayOutputStream()
        // small chunks: the service's answer gets cut off when it is too long
        val chunk = 12_000
        var off = 0L
        while (off < size) {
            val b64 = exec("tail -c +${off + 1} $f | head -c $chunk | base64 -w 0", 10_000) ?: break
            val bytes = try {
                android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT)
            } catch (t: IllegalArgumentException) {
                exec("rm -f $f")
                return null
            }
            out.write(bytes)
            off += chunk
        }
        exec("rm -f $f")
        return out.toString("UTF-8").substringBefore(mark).trimEnd()
    }

    private val scripts = HashMap<String, String>()

    /**
     * Writes [body] once to a script file in small pieces (the service only takes short commands) and
     * returns the command that runs it. Long pipelines sent directly come back empty.
     */
    /** The app's own files folder; root reads scripts from there (set by the service). */
    @Volatile var filesDir: java.io.File? = null

    fun script(name: String, body: String): String? = synchronized(scripts) {
        if (!available()) return null
        val dir = filesDir ?: return null
        val f = java.io.File(java.io.File(dir, "root-scripts").apply { mkdirs(); setExecutable(true, false); setReadable(true, false) }, "$name.sh")
        if (scripts[name] != body || !f.exists()) {
            f.writeText(body)
            f.setReadable(true, false)
            f.setExecutable(true, false)
            scripts[name] = body
        }
        "sh ${f.absolutePath}"
    }

    fun putSetting(table: String, key: String, value: String) = "settings put $table $key ${quote(value)}"

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

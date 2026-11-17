package io.github.ninte86hl.hugminn.probe

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Client for the firmware's root service "PServerBinder" found on AYN and Retroid handhelds.
 * Contract: transaction code 0, no interface token, data = string array [command, wait],
 * reply = byte array with (only) the first line of the command's output.
 *
 * Long output therefore goes through a scratch file that root itself can write (the service has no
 * access to the app's private folder) and is read back in base64 chunks, one line per call.
 */
object Root {
    private const val SERVICE = "PServerBinder"
    private const val DONE = "__HUGMINN_DONE__"

    @Volatile var lastError: String? = null
    @Volatile private var detected = false
    @Volatile var scratchDir: String? = null
        private set
    @Volatile var lineLimit: Int = 0
        private set

    fun binder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        null
    }

    fun available(): Boolean = binder() != null

    private val pool = Executors.newCachedThreadPool()

    /** One call to the service; gives up waiting after [timeoutMs] so a stuck call never blocks the app. */
    fun rawExec(cmd: String, wait: Boolean = true, timeoutMs: Long = 15_000): String? {
        val future = pool.submit<String?> { rawExecDirect(cmd, wait) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            lastError = "Zeitüberschreitung bei: " + cmd.take(80)
            null
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    private fun rawExecDirect(cmd: String, wait: Boolean): String? {
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

    /** Finds a folder root can write to and how long one reply line may be. */
    @Synchronized
    fun detect(): String {
        if (!detected && binder() != null) {
            val probe = rawExec("head -c 65536 /dev/zero | tr '\\000' 'a'")?.trimEnd('\n', '\r')
            lineLimit = probe?.length ?: 0
            val dirCmd = "for d in /data/local/tmp /dev /data/local /cache /mnt; do " +
                "if touch \$d/.hugminn 2>/dev/null; then rm -f \$d/.hugminn; echo \$d; break; fi; done"
            scratchDir = rawExec(dirCmd)?.trim()?.takeIf { it.startsWith("/") }
            detected = true
        }
        return "Zeilenlänge pro Antwort: $lineLimit Zeichen, Arbeitsordner für Root: ${scratchDir ?: "keiner gefunden"}"
    }

    private fun chunkBytes(): Int {
        val usable = minOf(lineLimit, 65536) - 8
        return maxOf(48, usable / 4 * 3)
    }

    private fun decode(b64: String?): ByteArray =
        if (b64.isNullOrBlank()) ByteArray(0) else runCatching { Base64.decode(b64.trim(), Base64.DEFAULT) }.getOrDefault(ByteArray(0))

    /** Runs [cmd] as root and returns its full output (stdout + stderr), or null without root. */
    fun exec(@Suppress("UNUSED_PARAMETER") ctx: Context, cmd: String, timeoutMs: Long = 20_000): String? {
        if (binder() == null) return null
        detect()
        val dir = scratchDir
        if (dir == null) {
            // No scratch folder: one line, base64, may be cut off by the reply limit.
            return decode(rawExec("( $cmd ) 2>&1 | base64 -w 0")).toString(Charsets.UTF_8) +
                "\n[ohne Arbeitsordner gelesen, evtl. gekürzt]"
        }
        val f = "$dir/hugminn_${System.nanoTime()}.out"
        rawExec("sh -c " + quote("( $cmd ) > $f 2>&1; echo $DONE >> $f"), wait = true, timeoutMs = timeoutMs)
        return collect(f, timeoutMs)
    }

    /** Starts [cmd] in the background as root; returns the output file to [collect] later, or null. */
    fun startBackground(cmd: String): String? {
        if (binder() == null) return null
        detect()
        val dir = scratchDir ?: return null
        val f = "$dir/hugminn_${System.nanoTime()}.out"
        val inner = "( $cmd ) > $f 2>&1; echo $DONE >> $f"
        rawExec("setsid sh -c " + quote(inner) + " < /dev/null > /dev/null 2>&1 &", wait = true, timeoutMs = 3_000)
        return f
    }

    /** Waits until the command writing [f] is done, reads the file in chunks and deletes it. */
    fun collect(f: String, timeoutMs: Long): String {
        val until = System.currentTimeMillis() + timeoutMs
        var finished = false
        while (System.currentTimeMillis() < until) {
            val tail = rawExec("tail -c 40 $f 2>/dev/null | tr -d '\\n'") ?: ""
            if (tail.contains(DONE)) {
                finished = true
                break
            }
            Thread.sleep(150)
        }
        val size = rawExec("wc -c < $f 2>/dev/null")?.trim()?.toLongOrNull() ?: 0L
        val out = ByteArrayOutputStream()
        val chunk = chunkBytes()
        var off = 0L
        while (off < size) {
            out.write(decode(rawExec("tail -c +${off + 1} $f | head -c $chunk | base64 -w 0")))
            off += chunk
        }
        rawExec("rm -f $f")
        val text = out.toString("UTF-8").substringBefore(DONE).trimEnd()
        return if (finished) text else "$text\n[Zeitüberschreitung nach ${timeoutMs / 1000} s]"
    }

    /** Runs a script from the app's assets as root; the script travels inline, so root needs no access to app files. */
    fun runAsset(ctx: Context, asset: String, args: String = "", timeoutMs: Long = 30_000): String? {
        if (binder() == null) return null
        val script = ctx.assets.open(asset).use { it.readBytes() }
        val b64 = Base64.encodeToString(script, Base64.NO_WRAP)
        return exec(ctx, "echo $b64 | base64 -d | sh -s -- $args", timeoutMs)
    }

    /** Stops a long-running command (e.g. getevent) by process name. */
    fun kill(name: String) {
        rawExec("pkill -x $name; true")
    }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

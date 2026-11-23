package io.github.ninte86hl.hugminn

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Anbernic without root: Android lets no app write the maker's own settings (cpu_mode, fan_mode,
 * led_effect …). A small helper, started once per boot through Anbernic's "Root Scripts", carries
 * out Hugminn's wishes – only from a fixed list of settings and only numbers.
 *
 * Like ROCKNIX: everything lives in its own folder, Download/Hugminn. Anbernic runs an imported
 * script line by line (no real shell), so the imported one only starts helper.sh from that folder
 * with "sh". Hugminn → helper: lines "key value" in Download/Hugminn/cmd. Helper → Hugminn: system
 * settings hugminn_helper_alive (a heartbeat) and hugminn_helper_diag (what it found).
 */
object AnbernicHelper {
    /** Settings the helper accepts (everything else is ignored by the helper too). */
    val allowed = setOf("cpu_mode", "fan_mode", "custom_fan_speed", "led_enable", "led_effect", "trigger_mode", "trigger_type", "abxy_mode")

    /** With "all files access": its own folder "hugminn" in internal storage; otherwise Download/Hugminn. */
    @Suppress("DEPRECATION")
    fun dir(): File = (if (Environment.isExternalStorageManager()) File(Environment.getExternalStorageDirectory(), "hugminn")
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hugminn")).also { it.mkdirs() }

    /** The same folder as root sees it (for the scripts). */
    private fun rootPath(): String = if (Environment.isExternalStorageManager()) "/sdcard/hugminn" else "/sdcard/Download/Hugminn"

    private fun read(name: String) = runCatching { File(dir(), name).readText().trim() }.getOrNull()

    /** Helper alive: its heartbeat (seconds, in the file "alive") is at most 30 s old. */
    fun alive(ctx: Context): Boolean {
        val t = read("alive")?.toLongOrNull() ?: return false
        return System.currentTimeMillis() / 1000 - t < 30
    }

    fun put(ctx: Context, key: String, value: String): Boolean {
        if (key !in allowed || !value.all { it.isDigit() }) { trace("refused $key=$value"); return false }
        val d = dir()
        // tidy up wishes the helper has long done (it may not delete them itself)
        d.listFiles { f -> f.name.startsWith("cmd-") && System.currentTimeMillis() - f.lastModified() > 10_000 }?.forEach { it.delete() }
        val r = runCatching {
            val name = "cmd-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}"
            // written whole first, then renamed: the helper never sees half a wish
            val tmp = File(d, ".$name")
            tmp.writeText("$key $value\n")
            if (!tmp.renameTo(File(d, name))) error("rename failed")
        }
        trace("$key=$value " + (r.exceptionOrNull()?.let { "FAILED ${it.javaClass.simpleName}: ${it.message}" } ?: "→ cmd file"))
        return r.isSuccess
    }

    /** Hugminn's side of the conversation, for the device test ("sent" in the folder). */
    fun trace(s: String) {
        runCatching {
            val f = File(dir(), "sent")
            if (f.length() > 20_000) f.writeText("")
            f.appendText(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()) + " " + s + "\n")
        }
    }

    /** For the device test. */
    fun report(ctx: Context): String {
        val diag = read("log")?.lines()?.takeLast(4)?.joinToString(" / ") ?: "no log"
        val sent = read("sent")?.lines()?.takeLast(4)?.joinToString(" / ") ?: "nothing sent"
        val beat = read("alive")?.toLongOrNull()?.let { "${System.currentTimeMillis() / 1000 - it} s ago" } ?: "none"
        val files = dir().listFiles()?.joinToString { it.name + (if (it.name.startsWith("id")) "=" + runCatching { it.readText().trim().take(80) }.getOrDefault("?") else "") } ?: "–"
        return "note: $diag · sent: $sent · heartbeat: $beat · folder: $files".take(900)
    }

    /** Writes helper.sh and the script to import into Download/Hugminn; returns the folder. */
    fun install(pkg: String): File {
        val d = dir()
        File(d, "helper.sh").writeText(helper(pkg))
        File(d, "hugminn-anbernic.sh").writeText(launcher())
        File(d, "hugminn-test.sh").delete()
        File(d, "id.txt").delete(); File(d, "id2.txt").delete()
        return d
    }

    /** What Anbernic imports: single plain commands only (it runs them one by one). */
    private fun launcher() = """
sh ${rootPath()}/helper.sh
""".trimStart()

    private fun helper(pkg: String): String = """
#!/system/bin/sh
# Hugminn helper for Anbernic: performance, fan and lighting without root.
# Accepts only these settings, only numbers: ${allowed.joinToString(" ")}
P=$pkg
D=${'$'}(dirname "${'$'}0")
note() { echo "${'$'}(date +%H:%M:%S) ${'$'}*" >> "${'$'}D/log"; chmod 666 "${'$'}D/log"; }
beat() { date +%s > "${'$'}D/alive"; chmod 666 "${'$'}D/alive"; }
# already running from the other path: nothing to do
if [ "${'$'}1" != "loop" ]; then
  pkill -f "helper.sh loop" 2>/dev/null
  note "start uid=${'$'}(id -u) folder=${'$'}D low=${'$'}([ -d /data/media/0/${'$'}(basename "${'$'}D") ] && echo ok)"
  beat
  dumpsys deviceidle whitelist +${'$'}P >/dev/null 2>&1
  setsid sh "${'$'}0" loop </dev/null >/dev/null 2>&1 &
  exit 0
fi
# every wish is its own file cmd-<time> from Hugminn; the helper only reads them (Hugminn tidies up)
seen=" "
n=100
while true; do
  now=" "
  for c in "${'$'}D"/cmd-*; do
    [ -f "${'$'}c" ] || continue
    b=${'$'}(basename "${'$'}c"); now="${'$'}now${'$'}b "
    case "${'$'}seen" in *" ${'$'}b "*) continue ;; esac
    [ -r "${'$'}c" ] || { note "cannot read ${'$'}b"; continue; }
    cat "${'$'}c" 2>/dev/null | while read k v; do
      case "${'$'}k" in ${allowed.joinToString("|")}) ;; *) note "ignored ${'$'}k"; continue ;; esac
      case "${'$'}v" in ''|*[!0-9]*) note "ignored ${'$'}k=${'$'}v"; continue ;; esac
      if settings put system "${'$'}k" "${'$'}v"; then note "${'$'}k=${'$'}v"; else note "failed ${'$'}k=${'$'}v"; fi
    done
    rm -f "${'$'}c" 2>/dev/null
  done
  seen="${'$'}now"
  n=${'$'}((n+1))
  # heartbeat every ~9 s
  if [ ${'$'}n -ge 30 ]; then n=0; beat; fi
  sleep 0.3
done
""".trimStart()
}

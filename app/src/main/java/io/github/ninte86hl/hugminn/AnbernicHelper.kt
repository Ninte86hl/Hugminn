package io.github.ninte86hl.hugminn

import android.content.Context
import java.io.File

/**
 * Anbernic without root: Android lets no app write the maker's own settings (cpu_mode, fan_mode,
 * led_effect …). A small helper, started once per boot through Anbernic's "Root Scripts", runs as
 * root and carries out Hugminn's wishes – only from a fixed list of settings and only numbers.
 *
 * Hugminn writes lines "key value" into files/helper/cmd; the helper renews files/helper/alive
 * every few seconds, so Hugminn knows it is running.
 */
object AnbernicHelper {
    /** Settings the helper accepts (everything else is ignored by the helper too). */
    val allowed = setOf("cpu_mode", "fan_mode", "custom_fan_speed", "led_enable", "led_effect", "trigger_mode", "trigger_type", "abxy_mode")

    fun dir(ctx: Context): File? = ctx.getExternalFilesDir("helper")?.also { it.mkdirs() }

    /** Helper alive: its heartbeat is at most 15 s old. */
    fun alive(ctx: Context): Boolean {
        val f = dir(ctx)?.let { File(it, "alive") } ?: return false
        return f.exists() && System.currentTimeMillis() - f.lastModified() < 15_000
    }

    /** For the device test: what the script wrote and which files are there. */
    fun report(ctx: Context): String {
        val d = dir(ctx) ?: return "no folder"
        val files = d.listFiles()?.joinToString { "${it.name} (${(System.currentTimeMillis() - it.lastModified()) / 1000} s)" } ?: "–"
        val log = runCatching { File(d, "log").readLines().take(14).joinToString(" | ") }.getOrDefault("no log")
        return "files: $files · log: $log".take(900)
    }

    fun put(ctx: Context, key: String, value: String): Boolean {
        if (key !in allowed || !value.all { it.isDigit() }) return false
        val d = dir(ctx) ?: return false
        return runCatching { File(d, "cmd").appendText("$key $value\n") }.isSuccess
    }

    /** The script for "Root Scripts": starts the helper (an older one is stopped first). */
    fun script(pkg: String): String = """
#!/system/bin/sh
# Hugminn helper for Anbernic: performance, fan and lighting without root.
# Accepts only these settings, only numbers: ${allowed.joinToString(" ")}
P=$pkg
L=/data/local/tmp/hugminn-helper.sh
# Hugminn's folder: the first way to it that works
D=""
for d in /storage/emulated/0/Android/data/${'$'}P/files/helper /sdcard/Android/data/${'$'}P/files/helper /data/media/0/Android/data/${'$'}P/files/helper; do
  if [ -d "${'$'}d" ] && touch "${'$'}d/.probe" 2>/dev/null; then D="${'$'}d"; break; fi
done
[ -z "${'$'}D" ] && { echo "Hugminn folder not found – tap 'Save the script' in Hugminn first"; exit 1; }
exec > "${'$'}D/log" 2>&1
echo "start ${'$'}(date)"; id; echo "folder ${'$'}D"; ls -ld "${'$'}D"
dumpsys deviceidle whitelist +${'$'}P
pkill -f hugminn-helper.sh 2>/dev/null; echo "old stopped: ${'$'}?"
cat > ${'$'}L <<'LOOP'
D="${'$'}1"
U=${'$'}(stat -c %u "${'$'}D"); G=${'$'}(stat -c %g "${'$'}D")
n=0
while true; do
  if [ -f "${'$'}D/cmd" ]; then
    mv "${'$'}D/cmd" "${'$'}D/cmd.run"
    while read k v; do
      case "${'$'}k" in ${allowed.joinToString("|")}) ;; *) continue ;; esac
      case "${'$'}v" in ''|*[!0-9]*) continue ;; esac
      settings put system "${'$'}k" "${'$'}v"
    done < "${'$'}D/cmd.run"
    rm -f "${'$'}D/cmd.run"
  fi
  n=${'$'}((n+1))
  if [ ${'$'}n -ge 10 ]; then
    n=0
    date +%s > "${'$'}D/alive"; chown ${'$'}U:${'$'}G "${'$'}D/alive"; chmod 666 "${'$'}D/alive"
  fi
  sleep 0.3
done
LOOP
chmod 755 ${'$'}L; echo "loop written: ${'$'}?"
date +%s > ${'$'}D/alive; chmod 666 ${'$'}D/alive
chown ${'$'}(stat -c %u ${'$'}D):${'$'}(stat -c %g ${'$'}D) ${'$'}D/alive
(setsid sh ${'$'}L "${'$'}D" </dev/null >/dev/null 2>&1 &) ; echo "helper started: ${'$'}?"
sleep 2; echo "running: ${'$'}(pgrep -f hugminn-helper.sh | tr '\n' ' ')"
ls -l "${'$'}D"
""".trimStart()
}

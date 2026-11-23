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
D=/data/media/0/Android/data/${'$'}P/files/helper
L=/data/local/tmp/hugminn-helper.sh
mkdir -p ${'$'}D
dumpsys deviceidle whitelist +${'$'}P >/dev/null 2>&1
pkill -f hugminn-helper.sh 2>/dev/null
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
chmod 755 ${'$'}L
date +%s > ${'$'}D/alive; chmod 666 ${'$'}D/alive
chown ${'$'}(stat -c %u ${'$'}D):${'$'}(stat -c %g ${'$'}D) ${'$'}D/alive
setsid sh ${'$'}L "${'$'}D" </dev/null >/dev/null 2>&1 &
echo "Hugminn helper started"
""".trimStart()
}

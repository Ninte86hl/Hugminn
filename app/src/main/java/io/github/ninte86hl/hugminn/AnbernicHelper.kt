package io.github.ninte86hl.hugminn

import android.content.Context
import android.os.Environment
import android.provider.Settings
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

    @Suppress("DEPRECATION")
    fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hugminn").also { it.mkdirs() }

    private fun setting(ctx: Context, key: String) = runCatching { Settings.System.getString(ctx.contentResolver, key) }.getOrNull()

    /** Helper alive: its heartbeat (seconds) is at most 30 s old. */
    fun alive(ctx: Context): Boolean {
        val t = setting(ctx, "hugminn_helper_alive")?.trim()?.toLongOrNull() ?: return false
        return System.currentTimeMillis() / 1000 - t < 30
    }

    fun put(ctx: Context, key: String, value: String): Boolean {
        if (key !in allowed || !value.all { it.isDigit() }) return false
        return runCatching { File(dir(), "cmd").appendText("$key $value\n") }.isSuccess
    }

    /** For the device test. */
    fun report(ctx: Context): String {
        val diag = setting(ctx, "hugminn_helper_diag") ?: "no note"
        val beat = setting(ctx, "hugminn_helper_alive")?.toLongOrNull()?.let { "${System.currentTimeMillis() / 1000 - it} s ago" } ?: "none"
        val files = dir().listFiles()?.joinToString { it.name } ?: "–"
        return "note: $diag · heartbeat: $beat · folder: $files".take(900)
    }

    /** Writes helper.sh and the script to import into Download/Hugminn; returns the folder. */
    fun install(pkg: String): File {
        val d = dir()
        File(d, "helper.sh").writeText(helper(pkg))
        File(d, "hugminn-anbernic.sh").writeText(launcher())
        return d
    }

    /** What Anbernic imports: single plain commands only (it runs them one by one). */
    private fun launcher() = """
settings put system hugminn_helper_diag launcher-started
sh /sdcard/Download/Hugminn/helper.sh
sh /data/media/0/Download/Hugminn/helper.sh
""".trimStart()

    private fun helper(pkg: String): String = """
#!/system/bin/sh
# Hugminn helper for Anbernic: performance, fan and lighting without root.
# Accepts only these settings, only numbers: ${allowed.joinToString(" ")}
P=$pkg
D=${'$'}(dirname "${'$'}0")
note() { settings put system hugminn_helper_diag "${'$'}*"; }
# already running from the other path: nothing to do
if [ "${'$'}1" != "loop" ]; then
  pkill -f "helper.sh loop" 2>/dev/null
  note "uid=${'$'}(id -u) folder=${'$'}D read=${'$'}([ -r "${'$'}D/helper.sh" ] && echo ok) t=${'$'}(date +%s)"
  dumpsys deviceidle whitelist +${'$'}P >/dev/null 2>&1
  setsid sh "${'$'}0" loop </dev/null >/dev/null 2>&1 &
  exit 0
fi
n=100
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
  # heartbeat every ~9 s
  if [ ${'$'}n -ge 30 ]; then n=0; settings put system hugminn_helper_alive ${'$'}(date +%s); fi
  sleep 0.3
done
""".trimStart()
}

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
# once per start: what the hardware offers (LED drivers, temperature sensors, fan) – read-only
hw() {
  {
    echo "## leds"
    for l in /sys/class/leds/*; do
      echo "${'$'}(basename "${'$'}l"): max=${'$'}(cat "${'$'}l/max_brightness" 2>/dev/null) now=${'$'}(cat "${'$'}l/brightness" 2>/dev/null) trigger=${'$'}(grep -o '\[[^]]*\]' "${'$'}l/trigger" 2>/dev/null) files=${'$'}(ls "${'$'}l" | tr '\n' ' ')"
      for x in multi_index multi_intensity color mode effect speed; do [ -f "${'$'}l/${'$'}x" ] && echo "   ${'$'}x=${'$'}(head -c 200 "${'$'}l/${'$'}x" | tr '\n' ' ')"; done
    done
    echo "## thermal"
    for z in /sys/class/thermal/thermal_zone*; do echo "${'$'}(basename "${'$'}z") ${'$'}(cat "${'$'}z/type" 2>/dev/null) ${'$'}(cat "${'$'}z/temp" 2>/dev/null)"; done
    echo "## hwmon"
    for h in /sys/class/hwmon/hwmon*; do echo "${'$'}(basename "${'$'}h") ${'$'}(cat "${'$'}h/name" 2>/dev/null): ${'$'}(ls "${'$'}h" | tr '\n' ' ')"; done
    echo "## fan"
    find /sys/class /sys/devices/platform -maxdepth 4 -iname '*fan*' 2>/dev/null | head -40
    for f in ${'$'}(find /sys/class /sys/devices/platform -maxdepth 5 \( -iname '*rpm*' -o -iname 'fan*_input' -o -iname '*speed*' \) 2>/dev/null | grep -i fan | head -10); do echo "${'$'}f=${'$'}(cat "${'$'}f" 2>/dev/null)"; done
    echo "## cpu"
    for p in /sys/devices/system/cpu/cpufreq/policy*; do
      echo "${'$'}(basename "${'$'}p"): cpus=${'$'}(cat "${'$'}p/related_cpus" 2>/dev/null) gov=${'$'}(cat "${'$'}p/scaling_governor" 2>/dev/null) min=${'$'}(cat "${'$'}p/scaling_min_freq" 2>/dev/null) max=${'$'}(cat "${'$'}p/scaling_max_freq" 2>/dev/null) hw=${'$'}(cat "${'$'}p/cpuinfo_min_freq" 2>/dev/null)-${'$'}(cat "${'$'}p/cpuinfo_max_freq" 2>/dev/null)"
      echo "   govs=${'$'}(cat "${'$'}p/scaling_available_governors" 2>/dev/null) freqs=${'$'}(cat "${'$'}p/scaling_available_frequencies" 2>/dev/null | head -c 300)"
    done
    echo "## gpu"
    for g in /sys/class/devfreq/*; do echo "${'$'}(basename "${'$'}g"): gov=${'$'}(cat "${'$'}g/governor" 2>/dev/null) min=${'$'}(cat "${'$'}g/min_freq" 2>/dev/null) max=${'$'}(cat "${'$'}g/max_freq" 2>/dev/null) avail=${'$'}(cat "${'$'}g/available_frequencies" 2>/dev/null | head -c 200)"; done
    echo "## led-ish"
    find /sys -maxdepth 7 \( -iname '*led*' -o -iname '*rgb*' -o -iname '*light*' -o -iname '*effect*' \) 2>/dev/null | grep -vE 'backlight|/leds/|kernel/slab|/module/' | head -60
    echo "## joypad"
    for d in ${'$'}(find /sys/bus/platform/devices /sys/bus/i2c/devices /sys/bus/serial/devices -maxdepth 1 2>/dev/null | grep -iE 'joy|pad|mcu|gamepad|rgb'); do echo "${'$'}d: ${'$'}(ls ${'$'}d | tr '\n' ' ')"; done
    echo "## joypad values"
    J=/sys/devices/platform/singleadc-joypad
    for x in led_switch led_mode led_speed led_level led_set led_sync_colour custum_rgb_r custum_rgb_g custum_rgb_b Led_rgb_r1 Led_rgb_g1 Led_rgb_b1 Led_rgb_r2 Led_rgb_g2 Led_rgb_b2 mcu_info adc_deadzone remapkey_switch remapkey_xbox_switch enable poll_interval; do
      [ -e "${'$'}J/${'$'}x" ] && echo "${'$'}x=${'$'}(head -c 300 "${'$'}J/${'$'}x" 2>&1 | tr '\n' ' ') (${'$'}(stat -c %a "${'$'}J/${'$'}x"))"
    done
    echo "## dev"
    ls -l /dev 2>/dev/null | grep -iE 'tty[A-Z]|hidraw|joy|led|mcu|uart|rgb|i2c' | awk '{print ${'$'}1, ${'$'}3, ${'$'}4, ${'$'}NF}'
    echo "## procs"
    ps -A -o PID,NAME 2>/dev/null | grep -iE 'anbernic|anbanic|rgsetting|joypad|led|light|rgb|control'
    for p in ${'$'}(ps -A -o PID,NAME 2>/dev/null | grep -iE 'anbernic|anbanic|rgsetting|joypad|rgb' | awk '{print ${'$'}1}'); do
      echo "pid ${'$'}p ${'$'}(cat /proc/${'$'}p/cmdline 2>/dev/null | tr '\0' ' '): ${'$'}(ls -l /proc/${'$'}p/fd 2>/dev/null | grep -oE '(/dev|/sys)/[^ ]+' | sort -u | tr '\n' ' ')"
    done
    echo "## pwm-fan"
    ls /sys/devices/platform/pwm-fan 2>/dev/null | tr '\n' ' '; echo; echo "pwm1=${'$'}(cat /sys/class/hwmon/hwmon0/pwm1 2>/dev/null)"
    find /sys/devices/platform/pwm-fan -maxdepth 3 -type f 2>/dev/null | head -30
    echo "## props"
    getprop | grep -iE 'fan|led|light|rgb|joy' | head -40
  } > "${'$'}D/hw.txt" 2>&1
  chmod 666 "${'$'}D/hw.txt"
}
# already running from the other path: nothing to do
if [ "${'$'}1" != "loop" ]; then
  pkill -f "helper.sh loop" 2>/dev/null
  note "start uid=${'$'}(id -u) folder=${'$'}D low=${'$'}([ -d /data/media/0/${'$'}(basename "${'$'}D") ] && echo ok)"
  beat
  dumpsys deviceidle whitelist +${'$'}P >/dev/null 2>&1
  hw; note "hardware report: ${'$'}(wc -l < "${'$'}D/hw.txt") lines"
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

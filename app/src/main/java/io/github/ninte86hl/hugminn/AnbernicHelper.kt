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
        // a heartbeat that can't be read this moment (being written) keeps the last good one
        read("alive")?.toLongOrNull()?.let { lastBeat = it }
        val now = lastBeat > 0 && System.currentTimeMillis() / 1000 - lastBeat < 30
        // the helper (re)started – e.g. after a restart Anbernic's app has put its own colour back: ours again
        if (now && !wasAlive) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ AnbernicLed.reapply(ctx.applicationContext) }, 1500)
        wasAlive = now
        return now
    }

    @Volatile private var wasAlive = false
    @Volatile private var lastBeat = 0L

    /** The helper's live values (CPU/GPU °C, GPU MHz and load, fan PWM 0–255); null when it isn't running. */
    fun stats(): Map<String, Float>? {
        val f = File(dir(), "stats")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 10_000) return null
        return runCatching {
            f.readText().trim().split(' ').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toFloatOrNull()?.let { k to it } } }.toMap()
        }.getOrNull()
    }

    /** The controller's light settings the helper may write (singleadc-joypad, numbers only). */
    val joypadLed = setOf("led_switch", "led_mode", "led_speed", "led_level", "led_set", "led_sync_colour",
        "custum_rgb_r", "custum_rgb_g", "custum_rgb_b", "Led_rgb_r1", "Led_rgb_g1", "Led_rgb_b1", "Led_rgb_r2", "Led_rgb_g2", "Led_rgb_b2")

    /** Writes one of the controller's light settings through the helper. */
    fun joypad(name: String, value: Int): Boolean = name in joypadLed && write("sys $name $value")

    /** Several light values in one wish: one file instead of a dozen, the helper sets them in this order. */
    fun joypad(values: List<Pair<String, Int>>): Boolean {
        val ok = values.filter { it.first in joypadLed }
        return ok.isNotEmpty() && write(ok.joinToString("\n") { (k, v) -> "sys $k $v" })
    }

    /** The light settings as the helper found them at its start (hw.txt), to put them back. */
    fun joypadNow(): Map<String, Int> = runCatching {
        File(dir(), "hw.txt").readLines().dropWhile { !it.startsWith("## joypad values") }.drop(1).takeWhile { !it.startsWith("## ") }
            .mapNotNull { l -> l.substringBefore(' ').split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toIntOrNull()?.let { k to it } } }
            .filter { it.first in joypadLed }
            // led_level reads back 0–255 but is written in percent
            .map { (k, v) -> if (k == "led_level") k to Math.round(v / 2.55f).coerceIn(0, 100) else k to v }.toMap()
    }.getOrDefault(emptyMap())

    /** Ends an app through the helper (the switcher's "close" without root). */
    fun stop(ctx: Context, pkg: String): Boolean {
        if (!pkg.matches(Regex("[a-zA-Z0-9._]+"))) return false
        return write("stop $pkg")
    }

    fun put(ctx: Context, key: String, value: String): Boolean {
        if (key !in allowed || !value.all { it.isDigit() }) { trace("refused $key=$value"); return false }
        return write("$key $value")
    }

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    /** Files are written one after the other, away from the main thread (storage can be slow right after standby). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun write(line: String): Boolean {
        writer.execute { writeNow(line) }
        return true
    }

    private fun writeNow(line: String): Boolean {
        val d = dir()
        // tidy up wishes the helper has long done (it may not delete them itself)
        d.listFiles { f -> f.name.startsWith("cmd-") && System.currentTimeMillis() - f.lastModified() > 10_000 }?.forEach { it.delete() }
        val r = runCatching {
            // time and a running number: the helper goes through them in name order, so in the order sent
            val name = "cmd-${System.currentTimeMillis()}-${"%06d".format(seq.incrementAndGet() % 1_000_000)}"
            // written whole first, then renamed: the helper never sees half a wish
            val tmp = File(d, ".$name")
            tmp.writeText("$line\n")
            if (!tmp.renameTo(File(d, name))) error("rename failed")
        }
        trace("$line " + (r.exceptionOrNull()?.let { "FAILED ${it.javaClass.simpleName}: ${it.message}" } ?: "→ cmd file"))
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
# written whole, then renamed: Hugminn never reads a half-written heartbeat (an empty one looked like "helper gone")
beat() { date +%s > "${'$'}D/.alive"; chmod 666 "${'$'}D/.alive"; mv -f "${'$'}D/.alive" "${'$'}D/alive"; }
# once per start: what the hardware offers (LED drivers, temperature sensors, fan) – read-only
hw() {
  {
    echo "## joypad values"
    J=/sys/devices/platform/singleadc-joypad
    for x in led_switch led_mode led_speed led_level led_set led_sync_colour custum_rgb_r custum_rgb_g custum_rgb_b Led_rgb_r1 Led_rgb_g1 Led_rgb_b1 Led_rgb_r2 Led_rgb_g2 Led_rgb_b2 mcu_info adc_deadzone remapkey_switch remapkey_xbox_switch enable poll_interval; do
      [ -e "${'$'}J/${'$'}x" ] && echo "${'$'}x=${'$'}(head -c 300 "${'$'}J/${'$'}x" 2>&1 | tr '\n' ' ') (${'$'}(stat -c %a "${'$'}J/${'$'}x"))"
    done
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
  dumpsys deviceidle whitelist +${'$'}P >/dev/null 2>&1 || note "background whitelist failed"
  # Android 12+ ends "phantom processes" started by apps – this helper is one (Anbernic's app starts it)
  settings put global settings_enable_monitor_phantom_procs false 2>/dev/null
  device_config set_sync_disabled_for_tests persistent 2>/dev/null
  device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || note "phantom limit failed"
  appops set ${'$'}P WRITE_SETTINGS allow 2>/dev/null || note "write settings failed"
  # lets Hugminn pause Anbernic's key mapper while its windows are open (a global setting)
  pm grant ${'$'}P android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || note "secure settings failed"
  hw; note "hardware report: ${'$'}(wc -l < "${'$'}D/hw.txt") lines"
  setsid sh "${'$'}0" loop </dev/null >/dev/null 2>&1 &
  exit 0
fi
# Started from Anbernic's app, the helper sits in that app's process group – when Android ends the app
# (standby, memory), it takes the whole group along. Move out into the root group and away from the
# low-memory killer, so the helper keeps running after standby.
for g in /sys/fs/cgroup/cgroup.procs /dev/cpuctl/tasks /dev/cpuset/tasks /dev/stune/tasks /dev/blkio/tasks; do
  [ -w "${'$'}g" ] && echo ${'$'}${'$'} > "${'$'}g" 2>/dev/null
done
echo -1000 > /proc/${'$'}${'$'}/oom_score_adj 2>/dev/null
note "loop pid=${'$'}${'$'} group=${'$'}(head -1 /proc/${'$'}${'$'}/cgroup 2>/dev/null)"
trap 'note "ended by signal"; exit 0' TERM INT HUP
# live values for Hugminn (Android doesn't let apps read them here): CPU/GPU temperature, GPU clock and load
CZ=""; GZ=""
for z in /sys/class/thermal/thermal_zone*; do
  read t < "${'$'}z/type" 2>/dev/null
  case "${'$'}t" in soc-*|big*|mid*|lit*|apcpu*) CZ="${'$'}CZ ${'$'}z/temp" ;; gpu-thmzone) GZ="${'$'}z/temp" ;; esac
done
GF=${'$'}(ls -d /sys/class/devfreq/*.gpu 2>/dev/null | head -1)
stats() {
  c=0; for f in ${'$'}CZ; do read v < "${'$'}f" 2>/dev/null; [ -n "${'$'}v" ] && [ "${'$'}v" -gt "${'$'}c" ] && c=${'$'}v; done
  g=0; [ -n "${'$'}GZ" ] && read g < "${'$'}GZ" 2>/dev/null
  m=0; l=""; [ -n "${'$'}GF" ] && { read m < "${'$'}GF/cur_freq" 2>/dev/null; read l < "${'$'}GF/load" 2>/dev/null; }
  p=""; read p < /sys/class/hwmon/hwmon0/pwm1 2>/dev/null
  echo "cpu=${'$'}c gpu=${'$'}g gpumhz=${'$'}m gpuload=${'$'}{l%%@*} pwm=${'$'}p" > "${'$'}D/.stats"
  chmod 666 "${'$'}D/.stats"; mv -f "${'$'}D/.stats" "${'$'}D/stats"
}
# every wish is its own file cmd-<time> from Hugminn; the helper only reads them (Hugminn tidies up)
# wishes still lying there from before (an earlier helper): not again
seen=" "
for c in "${'$'}D"/cmd-*; do [ -f "${'$'}c" ] && seen="${'$'}seen${'$'}(basename "${'$'}c") "; done
n=100
while true; do
  now=" "
  for c in "${'$'}D"/cmd-*; do
    [ -f "${'$'}c" ] || continue
    b=${'$'}(basename "${'$'}c"); now="${'$'}now${'$'}b "
    case "${'$'}seen" in *" ${'$'}b "*) continue ;; esac
    [ -r "${'$'}c" ] || { note "cannot read ${'$'}b"; continue; }
    cat "${'$'}c" 2>/dev/null | while read k v; do
      # the controller's lights: only these names, only numbers
      if [ "${'$'}k" = "sys" ]; then
        set -- ${'$'}v; nm=${'$'}1; val=${'$'}2
        case "${'$'}nm" in ${joypadLed.joinToString("|")}) ;; *) note "ignored sys ${'$'}nm"; continue ;; esac
        case "${'$'}val" in ''|*[!0-9]*) note "ignored sys ${'$'}nm=${'$'}val"; continue ;; esac
        echo "${'$'}val" > /sys/devices/platform/singleadc-joypad/${'$'}nm 2>/dev/null && note "${'$'}nm=${'$'}val (now ${'$'}(cat /sys/devices/platform/singleadc-joypad/${'$'}nm 2>/dev/null))" || note "failed ${'$'}nm=${'$'}val"
        continue
      fi
      # ending an app (the switcher's "close"): package names only
      if [ "${'$'}k" = "stop" ]; then
        case "${'$'}v" in *[!a-zA-Z0-9._]*|'') note "ignored stop ${'$'}v" ;; *) am force-stop "${'$'}v" && note "stopped ${'$'}v" ;; esac
        continue
      fi
      case "${'$'}k" in ${allowed.joinToString("|")}) ;; *) note "ignored ${'$'}k"; continue ;; esac
      case "${'$'}v" in ''|*[!0-9]*) note "ignored ${'$'}k=${'$'}v"; continue ;; esac
      if settings put system "${'$'}k" "${'$'}v"; then note "${'$'}k=${'$'}v"; else note "failed ${'$'}k=${'$'}v"; fi
    done
    rm -f "${'$'}c" 2>/dev/null
  done
  seen="${'$'}now"
  n=${'$'}((n+1))
  # live values every ~2 s, heartbeat every ~9 s
  [ ${'$'}((n % 6)) -eq 0 ] && stats
  if [ ${'$'}n -ge 30 ]; then n=0; beat; fi
  sleep 0.3
done
""".trimStart()
}

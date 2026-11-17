# Hugminn Probe – read-only hardware discovery, one section per call: sh -s -- <section>
case "$1" in
leds)
  ls /sys/class/leds 2>&1
  for d in /sys/class/sn3112* /sys/class/aw* /sys/class/*rgb* /sys/class/*joystick*; do
    [ -e "$d" ] || continue
    echo "$d:"; ls -l "$d" "$d"/led 2>/dev/null
    [ -r "$d/led/brightness" ] && echo "  brightness: $(cat $d/led/brightness 2>&1 | head -c 200)"
  done
  ;;
cpu)
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
    echo "$p cpus=$(cat $p/related_cpus) min=$(cat $p/cpuinfo_min_freq) max=$(cat $p/cpuinfo_max_freq) cur=$(cat $p/scaling_cur_freq) limit_min=$(cat $p/scaling_min_freq) limit_max=$(cat $p/scaling_max_freq) gov=$(cat $p/scaling_governor)"
    echo "  available: $(cat $p/scaling_available_frequencies 2>/dev/null)"
  done
  ;;
gpu)
  K=/sys/class/kgsl/kgsl-3d0
  for f in gpu_model gpuclk max_gpuclk min_clock_mhz max_clock_mhz gpu_busy_percentage num_pwrlevels default_pwrlevel min_pwrlevel max_pwrlevel thermal_pwrlevel devfreq/available_frequencies devfreq/cur_freq devfreq/min_freq devfreq/max_freq devfreq/governor; do
    echo "$f: $(cat $K/$f 2>&1)"
  done
  ;;
thermal)
  for z in /sys/class/thermal/thermal_zone*; do
    echo "$(basename $z) type=$(cat $z/type 2>/dev/null) temp=$(cat $z/temp 2>/dev/null)"
  done
  ;;
cooling)
  for c in /sys/class/thermal/cooling_device*; do
    echo "$(basename $c) type=$(cat $c/type 2>/dev/null) cur=$(cat $c/cur_state 2>/dev/null) max=$(cat $c/max_state 2>/dev/null)"
  done
  ;;
fan)
  ls /sys/class | grep -iE 'fan|pwm|hwmon|cool' 2>&1
  for h in /sys/class/hwmon/hwmon*; do echo "$h name=$(cat $h/name 2>/dev/null) files: $(ls $h | tr '\n' ' ')"; done
  ls -d /sys/devices/platform/*fan* /sys/devices/platform/*pwm* 2>/dev/null
  ;;
power)
  for s in /sys/class/power_supply/*; do echo "$s: $(ls $s | tr '\n' ' ')"; done
  B=/sys/class/power_supply/battery
  for f in capacity status current_now voltage_now temp charge_type input_suspend charging_enabled; do
    echo "battery/$f: $(cat $B/$f 2>&1)"
  done
  ;;
backlight)
  for b in /sys/class/backlight/*; do echo "$b brightness=$(cat $b/brightness 2>/dev/null) max=$(cat $b/max_brightness 2>/dev/null)"; done
  ;;
input)
  cat /proc/bus/input/devices 2>&1
  ;;
packages)
  timeout 5 pm list packages -f 2>/dev/null | grep -iE 'retroid|com\.ro\.|com\.rp\.|odin|ayn|moorechip|ayaneo|anbernic'
  ;;
services)
  timeout 5 service list 2>&1
  ;;
surfaceflinger)
  timeout 5 dumpsys SurfaceFlinger --list 2>&1 | head -40
  ;;
esac

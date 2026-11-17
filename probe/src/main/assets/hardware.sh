# Hugminn Probe – read-only hardware discovery. Runs as root through the firmware service.
echo "## SELinux"
getenforce 2>&1
id 2>&1

echo
echo "## LEDs"
ls /sys/class/leds 2>&1
for d in /sys/class/sn3112* /sys/class/aw* /sys/class/*rgb* /sys/class/*joystick*; do
  [ -e "$d" ] || continue
  echo "$d:"; ls -l "$d" "$d"/led 2>/dev/null
done

echo
echo "## CPU (cpufreq policies)"
for p in /sys/devices/system/cpu/cpufreq/policy*; do
  echo "$p cpus=$(cat $p/related_cpus 2>/dev/null) min=$(cat $p/cpuinfo_min_freq 2>/dev/null) max=$(cat $p/cpuinfo_max_freq 2>/dev/null) cur=$(cat $p/scaling_cur_freq 2>/dev/null) limit_min=$(cat $p/scaling_min_freq 2>/dev/null) limit_max=$(cat $p/scaling_max_freq 2>/dev/null) gov=$(cat $p/scaling_governor 2>/dev/null)"
  echo "  available: $(cat $p/scaling_available_frequencies 2>/dev/null)"
done

echo
echo "## GPU (kgsl)"
K=/sys/class/kgsl/kgsl-3d0
for f in gpu_model gpuclk max_gpuclk min_clock_mhz max_clock_mhz gpu_busy_percentage gpubusy num_pwrlevels default_pwrlevel min_pwrlevel max_pwrlevel thermal_pwrlevel devfreq/available_frequencies devfreq/cur_freq devfreq/min_freq devfreq/max_freq devfreq/governor; do
  echo "$f: $(cat $K/$f 2>&1)"
done

echo
echo "## Thermal zones"
for z in /sys/class/thermal/thermal_zone*; do
  echo "$(basename $z) type=$(cat $z/type 2>/dev/null) temp=$(cat $z/temp 2>/dev/null)"
done
echo "cooling devices:"
for c in /sys/class/thermal/cooling_device*; do
  echo "$(basename $c) type=$(cat $c/type 2>/dev/null) cur=$(cat $c/cur_state 2>/dev/null) max=$(cat $c/max_state 2>/dev/null)"
done

echo
echo "## Fan candidates"
find /sys/class /sys/devices/platform -maxdepth 4 -iname '*fan*' 2>/dev/null | head -60
ls /sys/class/hwmon 2>/dev/null
for h in /sys/class/hwmon/hwmon*; do echo "$h name=$(cat $h/name 2>/dev/null) files: $(ls $h | tr '\n' ' ')"; done

echo
echo "## Power supply"
for s in /sys/class/power_supply/*; do echo "$s: $(ls $s | tr '\n' ' ')"; done
B=/sys/class/power_supply/battery
for f in capacity status current_now voltage_now temp charge_type input_suspend charging_enabled; do
  echo "battery/$f: $(cat $B/$f 2>&1)"
done

echo
echo "## Backlight / displays"
ls /sys/class/backlight 2>&1
for b in /sys/class/backlight/*; do echo "$b brightness=$(cat $b/brightness 2>/dev/null) max=$(cat $b/max_brightness 2>/dev/null)"; done

echo
echo "## Input devices (kernel)"
cat /proc/bus/input/devices 2>&1

echo
echo "## Vendor packages and apps"
pm list packages -f 2>/dev/null | grep -iE 'retroid|com\.ro\.|com\.rp\.|odin|ayn|moorechip|aynec|com\.ayaneo|anbernic'
ls /system/priv-app /system/app /product/priv-app /product/app /vendor/app /system_ext/priv-app /system_ext/app 2>/dev/null | grep -iE 'retroid|odin|ayn|ro[A-Z_]|rp[A-Z_]|setting|launcher|dualscreen|mapping|fan|led|light|game'

echo
echo "## Binder services"
service list 2>&1

echo
echo "## SurfaceFlinger layers (first 40)"
dumpsys SurfaceFlinger --list 2>&1 | head -40

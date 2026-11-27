# To-do / ideas for later

Not started. In order of priority; the basic version for all AYN and Retroid devices comes first.

## Power-saving mode for light emulators ("Sparen")

Idea: a fourth performance profile next to Normal / Leistung / Hohe Leistung that saves battery for NES, SNES, GB/GBA, Mega Drive and the like.

**Facts (from the PULSE README, keiretrogaming/pulse, GPL-2.0 – ideas only, no code taken):**
- Snapdragon has no programmable watt cap (no real TDP). "TDP" is built from CPU and GPU clock caps.
- CPU: per cluster `/sys/devices/system/cpu/cpufreq/policyN/scaling_max_freq` (kHz), snapped to `scaling_available_frequencies`.
- GPU (Adreno, KGSL): `/sys/class/kgsl/kgsl-3d0/max_pwrlevel` is a level index (0 = fastest). Widen `min_pwrlevel` to the slowest level first, otherwise the cap snaps back.
- Write path: the firmware root service PServerBinder (Hugminn already uses it).

**Step 1 – profile "Sparen":**
- Cap the CPU (big cores more, prime cores almost fully) and set the GPU to a middle level; put everything back when another profile is chosen.
- Fits into the existing per-app profiles (AppProfiles). Automatic per system through the ROM extension that GameTitle already reads (RetroArch with NES/GBA saves, with PS2 not).
- First: a device-test step that reads the real frequency tables (Duo, RP5/RP6, Odin 2, Thor).
- Check whether Retroid's/AYN's own performance mode overwrites the caps when it switches; if so, apply again afterwards.
- Root calls not in hot paths (the root service works serially).

**Step 2 – "Auto-Sparen" with an FPS target:**
- Hugminn measures FPS already. Stable at the target → lower the clocks one step; FPS drops → one step up. Stop chasing unreachable targets.

## Orthogonal design system

See [design-system-plan.md](design-system-plan.md).

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

## RetroAchievements page

Idea: one place inside Hugminn for the running game's achievements, the same for every emulator that supports RetroAchievements (RetroArch, Dolphin, PPSSPP, ARMSX2, Flycast, DuckStation, NetherSX2/AetherSX2, melonDS-based, …), like RetroDualStation.

- **Own page in the Control Center** (next to the notifications page, reached with L1/R1): game, console, progress (e.g. 12/40, points); lists "Erhalten" (with date) and "Ausstehend" (description, points); Dot-Matrix style, controller only.
- **Own unlock notification:** Hugminn's card with badge, name and points, the same over every emulator (the emulators' own pop-ups can then be switched off).
- **Way 1 – online, RetroAchievements Web API:** user name + web API key entered once, kept on the device only. The emulator reports the game to RA; Hugminn asks for the last active game and the achievements with the user's progress, and polls every 30–60 s while a game runs to notice new unlocks (a few seconds late). Works for every RA emulator. Offline: show the last saved state.
- **Way 2 – offline, RAOfflineProxy** (misantronic/RAOfflineProxy, GPL-3.0, alpha): it sits between emulator and RA and knows game and unlocks at once. Its local data/API is not documented – find out from its source where it stores them, once its format is stable.
- **Way 3 – standalone emulators through root (AYN/Retroid firmware root service):** most emulators use rcheevos and write to the log when they load a game and award an achievement. Hugminn already reads logcat as root (GameTitle). So: per emulator, find its log lines for "game loaded / achievement awarded" (device-test step: play, unlock one, collect the lines) → instant notification, offline too, with the achievement list cached from the API. Fallback per emulator: its own files under /data/data (read only, never write; no use of the emulator's stored RA login).
- Open: how RetroDualStation does it (log, API?), so both feel the same.

## Own equalizer

Later: Hugminn's own equalizer instead of opening Retroid's (Retroid's intent action `action_equalizer_config` is used for now).

## Hugminn keyboard as Android's default keyboard

Today PadKeyboard only works inside Hugminn (Wi-Fi password). Goal: a real Android input method usable in every app, simple to use, oriented on the Nintendo Switch keyboard.

**Needed:** an InputMethodService (shows up under on-screen keyboards; with root Hugminn can enable/select it and go back), writing through the InputConnection, Hugminn's key service lets the keys through while the keyboard is open (Home/CC/switcher keep working), OK key label from the field (Search/Go/Next/Send), number pad for number/PIN fields, @ and .com for e-mail/web fields, capital letter at sentence start, no saving in password fields, opens on the screen with the field (Duo/Thor), touch keeps working. No word suggestions/autocorrect.

**Buttons (Switch positions; with Xbox layout the same positions, hints show the device's labels):**
- D-pad / left stick: choose key
- A (right): type · hold: special characters (ä, ß, é …)
- B (bottom): delete · hold: keeps deleting
- Y (left): space
- X (top): numbers & symbols (no permanent number row – letters only by default)
- L / R: cursor left/right
- L2: shift for the next letter · R2: caps lock on/off (works on the Pocket Classic too, which has no stick buttons); the ⇧ key on the keyboard does the same by touch/A
- + (Start): OK / send
- − (Select): edit bar – Markieren (then L/R extend the selection), Alles markieren, Kopieren, Ausschneiden, Einfügen, Rückgängig
- ⌨ key on the keyboard (or hold −): hide the keyboard
- Home: stays with Hugminn
- Kept free on purpose (simple): L3, R3, right stick (not on every device anyway)

## Mouse mode (point & click)

Retroid and AYN firmwares already have their own mouse mode with a shortcut – Hugminn does not build its own pointer.
- Instead: a CC tile "Mausmodus" (and optionally a Hugminn button action) that switches the firmware's mode, showing whether it is on.
- Also in the quick menu (Back held).
- Not on the Pocket Classic (no sticks, no external ports – touch is enough there): no tile, no hotkey.
- First find out how the firmware switches it: a before/after settings snapshot in the device test (like the Thor volume), on a Retroid and an AYN device.

## Quick menu on Back held (like RetroDECK)

Hold the Back button (long press) → a small menu over the game, chosen with the D-pad/stick and A, closed with B. A short press stays a normal Back (sent on release). Every Retroid/AYN device has Back, the Pocket Classic too. On devices with extra buttons (paddles etc.) the menu can also be put on them as a Hugminn button action.
- Tastatur (open the keyboard anywhere)
- Bildschirmfoto
- Lupe (magnify a part of the screen)
- Helligkeit (slider)
- Mausmodus (firmware's own mode; not on the Pocket Classic) – no extra L3 + R3 hotkey needed

External controllers: their Guide/Home button already works like the device's Home in Hugminn (setting "Controller-Home", prefs.padHome, with single/double/long actions) – so "Schnellmenü" as a Home action (e.g. long) covers them. Controllers without a Guide button: optional "Select held ~1.5 s" for external pads only.
Back double press: selectable option, default stays long press (double would delay every Back or send it twice).

No Select or stick combinations, so the emulators' own hotkeys and games stay untouched. Check: whether a firmware already uses Back held for something.

## From the Steam Deck (still open which ones)

FPS limit / refresh rate per game; overlay levels 1–4. (Charge limit exists already.)

## Orthogonal design system

See [design-system-plan.md](design-system-plan.md).

## Open tests

See [tests.md](tests.md).

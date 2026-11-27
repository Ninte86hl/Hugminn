# Open tests per device

Tests that are still open. Tick them off (`[x]`) or delete them when done, and add new ones with the test build they came with.
Last updated: 2026-11-27, test.286.

## All devices (AYN and Retroid)

- [ ] **test.286, Anbernic removed:** lighting (LED window), performance and fan profiles, closing apps in the switcher. All should work as before.
- [ ] **CC sliders:** all sliders snap to and show 5 % steps.
- [ ] **CC volume slider, Y:** popup with Ton / Vibration / Lautlos.
- [ ] **CC notifications page:** L1 from page 1. A opens, Y dismisses, X clears all.
- [ ] **CC switcher L1/R1:** from the game switcher, L1/R1 turn the Control Center page.
- [ ] **CC add picker:** tabs Anzeigen, Regler, Verbindung, Bildschirm, Leistung, Steuerung, Medien & Apps, Weitere.
- [ ] **New tiles:** Zuletzt gespielt, Akku-Spielzeit, Nachtlicht (root), Audio-Ausgang (A opens the output dialog), Uhr with date.
- [ ] **Status elements:** "stats" in 2×1 / 4×1 / 2×2 / 2×3, "statsgraph", "statsfps". Temperature and warnings in red.
- [ ] **Tile sizes:** perf/fan in 2×1, 2×2 and 3×2.
- [ ] **Dot-Matrix design:** dot circle (small gauges), capsule (battery), pill (wide 1-row tiles), red value and frame on warnings. Please send a screenshot.
- [ ] **Default layout per screen shape:** reset the layout and check 16:9 (wide), 4:3 and ≈1:1.
- [ ] **Fan curve:** 10 points, X = Hugminn's default curve, Y = test run (1.8 s per point), touch editing, labels not cut off.

## Retroid Pocket Duo

- [ ] **Home with Cocoon** as the launcher: top Cocoon, bottom its own home, focus ends on top. (Quickstep confirmed.)
- [ ] **Switcher badges:** ▲ Oben / ▼ Unten on the cards are correct.
- [ ] **Switcher previews:** an app on the bottom screen shows its own picture, not the top screen.
- [ ] **Brightness:** the global slider keeps the difference between top and bottom (offset); Y popup sets top and bottom on their own.

## AYN Thor

- [x] Lower screen backlight found (panel1-backlight), dims and switches off/on (test.282)
- [x] Brightness bottom: slider follows, Y popup, lower screen on/off tile (test.283)
- [ ] **test.285, volume bottom:** the global volume slider takes the lower screen along, Y sets top and bottom on their own, tiles "Lautstärke unten" and "Lautstärke oben + unten".
- [ ] **Lower screen off:** touch still reacts while the screen is off (no touch lock known on the Thor yet). Find a way if needed.
- [ ] **Home with two screens** as on the Duo (top launcher, bottom its home).

## Retroid Pocket 6 (and other single-screen Retroids)

- [ ] **Home:** single / double / long press as before (reference device for the Duo).

## External monitor (Retroid with Connect TV)

- [ ] **Resolution in the tile:** send a log with a monitor connected, after picking 1080P (60 Hz) in Retroid's Connect TV.

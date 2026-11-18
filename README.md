<p align="center">
  <img src="docs/icon.svg" alt="Hugminn icon" width="160">
</p>

<h1 align="center">Hugminn</h1>

<p align="center">
  <b>A game switcher and control center for Retroid handhelds</b><br>
  Android 13 · controller-first · Material Design
</p>

---

> **Status:** in active testing on the Retroid Pocket 6 (current version: see Releases). Features marked *(testing)* are built but not yet confirmed on the device.

## What is Hugminn?

Hugminn adds two console-style overlays to the Retroid Pocket 6, Pocket Nova and Pocket 5 without replacing your launcher. You keep your favourite frontend (such as Cocoon) for your library; Hugminn sits on top and is always one button press away.

### The name

In Norse mythology, Odin's two ravens fly over the world every day and report back what they have seen. Hugminn joins their names, and each raven is one part of the app:

- **Huginn** ("thought") is the **Control Center**. It keeps an eye on performance, temperature, fan and LEDs.
- **Muninn** ("memory") is the **Game Switcher**. It remembers what you played last and takes you straight back to it.

## Features

### Muninn – Game Switcher

- Its own switcher: Android's recent-apps view is not opened at all, so nothing flickers or shows through
- Large card for the selected app, smaller cards beside it, with Hugminn's own preview pictures (taken when you press Home and every few seconds while you play)
- D-pad, left stick or L1/R1 to browse; touch: swipe sideways to browse, swipe a card up to close it, tap to open
- Follows Android's current task list; apps closed elsewhere disappear
- Actions:

| Button | Action |
| --- | --- |
| A | Open the app (its running task comes back to the front) |
| □ / X | Close the app, also removing it from Android's recent apps *(testing)* |
| △ / Y | Options: App info, close all others; for RetroArch: save state, load state, RetroArch menu |
| ○ / B | Back to where you were |
| Select | Send feedback |

- The system's swipe-up gesture is covered by the switcher as soon as Android's view settles *(testing)*
- Classic mode (a frame over Android's own recent-apps view) remains available in the settings

### Huginn – Control Center

- Full-screen grid of square tiles with several pages; free cells open a picker sorted by type (displays, sliders, switches)
- Edit mode: hold A (or long-press) to move, resize and remove tiles by drag & drop or D-pad; tile colour; reset to default
- D-pad navigation follows the grid; nothing is preselected until you press a direction
- Switches (A toggles, □ opens the tile's submenu): WLAN, Bluetooth, stick LEDs, 60/120 Hz, Do not disturb, airplane mode, rotation lock, screen always on and more
- WLAN submenu: networks in range with signal, saved networks connect directly, password window for new ones *(testing)*
- Bluetooth submenu: paired devices (connect / disconnect), search and pair new devices *(testing)*
- Sliders for volume and brightness in 5 % steps, vertical or horizontal; confirm with A before the D-pad changes them
- Cards for performance and fan profiles (open the profile menu), CPU, GPU, RAM, temperatures, battery and FPS
- Gauges coloured by load (traffic light, cool → warm, accent colour, monochrome)
- Steam-like performance overlay in games (FPS, CPU, GPU, RAM, temperatures; position and items selectable)
- Button for Android's settings; own status bar (clock, battery) in the chosen style

### Stick LEDs

- On/off, colour wheel with live preview, 12 quick colours, left / right / both sticks
- Effects: colour cycle, offset rainbow, breathing, pulse; adjustable speed
- Brightness uses the firmware's own value (same as Retroid's LED window)
- Sticks pulse red when the battery drops to 10 %
- Optional link to an LED app such as [Bifrost](https://github.com/Pollux-MoonBench/Bifrost) or AmbiLuma instead of the built-in colours
- Note: the Pocket 6 lights each stick in one colour; a rainbow running around a single stick is not possible

### Home button

| Button | Default action |
| --- | --- |
| Home (press) | Home, as usual |
| Home (double press) | Game Switcher |
| Home (hold) | Control Center |

Every gesture can be reassigned (home, switcher, control center, recent apps, last app). The Back button is left alone, so it keeps working in RetroArch and other emulators.

### Styles and colours

- Three styles across the whole app: **Modern** (Material 3), **Pixel** (pixel font, stepped corners) and **Dot matrix** (Nothing-like dots)
- Themes: system, light, dark, OLED black
- Tile colour from presets or a colour wheel; it becomes the accent colour in every style
- PlayStation, Xbox or Nintendo button symbols, coloured or monochrome, following the device's controller layout

### Feedback and diagnostics

- Press Select in the switcher or the control center: screenshot plus your own short description are uploaded to the project (needs a GitHub token in the settings)
- Errors are reported automatically with the system log; upload progress as a notification
- Input test, guided LED zone test, log upload

## Supported devices

| Device | Chip | Display | Status |
| --- | --- | --- | --- |
| Retroid Pocket 6 | Snapdragon 8 Gen 2 (QCS8550) | 5.5" 16:9, 1080p, 120 Hz | primary target |
| Retroid Pocket Nova | QCS8550 | 4.5" 4:3, 1280×960, 120 Hz | planned |
| Retroid Pocket 5 | Snapdragon 865 | 5.5" 16:9, 1080p, 60 Hz | planned |

Hugminn detects the device and only shows what it supports, for example no 120 Hz toggle on the Pocket 5. On the Pocket Nova, the Control Center uses a 4:3 layout.

## Requirements

- One of the devices above on stock firmware
- Most system features use the firmware's built-in privileged service (`PServerBinder`), so no separate root setup is needed. Without it, Hugminn opens the matching system settings instead.
- Hugminn runs as an accessibility service (for the Home button, the overlays and screenshots).

## Installation

Every build is published under **Releases**, built automatically by GitHub Actions.

- **Direct download:** [hugminn.apk](https://github.com/Ninte86hl/Hugminn/releases/download/latest/hugminn.apk) (always the newest test build)
- **With [Obtainium](https://github.com/ImranR98/Obtainium) (recommended, keeps Hugminn up to date):** download [obtainium.json](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/obtainium.json) (also attached to every release) and import it in Obtainium under *Import/Export → Obtainium Import*.
  Manual setup works too: just add `https://github.com/Ninte86hl/Hugminn` as source.

After installing, open Hugminn and tap **Aktivieren** to turn on its accessibility service.

## Acknowledgements

These projects showed what is possible on AYN and Retroid handhelds and inspired parts of Hugminn:

- [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder): controller navigation for the recent-apps view
- [Better Dual Screen Assistant](https://github.com/stormpanda/better-dual-screen-assistant): system settings and the firmware's privileged service
- [Bifrost](https://github.com/Pollux-MoonBench/Bifrost): stick LED control and its integration API
- [P.U.L.S.E.](https://github.com/keiretrogaming/pulse): performance tuning on the Retroid Pocket 6
- [ClusterTune](https://github.com/AurelioB/ClusterTune): CPU and GPU frequency limits

Hugminn is an independent project and is not affiliated with Retroid, AYN or any of the projects above.

## Third-party assets

- [Jersey 10](https://github.com/scfried/soft-type-jersey) pixel font by The Soft Type Project Authors, SIL Open Font License 1.1 (see `app/src/main/assets/fonts/Jersey10-OFL.txt`)
- [Doto](https://github.com/oliverlalan/Doto) dot-matrix font by Óliver Lalan, SIL Open Font License 1.1 (see `app/src/main/assets/fonts/Doto-OFL.txt`)

## Author

Created by [Ninte86hl](https://github.com/Ninte86hl).

## License

To be decided.

<p align="center">
  <img src="docs/icon.svg" alt="Hugminn icon" width="160">
</p>

<h1 align="center">Hugminn</h1>

<p align="center">
  <b>A game switcher and control center for Retroid handhelds</b><br>
  Android 13 · controller-first · Material Design
</p>

---

> **Status:** early development. Nothing is ready to install yet. The first build will be a small test app that checks what the device allows.

## What is Hugminn?

Hugminn adds two console-style overlays to the Retroid Pocket 6, Pocket Nova and Pocket 5 without replacing your launcher. You keep your favourite frontend (such as Cocoon) for your library; Hugminn sits on top and is always one button press away.

### The name

In Norse mythology, Odin's two ravens fly over the world every day and report back what they have seen. Hugminn joins their names, and each raven is one part of the app:

- **Huginn** ("thought") is the **Control Center**. It keeps an eye on performance, temperature, fan and LEDs.
- **Muninn** ("memory") is the **Game Switcher**. It remembers what you played last and takes you straight back to it.

## Features (planned)

### Muninn – Game Switcher

- Opens the Android recent-apps view and adds its own bar along the bottom; the app cards stay uncovered
- Browse with the D-pad or the left stick
- Actions adapt to what is selected:

| Selected | Actions |
| --- | --- |
| App | Open, Close, App info |
| RetroArch | Open, Restart game, Save state, Load state, Close |
| Other emulator | Open, Restart game, Close |

- Shows the real game title and system for RetroArch
- Jump straight back to the previous game

### Huginn – Control Center

- Quick toggles: performance mode, fan mode, refresh rate (60/120 Hz), vibration, controller style, bypass charging, free RAM, screenshot
- Sliders for brightness and volume
- Live graphs for FPS (average and 1 % low), CPU, GPU, RAM, power draw, temperature and fan speed
- Optional in-game performance overlay
- Simplified performance profiles per game: Stock, Efficient, Balanced, Performance, plus a target-FPS mode
- Stick LEDs: colour per stick, brightness and effects, through [Bifrost](https://github.com/Pollux-MoonBench/Bifrost) when it is installed, otherwise through the device's own LED settings

### Controls

| Button | Default action |
| --- | --- |
| Home (press) | Home, as usual |
| Home (double press) | Game Switcher |
| Home (hold) | Control Center |

Every gesture can be reassigned. The Back button is left alone, so it keeps working in RetroArch and other emulators.

### Your buttons, your symbols

- Follows the device's controller setting automatically (Xbox or Retroid/Nintendo layout)
- Optional PlayStation-style symbols (✕ ○ □ △)
- Coloured or monochrome symbols
- Actions are tied to the button's position, so "confirm" is always the same physical button

### Design

- Material 3 with Material You colours
- Follows the system's light or dark theme
- Layouts for 16:9 and 4:3 screens, full controller navigation

## Supported devices

| Device | Chip | Display | Status |
| --- | --- | --- | --- |
| Retroid Pocket 6 | Snapdragon 8 Gen 2 (QCS8550) | 5.5" 16:9, 1080p, 120 Hz | primary target |
| Retroid Pocket Nova | QCS8550 | 4.5" 4:3, 1280×960, 120 Hz | planned |
| Retroid Pocket 5 | Snapdragon 865 | 5.5" 16:9, 1080p, 60 Hz | planned |

Hugminn detects the device and only shows what it supports, for example no 120 Hz toggle on the Pocket 5. On the Pocket Nova, the Control Center uses a 4:3 layout.

## Requirements

- One of the devices above on stock firmware
- Some features use the firmware's built-in system service (`PServerBinder`), so no root is needed. If it is missing, Hugminn falls back to [Shizuku](https://shizuku.rikka.app/) or opens the matching system settings.

## Installation

Hugminn is in early testing. Every build is published under **Releases**, built automatically by GitHub Actions.

- **Direct download:** [hugminn.apk](https://github.com/Ninte86hl/Hugminn/releases/download/latest/hugminn.apk) (always the newest test build)
- **With [Obtainium](https://github.com/ImranR98/Obtainium) (recommended, keeps Hugminn up to date):** download [obtainium.json](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/obtainium.json) (also attached to every release) and import it in Obtainium under *Import/Export → Obtainium Import*.
  Manual setup works too: source `https://github.com/Ninte86hl/Hugminn`, enable *Include prereleases*, APK filter `^hugminn\.apk$`.

After installing, open Hugminn and tap **Aktivieren** to turn on its accessibility service.

## Acknowledgements

These projects showed what is possible on AYN and Retroid handhelds and inspired parts of Hugminn:

- [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder): controller navigation for the recent-apps view
- [Better Dual Screen Assistant](https://github.com/stormpanda/better-dual-screen-assistant): system settings and the firmware's privileged service
- [Bifrost](https://github.com/Pollux-MoonBench/Bifrost): stick LED control and its integration API
- [P.U.L.S.E.](https://github.com/keiretrogaming/pulse): performance tuning on the Retroid Pocket 6
- [ClusterTune](https://github.com/AurelioB/ClusterTune): CPU and GPU frequency limits

Hugminn is an independent project and is not affiliated with Retroid, AYN or any of the projects above.

## Author

Created by [Ninte86hl](https://github.com/Ninte86hl).

## License

To be decided.

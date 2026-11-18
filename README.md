<p align="center">
  <img src="docs/icon.svg" alt="Hugminn icon" width="160">
</p>

<h1 align="center">Hugminn</h1>

<p align="center">
  <b>A game switcher and control center for Retroid handhelds</b><br>
  Android 13 · controller-first · Material Design
</p>

---

> **Status:** tested on the Retroid Pocket 6. Pocket Nova and Pocket 5 support is planned.

## What is Hugminn?

Hugminn adds two console-style overlays to the Retroid Pocket 6, Pocket Nova and Pocket 5 without replacing your launcher. You keep your favourite frontend (such as Cocoon) for your library; Hugminn sits on top and is always one button press away.

### Why Hugminn?

Android was made for touch screens. On a handheld you hold a controller, yet switching between games or changing the fan, the performance profile or the Wi-Fi still means reaching for the screen, digging through Android's settings or waiting for a quick settings panel that was never designed for a D-pad.

The goal of Hugminn is a **controller-friendly control center and game switcher for Android**, the way consoles have one: everything reachable with the buttons you are already holding, readable at a glance, and without leaving your game for longer than a button press. Touch still works, but you never need it.

### The name

In Norse mythology, Odin's two ravens fly over the world every day and report back what they have seen. Hugminn joins their names, and each raven is one part of the app:

- **Huginn** ("thought") is the **Control Center**. It keeps an eye on performance, temperature, fan and LEDs.
- **Muninn** ("memory") is the **Game Switcher**. It remembers what you played last and takes you straight back to it.

## Screenshots

<p align="center">
  <img src="docs/screenshots/styles.gif" alt="Control Center in all styles: Modern, Pixel, Dot matrix and Cocoon, light and dark" width="100%">
</p>
<p align="center"><sub>The Control Center in all four styles – Modern, Pixel, Dot matrix and Cocoon – light and dark</sub></p>

| | |
| --- | --- |
| ![Game switcher](docs/screenshots/modern/dark-switcher.png) | ![Performance overlay in a game](docs/screenshots/fps-overlay.png) |
| Muninn – game switcher with the running game's title | Performance overlay in a game |
| ![Performance profile](docs/screenshots/modern/dark-perf.png) | ![Fan profile](docs/screenshots/modern/dark-fan.png) |
| Performance profile, remembered per emulator | Fan profile, remembered per emulator |
| ![Custom fan curve](docs/screenshots/modern/dark-curve.png) | ![Stick LEDs](docs/screenshots/modern/dark-leds.png) |
| Custom fan curve | Stick LEDs: colours and effects |
| ![Edit menu](docs/screenshots/modern/dark-editmenu.png) | ![Tile colour](docs/screenshots/modern/dark-tile.png) |
| Edit: layout, tiles, style, colours | Tile colour |
| ![Edit mode](docs/screenshots/modern/dark-edit.png) | ![Add element](docs/screenshots/add-element.png) |
| Edit mode: move and resize elements on a grid with pages | Add displays, switches and sliders |

<details>
<summary>Modern – every screen, dark and light</summary>

| Dark | Light |
| --- | --- |
| ![Control Center · dark](docs/screenshots/modern/dark-main.png) | ![Control Center · light](docs/screenshots/modern/light-main.png) |
| Control Center · dark | Control Center · light |
| ![Performance profile · dark](docs/screenshots/modern/dark-perf.png) | ![Performance profile · light](docs/screenshots/modern/light-perf.png) |
| Performance profile · dark | Performance profile · light |
| ![Fan profile · dark](docs/screenshots/modern/dark-fan.png) | ![Fan profile · light](docs/screenshots/modern/light-fan.png) |
| Fan profile · dark | Fan profile · light |
| ![Custom fan curve · dark](docs/screenshots/modern/dark-curve.png) | ![Custom fan curve · light](docs/screenshots/modern/light-curve.png) |
| Custom fan curve · dark | Custom fan curve · light |
| ![Stick LEDs · dark](docs/screenshots/modern/dark-leds.png) | ![Stick LEDs · light](docs/screenshots/modern/light-leds.png) |
| Stick LEDs · dark | Stick LEDs · light |
| ![Edit menu · dark](docs/screenshots/modern/dark-editmenu.png) | ![Edit menu · light](docs/screenshots/modern/light-editmenu.png) |
| Edit menu · dark | Edit menu · light |
| ![Edit mode · dark](docs/screenshots/modern/dark-edit.png) | ![Edit mode · light](docs/screenshots/modern/light-edit.png) |
| Edit mode · dark | Edit mode · light |
| ![Tile colour · dark](docs/screenshots/modern/dark-tile.png) | ![Tile colour · light](docs/screenshots/modern/light-tile.png) |
| Tile colour · dark | Tile colour · light |
| ![Game switcher · dark](docs/screenshots/modern/dark-switcher.png) | ![Game switcher · light](docs/screenshots/modern/light-switcher.png) |
| Game switcher · dark | Game switcher · light |

</details>

<details>
<summary>Pixel – every screen, dark and light</summary>

| Dark | Light |
| --- | --- |
| ![Control Center · dark](docs/screenshots/pixel/dark-main.png) | ![Control Center · light](docs/screenshots/pixel/light-main.png) |
| Control Center · dark | Control Center · light |
| ![Performance profile · dark](docs/screenshots/pixel/dark-perf.png) | ![Performance profile · light](docs/screenshots/pixel/light-perf.png) |
| Performance profile · dark | Performance profile · light |
| ![Fan profile · dark](docs/screenshots/pixel/dark-fan.png) | ![Fan profile · light](docs/screenshots/pixel/light-fan.png) |
| Fan profile · dark | Fan profile · light |
| ![Custom fan curve · dark](docs/screenshots/pixel/dark-curve.png) | ![Custom fan curve · light](docs/screenshots/pixel/light-curve.png) |
| Custom fan curve · dark | Custom fan curve · light |
| ![Stick LEDs · dark](docs/screenshots/pixel/dark-leds.png) | ![Stick LEDs · light](docs/screenshots/pixel/light-leds.png) |
| Stick LEDs · dark | Stick LEDs · light |
| ![Edit menu · dark](docs/screenshots/pixel/dark-editmenu.png) | ![Edit menu · light](docs/screenshots/pixel/light-editmenu.png) |
| Edit menu · dark | Edit menu · light |
| ![Edit mode · dark](docs/screenshots/pixel/dark-edit.png) | ![Edit mode · light](docs/screenshots/pixel/light-edit.png) |
| Edit mode · dark | Edit mode · light |
| ![Tile colour · dark](docs/screenshots/pixel/dark-tile.png) | ![Tile colour · light](docs/screenshots/pixel/light-tile.png) |
| Tile colour · dark | Tile colour · light |
| ![Game switcher · dark](docs/screenshots/pixel/dark-switcher.png) | ![Game switcher · light](docs/screenshots/pixel/light-switcher.png) |
| Game switcher · dark | Game switcher · light |

</details>

<details>
<summary>Dot matrix – every screen, dark and light</summary>

| Dark | Light |
| --- | --- |
| ![Control Center · dark](docs/screenshots/dots/dark-main.png) | ![Control Center · light](docs/screenshots/dots/light-main.png) |
| Control Center · dark | Control Center · light |
| ![Performance profile · dark](docs/screenshots/dots/dark-perf.png) | ![Performance profile · light](docs/screenshots/dots/light-perf.png) |
| Performance profile · dark | Performance profile · light |
| ![Fan profile · dark](docs/screenshots/dots/dark-fan.png) | ![Fan profile · light](docs/screenshots/dots/light-fan.png) |
| Fan profile · dark | Fan profile · light |
| ![Custom fan curve · dark](docs/screenshots/dots/dark-curve.png) | ![Custom fan curve · light](docs/screenshots/dots/light-curve.png) |
| Custom fan curve · dark | Custom fan curve · light |
| ![Stick LEDs · dark](docs/screenshots/dots/dark-leds.png) | ![Stick LEDs · light](docs/screenshots/dots/light-leds.png) |
| Stick LEDs · dark | Stick LEDs · light |
| ![Edit menu · dark](docs/screenshots/dots/dark-editmenu.png) | ![Edit menu · light](docs/screenshots/dots/light-editmenu.png) |
| Edit menu · dark | Edit menu · light |
| ![Edit mode · dark](docs/screenshots/dots/dark-edit.png) | ![Edit mode · light](docs/screenshots/dots/light-edit.png) |
| Edit mode · dark | Edit mode · light |
| ![Tile colour · dark](docs/screenshots/dots/dark-tile.png) | ![Tile colour · light](docs/screenshots/dots/light-tile.png) |
| Tile colour · dark | Tile colour · light |
| ![Game switcher · dark](docs/screenshots/dots/dark-switcher.png) | ![Game switcher · light](docs/screenshots/dots/light-switcher.png) |
| Game switcher · dark | Game switcher · light |

</details>

<details>
<summary>Cocoon – every screen, dark and light</summary>

| Dark | Light |
| --- | --- |
| ![Control Center · dark](docs/screenshots/cocoon/dark-main.png) | ![Control Center · light](docs/screenshots/cocoon/light-main.png) |
| Control Center · dark | Control Center · light |
| ![Performance profile · dark](docs/screenshots/cocoon/dark-perf.png) | ![Performance profile · light](docs/screenshots/cocoon/light-perf.png) |
| Performance profile · dark | Performance profile · light |
| ![Fan profile · dark](docs/screenshots/cocoon/dark-fan.png) | ![Fan profile · light](docs/screenshots/cocoon/light-fan.png) |
| Fan profile · dark | Fan profile · light |
| ![Custom fan curve · dark](docs/screenshots/cocoon/dark-curve.png) | ![Custom fan curve · light](docs/screenshots/cocoon/light-curve.png) |
| Custom fan curve · dark | Custom fan curve · light |
| ![Stick LEDs · dark](docs/screenshots/cocoon/dark-leds.png) | ![Stick LEDs · light](docs/screenshots/cocoon/light-leds.png) |
| Stick LEDs · dark | Stick LEDs · light |
| ![Edit menu · dark](docs/screenshots/cocoon/dark-editmenu.png) | ![Edit menu · light](docs/screenshots/cocoon/light-editmenu.png) |
| Edit menu · dark | Edit menu · light |
| ![Edit mode · dark](docs/screenshots/cocoon/dark-edit.png) | ![Edit mode · light](docs/screenshots/cocoon/light-edit.png) |
| Edit mode · dark | Edit mode · light |
| ![Tile colour · dark](docs/screenshots/cocoon/dark-tile.png) | ![Tile colour · light](docs/screenshots/cocoon/light-tile.png) |
| Tile colour · dark | Tile colour · light |
| ![Game switcher · dark](docs/screenshots/cocoon/dark-switcher.png) | ![Game switcher · light](docs/screenshots/cocoon/light-switcher.png) |
| Game switcher · dark | Game switcher · light |

</details>

## Features

### Muninn – Game Switcher

- Its own switcher: Android's recent-apps view is not opened at all, so nothing flickers or shows through
- Large card for the selected app, smaller cards beside it, with Hugminn's own preview pictures (taken when you press Home and every few seconds while you play)
- D-pad, left stick or L1/R1 to browse; touch: swipe sideways to browse, swipe a card up to close it, tap to open
- Follows Android's current task list; apps closed elsewhere disappear; your home app (Cocoon, ES-DE …) is never listed
- The switcher shows the game an emulator is running (from the launcher, the open game file, RetroArch's log), e.g. "Crash Bandicoot 2" above the DuckStation card
- Actions:

| Button | Action |
| --- | --- |
| A | Open the app (its running task comes back to the front) |
| □ / X | Close the app, also removing it from Android's recent apps |
| △ / Y | Options: App info, close all others |
| ○ / B | Back to where you were |
| Select | Screenshot with the overlay (also in the Control Center), saved to Pictures/Screenshots |

- The system's swipe-up gesture is covered by the switcher as soon as Android's view settles
- Classic mode (a frame over Android's own recent-apps view) remains available in the settings

### Huginn – Control Center

- Full-screen grid of square tiles with several pages (rows and columns adjustable in the settings); free cells open a picker sorted by type (displays, sliders, switches)
- Edit mode: hold A (or long-press) to move, resize and remove tiles by drag & drop or D-pad; tile colour; reset to default
- D-pad navigation follows the grid; nothing is preselected until you press a direction
- Switches (A toggles, □ opens the tile's submenu): WLAN, Bluetooth, stick LEDs, 60/120 Hz, Do not disturb, airplane mode, rotation lock, screen always on and more
- WLAN submenu: networks in range with signal, saved networks connect directly, password window for new ones
- Bluetooth submenu: paired devices (connect / disconnect), search and pair new devices
- Sliders for volume and brightness in 5 % steps, vertical or horizontal; confirm with A before the D-pad changes them
- Cards for performance and fan profiles (open the profile menu), CPU, GPU, RAM, temperatures, battery and FPS
- Battery card shows the time left (or the time until full while charging)
- Music tile: what is playing, play/pause, next and previous track for any music app
- Performance and fan profile per app: choose "Remember for …" while an emulator runs, and it always starts with that profile; leaving it brings back the previous setting
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

- Four styles across the whole app: **Modern** (Material 3), **Pixel** (pixel font, stepped corners), **Dot matrix** (Nothing-like dots) and **Cocoon** (light and playful with colourful icon badges, made to sit next to the Cocoon launcher)
- Themes: system, light, dark, OLED black
- Back up all settings to a file and restore them (Settings → Backup)
- Languages: English and German (follows the system language, or choose in the settings)
- Tile colour from presets or a colour wheel; it becomes the accent colour in every style
- PlayStation, Xbox or Nintendo button symbols, coloured or monochrome, following the device's controller layout

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

Every release is published under **Releases**, built and signed automatically by GitHub Actions.

- **Direct download:** [hugminn.apk](https://github.com/Ninte86hl/Hugminn/releases/latest/download/hugminn.apk) (always the newest release)
- **With [Obtainium](https://github.com/ImranR98/Obtainium) (recommended, keeps Hugminn up to date):** download [obtainium.json](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/obtainium.json) (also attached to every release) and import it in Obtainium under *Import/Export → Obtainium Import*.
  Manual setup works too: just add `https://github.com/Ninte86hl/Hugminn` as source.

After installing, open Hugminn: a short introduction explains the Game Switcher and the Control Center, turns on the accessibility service and lets you switch off what you don't need.

**Test builds** from the `test` branch (with extra diagnostic tools) are published as pre-releases named *Hugminn Test*. They are signed with the same key, so you can switch between test and release builds without reinstalling.

## Reporting bugs

Open Hugminn's settings and tap **Fehler auf GitHub melden**. It opens a new [issue](https://github.com/Ninte86hl/Hugminn/issues) already filled in with the version, your device and the last error; just describe what happened. Hugminn itself never sends anything.

## Permissions

Hugminn has no internet permission: it never sends anything anywhere. (Reporting a bug opens your browser with a prefilled GitHub issue; you decide whether to submit it.)

| Permission | What Hugminn uses it for |
| --- | --- |
| Accessibility service | The heart of the app: catching the Home button and controller buttons while an overlay is open, drawing the Control Center, switcher and performance overlay on top of everything, taking screenshots for the switcher previews, and confirming the Bluetooth pairing dialog for you. Hugminn does not read or store what you type or what other apps show. |
| Reorder tasks (`REORDER_TASKS`) | Bringing the game you pick in the switcher back to the front. |
| See installed apps (`QUERY_ALL_PACKAGES`) | Showing app names and icons in the switcher and telling games, emulators and RetroArch apart. |
| Usage access (`PACKAGE_USAGE_STATS`) | Only without the firmware's root service: guessing your recent apps when the task list cannot be read. |
| Location (`ACCESS_FINE_LOCATION`) and Wi-Fi state | Android only returns the list of nearby Wi-Fi networks to apps with location access. Hugminn uses it only for the Wi-Fi menu and never determines your location. |
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Finding, pairing and connecting Bluetooth devices in the Bluetooth menu. The scan is flagged as never used for location. |
| Photos (writing to `Pictures/Screenshots`) | Saving screenshots you take with Select. No storage permission is needed for this. |

On Retroid and AYN handhelds, Hugminn grants itself these permissions once through the firmware's built-in privileged service (`PServerBinder`), so you do not have to tap through permission dialogs. The same service changes the firmware settings (performance, fan, LEDs, button layout) that Android does not offer to normal apps.

## Acknowledgements

These projects showed what is possible on AYN and Retroid handhelds and inspired parts of Hugminn:

- [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder): controller navigation for the recent-apps view
- [Better Dual Screen Assistant](https://github.com/stormpanda/better-dual-screen-assistant): system settings and the firmware's privileged service
- [Bifrost](https://github.com/Pollux-MoonBench/Bifrost): stick LED control and its integration API
- [P.U.L.S.E.](https://github.com/keiretrogaming/pulse): performance tuning on the Retroid Pocket 6; the idea of measuring FPS through SurfaceFlinger TimeStats
- [ClusterTune](https://github.com/AurelioB/ClusterTune): CPU and GPU frequency limits

Hugminn is an independent project and is not affiliated with Retroid, AYN or any of the projects above.

## Third-party assets

- [Inter](https://github.com/rsms/inter) font by The Inter Project Authors, SIL Open Font License 1.1 (see `app/src/main/assets/fonts/Inter-OFL.txt`)
- [NF Pixels](https://github.com/sgigou/NF-Pixels) pixel font by Steve Gigou, SIL Open Font License 1.1 (see `app/src/main/assets/fonts/NFPixels-OFL.txt`)
- [Doto](https://github.com/oliverlalan/Doto) dot-matrix font by Óliver Lalan, SIL Open Font License 1.1 (see `app/src/main/assets/fonts/Doto-OFL.txt`)

## Author

Created by [Ninte86hl](https://github.com/Ninte86hl).

## License

Hugminn is free software, licensed under the [GNU General Public License v3.0](LICENSE). You may use, study, share and change it; changed versions you share must be under the same license. The bundled fonts keep their own SIL Open Font License.

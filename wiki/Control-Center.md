# Control Center (Huginn)

![Control Center](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/docs/screenshots/modern/dark-main.png)

Hold **Home** to open the Control Center: a full-screen grid of tiles with everything you change while playing.

## Navigating
- The D-pad moves through the grid; nothing is selected until you press a direction.
- **A** toggles a switch or confirms a slider, **□ / X** opens the tile's submenu, **△ / Y** opens the edit menu, **R1/L1** changes the page, **○ / B** closes.
- **Select** takes a screenshot.
- The device's **volume keys** work as usual; a volume slider on screen moves along.
- **Start** plays/pauses the music, **L2 / R2** skip to the previous / next track.

## Tiles
- **Switches:** Wi-Fi, Bluetooth, stick LEDs, 60/120 Hz, Do not disturb, airplane mode, rotation lock, screen always on and more.
- **Sliders:** volume and brightness in 5 % steps, vertical or horizontal. Press A first, then the D-pad changes the value, so you never change it by accident while moving.
- **Cards:** performance and fan profile, CPU, GPU, RAM, temperatures, battery and FPS. Gauges are coloured by load.
- **Battery:** shows the time left, or the time until full while charging.
- **Music:** what is playing, play/pause, next and previous track for any music app. As a large tile (2×2, 4×2 or 4×4) with cover, artist, progress and buttons.
  - Works with every player: from its media session, or from its notification for players that publish nothing else (e.g. Android's own music app).
  - Titles are tidied: video tags like "(Official Video)" go, "Artist - Song" is split, file names lose number and extension ("02 Song.mp3" → "Song").
  - A song too long for its line runs from right to left.
- **Screen off:** puts the handheld to sleep like the power button; the game pauses until you wake it.
- **Device battery:** battery level of connected Bluetooth controllers and headphones that report one.
- **Settings:** opens Android's settings.

## Sizes
Each element comes in the sizes that suit it (in [edit mode](Editing-the-Control-Center): A → Size). Elements in a size that is no longer offered take the nearest one.

| Element | Sizes | What the large sizes show |
| --- | --- | --- |
| Switches (Wi-Fi, Bluetooth, LEDs …) | 1×1, 2×1 | 1×1 the icon, 2×1 with name and state |
| Wi-Fi | 1×1, 2×1, 2×2, 4×2 | Current network and the strongest ones in range; tap one to connect |
| Bluetooth | 1×1, 2×1, 2×2, 4×2 | Paired devices with battery level; tap one to connect or disconnect |
| Music | 1×1, 2×1, 2×2, 4×2, 4×4 | Song, artist, cover, progress and ⏮ ⏯ ⏭ buttons; long songs scroll |
| System overview | 4×2, 4×4 | FPS, CPU, GPU, RAM, battery and power draw as rings; 4×4 adds frame time and temperature graphs |
| Performance / Fan | 2×1, 2×2, 3×2 | The current profile, big, with the live CPU clock or fan speed and a bar |
| FPS | 2×1, 2×2, 4×2 | Frame rate with frame time graph |
| CPU / GPU / RAM / Temperature / Power | 1×1, 2×1, 2×2 | 1×1 the value, 2×1 with details and bar, 2×2 as a ring |
| Battery | 1×1, 2×1, 2×2 | Ring with charge, time left, power draw and temperature |
| Brightness / Volume | 1×2–1×4 upright, 2×1–4×1 across | |

Every element may be added more than once (e.g. a small and a large music tile). Elements stay exactly where you put them in edit mode.

On large tiles, A does the same as on the small one (switch, play/pause, open the menu) and □ / X opens the submenu.

## Submenus
- **Wi-Fi:** networks in range with their signal; saved networks connect directly, new ones open a password window you can fill in with the controller.
- **Bluetooth:** paired devices (connect / disconnect) with the battery level of connected devices, search and pair new devices. Hugminn confirms the pairing dialog for you.
- **Performance and fan:** see [Performance and fan](Performance-and-Fan).
- **Stick LEDs:** see [Stick LEDs](Stick-LEDs).

## Pause the game
If you like, the game pauses (picture and sound) while the Control Center is open (Settings → Control Center → *Pause game*).

To change which tiles appear and where, see [Editing the Control Center](Editing-the-Control-Center).

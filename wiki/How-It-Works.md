# How it works

Technical background for the curious. You do not need any of this to use Hugminn.

## Accessibility service
Hugminn runs as an accessibility service. This is the only way for a normal app to react to the Home button, read controller buttons while its own windows are open and draw over every other app. Hugminn's windows are accessibility overlays; the performance overlay is not touchable, so every input reaches the game.

## Root service of the firmware
Retroid and AYN firmware ship a privileged service (`PServerBinder`) that runs commands as root. Hugminn uses it for everything Android does not allow normal apps: performance and fan profiles, the custom fan curve, stick LEDs, reading the task list, granting its own permissions and measuring FPS. Without it, the Game Switcher and the graphs still work, and device switches open Android's settings instead.

Longer commands are written to small script files inside Hugminn's own folder and then started, because the service only accepts short commands.

## Game Switcher
- Hugminn reads Android's task list and shows its own cards; Android's recent-apps view is not opened, so nothing flickers.
- Preview pictures are Hugminn's own screenshots, taken when you press Home and every few seconds while you play.
- Opening a card brings the app's existing task to the front (`REORDER_TASKS`), so the game continues where it was.
- Your home app is recognised by being Android's default home screen (or having "launcher" in its name) and is left out.

### Game titles
For an emulator, Hugminn looks for the running game in this order:
1. The game file the emulator has open (newest first, only files on storage, not caches)
2. The emulator's log
3. RetroArch's content history
4. The file the launcher passed when it started the emulator

The file name is then cleaned up: extensions such as `.iso` or `.xiso.iso` are removed, and names written in capitals become normal title case (abbreviations like HD, 3D or roman numerals stay).

## Pausing the game
While the switcher or Control Center is open, Hugminn places a black screen of its own over the game and takes audio focus. Android then pauses the game like any app in the background, including its sound. When the overlay closes, the black screen goes away and the game continues.

## FPS
Hugminn uses Android's SurfaceFlinger *TimeStats* (the idea comes from P.U.L.S.E.): the compositor records how long each picture of the game stayed on screen. Hugminn reads these times about every second, snaps them to the display's refresh steps (at 120 Hz: 8.3 ms, 16.7 ms …), averages over two seconds and rounds to rates the display can reach, such as 30, 60 or 120 FPS. This measures what you actually see, not what the game claims.

CPU and GPU load, RAM, temperatures and power are read from the kernel; the battery estimate divides the remaining charge by the average power draw.

## Profiles per app and per game
When the app in the foreground changes, Hugminn checks whether it has a remembered profile and sets it. If an emulator has profiles for single games, Hugminn looks up the running game every few seconds while the emulator is in front (the same way as for the [switcher's titles](#game-titles)) and switches to the game's profile. When you go home or to an app without a profile, the previous setting is restored.

## Battery of Bluetooth devices
Android reports the battery of Bluetooth devices that send it (most headphones, many controllers). Game controllers often report it only to the input system, so Hugminn also asks there. Devices that report nothing show no value.

## Stick LEDs as a display
In *Battery* or *Temperature* mode, Hugminn writes a status colour to the sticks every few seconds instead of running an effect. The low-battery warning (pulsing red at 10 %) still takes priority.

## Backup
Settings are stored in Android's shared preferences and can be exported as a JSON file through Android's file picker. Private values (such as the last error message) are left out.

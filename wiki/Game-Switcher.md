# Game Switcher (Muninn)

![Game switcher](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/docs/screenshots/modern/dark-switcher.png)

Muninn shows your recently used games and apps as large cards, the selected one in the middle with its preview picture. Open it by pressing **Home twice**.

## What it shows
- **Preview pictures** taken by Hugminn itself when you press Home and every few seconds while you play, so the cards show where you left off.
- **The game title** above the card when an emulator is running, e.g. "Crash Bandicoot 2" above DuckStation. Hugminn finds it from the open game file, the emulator's log, RetroArch's history or the launcher that started it (see [How it works](How-It-Works#game-titles)).
- Only real apps: your home app (Cocoon, ES-DE …) and Hugminn itself are never listed. Apps closed elsewhere disappear.

## Controls

| Button | Action |
| --- | --- |
| D-pad, left stick, L1/R1 | Browse |
| A | Open the app (its running task comes back to the front) |
| □ / X | Close the app, also removing it from Android's recent apps |
| △ / Y | Options: app info, close all others |
| ○ / B | Back to where you were |
| Select | Screenshot including the switcher, saved to Pictures/Screenshots |

Touch works too: swipe sideways to browse, swipe a card up to close it, tap to open.

## Options
- **Pause the game:** while the switcher is open, the game is covered and pauses, including its sound. It continues when you go back.
- **Swipe gesture:** swiping up from the bottom of the screen can open Hugminn's switcher instead of Android's recent apps.
- **Classic mode:** instead of its own switcher, Hugminn can lay a frame over Android's own recent-apps view (Settings → Game Switcher → *Own overview* off).

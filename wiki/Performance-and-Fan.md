# Performance and fan

## Performance profile

![Performance profile](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/docs/screenshots/modern/dark-perf.png)

*Normal*, *Performance* or *High performance*: the firmware's own profiles, which set how fast CPU and GPU may run. The card shows the current CPU limit.

As a large card (2×2 or 3×2) it shows the current profile big, with the live CPU clock and a bar; A opens the profiles.

## Fan profile

![Fan profile](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/docs/screenshots/modern/dark-fan.png)

*Off*, *Quiet*, *Smart*, *Sport* or *Custom curve*, the same modes as in Retroid's settings.

As a large card (2×2 or 3×2) it shows the current profile big, with how fast the fan turns (in percent) and the temperatures.

## Custom fan curve

![Custom fan curve](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/docs/screenshots/modern/dark-curve.png)

Five points set the fan speed for each temperature. Choose a point with ◀ ▶, change its speed with ▲ ▼, apply with A and leave with B. Hugminn always keeps a minimum speed and runs the fan at full speed from 90 °C, so the handheld cannot overheat.

## Profiles per emulator and per game
While a game or emulator is running, both menus offer **"Remember for …"**:
- **for the emulator** (e.g. *Remember for Cemu*): the emulator always starts with this profile.
- **for the game** (e.g. *Remember for The Wind Waker*), as soon as Hugminn has recognised the running game: only this game gets the profile.

A game's profile beats the emulator's; whatever it leaves open (for example only the fan is set) comes from the emulator's profile. Because the game is recognised a few seconds after it starts, the emulator's profile applies first and the game's follows. Switching games inside the emulator is noticed too. About 5 seconds after a profile takes over, a short notice in the top right corner says which one (e.g. "Profile for The Wind Waker: High performance · Fan Sport"). When you leave (home screen or an app without its own profile), the previous setting comes back.

Game profiles work for emulators whose game title Hugminn can see. For Android games the app itself is the game, so the profile per app is all you need.

All saved profiles are listed in the [settings](Settings) → *Profiles per app*, where you can remove them.

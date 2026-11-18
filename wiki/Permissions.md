# Permissions

Hugminn has **no internet permission**: it never sends anything anywhere. (Reporting a bug opens your browser with a prefilled GitHub issue; you decide whether to submit it.)

| Permission | What Hugminn uses it for |
| --- | --- |
| Accessibility service | The heart of the app: catching the Home button and controller buttons while an overlay is open, drawing the Control Center, switcher and performance overlay on top of everything, taking screenshots for the switcher previews, and confirming the Bluetooth pairing dialog for you. Hugminn does not read or store what you type or what other apps show. |
| Reorder tasks (`REORDER_TASKS`) | Bringing the game you pick in the switcher back to the front. |
| See installed apps (`QUERY_ALL_PACKAGES`) | Showing app names and icons in the switcher and telling games, emulators and RetroArch apart. |
| Usage access (`PACKAGE_USAGE_STATS`) | Only without the firmware's root service: guessing your recent apps when the task list cannot be read. |
| Location (`ACCESS_FINE_LOCATION`) and Wi-Fi state | Android only returns the list of nearby Wi-Fi networks to apps with location access. Hugminn uses it only for the Wi-Fi menu and never determines your location. |
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Finding, pairing and connecting Bluetooth devices in the Bluetooth menu and reading their battery level. The scan is flagged as never used for location. |
| Notification access (only the media part) | Lets the music tile see what is playing: song, artist, cover and position. Hugminn switches it on itself through the root service. Other notifications are not read; only for players that publish nothing else (such as Android's own music app), Hugminn looks at that player's playback notification. |
| Photos (writing to `Pictures/Screenshots`) | Saving screenshots you take with Select. No storage permission is needed for this. |

On Retroid and AYN handhelds, Hugminn grants itself these permissions once through the firmware's built-in privileged service (`PServerBinder`), so you do not have to tap through permission dialogs. The same service changes the firmware settings (performance, fan, LEDs, button layout) that Android does not offer to normal apps.


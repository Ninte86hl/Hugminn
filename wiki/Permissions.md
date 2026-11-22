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
| Photos (writing to `Pictures/Screenshots`) | Saving screenshots you take with the screenshot tile. No storage permission is needed for this. |
| Microphone (`RECORD_AUDIO`) | Only for [capture](Capture): Android requires it to record the game sound, and for the optional microphone track. Used only while a video is recording. |
| Notifications (`POST_NOTIFICATIONS`) and foreground service | While recording, Android requires a visible notification ("Hugminn is recording"). |
| Battery optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Without root: lets Android show its own "allow in the background?" dialog, so the system doesn't end Hugminn. |
| Modify system settings (`WRITE_SETTINGS`) | Without root: brightness, screen timeout and auto-rotate in the Control Center. With root these go through the root service. |

Since 1.12, the [setup wizard](Setup-Wizard) asks for each permission one by one with a short reason; nothing is granted silently. Where Android has no dialog (music access, background whitelist), Hugminn switches it on through the firmware's built-in privileged service (`PServerBinder`) – but only after you tap *Allow*. (Installations from before 1.12 keep working as before.) The same service changes the firmware settings (performance, fan, LEDs, button layout) that Android does not offer to normal apps.

**Test versions** additionally have the internet permission for their feedback tool: only when you press Select (or the floating feedback button), a report (screenshot, settings, diagnostics, recently opened windows) goes to the developer's private feedback repository. The regular version can't do that.


<p align="center">
  <img src="docs/icon.svg" alt="Hugminn-Symbol" width="160">
</p>

<h1 align="center">Hugminn</h1>

<p align="center">
  <b>Ein Game Switcher und Control Center für Retroid-Handhelds</b><br>
  Android 13 · für den Controller gemacht · Material Design
</p>

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.ninte86hl.hugminn%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FNinte86hl%2FHugminn%22%2C%22author%22%3A%22Ninte86hl%22%2C%22name%22%3A%22Hugminn%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20false%2C%20%5C%22fallbackToOlderReleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5EHugminn%20v%5C%5C%5C%5Cd%5C%22%2C%20%5C%22filterReleaseNotesByRegEx%5C%22%3A%20%5C%22%5C%22%2C%20%5C%22verifyLatestTag%5C%22%3A%20false%2C%20%5C%22sortMethodChoice%5C%22%3A%20%5C%22date%5C%22%2C%20%5C%22useLatestAssetDateAsReleaseDate%5C%22%3A%20false%2C%20%5C%22releaseTitleAsVersion%5C%22%3A%20false%2C%20%5C%22trackOnly%5C%22%3A%20false%2C%20%5C%22versionExtractionRegEx%5C%22%3A%20%5C%22%5C%22%2C%20%5C%22matchGroupToUse%5C%22%3A%20%5C%22%5C%22%2C%20%5C%22versionDetection%5C%22%3A%20true%2C%20%5C%22releaseDateAsVersion%5C%22%3A%20false%2C%20%5C%22useVersionCodeAsOSVersion%5C%22%3A%20false%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5Ehugminn%5C%5C%5C%5C.apk%24%5C%22%2C%20%5C%22invertAPKFilter%5C%22%3A%20false%2C%20%5C%22autoApkFilterByArch%5C%22%3A%20true%2C%20%5C%22appName%5C%22%3A%20%5C%22Hugminn%5C%22%2C%20%5C%22appAuthor%5C%22%3A%20%5C%22Ninte86hl%5C%22%2C%20%5C%22shizukuPretendToBeGooglePlay%5C%22%3A%20false%2C%20%5C%22allowInsecure%5C%22%3A%20false%2C%20%5C%22exemptFromBackgroundUpdates%5C%22%3A%20false%2C%20%5C%22skipUpdateNotifications%5C%22%3A%20false%2C%20%5C%22about%5C%22%3A%20%5C%22Game%20Switcher%20%28Muninn%29%20and%20Control%20Center%20%28Huginn%29%20for%20Retroid%20and%20AYN%20handhelds.%5C%22%2C%20%5C%22refreshBeforeDownload%5C%22%3A%20false%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D"><img src="docs/badges/obtainium.png" alt="Mit Obtainium holen" height="54"></a>
</p>

<p align="center"><a href="README.md">English</a> · <b>Deutsch</b></p>

---

> **Stand:** getestet auf dem Retroid Pocket 6 und dem Retroid Pocket Nova. Unterstützung für den Pocket 5 ist geplant.

## Was ist Hugminn?

<p align="center">
  <img src="docs/screenshots/styles.gif" alt="Control Center in allen Stilen: Modern, Pixel, Punktmatrix und Cocoon, hell und dunkel" width="100%">
</p>
<p align="center"><sub>Das Control Center in allen vier Stilen, hell und dunkel</sub></p>

Hugminn ergänzt den Retroid Pocket 6, Pocket Nova und Pocket 5 um zwei Oberflächen wie bei einer Konsole, ohne deinen Launcher zu ersetzen. Du behältst dein Lieblings-Frontend (zum Beispiel Cocoon) für deine Spielesammlung; Hugminn liegt darüber und ist immer nur einen Tastendruck entfernt.

### Das Wichtigste

- **Game Switcher mit Spieltiteln** – Home zweimal zeigt deine letzten Spiele als Karten und nennt das Spiel, das ein Emulator gerade spielt („Crash Bandicoot 2“ über DuckStation, auch bei RetroArch, Cemu, HAKU-X …).
- **Profile pro Spiel** – Leistungs- und Lüfterprofile pro Emulator *oder pro Spiel*; Hugminn erkennt das laufende Spiel und schaltet selbst um.
- **Echte FPS** – gemessen an dem, was der Bildschirm zeigt (SurfaceFlinger), als Karte, in der System-Übersicht und als Overlay über dem Spiel.
- **Große Kacheln** – System-Übersicht (4×4 mit Graphen), WLAN- und Bluetooth-Listen, Leistung mit aktuellem CPU-Takt, Lüfter mit Drehzahl, Akku mit Restzeit.
- **Musik** – Kachel bis 4×4 mit Cover, Fortschritt und Tasten für jeden Player (YouTube, Androids eigene Musik-App und andere); Start und L2/R2 steuern sie.
- **Stick-LEDs** – Farben und Effekte, oder als Anzeige für Akku oder Temperatur.
- **Bluetooth-Akku** – Controller und Kopfhörer zeigen ihren Ladestand.
- **Vier Stile** – Modern, Pixel, Punktmatrix und Cocoon, hell und dunkel; Kacheln, Tasten, Balken und Graphen folgen dem Stil.
- **Für den Controller gemacht** – alles mit den Tasten, das Spiel pausiert, solange du in den Menüs bist, die Lautstärketasten funktionieren weiter; Touch geht auch.

<p align="center">
  <img src="docs/screenshots/large-tiles.png" alt="Control Center im Punktmatrix-Stil mit großen Leistungs- und Lüfter-Karten" width="100%">
</p>
<p align="center"><sub>Große Leistungs- und Lüfter-Karte mit aktuellem CPU-Takt und Drehzahl (Punktmatrix-Stil)</sub></p>

### Warum Hugminn?

Android ist für Touchscreens gemacht. Am Handheld hältst du aber einen Controller in der Hand – und trotzdem musst du zum Spielewechseln oder um Lüfter, Leistungsprofil oder WLAN umzustellen auf den Bildschirm tippen, dich durch Androids Einstellungen wühlen oder ein Schnelleinstellungsmenü bedienen, das nie für ein Steuerkreuz gedacht war.

Das Ziel von Hugminn ist ein **controllerfreundliches Control Center und ein Game Switcher für Android**, so wie Konsolen sie haben: alles mit den Tasten erreichbar, die du sowieso hältst, auf einen Blick lesbar und ohne das Spiel länger als einen Tastendruck zu verlassen. Touch funktioniert weiterhin, du brauchst es aber nie.

### Der Name

In der nordischen Sage fliegen Odins zwei Raben jeden Tag über die Welt und berichten ihm, was sie gesehen haben. Hugminn verbindet ihre Namen, und jeder Rabe ist ein Teil der App:

- **Huginn** („Gedanke“) ist das **Control Center**. Es behält Leistung, Temperatur, Lüfter und LEDs im Blick.
- **Muninn** („Erinnerung“) ist der **Game Switcher**. Er merkt sich, was du zuletzt gespielt hast, und bringt dich direkt dorthin zurück.

## Bilder

| Dunkel | Hell |
| --- | --- |
| ![Modern dunkel](docs/screenshots/modern/dark-main.png) | ![Modern hell](docs/screenshots/modern/light-main.png) |
| Modern | Modern |
| ![Pixel dunkel](docs/screenshots/pixel/dark-main.png) | ![Pixel hell](docs/screenshots/pixel/light-main.png) |
| Pixel | Pixel |
| ![Punktmatrix dunkel](docs/screenshots/dots/dark-main.png) | ![Punktmatrix hell](docs/screenshots/dots/light-main.png) |
| Punktmatrix | Punktmatrix |
| ![Cocoon dunkel](docs/screenshots/cocoon/dark-main.png) | ![Cocoon hell](docs/screenshots/cocoon/light-main.png) |
| Cocoon | Cocoon |

Jede Ansicht in jedem Stil: **[Stile und Farbschemata](https://github.com/Ninte86hl/Hugminn/wiki/Stile-und-Farbschemata)** im Wiki.

## Funktionen

Jede Funktion hat eine eigene Seite im **[Wiki](https://github.com/Ninte86hl/Hugminn/wiki/Startseite)**.

| | |
| --- | --- |
| **[Game Switcher](https://github.com/Ninte86hl/Hugminn/wiki/Game-Switcher-DE)** | Home zweimal: deine letzten Spiele als große Karten mit Vorschaubild und dem Titel des laufenden Spiels, z. B. „Crash Bandicoot 2“ über DuckStation. Blättern mit dem Steuerkreuz, Apps mit einer Taste schließen; das Spiel kann solange pausieren. |
| **[Control Center](https://github.com/Ninte86hl/Hugminn/wiki/Control-Center-DE)** | Home lang: WLAN, Bluetooth, Helligkeit, Lautstärke, Musik, Akku-Restzeit, Controller-Akku, Bildschirm aus und Live-Werte für CPU, GPU, RAM und Temperatur in einem Raster aus Kacheln – viele auch als große Kacheln, dazu eine System-Übersicht. |
| **[Bearbeiten](https://github.com/Ninte86hl/Hugminn/wiki/Control-Center-bearbeiten)** | Kacheln auf mehreren Seiten anordnen, vergrößern und hinzufügen; Kachelfarbe wählen. |
| **[Leistung und Lüfter](https://github.com/Ninte86hl/Hugminn/wiki/Leistung-und-Luefter)** | Leistungs- und Lüfterprofile, eigene Lüfterkurve und Profile, die sich Hugminn pro Emulator oder pro Spiel merkt. |
| **[Musik](https://github.com/Ninte86hl/Hugminn/wiki/Control-Center-DE)** | Musik-Kachel bis 4×4 mit Cover, Fortschritt und Tasten für jeden Player; Start spielt/pausiert, L2/R2 springen zwischen Titeln, solange das Control Center offen ist. |
| **[Stick-LEDs](https://github.com/Ninte86hl/Hugminn/wiki/Stick-LEDs-DE)** | Farbrad, Schnellfarben und Effekte; oder die Sticks zeigen Akku oder Temperatur als Farbe. |
| **[Leistungs-Overlay](https://github.com/Ninte86hl/Hugminn/wiki/Leistungs-Overlay)** | FPS, Frametime, CPU, GPU, Temperatur und mehr über dem Spiel, im gewählten Stil. |
| **[Home-Taste](https://github.com/Ninte86hl/Hugminn/wiki/Home-Taste)** | Drücken, zweimal drücken und halten, jeweils mit eigener Aktion. |
| **[Stile und Farbschemata](https://github.com/Ninte86hl/Hugminn/wiki/Stile-und-Farbschemata)** | Modern, Pixel, Punktmatrix und Cocoon; hell, dunkel oder OLED-Schwarz; deine eigene Akzentfarbe. |
| **[Einstellungen](https://github.com/Ninte86hl/Hugminn/wiki/Einstellungen)** | Alles einstellbar, Sichern und Wiederherstellen, Deutsch und Englisch. |

Pausieren: Auf Wunsch hält das Spiel (Bild und Ton) an, solange der Switcher oder das Control Center offen ist. Alles geht mit dem Controller; Touch funktioniert auch.

## Unterstützte Geräte

| Gerät | Chip | Bildschirm | Stand |
| --- | --- | --- | --- |
| Retroid Pocket 6 | Snapdragon 8 Gen 2 (QCS8550) | 5,5" 16:9, 1080p, 120 Hz | Hauptgerät |
| Retroid Pocket Nova | QCS8550 | 4,5" 4:3, 1280×960, 120 Hz | getestet (standardmäßig 5 Rasterzeilen) |
| Retroid Pocket 5 | Snapdragon 865 | 5,5" 16:9, 1080p, 60 Hz | geplant |

Hugminn erkennt das Gerät und zeigt nur, was es kann – zum Beispiel keinen 120-Hz-Schalter auf dem Pocket 5. Auf dem Pocket Nova nutzt das Control Center ein 4:3-Layout.

## Voraussetzungen

- Eines der Geräte oben mit Original-Firmware
- Die meisten Systemfunktionen laufen über den eingebauten privilegierten Dienst der Firmware (`PServerBinder`); ein eigenes Root ist nicht nötig. Ohne ihn öffnet Hugminn stattdessen die passenden Systemeinstellungen.
- Hugminn läuft als Bedienungshilfe (für die Home-Taste, die Overlays und Screenshots). Technische Details: [So funktioniert es](https://github.com/Ninte86hl/Hugminn/wiki/So-funktioniert-es).

## Installation

Jede Version erscheint unter **Releases**, automatisch gebaut und signiert von GitHub Actions.

- **Direkter Download:** [hugminn.apk](https://github.com/Ninte86hl/Hugminn/releases/latest/download/hugminn.apk) (immer die neueste Version)
- **Mit [Obtainium](https://github.com/ImranR98/Obtainium) (empfohlen, hält Hugminn aktuell):** am Handheld [Zu Obtainium hinzufügen](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.ninte86hl.hugminn%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FNinte86hl%2FHugminn%22%2C%22author%22%3A%22Ninte86hl%22%2C%22name%22%3A%22Hugminn%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20false%2C%20%5C%22fallbackToOlderReleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5EHugminn%20v%5C%5C%5C%5Cd%5C%22%2C%20%5C%22filterReleaseNotesByRegEx%5C%22%3A%20%5C%22%5C%22%2C%20%5C%22verifyLatestTag%5C%22%3A%20false%2C%20%5C%22sortMethodChoice%5C%22%3A%20%5C%22date%5C%22%2C%20%5C%22useLatestAssetDateAsReleaseDate%5C%22%3A%20false%2C%20%5C%22releaseTitleAsVersion%5C%22%3A%20false%2C%20%5C%22trackOnly%5C%22%3A%20false%2C%20%5C%22versionExtractionRegEx%5C%22%3A%20%5C%22%5C%22%2C%20%5C%22matchGroupToUse%5C%22%3A%20%5C%22%5C%22%2C%20%5C%22versionDetection%5C%22%3A%20true%2C%20%5C%22releaseDateAsVersion%5C%22%3A%20false%2C%20%5C%22useVersionCodeAsOSVersion%5C%22%3A%20false%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5Ehugminn%5C%5C%5C%5C.apk%24%5C%22%2C%20%5C%22invertAPKFilter%5C%22%3A%20false%2C%20%5C%22autoApkFilterByArch%5C%22%3A%20true%2C%20%5C%22appName%5C%22%3A%20%5C%22Hugminn%5C%22%2C%20%5C%22appAuthor%5C%22%3A%20%5C%22Ninte86hl%5C%22%2C%20%5C%22shizukuPretendToBeGooglePlay%5C%22%3A%20false%2C%20%5C%22allowInsecure%5C%22%3A%20false%2C%20%5C%22exemptFromBackgroundUpdates%5C%22%3A%20false%2C%20%5C%22skipUpdateNotifications%5C%22%3A%20false%2C%20%5C%22about%5C%22%3A%20%5C%22Game%20Switcher%20%28Muninn%29%20and%20Control%20Center%20%28Huginn%29%20for%20Retroid%20and%20AYN%20handhelds.%5C%22%2C%20%5C%22refreshBeforeDownload%5C%22%3A%20false%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D) antippen – Obtainium öffnet sich mit Hugminn zum Hinzufügen. Oder [obtainium.json](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/obtainium.json) herunterladen und in Obtainium unter *Import/Export → Obtainium Import* importieren.
  Es geht auch von Hand: einfach `https://github.com/Ninte86hl/Hugminn` als Quelle hinzufügen.

Nach der Installation Hugminn öffnen: eine kurze [Einführung](https://github.com/Ninte86hl/Hugminn/wiki/Einrichtungsassistent) erklärt Game Switcher und Control Center, schaltet die Bedienungshilfe ein und lässt dich abschalten, was du nicht brauchst.

**Testversionen** aus dem `test`-Zweig (mit zusätzlichen Diagnosewerkzeugen) erscheinen als Vorabversionen namens *Hugminn Test*; für Obtainium gibt es [obtainium-test.json](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/obtainium-test.json) (hängt auch an jeder Testversion). Sie sind mit demselben Schlüssel signiert, du kannst also ohne Neuinstallation zwischen Test- und normaler Version wechseln.

## Fehler melden

Einstellungen → **Fehler melden** öffnet ein vorausgefülltes [Issue](https://github.com/Ninte86hl/Hugminn/issues) auf GitHub. Mehr dazu im [Wiki](https://github.com/Ninte86hl/Hugminn/wiki/Fehler-melden).

## Berechtigungen

Hugminn hat **keine Internet-Berechtigung** und sendet nie etwas irgendwohin. Es braucht die Bedienungshilfe und ein paar Berechtigungen für WLAN, Bluetooth und den Switcher; wofür jede einzelne ist, steht im Wiki: **[Berechtigungen](https://github.com/Ninte86hl/Hugminn/wiki/Berechtigungen)**.

## Danksagungen

Diese Projekte haben gezeigt, was auf AYN- und Retroid-Handhelds möglich ist, und Teile von Hugminn inspiriert:

- [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder): Controller-Steuerung für die Übersicht der letzten Apps
- [Better Dual Screen Assistant](https://github.com/stormpanda/better-dual-screen-assistant): Systemeinstellungen und der privilegierte Dienst der Firmware
- [Bifrost](https://github.com/Pollux-MoonBench/Bifrost): Steuerung der Stick-LEDs und ihre Schnittstelle
- [P.U.L.S.E.](https://github.com/keiretrogaming/pulse): Leistungsoptimierung auf dem Retroid Pocket 6; die Idee, FPS über SurfaceFlinger TimeStats zu messen
- [ClusterTune](https://github.com/AurelioB/ClusterTune): CPU- und GPU-Frequenzgrenzen

Hugminn ist ein unabhängiges Projekt und gehört weder zu Retroid, AYN noch zu einem der Projekte oben.

## Fremde Inhalte

- [Inter](https://github.com/rsms/inter) von The Inter Project Authors, SIL Open Font License 1.1 (siehe `app/src/main/assets/fonts/Inter-OFL.txt`)
- [NF Pixels](https://github.com/sgigou/NF-Pixels) Pixelschrift von Steve Gigou, SIL Open Font License 1.1 (siehe `app/src/main/assets/fonts/NFPixels-OFL.txt`)
- [Doto](https://github.com/oliverlalan/Doto) Punktmatrix-Schrift von Óliver Lalan, SIL Open Font License 1.1 (siehe `app/src/main/assets/fonts/Doto-OFL.txt`)
- „Get it on Obtainium“-Badge vom [Obtainium](https://github.com/ImranR98/Obtainium)-Projekt, das ihn zum Verlinken von Apps bereitstellt

## Autor

Erstellt von [Ninte86hl](https://github.com/Ninte86hl).

## Lizenz

Hugminn ist freie Software unter der [GNU General Public License v3.0](LICENSE). Du darfst es nutzen, untersuchen, weitergeben und verändern; veränderte Versionen, die du weitergibst, müssen unter derselben Lizenz stehen. Die enthaltenen Schriften behalten ihre eigene SIL Open Font License.

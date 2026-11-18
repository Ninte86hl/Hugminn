# So funktioniert es

Technischer Hintergrund für Neugierige. Zum Benutzen von Hugminn brauchst du nichts davon.

## Bedienungshilfe
Hugminn läuft als Bedienungshilfe. Nur so kann eine normale App auf die Home-Taste reagieren, Controller-Tasten lesen, solange ihre eigenen Fenster offen sind, und über allen anderen Apps zeichnen. Hugminns Fenster sind Overlays der Bedienungshilfe; das Leistungs-Overlay nimmt keine Eingaben an, alles erreicht das Spiel.

## Root-Dienst der Firmware
Die Firmware von Retroid und AYN bringt einen privilegierten Dienst mit (`PServerBinder`), der Befehle als Root ausführt. Hugminn nutzt ihn für alles, was Android normalen Apps nicht erlaubt: Leistungs- und Lüfterprofile, die eigene Lüfterkurve, Stick-LEDs, die Aufgabenliste lesen, eigene Berechtigungen erteilen und FPS messen. Ohne ihn funktionieren Game Switcher und Graphen weiter, und Geräteschalter öffnen stattdessen Androids Einstellungen.

Längere Befehle schreibt Hugminn in kleine Skriptdateien im eigenen Ordner und startet diese, weil der Dienst nur kurze Befehle annimmt.

## Game Switcher
- Hugminn liest Androids Aufgabenliste und zeigt eigene Karten; Androids Übersicht wird nicht geöffnet, deshalb flackert nichts.
- Die Vorschaubilder sind Hugminns eigene Screenshots, aufgenommen beim Drücken von Home und alle paar Sekunden beim Spielen.
- Eine Karte öffnen holt die bestehende Aufgabe der App nach vorn (`REORDER_TASKS`), das Spiel läuft also dort weiter, wo es war.
- Dein Launcher wird erkannt, weil er Androids Standard-Startbildschirm ist (oder „launcher“ im Namen trägt), und bleibt außen vor.

### Spieltitel
Bei einem Emulator sucht Hugminn das laufende Spiel in dieser Reihenfolge:
1. Die Spieldatei, die der Emulator geöffnet hat (neueste zuerst, nur Dateien auf dem Speicher, keine Caches)
2. Das Protokoll (Log) des Emulators
3. RetroArchs Verlauf
4. Die Datei, die der Launcher beim Start übergeben hat

Der Dateiname wird dann aufgeräumt: Endungen wie `.iso` oder `.xiso.iso` fallen weg, und Namen in Großbuchstaben werden normal geschrieben (Abkürzungen wie HD, 3D oder römische Zahlen bleiben).

## Spiel pausieren
Solange Switcher oder Control Center offen sind, legt Hugminn einen eigenen schwarzen Bildschirm über das Spiel und übernimmt den Audiofokus. Android pausiert das Spiel dann wie jede App im Hintergrund, auch den Ton. Schließt sich das Overlay, verschwindet der schwarze Bildschirm und das Spiel läuft weiter.

## FPS
Hugminn nutzt Androids SurfaceFlinger-*TimeStats* (die Idee stammt von P.U.L.S.E.): Der Compositor zeichnet auf, wie lange jedes Bild des Spiels auf dem Bildschirm stand. Hugminn liest diese Zeiten etwa jede Sekunde, rastet sie auf die Bildwiederholschritte des Displays ein (bei 120 Hz: 8,3 ms, 16,7 ms …), mittelt über zwei Sekunden und rundet auf erreichbare Raten wie 30, 60 oder 120 FPS. Gemessen wird also, was du wirklich siehst, nicht was das Spiel behauptet.

CPU- und GPU-Last, RAM, Temperaturen und Leistung kommen aus dem Kernel; die Akku-Restzeit teilt die verbleibende Ladung durch die durchschnittliche Leistungsaufnahme.

## Profile pro App und pro Spiel
Wechselt die App im Vordergrund, prüft Hugminn, ob sie ein gemerktes Profil hat, und stellt es ein. Hat ein Emulator Profile für einzelne Spiele, schaut Hugminn alle paar Sekunden nach, welches Spiel läuft (genauso wie bei den [Spieltiteln](#spieltitel)), und wechselt auf das Profil des Spiels. Gehst du zum Startbildschirm oder in eine App ohne Profil, kommt die vorige Einstellung zurück.

## Akku von Bluetooth-Geräten
Android meldet den Akku von Bluetooth-Geräten, die ihn senden (die meisten Kopfhörer, viele Controller). Gamecontroller melden ihn oft nur an das Eingabesystem, deshalb fragt Hugminn auch dort. Geräte, die nichts melden, zeigen keinen Wert.

## Stick-LEDs als Anzeige
Im Modus *Akku* oder *Temperatur* schreibt Hugminn alle paar Sekunden eine Statusfarbe auf die Sticks, statt einen Effekt abzuspielen. Die Warnung bei schwachem Akku (rotes Pulsieren bei 10 %) hat weiter Vorrang.

## Sichern
Die Einstellungen liegen in Androids Shared Preferences und lassen sich über die Dateiauswahl als JSON-Datei exportieren. Private Werte (etwa die letzte Fehlermeldung) bleiben außen vor.

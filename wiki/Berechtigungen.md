# Berechtigungen

Hugminn hat **keine Internet-Berechtigung**: es sendet nie etwas irgendwohin. (Fehler melden öffnet deinen Browser mit einem vorausgefüllten GitHub-Issue; ob du es abschickst, entscheidest du.)

| Berechtigung | Wofür Hugminn sie braucht |
| --- | --- |
| Bedienungshilfe | Das Herz der App: Home-Taste und Controller-Tasten abfangen, solange ein Overlay offen ist, Control Center, Switcher und Leistungs-Overlay über allem anzeigen, Screenshots für die Vorschaubilder aufnehmen und den Bluetooth-Kopplungsdialog für dich bestätigen. Hugminn liest oder speichert nicht, was du tippst oder was andere Apps anzeigen. |
| Aufgaben neu ordnen (`REORDER_TASKS`) | Das im Switcher gewählte Spiel wieder nach vorn holen. |
| Installierte Apps sehen (`QUERY_ALL_PACKAGES`) | App-Namen und Symbole im Switcher zeigen und Spiele, Emulatoren und RetroArch unterscheiden. |
| Nutzungszugriff (`PACKAGE_USAGE_STATS`) | Nur ohne Root-Dienst der Firmware: die letzten Apps erraten, wenn die Aufgabenliste nicht lesbar ist. |
| Standort (`ACCESS_FINE_LOCATION`) und WLAN-Status | Android gibt die Liste der WLAN-Netze in der Nähe nur an Apps mit Standortzugriff heraus. Hugminn nutzt sie nur für das WLAN-Menü und bestimmt nie deinen Standort. |
| Geräte in der Nähe (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | Bluetooth-Geräte im Bluetooth-Menü finden, koppeln, verbinden und ihren Akkustand lesen. Die Suche ist als „nie für den Standort“ gekennzeichnet. |
| Benachrichtigungszugriff (nur der Medienteil) | Damit die Musik-Kachel sieht, was läuft: Titel, Interpret, Cover und Position. Hugminn schaltet ihn selbst über den Root-Dienst ein. Andere Benachrichtigungen werden nicht gelesen; nur bei Playern, die sonst nichts melden (wie Androids eigene Musik-App), schaut Hugminn auf deren Wiedergabe-Benachrichtigung. |
| Fotos (Schreiben nach `Pictures/Screenshots`) | Screenshots speichern, die du mit der Screenshot-Kachel aufnimmst. Dafür ist keine Speicherberechtigung nötig. |
| Mikrofon (`RECORD_AUDIO`) | Nur für die [Aufnahme](Aufnahme): Android verlangt es, um den Spielton aufzunehmen, und für die optionale Mikrofonspur. Wird nur während einer Videoaufnahme benutzt. |
| Mitteilungen (`POST_NOTIFICATIONS`) und Vordergrunddienst | Während einer Aufnahme verlangt Android eine sichtbare Mitteilung („Hugminn nimmt auf“). |
| Ausnahme von der Akku-Optimierung (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Ohne Root: damit Android seinen eigenen Dialog „im Hintergrund erlauben?“ zeigen kann und Hugminn nicht beendet. |
| Systemeinstellungen ändern (`WRITE_SETTINGS`) | Ohne Root: Helligkeit, Bildschirm-Aus-Zeit und automatisches Drehen im Control Center. Mit Root läuft das über den Root-Dienst. |

Seit 1.12 fragt der [Einrichtungsassistent](Einrichtungsassistent) jede Berechtigung einzeln mit kurzem Grund ab; nichts wird still erteilt. Wo Android keinen Dialog hat (Musik-Zugriff, Hintergrund-Whitelist), schaltet Hugminn es über den eingebauten privilegierten Dienst der Firmware (`PServerBinder`) ein – aber erst, wenn du auf *Erlauben* tippst. (Installationen von vor 1.12 laufen weiter wie bisher.) Derselbe Dienst ändert die Firmware-Einstellungen (Leistung, Lüfter, LEDs, Tastenlayout), die Android normalen Apps nicht anbietet.

**Test-Versionen** haben zusätzlich die Internet-Berechtigung für ihr Feedback-Werkzeug: nur wenn du Select (oder den schwebenden Feedback-Knopf) drückst, geht ein Bericht (Screenshot, Einstellungen, Diagnose, zuletzt geöffnete Fenster) an das private Feedback-Repository des Entwicklers. Die normale Version kann das nicht.

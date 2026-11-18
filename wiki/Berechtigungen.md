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
| Fotos (Schreiben nach `Pictures/Screenshots`) | Screenshots speichern, die du mit Select aufnimmst. Dafür ist keine Speicherberechtigung nötig. |

Auf Retroid- und AYN-Handhelds erteilt sich Hugminn diese Berechtigungen einmalig selbst über den eingebauten privilegierten Dienst der Firmware (`PServerBinder`), du musst dich also nicht durch Berechtigungsdialoge tippen. Derselbe Dienst ändert die Firmware-Einstellungen (Leistung, Lüfter, LEDs, Tastenlayout), die Android normalen Apps nicht anbietet.

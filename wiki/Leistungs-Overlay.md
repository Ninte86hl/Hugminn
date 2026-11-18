# Leistungs-Overlay

Eine kleine Anzeige über dem Spiel, wie das Leistungs-Overlay von Steam. Sie nimmt nie Berührungen oder Tasten an; alles erreicht das Spiel.

## Werte
Beliebig wählbar: **FPS**, **Frametime-Graph**, **CPU**, **GPU**, **RAM**, **Temperatur**, **Leistungsaufnahme**, **Akku** und **Uhrzeit**.

## Einstellungen
Im Bearbeiten-Menü des Control Centers → *Leistungs-Overlay*:
- An / aus
- Position: oben links, oben Mitte, oben rechts, unten links, unten rechts
- Zeile oder Spalte
- Größe: mit dem Steuerkreuz (◀ ▶) ändern und das Ergebnis gleich sehen

## Aussehen
Das Overlay folgt dem gewählten [Stil](Stile-und-Farbschemata) und hell oder dunkel: Schrift, Rahmen und Farben passen zum Control Center. FPS-Wert und Graph haben die Akzentfarbe (Kachelfarbe), CPU, GPU und Temperatur die Farben der Anzeigen. Änderungen gelten sofort.

## So werden die FPS gemessen
Hugminn fragt Androids Bildschirm-Compositor, wie oft das Spiel wirklich ein neues Bild zeigt, mittelt über zwei Sekunden und rundet auf Bildraten, die der Bildschirm erreichen kann (z. B. 30, 60 oder 120 FPS bei 120 Hz). Details unter [So funktioniert es](So-funktioniert-es#fps).

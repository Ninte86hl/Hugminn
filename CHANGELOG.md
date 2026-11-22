# Changelog

Each release on GitHub shows its section of this file. / Jede Version auf GitHub zeigt ihren Abschnitt aus dieser Datei.

## 1.14.0

### 🇬🇧 English
**Lights (stick LEDs)**
- New LED window with three tabs (L1/R1): *Device*, *Mode* and *Colour*. Every light on its own – the two sticks, on the AYN Odin 2 also the two side lights.
- *Shows*: a switch per light with Off · Colour · Combo · Effect. Each light keeps its colour and effect, the switch only picks what lights – switching needs no setting up again.
- Brightness and saturation per light as bars in 5 % steps; colour combinations (left lights the first colour, right ones the second).
- *Colour*: 15 colours, the 16th tile opens the colour wheel – the LED shows each colour right away. After choosing, the window goes back to *Device* for the next light.
- *Mode*: static, breathing, pulsing, rainbow, colour cycle, battery and temperature display, with speed; breathing and pulsing use the light's colour. *For all lights that are on* gives every light the same effect.
- North button on *Shows* jumps straight to the setting of what the light shows; west copies the light to all others.
- Rainbow and colour cycle run calmer, without flicker.
- Fits 16:9 and 4:3: on small screens the content shrinks so everything is visible.
- LED tester in the device test: lights every LED setting and driver zone in turn and asks what lit up.
- AYN Odin 2: the tile is called *Lights* and covers sticks and side lights.

**Buttons**
- Buttons go by position, the same on every device and layout: **north** = options / set up, **west** = second action (switcher: close the app, Control Center: edit, LED window: copy to all, keyboard: delete). Confirm and back follow the layout as on that console.
- In the Control Center, *Options* and *Edit* swapped places to match the game switcher.
- Button symbols are called **Modern**, **Retro** and **Modern (alternative)** (shapes); shoulder, trigger, Start and Select are labelled the way each style names them (LB/RB · L/R · L1/R1 …).
- The hints at the bottom always show the D-pad, north, west, confirm and back in the same order; L/R only when there are pages or tabs.
- **Edit mode: A finishes and keeps the changes, B now cancels** and leaves the layout as it was. North opens an element's options (or adds one on a free cell), west removes it.
- Select no longer takes a screenshot in the switcher and the Control Center (Power + Volume down or the screenshot tile still do).
- Keyboard (Wi-Fi password): Select (or the ⌨ button) switches to Android's own keyboard and back; the A that chose the network no longer types a letter.

**Video apps**
- Always landscape: YouTube, Netflix, Disney+, Prime Video and Crunchyroll are shown fully sideways, not only full-screen videos. No root needed. Settings → Game Switcher.
- Keep playing: opening the switcher or the Control Center no longer pauses music or videos – games still pause.
- Switching to a paused video app keeps it paused.
- Hide the navigation bar (root): the bar or gesture line disappears everywhere, a swipe from the bottom brings it back for a moment. Settings → Look.

**Control Center and switcher**
- Switcher previews in the game's own shape: black bars are cut off, a 4:3, 8:7 or square game gets a card in its format.
- The Performance and Fan cards open their profiles on touch; touching a value card no longer redraws the Control Center.
- Edit mode: moving the cursor no longer makes the tiles flicker; the selection frame follows each tile's own corners and is no longer cut off at the edge.

**AYN handhelds**
- The built-in "Odin Controller" counts as the handheld's own controls, not an external controller.
- The trigger mode tile also shows before the mode was ever changed in AYN's settings.
- AYN Odin 2: battery and GPU values are read where the firmware hides the usual files.
- The Button layout tile uses AYN's controller style – Modern, Retro or built-in controls off.
- "Nintendo" is called "Retro" everywhere.

**Other**
- Missing English translations added.
- Built-in controls off: closing the Control Center from that menu no longer crashes Hugminn.

### 🇩🇪 Deutsch
**Beleuchtung (Stick-LEDs)**
- Neues LED-Fenster mit drei Tabs (L1/R1): *Gerät*, *Modus* und *Farbe*. Jedes Licht einzeln – die beiden Sticks, auf dem AYN Odin 2 auch die beiden Seitenleuchten.
- *Zeigt*: ein Schalter pro Licht mit Aus · Farbe · Kombi · Effekt. Jedes Licht merkt sich Farbe und Effekt, der Schalter wählt nur, was leuchtet – Umschalten ohne neu einzustellen.
- Helligkeit und Sättigung pro Licht als Balken in 5-%-Schritten; Farbkombinationen (linke Lichter die erste Farbe, rechte die zweite).
- *Farbe*: 15 Farben, die 16. Kachel öffnet das Farbrad – die LED zeigt jede Farbe sofort. Nach der Wahl geht es zurück zu *Gerät* für das nächste Licht.
- *Modus*: Statisch, Atmen, Pulsieren, Regenbogen, Farbwechsel, Akku- und Temperaturanzeige, mit Tempo; Atmen und Pulsieren nehmen die Farbe des Lichts. *Für alle eingeschalteten Lichter* gibt allen denselben Effekt.
- Nord-Taste auf *Zeigt* springt direkt zur Einstellung dessen, was das Licht zeigt; West überträgt das Licht auf alle anderen.
- Regenbogen und Farbwechsel laufen ruhiger, ohne Flackern.
- Passt auf 16:9 und 4:3: Auf kleinen Bildschirmen wird der Inhalt verkleinert, damit alles zu sehen ist.
- LED-Tester im Gerätetest: schaltet jede LED-Einstellung und Treiber-Zone nacheinander ein und fragt, was geleuchtet hat.
- AYN Odin 2: Die Kachel heißt *Beleuchtung* und umfasst Sticks und Seitenleuchten.

**Tasten**
- Die Tasten gehen nach Position, auf jedem Gerät und Layout gleich: **Nord** = Optionen / Einstellen, **West** = zweite Aktion (Switcher: App schließen, Control Center: Bearbeiten, LED-Fenster: auf alle übertragen, Tastatur: löschen). Bestätigen und Zurück folgen dem Layout wie bei der jeweiligen Konsole.
- Im Control Center haben *Optionen* und *Bearbeiten* die Plätze getauscht, passend zum Game Switcher.
- Die Tastensymbole heißen **Modern**, **Retro** und **Modern (alternativ)** (Formen); Schulter, Trigger, Start und Select sind so beschriftet, wie der jeweilige Stil sie nennt (LB/RB · L/R · L1/R1 …).
- Die Hinweise unten zeigen immer Steuerkreuz, Nord, West, Bestätigen und Zurück in derselben Reihenfolge; L/R nur, wenn es Seiten oder Tabs gibt.
- **Bearbeitungsmodus: A beendet und behält die Änderungen, B bricht jetzt ab** und lässt das Layout, wie es war. Nord öffnet die Optionen eines Elements (oder fügt auf einem freien Feld eins hinzu), West entfernt es.
- Select macht im Switcher und Control Center keinen Screenshot mehr (Power + Leiser oder die Screenshot-Kachel weiterhin).
- Tastatur (WLAN-Passwort): Select (oder der ⌨-Knopf) wechselt zur Android-Tastatur und zurück; das A, mit dem das Netz gewählt wurde, tippt keinen Buchstaben mehr.

**Video-Apps**
- Immer quer: YouTube, Netflix, Disney+, Prime Video und Crunchyroll erscheinen komplett quer, nicht nur Videos im Vollbild. Ohne Root. Einstellungen → Game Switcher.
- Laufen weiter: Switcher und Control Center pausieren Musik und Videos beim Öffnen nicht mehr – Spiele halten weiterhin an.
- Wechselt man zu einer pausierten Video-App, bleibt sie pausiert.
- Navigationsleiste ausblenden (Root): Die Leiste bzw. der Gestenstrich verschwindet überall, ein Wisch von unten holt sie kurz zurück. Einstellungen → Darstellung.

**Control Center und Switcher**
- Vorschaubilder im Switcher im Format des Spiels: Schwarze Balken werden abgeschnitten, ein 4:3-, 8:7- oder quadratisches Spiel bekommt eine Karte in seinem Format.
- Die Karten Leistung und Lüfter öffnen ihre Profile per Touch; Antippen einer Wertekarte baut das Control Center nicht mehr neu auf.
- Bearbeitungsmodus: Beim Bewegen der Auswahl flackern die Kacheln nicht mehr; der Auswahlrahmen folgt den Ecken jeder Kachel und wird am Rand nicht mehr abgeschnitten.

**AYN-Handhelds**
- Der eingebaute „Odin Controller“ zählt als eigene Steuerung des Geräts, nicht als externer Controller.
- Die Kachel Trigger-Modus erscheint auch, wenn der Modus in AYNs Einstellungen noch nie umgestellt wurde.
- AYN Odin 2: Akku- und GPU-Werte werden gelesen, wo die Firmware die üblichen Dateien versteckt.
- Die Kachel Tastenlayout nutzt AYNs Controller-Stil – Modern, Retro oder eingebaute Steuerung aus.
- Aus „Nintendo“ wird überall „Retro“.

**Sonstiges**
- Fehlende englische Übersetzungen ergänzt.
- Eingebaute Steuerung aus: Das Schließen des Control Centers aus diesem Menü bringt Hugminn nicht mehr zum Absturz.

## 1.13.2

### 🇬🇧 English
- Control Center: touching a tile only uses it (sliders, menus of Performance, Fan & co.) and never picks it up any more – 1.13.1 did not catch every slider. Tiles are moved by touch only in edit mode.
- Every button hint at the bottom of the Control Center can be tapped and does what its button does – "Y Edit" opens editing for touch.

### 🇩🇪 Deutsch
- Control Center: Berühren einer Kachel bedient sie nur noch (Regler, Menüs von Leistung, Lüfter & Co.) und hebt sie nie mehr an – 1.13.1 hat nicht jeden Regler erwischt. Verschoben wird per Touch nur noch im Bearbeiten-Modus.
- Jeder Tastenhinweis unten im Control Center lässt sich antippen und macht dasselbe wie die Taste – „Y Bearbeiten“ öffnet das Bearbeiten für Touch.

## 1.13.1

### 🇬🇧 English
- Control Center: moving a slider (brightness, volume) no longer picks up the whole tile by mistake. Moving a tile by touch now needs a longer hold (0.8 s), and a short vibration tells you when moving starts.

### 🇩🇪 Deutsch
- Control Center: Wer einen Schieberegler bedient (Helligkeit, Lautstärke), verschiebt nicht mehr versehentlich die ganze Kachel. Kacheln per Touch verschieben braucht jetzt etwas länger Gedrückthalten (0,8 s), und eine kurze Vibration zeigt an, wann das Verschieben beginnt.

## 1.13.0

### 🇬🇧 English
**Background & tiles** (Control Center → Y → Background, or Settings → Appearance)
- Background of the Control Center and the game switcher: the style's colour, the Android wallpaper (root) or a picture of your own – with darkening (lightening in light themes) and blur.
- Tiles in three styles: standard, OLED black (pitch-black tiles, dark colour schemes) or glass (see-through). Active tiles keep the tile colour you chose, including your own colour from the wheel.
- Glass strength in five steps, from clear to frosted.
- New selection mark in the Control Center and the game switcher: corner brackets that settle in briefly and then stay still – the default in the Cocoon style, selectable for every style. The full frame stays available.
- On a picture, the headers show their texts in full colour, and the button hints (Control Center) and the bottom bar (game switcher) sit on a capsule made like the tiles – standard, OLED black or glass.
- Readability first: text on active tiles and the selection mark adapt to light or dark tiles, glass tiles always keep a calm background behind them, and a very dark own colour on OLED tiles gets a light edge.

**New style: Liquid Glass**
- A fifth style next to Modern, Pixel, Dot matrix and Cocoon: see-through glass tiles over your blurred Android wallpaper (root) or your own picture, clean type, a soft blue accent and corner marks – smoked glass in dark colour schemes, frosted glass in light ones. Without a picture, a soft colour wash lies behind the glass.

**Edit menu (Y) in categories**
- Layout, Style, Background and Overlay side by side, switched with L1/R1. Values change right in their line with ◀ ▶ and show at once behind the menu – far fewer submenus.
- The menu sits at the side as a panel, so every change can be watched right next to it on the Control Center – including the selection mark. Wider on 4:3 screens like the Pocket Nova.
- Changing style, colour scheme or tile colour (also with the colour wheel) keeps you in the menu, and the values on the cards stay in place while the panel is redrawn.

### 🇩🇪 Deutsch
**Hintergrund & Kacheln** (Control Center → Y → Hintergrund, oder Einstellungen → Darstellung)
- Hintergrund von Control Center und Game Switcher: die Farbe des Stils, das Android-Hintergrundbild (Root) oder ein eigenes Bild – mit Abdunkeln (bei hellen Farbschemen Aufhellen) und Weichzeichnen.
- Kacheln in drei Varianten: Standard, OLED-Schwarz (tiefschwarze Kacheln, dunkle Farbschemen) oder Glas (durchscheinend). Aktive Kacheln behalten die gewählte Kachelfarbe, auch die eigene Farbe vom Farbrad.
- Glas-Stärke in fünf Stufen, von klar bis milchig.
- Neue Auswahl-Markierung im Control Center und im Game Switcher: Ecken, die kurz einrasten und dann ruhig stehen – Standard im Stil Cocoon, für jeden Stil wählbar. Der volle Rahmen bleibt wählbar.
- Auf einem Bild zeigen die Kopfzeilen ihre Texte in voller Farbe, und die Tastenhinweise (Control Center) und die untere Leiste (Game Switcher) liegen auf einer Kapsel im Stil der Kacheln – Standard, OLED-Schwarz oder Glas.
- Lesbarkeit zuerst: Schrift auf aktiven Kacheln und die Markierung passen sich hellen oder dunklen Kacheln an, Glas-Kacheln haben immer einen ruhigen Hintergrund, und eine sehr dunkle eigene Farbe bekommt auf OLED-Kacheln einen hellen Rand.

**Neuer Stil: Liquid Glass**
- Ein fünfter Stil neben Modern, Pixel, Punktmatrix und Cocoon: durchscheinende Glas-Kacheln über deinem weichgezeichneten Android-Hintergrundbild (Root) oder eigenen Bild, klare Schrift, ein sanfter blauer Akzent und Ecken als Markierung – Rauchglas im dunklen, Milchglas im hellen Farbschema. Ohne Bild liegt ein sanfter Farbverlauf hinter dem Glas.

**Bearbeiten-Menü (Y) in Kategorien**
- Layout, Stil, Hintergrund und Overlay nebeneinander, umgeschaltet mit L1/R1. Werte ändern sich direkt in ihrer Zeile mit ◀ ▶ und sind sofort hinter dem Menü zu sehen – deutlich weniger Untermenüs.
- Das Menü sitzt als Seitenleiste am Rand, so ist jede Änderung direkt daneben im Control Center zu sehen – auch die Auswahl-Markierung. Breiter auf 4:3-Bildschirmen wie dem Pocket Nova.
- Beim Wechsel von Stil, Farbschema oder Kachelfarbe (auch mit dem Farbrad) bleibst du im Menü, und die Werte auf den Karten bleiben beim Neuzeichnen stehen.

## 1.12.2

### 🇬🇧 English
- Control Center: the large Wi-Fi and Bluetooth cards work with the D-pad – A steps into the list, up/down picks a network or device, A connects (or disconnects), B leaves the list. When the radio is off, A still switches it on; X opens the options as before.
- The selection stays on the same network or device when the list re-sorts itself (new scan, a device connects).
- Wi-Fi password window: the "Connect" button follows the app language (it was always German).
- On-screen keyboard: QWERTY layout and "Space" when the app is not in German (QWERTZ stays for German).
- Bluetooth search: devices that are already paired no longer show up again under "New devices" (also the "LE_" twin that LE-Audio headphones like the Sony WH-1000XM3 announce).

### 🇩🇪 Deutsch
- Controlcenter: Die großen WLAN- und Bluetooth-Kacheln lassen sich mit dem Steuerkreuz bedienen – A geht in die Liste, hoch/runter wählt ein Netz oder Gerät, A verbindet (bzw. trennt), B verlässt die Liste. Ist der Funk aus, schaltet A ihn wie bisher ein; X öffnet weiter die Optionen.
- Die Auswahl bleibt auf demselben Netz bzw. Gerät, wenn sich die Liste neu sortiert (neuer Suchlauf, Gerät verbindet sich).
- WLAN-Passwortfenster: Der Knopf „Verbinden“ folgt jetzt der App-Sprache (war immer deutsch).
- Bildschirmtastatur: QWERTY-Belegung und „Space“, wenn die App nicht auf Deutsch ist (auf Deutsch bleibt QWERTZ).
- Bluetooth-Suche: Bereits gekoppelte Geräte tauchen nicht mehr zusätzlich unter „Neue Geräte“ auf (auch nicht der „LE_“-Zwilling, den LE-Audio-Kopfhörer wie der Sony WH-1000XM3 aussenden).

## 1.12.1

### 🇬🇧 English
- Capture: the stick LEDs pulse red while recording even when they are switched off – they go on just for the recording and off again afterwards.

### 🇩🇪 Deutsch
- Aufnahme: Die Stick-LEDs pulsieren während der Aufnahme auch dann rot, wenn sie ausgeschaltet sind – sie gehen nur für die Aufnahme an und danach wieder aus.

## 1.12.0

### 🇬🇧 English
A big one: capture with game sound, a device test, emulator shortcuts in the switcher, a new guided setup that asks before it does anything – and many fixes from community feedback (thanks to the Pocket 5 tester on Reddit!).

**Capture** (the tile arrived in 1.11 – this is everything it can do now; new in 1.12: the menu with tabs, maximum length, LEDs returning to your colours, microphone without root)
- A on the tile takes a screenshot of the game (the Control Center closes first); while recording, the tile shows "● 1:24" and A stops the video.
- Capture menu with two tabs (Take · Settings): L1/R1 switch tabs, ◀ ▶ change values.
- Videos in 1080p or 720p at 60 or 30 FPS, with game sound, game sound + microphone, microphone only or no sound; saved to Movies/Hugminn.
- Screenshot or video after a 3-second countdown; a maximum length (5 s up to 10 min) after which the video stops and saves itself.
- Recording indicator: the stick LEDs pulse red like a camera light, a red dot with the time, both or nothing – afterwards the LEDs return to your colours or effect.
- Turning the screen off stops and saves the video cleanly.
- "Keep recording": the last 15/30/60 seconds are always kept, so you can save a moment after it happened – like the capture button on a console.
- New Home button actions: Screenshot, Record video / stop, Save the last seconds.
- Without root, Android asks for the microphone, so videos have sound.

**Game switcher**
- Y → Shortcuts: a card with the emulator's shortcuts in your button symbols – common layout (Select as hotkey, like EmuDeck/ROCKNIX) or your own.
- Settings → Emulator shortcuts: L1/R1 switch the emulator; a shortcut is recorded by holding the buttons and letting go.
- Y → Pin keeps an app open (X and "Close all others" leave it alone); a pin shows right next to its name.

**Device test and setup**
- New device test (Settings → Help & about): permissions, the handheld's features, live values, capture, controllers, a button test (incl. volume, Home, Back) and an LED test, with a ✓/⚠/✗ summary that opens a filled-in device report on GitHub.
- New setup for new installs: every permission is asked one by one with a short reason (nothing is granted silently any more), you open the Game Switcher and the Control Center once yourself (back to the setup after 5 seconds), a button test, and at the end an optional summary to send – you see exactly what is in it first.
- "Stay in the background" without root: a short guide and Android's own "allow in the background" dialog, in the setup and in the settings.
- Setup dialogs follow the chosen style; with root, cards that root handles anyway are left out.
- The GitHub device report template fills in device, version, root and the test result by itself.

**Control Center and overlay**
- Fix: adding an element from the edit menu (outside "Arrange & size") did nothing – it now stays and the Control Center shows where it landed.
- Focus ring: on white (active) tiles it is drawn dark, as thick as on dark tiles.
- Clearer icons for the stick LEDs (stick with light ring) and Do not disturb (circle with bar).
- Performance overlay: the box keeps a steady size; position and layout change with ◀ ▶ like the size.

**Stability**
- Fix: after standby the Control Center could stay on screen and block all buttons. Nothing of Hugminn opens on the lock screen any more, the power key closes it first, and a window left behind removes itself.
- Waking up: if the built-in controls are off and no external controller is connected, they come back on.
- Faster Home button: Hugminn no longer waits for the app in front (that could hold up the buttons for half a second, e.g. right after waking up).
- Per-game profiles are applied strictly one after another, so quick app switches can't overtake each other.

---

### 🇩🇪 Deutsch
Ein großes Update: Aufnahme mit Spielton, ein Gerätetest, Tastenkürzel im Spielwechsler, eine neue geführte Einrichtung, die fragt, bevor sie etwas tut – und viele Fixes aus dem Community-Feedback (danke an den Pocket-5-Tester auf Reddit!).

**Aufnahme** (die Kachel kam mit 1.11 – hier alles, was sie jetzt kann; neu in 1.12: das Menü mit Reitern, die maximale Länge, LEDs zurück auf deine Farben, Mikrofon ohne Root)
- A auf der Kachel macht einen Screenshot vom Spiel (das Control Center schließt sich vorher); während der Aufnahme zeigt die Kachel „● 1:24“, und A stoppt das Video.
- Aufnahme-Menü mit zwei Reitern (Aufnehmen · Einstellungen): L1/R1 wechseln die Reiter, ◀ ▶ ändern die Werte.
- Videos in 1080p oder 720p mit 60 oder 30 FPS, mit Spielton, Spielton + Mikrofon, nur Mikrofon oder ohne Ton; gespeichert in Filme/Hugminn.
- Screenshot oder Video nach 3-Sekunden-Countdown; eine maximale Länge (5 s bis 10 min), nach der das Video selbst endet und speichert.
- Aufnahme-Anzeige: Die Stick-LEDs pulsieren rot wie das Licht einer Kamera, ein roter Punkt mit Zeit, beides oder nichts – danach gehen die LEDs zurück auf deine Farben bzw. deinen Effekt.
- Bildschirm aus beendet und speichert das Video sauber.
- „Mitschneiden“: Die letzten 15/30/60 Sekunden werden immer behalten, so lässt sich ein Moment auch nachträglich speichern – wie die Aufnahme-Taste einer Konsole.
- Neue Aktionen für die Home-Taste: Screenshot, Video aufnehmen / stoppen, Letzte Sekunden speichern.
- Ohne Root fragt Android nach dem Mikrofon, damit Videos Ton haben.

**Spielwechsler**
- Y → Tastenkürzel: eine Karte mit den Kürzeln des Emulators in deinen Tastensymbolen – gängige Belegung (Select als Hotkey wie bei EmuDeck/ROCKNIX) oder deine eigene.
- Einstellungen → Tastenkürzel: L1/R1 wechseln den Emulator; eine Belegung wird aufgenommen, indem man die Tasten hält und loslässt.
- Y → Anheften hält eine App offen (X und „Alle anderen schließen“ lassen sie in Ruhe); eine Pinnadel steht direkt neben dem Namen.

**Gerätetest und Einrichtung**
- Neuer Gerätetest (Einstellungen → Hilfe & Info): Berechtigungen, Funktionen des Handhelds, Messwerte, Aufnahme, Controller, ein Tastentest (mit Lautstärke, Home, Zurück) und ein LED-Test, mit ✓/⚠/✗-Zusammenfassung, die einen ausgefüllten Gerätebericht auf GitHub öffnet.
- Neue Einrichtung für neue Installationen: Jede Berechtigung wird einzeln mit kurzem Grund erfragt (nichts wird mehr still erteilt), Spielwechsler und Control Center öffnest du einmal selbst (nach 5 Sekunden zurück zur Einrichtung), ein Tastentest und am Ende eine freiwillige Zusammenfassung – du siehst vorher genau, was drinsteht.
- „Im Hintergrund bleiben“ ohne Root: eine kurze Anleitung und Androids eigener Dialog „im Hintergrund erlauben“, in der Einrichtung und in den Einstellungen.
- Die Fenster der Einrichtung folgen dem gewählten Stil; mit Root entfallen Karten, die Root ohnehin erledigt.
- Die GitHub-Vorlage für Geräteberichte trägt Gerät, Version, Root und das Testergebnis selbst ein.

**Control Center und Overlay**
- Fix: Ein Element über das Bearbeiten-Menü hinzufügen (außerhalb von „Anordnen & Größe“) tat nichts – jetzt bleibt es, und das Control Center zeigt, wo es gelandet ist.
- Fokusrahmen: Auf weißen (aktiven) Kacheln wird er dunkel gezeichnet, so dick wie auf dunklen.
- Klarere Symbole für Stick-LEDs (Stick mit Leuchtring) und Nicht stören (Kreis mit Balken).
- Leistungs-Overlay: Der Kasten behält eine ruhige Größe; Position und Anordnung mit ◀ ▶ wie die Größe.

**Stabilität**
- Fix: Nach dem Standby konnte das Control Center stehen bleiben und alle Tasten blockieren. Auf dem Sperrbildschirm öffnet sich nichts mehr von Hugminn, die Power-Taste schließt es vorher, und ein zurückgebliebenes Fenster entfernt sich selbst.
- Aufwachen: Ist die eingebaute Steuerung aus und kein externer Controller verbunden, geht sie wieder an.
- Schnellere Home-Taste: Hugminn wartet nicht mehr auf die App im Vordergrund (das konnte die Tasten z. B. direkt nach dem Aufwachen eine halbe Sekunde aufhalten).
- Profile pro Spiel werden streng nacheinander angewendet, damit sich schnelle App-Wechsel nicht überholen.

## 1.11.1

### 🇬🇧 English
- Capture: while recording, the stick LEDs pulse red like a camera light (instead of, or together with, the red dot – adjustable).
- Capture: "Video in 3 s" with a countdown; switching the screen off stops and saves the video cleanly (can be turned off).

### 🇩🇪 Deutsch
- Aufnahme: Während der Aufnahme pulsieren die Stick-LEDs rot wie das Licht einer Kamera (statt oder zusammen mit dem roten Punkt – einstellbar).
- Aufnahme: „Video in 3 s“ mit Countdown; Bildschirm aus beendet und speichert das Video sauber (abschaltbar).

## 1.11.0

### 🇬🇧 English
- New Capture tile (replaces Screenshot): A takes a screenshot of the game, X opens a menu with a 3-second timer, video recording with game sound (or sound + microphone), quality (720p/1080p, 30/60 FPS), a red dot with the time while recording, and "keep the last 15/30/60 seconds" to save a clip afterwards, like on a console. Videos go to Movies/Hugminn.
- Home button: new actions Screenshot, Record video / stop and Save the last seconds.
- Shortcuts: the red and blue icons now also show in launchers like Cocoon (add the shortcuts again once).

### 🇩🇪 Deutsch
- Neue Kachel Aufnahme (ersetzt Screenshot): A macht einen Screenshot vom Spiel, X öffnet ein Menü mit 3-Sekunden-Timer, Videoaufnahme mit Spielton (oder Ton + Mikrofon), Qualität (720p/1080p, 30/60 FPS), rotem Punkt mit Zeit während der Aufnahme und „letzte 15/30/60 Sekunden mitschneiden“, um nachträglich einen Clip zu speichern – wie bei einer Konsole. Videos landen in Filme/Hugminn.
- Home-Taste: neue Aktionen Screenshot, Video aufnehmen / stoppen und Letzte Sekunden speichern.
- Verknüpfungen: Die roten und blauen Symbole erscheinen jetzt auch in Launchern wie Cocoon (Verknüpfungen einmal neu hinzufügen).

## 1.10.32

### 🇬🇧 English
- New app icon: the two ravens in red (Huginn, Control Center) and blue (Muninn, game switcher) on a graphite handheld.

### 🇩🇪 Deutsch
- Neues App-Symbol: die beiden Raben in Rot (Huginn, Control Center) und Blau (Muninn, Spielwechsler) auf einem Handheld in Graphit.

## 1.10.31

### 🇬🇧 English
- Own colours: the Control Center (Huginn) is red, the game switcher (Muninn) blue – in their shortcut icons and in their headers.

### 🇩🇪 Deutsch
- Eigene Farben: Control Center (Huginn) rot, Spielwechsler (Muninn) blau – in den Verknüpfungs-Symbolen und in den Überschriften.

## 1.10.30

### 🇬🇧 English
- "Control Center" and "Game switcher" now also appear in Android's "Add shortcut" list, which frontends like Cocoon read (Cocoon has to be the default launcher).

### 🇩🇪 Deutsch
- „Control Center“ und „Spielwechsler“ erscheinen jetzt auch in Androids Liste „Verknüpfung hinzufügen“, die Frontends wie Cocoon auslesen (Cocoon muss Standard-Launcher sein).

## 1.10.29

### 🇬🇧 English
- Settings: shortcuts have their own page "Shortcuts".

### 🇩🇪 Deutsch
- Einstellungen: Die Verknüpfungen haben eine eigene Seite „Verknüpfungen“.

## 1.10.28

### 🇬🇧 English
- "Built-in controls off" without a controller: the countdown now lasts 30 seconds.

### 🇩🇪 Deutsch
- „Eingebaute Steuerung aus“ ohne Controller: Der Countdown dauert jetzt 30 Sekunden.

## 1.10.27

### 🇬🇧 English
- Settings → Shortcuts: put the Control Center or the game switcher on the home screen, or show both as separate apps ("Hugminn Control Center", "Hugminn Game switcher") for frontends like Cocoon or ES-DE.

### 🇩🇪 Deutsch
- Einstellungen → Verknüpfungen: Control Center oder Spielwechsler auf den Startbildschirm legen, oder beide als eigene Apps anzeigen („Hugminn Control Center“, „Hugminn Spielwechsler“) für Frontends wie Cocoon oder ES-DE.

## 1.10.26

### 🇬🇧 English
- "Built-in controls off" no longer switches itself back on after two seconds (Hugminn took the disappearing built-in controller for a disconnected one).

### 🇩🇪 Deutsch
- „Eingebaute Steuerung aus“ schaltet sich nicht mehr nach zwei Sekunden selbst wieder ein (Hugminn hielt den verschwindenden eingebauten Controller für einen getrennten).

## 1.10.25

### 🇬🇧 English
- Long press on the "Control Center" quick settings tile opens Hugminn's settings.

### 🇩🇪 Deutsch
- Langer Druck auf die Schnelleinstellungs-Kachel „Control Center“ öffnet die Hugminn-Einstellungen.

## 1.10.24

### 🇬🇧 English
- Countdown for "Built-in controls off": says why it ended early.

### 🇩🇪 Deutsch
- Countdown bei „Eingebaute Steuerung aus“: zeigt an, warum er vorzeitig endet.

## 1.10.23

### 🇬🇧 English
- "Built-in controls off" without a controller: the Control Center closes and a countdown in the middle of the screen stays until a controller connects or the 60 seconds are over.

### 🇩🇪 Deutsch
- „Eingebaute Steuerung aus“ ohne Controller: Das Control Center schließt sich, und ein Countdown in der Bildschirmmitte bleibt stehen, bis ein Controller verbunden ist oder die 60 Sekunden um sind.

## 1.10.22

### 🇬🇧 English
- New quick settings tile "Control Center": opens the Control Center without pressing a button (add it via the pencil in Android's quick settings).
- App shortcuts: long press the Hugminn icon for "Control Center" and "Game switcher"; both can be placed on the home screen or in a frontend.

### 🇩🇪 Deutsch
- Neue Schnelleinstellungs-Kachel „Control Center“: öffnet das Control Center ohne Tastendruck (über den Stift in Androids Schnelleinstellungen hinzufügen).
- App-Verknüpfungen: lange auf das Hugminn-Symbol drücken für „Control Center“ und „Spielwechsler“; beide lassen sich auf den Startbildschirm oder in ein Frontend legen.

## 1.10.21

### 🇬🇧 English
- "Built-in controls off" also works before the external controller is connected; if none connects within 60 seconds, the built-in controls come back on.

### 🇩🇪 Deutsch
- „Eingebaute Steuerung aus“ geht auch, bevor der externe Controller verbunden ist; kommt innerhalb von 60 Sekunden keiner, geht die eingebaute Steuerung wieder an.

## 1.10.20

### 🇬🇧 English
- Control Center: swipe down from the top edge to open Android's quick settings (a way out if no buttons respond).

### 🇩🇪 Deutsch
- Control Center: von der oberen Kante nach unten wischen öffnet Androids Schnelleinstellungen (Rückversicherung, falls keine Taste reagiert).

## 1.10.19

### 🇬🇧 English
- "Built-in controls off" now really switches the handheld's controls off (same settings as Retroid's own quick setting), and switching back on restores the exact layout from before.

### 🇩🇪 Deutsch
- „Eingebaute Steuerung aus“ schaltet die Tasten des Handhelds jetzt wirklich ab (dieselben Einstellungen wie Retroids eigene Schnelleinstellung); beim Wiedereinschalten kommt genau das vorherige Layout zurück.

## 1.10.18

### 🇬🇧 English
- Disconnecting the external controller now switches the built-in controls back on (the Retroid's own controls were mistaken for an external controller).

### 🇩🇪 Deutsch
- Wird der externe Controller getrennt, geht die eingebaute Steuerung jetzt wieder an (die Retroid-eigene Steuerung wurde fälschlich als externer Controller erkannt).

## 1.10.17

### 🇬🇧 English
- Battery of Bluetooth controllers: also found when the controller's input name differs from its Bluetooth name (e.g. 8BitDo in Xbox mode).

### 🇩🇪 Deutsch
- Akku von Bluetooth-Controllern: wird auch gefunden, wenn der Controller sich bei der Eingabe anders nennt als bei Bluetooth (z. B. 8BitDo im Xbox-Modus).

## 1.10.16

### 🇬🇧 English
- "Controller style" tile can also switch the built-in controls off while an external controller is used (like Retroid's quick setting). Only offered with a controller connected; if it disconnects, the built-in controls come back on by themselves.

### 🇩🇪 Deutsch
- Kachel „Tastenlayout“ kann die eingebaute Steuerung auch ausschalten, solange ein externer Controller genutzt wird (wie Retroids Schnelleinstellung). Nur mit verbundenem Controller; wird er getrennt, geht die eingebaute Steuerung von selbst wieder an.

## 1.10.15

### 🇬🇧 English
- External controllers (e.g. 8BitDo over Bluetooth): their Home/Guide button opens the Game Switcher and Control Center like the handheld's Home button (Settings → Home button, on by default).

### 🇩🇪 Deutsch
- Externe Controller (z. B. 8BitDo per Bluetooth): Ihre Home-/Guide-Taste öffnet Game Switcher und Control Center wie die Home-Taste des Geräts (Einstellungen → Home-Taste, standardmäßig an).

## 1.10.14

### 🇬🇧 English
- English names follow Retroid's own: "Charging separation" (was "Bypass charging") and "Controller style" (was "Button layout").

### 🇩🇪 Deutsch
- Englische Namen wie bei Retroid: „Charging separation“ (vorher „Bypass charging“) und „Controller style“ (vorher „Button layout“).

## 1.10.13

### 🇬🇧 English
- Dot matrix is the default style for new installs. Who already uses Hugminn keeps their style (Modern if never changed).

### 🇩🇪 Deutsch
- Punktmatrix ist der Standardstil für neue Installationen. Wer Hugminn schon nutzt, behält seinen Stil (Modern, wenn nie geändert).

## 1.10.12

### 🇬🇧 English
- Edit menu of the Control Center: new entry "Grid" for rows and columns; the grid changes at once.
- Settings → Profiles per app: grouped by emulator with its system (e.g. "DuckStation · PlayStation"), each entry says whether it is for all games or only one game.

### 🇩🇪 Deutsch
- Bearbeiten-Menü des Control Centers: neuer Eintrag „Raster“ für Zeilen und Spalten; das Raster ändert sich sofort.
- Einstellungen → Profile pro App: nach Emulator mit seinem System gruppiert (z. B. „DuckStation · PlayStation“), jeder Eintrag sagt, ob er für alle Spiele oder nur ein Spiel gilt.

## 1.10.11

### 🇬🇧 English
- 2×1 switches share one name size, so a short name is no longer bigger than a long one beside it.
- "Remember for …" in the performance and fan menus only for games and emulators (not for e.g. Obtainium).

### 🇩🇪 Deutsch
- 2×1-Schalter haben eine gemeinsame Schriftgröße, ein kurzer Name ist nicht mehr größer als ein langer daneben.
- „Für … merken“ in den Menüs für Leistung und Lüfter nur noch bei Spielen und Emulatoren (nicht z. B. bei Obtainium).

## 1.10.10

### 🇬🇧 English
- Large performance and fan cards: profile name and live value in sizes that match the other cards, a little smaller on 4:3 screens.

### 🇩🇪 Deutsch
- Große Leistungs- und Lüfter-Karte: Profilname und Live-Wert in Größen passend zu den anderen Karten, auf 4:3-Bildschirmen etwas kleiner.

## 1.10.9

### 🇬🇧 English
- Pocket Nova and other 4:3 screens: the Control Center grid has 5 rows by default (still adjustable in the settings).

### 🇩🇪 Deutsch
- Pocket Nova und andere 4:3-Bildschirme: Das Raster des Control Centers hat standardmäßig 5 Zeilen (weiter in den Einstellungen änderbar).

## 1.10.8

### 🇬🇧 English
- Pocket Nova: switch names shrink to fit instead of being cut off ("Bluetoo…"), the LED effects wrap into two rows instead of running off the window.

### 🇩🇪 Deutsch
- Pocket Nova: Schalter-Namen werden verkleinert statt abgeschnitten („Bluetoo…“), die LED-Effekte brechen in zwei Zeilen um, statt aus dem Fenster zu laufen.

## 1.10.8

### 🇬🇧 English
- Pocket Nova (4:3): names on 2×1 switches shrink to fit instead of being cut off ("Bluetoo…"), the switcher's bottom bar leaves the app name more room.

### 🇩🇪 Deutsch
- Pocket Nova (4:3): Namen auf 2×1-Schaltern werden verkleinert statt abgeschnitten („Bluetoo…“), die untere Leiste des Switchers lässt dem App-Namen mehr Platz.

## 1.10.7

### 🇬🇧 English
- Sizes tidied up: music without 2×4, FPS as 2×1, 2×2 and 4×2, sliders across at most 4 wide. Elements in a size no longer offered take the nearest one.

### 🇩🇪 Deutsch
- Größen aufgeräumt: Musik ohne 2×4, FPS als 2×1, 2×2 und 4×2, Regler waagerecht höchstens 4 breit. Elemente in einer Größe, die es nicht mehr gibt, nehmen die nächstpassende.

## 1.10.6

### 🇬🇧 English
- Performance and fan cards no longer come as 1×1 (too small for them); existing 1×1 cards become 2×1.

### 🇩🇪 Deutsch
- Leistungs- und Lüfter-Karte gibt es nicht mehr als 1×1 (zu klein dafür); vorhandene 1×1-Karten werden zu 2×1.

## 1.10.5

### 🇬🇧 English
- Small 1×1 cards shrink long values (e.g. "Performance") to fit instead of cutting them off.

### 🇩🇪 Deutsch
- Kleine 1×1-Karten verkleinern lange Werte (z. B. „Leistung“), statt sie abzuschneiden.

## 1.10.4

### 🇬🇧 English
- Large performance card shows the live CPU clock, the large fan card how fast the fan turns (in percent), each with a bar in the style.

### 🇩🇪 Deutsch
- Große Leistungs-Karte zeigt den aktuellen CPU-Takt, die große Lüfter-Karte, wie schnell der Lüfter dreht (in Prozent), jeweils mit Balken im Stil.

## 1.10.3

### 🇬🇧 English
- Large performance and fan cards show just the current profile, big, with live details; new size 3×2 instead of 2×3 (A still opens the profiles).
- Profile notice: one notice only, about 5 seconds after the switch, in the top right corner.

### 🇩🇪 Deutsch
- Große Leistungs- und Lüfter-Karte zeigen nur das aktuelle Profil, groß, mit Live-Details; neue Größe 3×2 statt 2×3 (A öffnet weiter die Profile).
- Profil-Hinweis: nur noch ein Hinweis, etwa 5 Sekunden nach dem Wechsel, oben rechts.

## 1.10.2

### 🇬🇧 English
- A short notice says when a profile per game or emulator takes over ("Profile for The Wind Waker: High performance · Fan Sport").
- Performance and fan cards also as 2×3; their profiles sit two per row and stay readable.
- "Add element": switching categories no longer redraws the whole window.

### 🇩🇪 Deutsch
- Ein kurzer Hinweis zeigt, wenn ein Profil pro Spiel oder Emulator übernimmt („Profil für The Wind Waker: Hohe Leistung · Lüfter Sport“).
- Leistungs- und Lüfter-Karte auch als 2×3; ihre Profile stehen zu zweit pro Zeile und bleiben lesbar.
- „Element hinzufügen“: Beim Wechsel der Kategorie wird nicht mehr das ganze Fenster neu aufgebaut.

## 1.10.1

### 🇬🇧 English
- Music tile: a song too long for its line runs from right to left.

### 🇩🇪 Deutsch
- Musik-Kachel: Ein zu langer Titel läuft von rechts nach links durch.

## 1.10.0

### 🇬🇧 English
- Control Center: Start plays/pauses the music, L2/R2 skip to the previous/next track.

### 🇩🇪 Deutsch
- Control Center: Start spielt/pausiert die Musik, L2/R2 springen zum vorherigen/nächsten Titel.

## 1.9.9

### 🇬🇧 English
- Music tile tidies video titles: "(Official Video)", "[OFFICIAL VIDEO]" and the like are removed, "Artist - Song" is split into song and artist, channel names lose "Official", "VEVO" and "- Topic".

### 🇩🇪 Deutsch
- Musik-Kachel räumt Video-Titel auf: „(Official Video)“, „[OFFICIAL VIDEO]“ und Ähnliches fallen weg, „Interpret - Titel“ wird in Titel und Interpret getrennt, Kanalnamen verlieren „Official“, „VEVO“ und „- Topic“.

## 1.9.8

### 🇬🇧 English
- Game Switcher: header "Muninn · Game Switcher" with clock and battery, like the Control Center's.

### 🇩🇪 Deutsch
- Game Switcher: Kopfzeile „Muninn · Game Switcher“ mit Uhrzeit und Akku, wie beim Control Center.

## 1.9.7

### 🇬🇧 English
- Control Center: elements sit exactly where they were placed in edit mode (pages with few elements were centred before).

### 🇩🇪 Deutsch
- Control Center: Elemente sitzen genau dort, wo sie im Bearbeitungsmodus platziert wurden (Seiten mit wenigen Elementen wurden vorher zentriert).

## 1.9.6

### 🇬🇧 English
- Music buttons in the style: Pixel draws them from blocks, Dot matrix from dots, Cocoon puts them on soft round buttons.

### 🇩🇪 Deutsch
- Musik-Tasten im Stil: Pixel zeichnet sie aus Blöcken, Punktmatrix aus Punkten, Cocoon setzt sie auf weiche runde Knöpfe.

## 1.9.5

### 🇬🇧 English
- The device's volume keys work while the Control Center is open (a volume slider on screen moves along).
- Music tile: file names as titles are cleaned up ("02 Song.mp3" → "Song").

### 🇩🇪 Deutsch
- Die Lautstärketasten des Geräts funktionieren bei offenem Control Center (ein Lautstärke-Regler bewegt sich mit).
- Musik-Kachel: Dateinamen als Titel werden aufgeräumt („02 Lied.mp3“ → „Lied“).

## 1.9.4

### 🇬🇧 English
- Music tile: Android's own music app now shows song and artist (its notification was not found before).

### 🇩🇪 Deutsch
- Musik-Kachel: Androids eigene Musik-App zeigt jetzt Titel und Interpret (ihre Benachrichtigung wurde vorher nicht gefunden).

## 1.9.3

### 🇬🇧 English
- Music tile: black bars of video thumbnails (e.g. YouTube) are cut off, the picture fills the cover.

### 🇩🇪 Deutsch
- Musik-Kachel: schwarze Balken von Video-Vorschaubildern (z. B. YouTube) werden abgeschnitten, das Bild füllt das Cover.

## 1.9.2

### 🇬🇧 English
- Music tile 4×2 and 2×2: the song takes one line, so progress and buttons always fit.

### 🇩🇪 Deutsch
- Musik-Kachel 4×2 und 2×2: der Titel nimmt eine Zeile, damit Fortschritt und Tasten immer Platz haben.

## 1.9.1

### 🇬🇧 English
- Music tile reads the player's song from the same place as Android's own media notification, and from players with their own notification layout (such as Android's own music app).

### 🇩🇪 Deutsch
- Musik-Kachel liest den Titel des Players von derselben Stelle wie Androids eigene Medien-Benachrichtigung, auch bei Playern mit eigenem Benachrichtigungs-Layout (wie Androids eigene Musik-App).

## 1.9.0

### 🇬🇧 English
- Music tile also works with players that publish no song information (such as Android's own music app): app, playing state, and title and picture from the player's notification.

### 🇩🇪 Deutsch
- Musik-Kachel funktioniert auch mit Playern, die keine Titel-Infos melden (wie Androids eigene Musik-App): App, Wiedergabe-Status sowie Titel und Bild aus der Benachrichtigung des Players.

## 1.8.9

### 🇬🇧 English
- Tiles follow their style more closely: Pixel with stepped frames, block bars and stepped graphs; Dot matrix with dotted rings, dot bars and dot graphs, accent only where something is on; Cocoon with colourful badges on the large cards.

### 🇩🇪 Deutsch
- Kacheln passen besser zum Stil: Pixel mit Treppenrahmen, Blockbalken und Stufen-Graphen; Punktmatrix mit Punkt-Rahmen, Punktbalken und Punkt-Graphen, Akzent nur wo etwas an ist; Cocoon mit bunten Abzeichen auf den großen Karten.

## 1.8.8

### 🇬🇧 English
- Music tile: if Android does not yet let Hugminn see what is playing (e.g. right after an update), it switches the access on and tries again.

### 🇩🇪 Deutsch
- Musik-Kachel: Lässt Android Hugminn noch nicht sehen, was läuft (z. B. direkt nach einem Update), schaltet es den Zugriff ein und versucht es erneut.

## 1.8.7

### 🇬🇧 English
- "Add element" shows the categories (displays, sliders, switches) side by side; ◀ ▶ or L1/R1 switches between them, no more long scrolling.

### 🇩🇪 Deutsch
- „Element hinzufügen“ zeigt die Kategorien (Anzeigen, Regler, Schalter) nebeneinander; ◀ ▶ oder L1/R1 wechselt zwischen ihnen, kein langes Scrollen mehr.

## 1.8.6

### 🇬🇧 English
- Large tiles polished: rings in the system overview sit with their labels, performance and fan names fit, Wi-Fi list shows the networks in range, a clear note when Wi-Fi or Bluetooth is off, temperature graph uses its useful range.

### 🇩🇪 Deutsch
- Große Kacheln verbessert: Ringe der System-Übersicht sitzen bei ihren Beschriftungen, Namen bei Leistung und Lüfter passen, die WLAN-Liste zeigt die Netze in Reichweite, klarer Hinweis wenn WLAN oder Bluetooth aus ist, Temperatur-Graph mit sinnvollem Bereich.

## 1.8.5

### 🇬🇧 English
- Elements can be added to the Control Center more than once (e.g. a small and a large music tile).

### 🇩🇪 Deutsch
- Elemente lassen sich mehrfach ins Control Center legen (z. B. eine kleine und eine große Musik-Kachel).

## 1.8.4

### 🇬🇧 English
- Graphs (frame time, temperature) follow the chosen gauge colours, in the Control Center and in the performance overlay.

### 🇩🇪 Deutsch
- Graphen (Frametime, Temperatur) folgen den gewählten Farben der Anzeigen, im Control Center und im Leistungs-Overlay.

## 1.8.3

### 🇬🇧 English
- Large tiles: system overview (4×2, 4×4), Wi-Fi and Bluetooth with lists (2×2, 4×2), performance and fan with all profiles to tap (2×2), battery with power draw and temperature (2×2).

### 🇩🇪 Deutsch
- Große Kacheln: System-Übersicht (4×2, 4×4), WLAN und Bluetooth mit Listen (2×2, 4×2), Leistung und Lüfter mit allen Profilen zum Antippen (2×2), Akku mit Leistungsaufnahme und Temperatur (2×2).

## 1.8.2

### 🇬🇧 English
- Large music tile (2×2, 4×2, 2×4, 4×4) with cover, artist, progress and buttons; FPS card also as 4×2 and 4×4.

### 🇩🇪 Deutsch
- Große Musik-Kachel (2×2, 4×2, 2×4, 4×4) mit Cover, Interpret, Fortschritt und Tasten; FPS-Karte auch als 4×2 und 4×4.

## 1.8.1

### 🇬🇧 English
- Profiles per game: "Remember for <game>" in the performance and fan menus; a game's profile beats the emulator's.

### 🇩🇪 Deutsch
- Profile pro Spiel: „Für <Spiel> merken“ in den Menüs für Leistung und Lüfter; ein Spielprofil geht vor dem des Emulators.

## 1.8.0

### 🇬🇧 English
- Battery of Bluetooth devices in the Bluetooth menu and as a new "Device battery" tile.

### 🇩🇪 Deutsch
- Akku von Bluetooth-Geräten im Bluetooth-Menü und als neue Kachel „Geräte-Akku“.

## 1.7.9

### 🇬🇧 English
- Stick LEDs can show the battery charge or the temperature as a colour.

### 🇩🇪 Deutsch
- Stick-LEDs können Akku oder Temperatur als Farbe anzeigen.

## 1.7.8

### 🇬🇧 English
- New "Screen off" tile: sleep like the power button.

### 🇩🇪 Deutsch
- Neue Kachel „Bildschirm aus“: Ruhemodus wie die Power-Taste.

## 1.7.7

### 🇬🇧 English
- The performance overlay follows the chosen style and light or dark mode.

### 🇩🇪 Deutsch
- Das Leistungs-Overlay folgt dem gewählten Stil und hell oder dunkel.

## 1.7.6

### 🇬🇧 English
- Setup wizard: new pictures for every style; the switcher page shows the switcher in your style.

### 🇩🇪 Deutsch
- Einrichtungsassistent: neue Bilder für jeden Stil; die Switcher-Seite zeigt den Switcher in deinem Stil.

# Performance overlay

A small display over the game, like Steam's performance overlay. It never takes touches or button presses; everything reaches the game.

## Values
Choose any of: **FPS**, **frame time graph**, **CPU**, **GPU**, **RAM**, **temperature**, **power draw**, **battery** and **clock**.

## Settings
In the Control Center's edit menu → *Performance overlay*:
- On / off
- Position: top left, top centre, top right, bottom left, bottom right
- Row or column
- Size: change it with the D-pad (◀ ▶) and see the result live

## Look
The overlay follows the chosen [style](Styles-and-Themes) and light or dark mode: its font, frame and colours match the Control Center. The FPS value and the graph use the accent (tile) colour; CPU, GPU and temperature use the gauge colours. Changes apply at once.

## How FPS is measured
Hugminn asks Android's display compositor how often the game really shows a new picture, averages it over two seconds and rounds to the rates the display can reach (e.g. 30, 60 or 120 FPS at 120 Hz). Details in [How it works](How-It-Works#fps).

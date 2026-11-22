# Stick LEDs

![Stick LEDs](https://raw.githubusercontent.com/Ninte86hl/Hugminn/main/docs/screenshots/modern/dark-leds.png)

The LED tile (on the AYN Odin 2: *Lights*) opens a window with three tabs – **L1 / R1** switch between them. Every light is set on its own: on the Retroid Pocket the two sticks, on the AYN Odin 2 the two sticks and the two side lights.

## Device
- **◀ Light ▶** – which light you are setting.
- **Shows** – a switch with four stops: **Off · Colour · Combo · Effect**. Each light keeps its colour and its effect; the switch only picks which of them lights. Switching back and forth needs no setting up again.
- **◀ Brightness / Saturation ▶** and a bar – brightness and colour strength of this light, in 5 % steps.
- **◀ Combo ▶** – colour pairs (left lights the first colour, right ones the second). **A** switches every light that is on to the combo.

## Mode
Effects one at a time (◀ ▶): static, breathing, pulsing, rainbow, colour cycle – and as displays *battery* (green, yellow from 50 %, red from 20 %) and *temperature* (green, yellow from 60 °C, red from 75 °C). Breathing and pulsing use the light's colour. A bar sets the speed. *For all lights that are on* gives every light the same effect.

## Colour
15 colours, the 16th tile opens the colour wheel: steer with the D-pad, the LED shows each colour right away, A takes it, B cancels. After choosing a colour the window goes back to *Device*, ready for the next light.

## Buttons
| Button | |
| --- | --- |
| D-pad / stick | move between the fields |
| A | take a field (then ◀ ▶ change it), A again confirms |
| North (Y · Retro X · △) | on *Shows*: straight to the setting of what the light shows (colour, combo or effect) |
| West (X · Retro Y · □) | for all lights: *Device* copies this light (colour, effect, brightness), *Mode* the effect shown, *Colour* the colour under the cursor |
| B | back to *Device*; there it closes the window |

The sticks pulse red when the battery drops to 10 %.

## LED apps
Instead of the built-in colours, the LED tile can open an LED app such as [Bifrost](https://github.com/Pollux-MoonBench/Bifrost) or AmbiLuma (Settings → Control Center → *LED app*). Hugminn then leaves the LEDs to that app.

## LED tester
The [device test](Device-Test) lights every LED setting and driver zone in turn and asks what lights up – useful to find out what a new device can do.

## Limits
Each stick lights in a single colour, so a rainbow running *around* one stick is not possible. "Rainbow" gives the two sides offset colours instead.

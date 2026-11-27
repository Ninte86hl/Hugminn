# Plan for later: orthogonal design system

**Status:** planned for after the basic version works on all devices (Retroid, AYN incl. Thor, Anbernic, Pocket Duo). Not started.

## Goal

One consistent UI structure for every screen and every device, **without scaling**. Today some views are built for the main screen and then shrunk, which does not always look good.

## Where Hugminn scales today

- **Second screen (Duo, Thor):** the Control Center and the switcher are laid out for the main screen and then shrunk with `Displays.fitted` (`ControlCenter.kt`, `OwnSwitcher.kt`). On 4:3 and ≈1:1 screens this leaves black edges and makes everything smaller.
- **Tile contents:** some tiles squeeze their content with `scaleX`/`scaleY` when it does not fit (for example `ControlCenter.kt`, where tiles are rendered).

## Principles

1. **One grid for everything.** Columns, cell size and gap come from the real area of the screen the view is on. Every view is built for its own screen, never shrunk.
2. **Fixed size steps instead of scaling.** Text, icons and padding come in a few steps (S / M / L). A tile picks the step that fits its cell.
3. **Independent building blocks (orthogonal).** Shape × size × state × colour role. Each one can change without touching the others.
4. **Styles only swap tokens.** Standard, Cocoon and Dot-Matrix change colours and shapes, never the layout.
5. **Reflow instead of squeezing.** What does not fit moves to the next row or page, or shrinks to a smaller size (for example 4×1 → 2×1).

## Screen shapes to cover

16:9 (main screens), 4:3 (Duo lower screen, Odin-type devices) and ≈1:1 (Thor lower screen, square devices).

## Order

1. Control Center on the second screen (most visible, helps Duo and Thor at once)
2. Tile contents (size steps instead of scale)
3. Switcher
4. Menus and settings windows

## Design rules that stay (Dot-Matrix, agreed)

- Grey cards (#1C1C1C) with large corners
- Normal tiles, switches and sliders active in white
- Info tiles with red accents; strong red only for warnings; no green, no blue
- Mixed shapes: dot circle for small gauges, standing capsule for battery, pill for wide one-row tiles

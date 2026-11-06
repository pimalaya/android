---
cairn: change
id: brand-palette
status: active
created: 2026-11-05
---

# The app's own palette

## Why

The app draws in the phone maker's palette (`Theme.DeviceDefault`, docs/design.md: "The colours are the device theme's, never a fixed palette"), so it looks like a different app on every phone and like none of them in particular. The owner asked on 2026-11-05 for a strong identity built on the logo's shades and on the design system started for the website, the palette the "Onboarding, one path" frames of the Pimalaya Android design canvas carry.

## What

One palette in both themes, the logo's magenta and warm greys leaning to it:

| Token | Light | Dark | For |
|-------|-------|------|-----|
| accent | `#BF1E83` | `#EFA3C8` | the accent: primary buttons, ticks, links, the FAB |
| accent ink | `#97156A` | `#F3B6D3` | accent text on a tonal fill |
| page | `#FFFFFF` | `#1A0D15` | the page and the bars |
| surface | `#F9F1F3` | `#23121C` | cards, pills, tonal buttons |
| divider light | `#ECD9E1` | `#F7E9F0` at 10% | hairlines inside cards |
| divider | `#D9BFCB` | `#5A4351` | rings and tracks |
| wash | `#F7D9EA` | `#422232` | tonal fills |
| ink | `#301F2A` | `#F7E9F0` | primary text |
| ink secondary | `#6F5464` | `#BB9CAD` | secondary text, idle controls |
| on accent | white | `#410B2B` | text on the accent |

The dark accent was the logo gradient's middle stop, `#E267A3`, until the owner found it too dark and loud on the dark page (2026-11-06): it is now the light accent lightened and calmed, OKLCH 0.80 0.10 at its hue (0.54 0.21 by day), with a deep plum of that hue on it rather than the page's near-black.

- The theme sets them as the platform's attributes (`colorAccent`, `colorBackground`, `textColorPrimary`, `textColorSecondary`, `textColorHint`, `colorControlNormal`, `colorControlActivated`), so every view drawing from the theme follows; the neutral colours the app keeps (`surface`, `divider`, `divider_light`) take the palette's.
- Dialogs and the date and time pickers take a dialog theme of the same palette, not the maker's.
- Text on the accent is white or a deep plum by contrast (WCAG luminance above 0.179 takes the plum), where it was black above 0.5: white on the light accent, plum on the dark one.
- The home-screen widgets keep their own ground, their text in the palette's inks.

## Open points

- **Serif titles**: the frames set the large titles in Source Serif 4. That bundles a font file (OFL 1.1) the app does not ship today, so it waits for the owner's go-ahead (no new asset unasked).
- The extended FAB, the bottom navigation's indicator and the chips keep their shapes; only their colours move.

---
cairn: delta
change: brand-palette
---

Folds into `spec/offline-store.md`, beside the chrome the lists share.

## ADDED Requirements

### Requirement: The app draws in its own palette
The app SHALL draw in its own palette, the logo's magenta accent over warm greys leaning to it, in a light and a dark theme following the system setting, whatever the phone maker's theme: the page, the cards, the text, the dividers, the controls, the dialogs and the pickers. Text on the accent SHALL be white or a deep plum of the accent's hue, whichever contrasts more by WCAG luminance.

#### Scenario: Two phones
- GIVEN the app on two phones of different makers
- WHEN the mail list shows
- THEN both draw the same accent, page and card tones

#### Scenario: A primary button in the dark theme
- GIVEN the dark theme, whose accent is the light magenta
- WHEN a Continue button shows
- THEN its label is in the deep plum, not white

## MODIFIED Requirements

## REMOVED Requirements

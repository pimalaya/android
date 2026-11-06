---
cairn: change
id: redesign-for-the-palette
status: active
created: 2026-11-06
---

# The app redesigned for its palette

## Why

The palette (brand-palette) suits the setup but not the rest of the app, the owner found on the device on 2026-11-06, and the avatars' colours clash with it. The owner asked to redesign on the Pimalaya Android canvas first, the canvas cleaned to the screens the app shows today, in the palette, light and dark.

## What

The canvas (https://claude.ai/artifact/UJsQXRtqaiQZ6ufXpqAZLW, version 106) holds only the app as it is today, each screen in light and dark, drawn from four audits of the code on 2026-11-06. On top of it, these changes are proposed:

- **Avatars**: a soft fill with the letter in its own hue, eight hues at one OKLCH lightness ("tonal"), in place of HSV(h, 0.40, 0.55) under a white letter, whose yellows read light and blues heavy. The Avatars board compares it with the palette's own family, today's discs evened out, and today's; every frame's avatars tweak switches them.
- **Top-right actions**: every screen's actions in the reader's toolbox, a 40dp surface pill of 38x36dp icon buttons (owner, 2026-11-06): Contacts (Next birthday, Find duplicates, Import or export), Tasks (Clarify, Weekly review), the selections, the contact and entry pages, and Reset in the sync chooser.
- **Selection**: the picked check disc at the avatar's 38dp rather than 42dp (owner, 2026-11-06).
- **Sync wording**: one phrase per step on every backend (owner, 2026-11-06), drawn on the Sync wording board: Connecting, Fetching changes, Downloading envelopes, Downloading messages, Downloading contacts, Downloading events, Sending changes, Updating the phone's contacts or calendar, Resolving conflicts, Loading older mail, Waiting for Wi-Fi, Up to date, each count as "N of M" after its step.
- **Badges on a card** ("In progress", "Reply received"): on the wash with the accent ink. Today they are surface on a surface card, so they do not show.

## Found on the way, not in this change

- The launcher's Capture shortcut icon keeps the old teal `#00696F`.
- An HTML message renders on a white page with black text in the dark theme.
- In the sync chooser that account settings open, Reset drafts the whole domain (`MergedFilter.reset`), so it re-ticks collections other accounts had left out, unseen, and Apply then starts their first syncs.
- `FilterPage.open`, `FilterPage.openFiles`, `MainActivity.openFilter` and the bar's filter button can no longer be reached.
- cairn/spec/mail.md still describes role chips, and cairn/spec/tasks.md two chip rows.

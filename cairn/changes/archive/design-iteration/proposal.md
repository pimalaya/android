---
cairn: change
id: design-iteration
status: landed
created: 2026-10-07
---

# Second pass on the unified design

## Why

The first pass landed the design's layout, and a look on the device turned up where it still departs from it, and one place where the design does not fit the app. The design's drawer is a single account's mailboxes, but this app is one merged view over every account and collection: a drawer that opens one mailbox competes with the filter that narrows the merged list, and there is no way to say which mailboxes "all" covers. So the drawer lists the accounts instead, and the mail list stays one page.

## What

- Drawer: the app's name, then one card per account (disc, address, the domains it covers, a pill saying when it last synced, a check in the corner while it takes part), a line, then the actions. No mailboxes.
- Mail: one shared page, no mailbox narrowing; a message opens on tap and joins a selection on a long press, the bar then acting on the whole selection; the star centred on the subject's line and filled yellow when on.
- The extended add button shrinks to a square glyph while scrolling, and carries a pencil on mail and a person-plus on contacts.
- The bottom bar's selected item sits on a neutral grey indicator.
- The contact editor and the entry page group their sections into cards.
- The agenda's header carries this week in one card under its month and year.

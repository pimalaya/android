---
cairn: change
id: scoped-pull-sync
status: landed
created: 2026-10-07
---

# A list's pull syncs its own domain within the filter

## Why

Pulling the contacts list ran the full sync of every domain, and pulling the mail or the agenda synced every account of that domain whatever the filter hid. A pull is a gesture on one list, so it should refresh what that list shows. The drawer's sync is there for everything at once. The drawer also carried two signals for one account (a corner check and a sync pill), and its actions scrolled away under a long account list.

## What

- A list's pull syncs only its domain, and within it only the accounts and collections the filter shows. The drawer's sync takes every domain and everything, whatever the filter hides.
- The drawer's account pill says Deactivated for an account that does not take part, and otherwise when it last synced, under a sync glyph; the corner check goes. The actions stay fixed at the bottom, and the cards start further below the title.
- The week's number sits between its arrows.

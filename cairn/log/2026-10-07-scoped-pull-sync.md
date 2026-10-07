---
cairn: log
change: scoped-pull-sync
landed: 2026-10-07
---

# A list's pull syncs its own domain within the filter

Capabilities moved: offline-store (added: a list's pull syncs what it shows; modified: the bottom bar switches domains), mail (modified: a sync skips what the filter hides).

**Pull.** The passes take a scope (`MergedFilter`, null for everything). `SyncRunner.syncRemote(scope)` drops the books the filter hides. `mailPass` and `calendarPass` skip hidden accounts, and `fetchMail` and `fetchCalendars` skip hidden mailboxes and calendars. Each pull passes the live filter: the contacts list's new `syncContacts`, which is the filtered remote run plus the phone pass, and the mail and agenda pulls. The drawer's `syncAll` and the first sync of a fresh account pass null, so the drawer now also reconciles the mailboxes the filter hides, which it used to skip.

**Drawer.** The corner check is gone. The pill reads Deactivated in the plain tone when the account does not take part (`accountEnabled`); otherwise it reads when the account last synced, or that it never has, always under the sync glyph. The divider and the actions moved out of the scroll view to sit fixed at the bottom, padded by the bottom inset. The cards start 16dp below the title.

**Week.** The number pill sits between the two arrows.

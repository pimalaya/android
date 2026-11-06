---
cairn: change
id: phone-deletions-held
status: landed
created: 2026-11-06
---

# A mass deletion on the phone waits for the user

## Why

The phone pass carried every raw contact or event another app deleted upstream as a server deletion, with no bound (audit-s1-residuals I7): any app holding WRITE_CONTACTS or WRITE_CALENDAR, a bulk delete or a provider glitch could empty a book or a calendar on the server. The spoke also erased the deleted row before the store recorded its deletion, so a crash between the two lost the deletion, and nothing could hold one back.

## What

- The phone pass (`PhonePass.enumerate`) counts the members the phone deleted against the members the store holds there, the store's count since another app can lower the phone's. More than half of at least 20, the rule pimdir parks a server round by (SYNC §5), is held: the deletions are left out of the round, the rows stay deleted on the phone, every pass finds them again.
- The deleted rows are erased (`PhoneSpoke.purge`) only after the round that took their deletion, for contacts and calendars alike.
- When the app's sync ends, a dialog names each book or calendar with how many of its members were deleted: *Delete on the server* lets them go on the next pass, *Put them back* unbinds them and erases the rows so the next pass projects them again from the store, *Later* leaves them held and asks again only when the counts change. Like a parked round, the hold, a release and a restore live in memory for the process.
- A background pass holds the same way and never asks: the dialog waits for the app's next sync.

Out of scope, left in audit-s1-residuals I7: keeping a book the user does not own out of the phone's aggregation. `AGGREGATION_MODE_DISABLED` would hide the book from the Contacts app altogether, and no book states an owner yet (io-webdav reads no `DAV:owner`).

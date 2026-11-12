---
cairn: log
change: write-against-the-current-revision
date: 2026-11-11
---

# Calendar and task saves against the revision they read

Approved by the owner the same day; worked by one agent, reviewed by another over three passes.

- Root cause, confirmed: task and entry writes used the handle read at load (TaskWrites around 121, 148, 167; EventWrites around 70, 227, 239) and Java's `Mutation` never sent `ifMatch`, though io-pimdir (mutate.rs around 681 to 692) and the bridge accepted it. A save after a sync failed with "unknown handle" or wrote the stale body over the sync's change.
- Bridge: a mutate failure carries io-pimdir's code (`refused: body-replaced`); `Driver::drive` keeps the engine's error apart from the driver's. Client: `Mutation.ifMatch`, a `BodyReplaced` exception from that code.
- Writes: `StoredEvent.object` holds the hash of the body read; every task and entry edit (page, Clarify, tick, batch, occurrence delete, split staged in one transaction) uses `handleFor` at write time with `ifMatch`; removes (`mutateRemoveOver`) and moves check the stored body in their transaction.
- Recovery (StaleEdit): `CalendarClient.mergeEvent(read, would-write, fresh)`; clean, staged under `fresh.object`; a collision opens EventView's conflict mode on in-memory sides, both values as chips, nothing stored until settled; tasks and Clarify reach it through MainActivity, a Clarify move completing once settled; back from a task's conflict page asks before discarding. Categories merge as sets. Batch due, category and move merge per item, a collision skipped and reported (`task_batch_changed`); completion re-applied. A stale delete is refused and the page reopened on the fresh entry.
- New alarms carry a random UUID (alarm.rs), as ical-rs matches a VALARM without one by position.
- Tests failing first: WritesAfterAPassTest (task page after the filing pass, notes and location collisions, categories and alarms from each side, a moved occurrence, a stale remove, Clarify recovery and move, back asking, batch delete and due), the bridge's `an_edit_over_a_replaced_body_is_refused_with_its_code`, alarm.rs `each_new_alarm_is_named_by_a_uuid_of_its_own`.
- Checked by the agent: app 966 tests, client 14, assembleDebug, cargo 648, clippy and fmt; merged here with contact-moves-carrying-edits and run again.
- Mail needed nothing (it stages no body edit, flags read at write time). Contacts are still unguarded: filed in guarded-contact-updates.
- Left: a "this and following" save over a stale series reopens rather than merges; ical-rs keeping two values of an at-most-once property and matching VALARMs without UID by position (ical cairn/changes/merge-respects-cardinality).

Capabilities moved: offline-store (A calendar or task save names what it read, added), calendar (A new alarm carries its own UID, added).

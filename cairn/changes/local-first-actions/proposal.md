---
cairn: change
id: local-first-actions
status: draft
created: 2026-10-08
---

# Every local action is visible at once, the sync carries it out as staged

## Why

Seen on Posteo (IMAP) on 2026-10-08: a deleted message says "moved to trash", the trash stays empty until a sync. Not the first action to behave so; each was fixed on its own path.

The cause is one seam. pimdir already models what the user expects: `Move`, `Copy` and `Add` stage a `Created` placement in the target under a provisional handle at once, the source a tombstone, and the target's next listing lands the create on the server's handle by link id (SYNC §3, §6, §7). The app never uses `Move` or `Copy` (the bridge's `MutationJson`, rust/src/offline.rs:1003, wires `setFlags`, `remove`, `edit`, `add`), and its push translates one intent into another: a mail `Remove` becomes a server move to the trash (`MailEngine.pushOne` calls `client.deleteMessage`, MailEngine.java:482), so the store records "gone from the inbox" while the server did "moved to the trash". The trash learns of it only when it is next listed.

The same audit (2026-10-08) found:
- Mail and calendar push ignore `Remove { to }`, which SYNC §4 says a connector MUST honour or reject (MailEngine.java:477, CalendarEngine.java:258); mail rejects every `Add`, server-side copies included.
- Deleting from the trash stages `\Deleted` and nothing ever expunges it, on any backend; the row shows no marker.
- The Sent copy of a sent message appears only once Sent is walked again (`append_sent`, rust/src/client/imap.rs:759).
- No pending state is shown anywhere but the outbox row.
- Contacts create and delete write rows directly (`PimdirContacts.save`, `stageDelete`), against SYNC §7's "never by direct row edits".

## What

**The rule.** Every user action is a pimdir mutation; lists read placements; the sync carries each one out as staged, a `Move` as a server move, a `Remove` as a server delete. Nothing is translated on the way.

1. **Move and Copy reach pimdir.** `MutationJson` gains `move` and `copy`; `PimdirEngine.mutateMove` / `mutateCopy`.
2. **Mail push honours the seam.** `Remove { to }` relocates (IMAP MOVE, else COPY + `\Deleted` + `UID EXPUNGE` with UIDPLUS, the marker alone without; Graph move; Gmail label modify, trash and untrash; JMAP `mailboxIds`), a `Remove` with no `to` deletes; `Add` with an origin copies server-side (IMAP COPY, Graph copy, JMAP `mailboxIds`, Gmail label add), without one it appends (IMAP only). Calendar rejects a `Remove { to }` it cannot relocate.
3. **Delete is a move into the trash.** A delete outside the trash stages `Move` into the account's trash collection: the row leaves its mailbox and is in the trash at once.
4. **Delete from the trash is permanent.** Staged as `Remove`, pushed as a real delete: IMAP `\Deleted` + `UID EXPUNGE` (RFC 4315) where the server has `UIDPLUS`, the marker alone otherwise (shown on the row); Graph `permanentDelete`; Gmail `messages.delete`; JMAP `Email/set` destroy. The store keeps the row in its own trash view until purged (`deleted-items`).
5. **The Sent copy is local at once.** Sending stages an `Add` of the submitted message into the Sent collection beside the submission. Where the provider files sent mail itself (Gmail, Graph), the Sent listing lands the create by its `Message-ID` before any push derives (SYNC §5 "creates wait for the page that lands them"); on IMAP the create's push is the append, once the submission went, replacing `append_sent`. A JMAP account stages none: it sends nothing yet.
6. **Pending is shown.** A visible row whose placement is `Created` or `Dirty` and not yet pushed shows a small pending mark, in all three domains; a moved item's mark is on its target's `Created` row. A `Tombstone` is never listed, so it carries none. A change the server refused for good shows it refused.
7. **Contacts go through mutations.** `PimdirContacts.save` and `stageDelete` become `mutateAdd` and `mutateRemove`.

## Out of scope

Drafts (the composer stores nothing before send), a "move to mailbox" action in the UI (enabled by 1-2, a separate UI change), RSVP.

## Design notes (found while implementing)

- **A message's link id is its handle here** (the UID, or the provider's id; rust/src/client/listing.rs `Named`), not its `Message-ID`. A move's target is therefore never landed by the arrival's link id on IMAP or Graph, where the handle changes. So mail moves are delivered by the source's `Remove { to }` alone: the target's `Add` waits (rejected) while a move's tombstone is its source, and once the relocation is accepted the connector withdraws the target's pending create, the arrival coming with the target's next listing under its own handle. A copy or an append is accepted with no handle assigned, the arrival likewise.
- **The store files a message under its handle.** A mail create landed onto an arrival takes the arrival's handle as its identity, and the item it was staged as goes with its superseded provisional handle; a fresh message created from another one takes the summary the store holds of it.
- **A removed pending create is withdrawn by io-pimdir.** Its `Remove` writes the tombstone then a `Deleted` drop of the handle in one batch (io-pimdir c5e2c65), which the store applies in order (log 2026-10-08-ordered-write-batch); the bridge workaround that wrote the drop as a second batch is gone.
- **A trash delete knows UIDPLUS at staging.** A mail session reports whether it erases one message alone (`expungesOne`), recorded per account at each walk; without it a trash delete stages `\Deleted` in place, the row staying marked. An account no session recorded yet reads as erasing.
- **The sent copy lands by `Message-ID`.** Before a page reaches the engine, an arrival carrying the `Message-ID` of a pending create in that mailbox is named by the create's key, so the engine lands the create on it rather than pushing a second copy.

## Risks

- **Landing by link id (task 0).** The sent copy relies on io-pimdir landing an arrival named by the create's own (unminted) key, then dropping the provisional handle as superseded. The move's destination is derived from a pending create under the identity's key or the key minted from it once (`dup:<key>#<provisional>`, STORAGE §9).
- **A provider rewriting the `Message-ID`** of a sent message would leave the staged sent copy pending beside the provider's own (Gmail, Graph); on IMAP it would be appended as a second copy if the server also files sent mail itself and lists it only after the push. Checked live on Gmail, Graph and Fastmail test accounts, never a personal account.
- **Unpushable creates wait visibly.** A sent copy on Gmail or Graph whose arrival never lands stays marked pending; a JMAP account stages none, sending from JMAP being unsupported yet.

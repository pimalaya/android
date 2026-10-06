---
cairn: tasks
id: incremental-enumeration
---

# Tasks

## WebDAV

- [x] The RFC 6578 sync-collection driver moves beside the shared runners; both DAV backends call it.
- [x] `sync_caldav_events` and `multiget_caldav_events`, mirroring the card verbs.
- [x] `syncEvents` and `multigetEvents` natives, and the `EventDelta` they answer.
- [x] `CalendarEngine` enumerates from the cursor and fetches what the merge names.

## IMAP

- [x] `enumerate` on a `(UIDVALIDITY, HIGHESTMODSEQ)` cursor: QRESYNC where the server has it, a windowed `UID FLAGS` spine otherwise.
- [x] `fetch_envelopes` takes the UIDs to read rather than a window.
- [x] `listMailboxes`, `enumerateMailbox` and `fetchEnvelopes` natives replace `syncMail`.
- [x] `MailEngine` drops the pre-walk and services the yields as they come.

## Closing

- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green, `cargo fmt` and `clippy` run.
- [x] Spec folded, log written, CHANGELOG entry added.

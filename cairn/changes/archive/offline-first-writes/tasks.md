---
cairn: tasks
id: offline-first-writes
---

# Tasks

## The bridge

- [x] `MutationJson` gains `setFlags`, `remove`, `add` and `move`, mapped onto `PimdirMutation`.
- [x] `syncMail` answers the mailbox roster with its RFC 6154 roles beside the messages.
- [x] `submitMessage` submits one stored RFC 5322 message: the `Bcc` header becomes the envelope's blind recipients and leaves the bytes, which are then filed in `\Sent`.

## The engines

- [x] `PimdirEngine`, the storage half every driver shares (load, lookup, write, the push result shape).
- [x] `OfflineEngine` sits on it, keeping its tally through the write hook.
- [x] `MailEngine`: one account walk per pass, `enumerate` and meta `fetch` off that cache, full `fetch` reads the message, push writes markers by diffing `base_flags` and services a removal with the trash move.
- [x] `CalendarEngine`: `enumerate` from `listEvents` with the bodies in hand, push creates, updates and deletes guarded by the base ETag.

## The store

- [x] `PimdirStorage` answers a binding's `base_flags`, which the marker diff reads.
- [x] `PimdirStorage` loads a staged removal back as the tombstone the engine derives a remove from, and an upsert honours one. Not foreseen: without it no domain's delete could reach a server.
- [x] `MailStore` keeps the outbox collection across a roster replace, and lists its rows as pending.
- [x] `EventStore` drops `replaceEvents` and `replaceEvent`; the engine writes the collection.

## The app

- [x] The mail store remembers each account's trash mailbox, refreshed by every walk. Landed beside the roster rather than on `AccountConnection`, which would have threaded a string the sync writes through the onboarding flow and the credential store; the sent mailbox is not stored at all, the submission resolving it server-side and nothing offline deciding on it.
- [x] `MessageView` stages the markers and the delete, and returns without a round trip.
- [x] `EventView` stages the create, the update and the delete.
- [x] `MessageCompose` composes and stages into the outbox.
- [x] `MainActivity.syncMail` runs the engine per mailbox and drains the outbox first.
- [x] `MainActivity.syncCalendars` runs the engine per calendar.
- [x] The strings a pending row and a drained outbox need.

## Closing

- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green, `cargo fmt` run.
- [x] Spec folded, log written, CHANGELOG entry added.

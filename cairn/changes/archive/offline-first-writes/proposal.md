---
cairn: change
id: offline-first-writes
status: landed
created: 2026-09-08
---

# Every action is a local write; only a sync needs the network

## Why

Contacts are offline first and the other two domains are not. Saving a contact stages into the store and returns, and the next sync pushes it; the network is reached by the sync alone. Mail and calendar do the opposite: they call the server first and write the store only once it answered, so with the radio off marking a message read, flagging one, deleting one, editing a calendar entry, creating one, deleting one and sending a message all fail outright. Nothing was wrong with the store, nothing the user asked for was ambiguous, and the app still refused, which is the premise of an offline-first client broken in six places.

The store is not what stands in the way. It is io-pimdir's, it already carries `bindings` with a base revision, a base flag set and a conflict, and it already holds a mail spine and a calendar's objects. What is missing is that mail and calendar never run the engine: a refresh calls `replaceMessages` or `replaceEvents` and overwrites the collection wholesale, which is exactly the write that would erase a staged edit, so staging one was never safe. `cairn/spec/offline-store.md` says as much: mail and calendar are read-only mirrors that carry no staged edit and no conflict.

The engine already models everything the two domains need. `PimdirMutation` has `SetFlags`, `Remove`, `Edit`, `Add` and `Move`, and the push wire already carries all four change kinds. The bridge exposes one of them, `Edit`, because contacts is all that ever staged.

## What

Retire the read-only mirror. Mail and calendar reconcile through io-pimdir's sync the way contacts do, every user action becomes a local mutation, and the network is reached by a sync pass and by fetching a message body, and by nothing else.

**The bridge learns the rest of the mutations.** `MutationJson` gains `setFlags`, `remove`, `add` and `move` beside `edit`, which is a translation and no new engine behaviour.

**Mail runs the engine.** A driver per account services the collection yields against the store and the remote yields against the account: one `syncMail` walk per pass feeds every mailbox's `enumerate` from a cache, so the account is still authenticated once and not once per mailbox; `fetch` at the meta tier answers from that same cache with no body, since a message's handle is its link id and its summary comes off the envelope; `fetch` at full reads the message. Push writes the markers by diffing the change's flag set against the binding's `base_flags`, so the app never replaces a keyword set it does not model, and services a removal with the existing trash move.

**Calendar runs the engine.** One `listEvents` per calendar is the `enumerate`, and it carries the bodies, so `fetch` costs nothing; push is the create, the update guarded by the base ETag and the delete guarded by it.

**A mail account carries where its trash is.** The delete has to know offline whether the account has a mailbox marked `\Trash` (RFC 6154), because that is what decides between a move, which takes the row out of the list, and a `\Deleted` marker, which leaves it there saying so. The mailbox roster the walk already builds gains its RFC 6154 roles and the account stores them beside the endpoint it submits through.

**Sending is an outbox.** The composer composes RFC 5322 on the device, which needs nothing but the draft, and stages the message into the account's outbox, a local collection no server enumerates. A sync drains it: each message is submitted, a copy is filed in `\Sent` and the outbox row goes. The stored copy carries its `Bcc` header, which RFC 5322 section 3.6.3 provides for exactly this, and the bridge strips it into the envelope's blind recipients on the way out, so a blind copy is neither lost nor disclosed.

**The reader stops calling the server.** `MessageView`, `EventView` and `MessageCompose` stage and return. What they used to report, a refusal from the server, is reported by the sync that carries the push instead.

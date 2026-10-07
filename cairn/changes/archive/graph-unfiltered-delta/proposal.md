---
cairn: change
id: graph-unfiltered-delta
status: landed
created: 2026-10-07
---

# Graph mail: one unfiltered delta link, mail listed by band

## Why

short-first-sync left Graph widening by relisting: a message delta link made under `$filter=receivedDateTime ge X` only ever reports changes for that filter, and Graph's delta accepts `receivedDateTime ge` alone (no upper bound), so every widening relisted the whole wider scope. Filling a mailbox of `n` messages 500 at a time listed about `n²/1000` messages: unworkable on a large mailbox.

## What

Decided by Clément: the Graph mail connector is bound to no scope, as IMAP, Gmail and JMAP are.

1. **One delta link per mailbox, with no filter.** `$select=id,sentDateTime,isRead,flag`, `Prefer: odata.maxpagesize=1000` on every request. Its first pass names every message of the folder by id once (1,254 ids in under a second on the test tenant); every delta after it reports a change whatever the message's date. A change dated out of the current scope is dropped (the `sentDateTime` check, as the other connectors check the `Date` header), a removal applies whatever the date, and a change in scope is listed by id and markers alone: the store keeps the summary of a message it binds, and the driver reads any other one by id with the summary `$select`, `$batch` of 20.
2. **Mail listed by band.** A first chunk, a scroll widening and the fill list only the band they lack by `/messages` (`$filter=sentDateTime ge A and sentDateTime lt B`, exact on the `Date` header, `$orderby=sentDateTime desc`, the summary `$select`, `$top` 1,000). A page resumes below the oldest `Date` the pages before reached, that second included and its messages already listed skipped, rather than by Graph's `$skip` next link, which loses a message whenever one above it goes during the listing.
3. **`scope_bound` false** for Graph mail: io-pimdir runs band rounds for widenings and keeps the checkpoint.
4. **First-sync ordering.** The dialog waits for the first chunk only. A mailbox's first round lists its band by `/messages` and closes with no checkpoint. The next pass finds the scope covered and no checkpoint, so io-pimdir opens a round over it: that round walks the delta's first pass, each Graph page a round page (resumable from Graph's next link), the members in scope listed by id, the last page carrying the delta link as the checkpoint. The first pass is the folder as it ends, so a message deleted, moved, read or arrived between the chunk and the link is read there, a deletion as an absence from the round. The driver tells the connector which round it is by `covered` (the store's coverage holds the scope).
5. **Migration.** A delta link made under a filter (short-first-sync) and a round cursor from then are refused, so the next pass makes the unfiltered link without relisting the band.

## Rejected

- One round for both (the band's pages, then the delta's first pass on the same round): a message listed by a band page and deleted before the first pass reaches it would stay stamped by the round, so never found absent, and Graph's delta would never report it, having never named it.
- The delta's first pass before the band: the dialog would wait on the whole folder's ids.

## Out of scope

- Undated mail and band rounds: io-pimdir f9b13f8 drops a bound undated message when a band round closes (a band listed by date never lists it). Fixed in io-pimdir; a test here pins the behaviour, ignored until the bridge moves to it.

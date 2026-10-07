# Performance notes

Living notes on sync throughput and surprising counts. Nothing here is a correctness or data-loss issue; it is speed and things that look off and want a closer look later.

## Per-contact pushes

The io-offline engine hands the driver one WantsPush yield per collection round carrying the whole change list (sync.rs collects the round's pushes before yielding), so batching is entirely a client-adapter concern; an earlier revision of this note wrongly blamed the seam. What each backend does with the round today:

- Google People: creates and deletes group into people.batchCreateContacts (200 a call) and people.batchDeleteContacts (500 a call), one write-quota unit per call, so an import of 150 contacts is one request where it used to be 150 sequential people.createContact calls (which also brushed the 90-writes-per-minute quota). Updates stay per-card on purpose: batchUpdateContacts shares one updateMask across the whole batch (a clientData-carrying card would clobber the others' stash handling), needs a people.get-flavoured etag per person (the list etag the engine holds is rejected, see the per-card retry in update_google_card), and fails as a unit, so per-card guarded updates with the existing etag-gap retry stay the safer shape. Post-cascade-fix, mass update rounds should no longer occur anyway.
- CardDAV: no batch verb exists in the standards (RFC 6352 batches reads only via addressbook-multiget; RFC 5995's POST add-member is still one resource per request; Apple's CalendarServer bulk POST extension died unstandardized and unimplemented elsewhere), so the round parallelizes instead: up to 4 pushes in flight, each its own guarded PUT or DELETE on its own connection, failures and 412 handling unchanged per request.
- JMAP: the whole round rides ContactCard/set calls (50 changes each): creates, content updates, membership patches and destroys share one request, and the per-object response maps back to per-change results, so a rejected object reports rejected instead of failing the round.
- Graph: the round rides $batch calls (20 inner requests each, the endpoint's hard limit), creates as POST, updates as delta-trimmed PATCH, deletes as DELETE, with per-item statuses mapping back likewise (a 404 on a delete reads as already converged).

## Full push count when disabling phone mirroring

Observed: unchecking a book's local (phone) mirror still runs a sync for a while and reports ~150 pushes at the end, with nothing edited. No data loss, just an unexpected full-set push.

Explanation (confirmed by reading the axis code): setBookState clears the phone axis when mirroring goes off (the data-loss fix, see docs/phone-sync-plan.md), so a phone pass that still runs before the spoke tears down sees every card as never-projected (status created) and re-adds the full set; the pushes hit the phone axis only (local, cheap), not the server. The remaining polish would be skipping the phone passes as soon as the mirror flag is off rather than while the Android account still exists.

## Mail listing page sizes (unmeasured here)

The whole-mailbox listing (cairn change full-mail-index, on pimdir's `scoped-mail-sync`) pages every backend: IMAP 500 UIDs per `UID FETCH` of `FLAGS`, `RFC822.SIZE` and nine header fields (no `BODYSTRUCTURE`), Graph 1,000 messages per `/messages` page and 1,000 ids per page of its unfiltered message delta (graph-unfiltered-delta), Gmail 100 ids per `messages.list` with one paced metadata read each, JMAP 500 per `Email/query` capped by `maxObjectsInGet`; the list reads 50 rows a page and keeps 24 pages. These are the joint plan's defaults. The only measurement behind them is Graph's on the test tenant (2026-10-07, recorded in pimdir's `scoped-mail-sync` proposal: the summary `$select` honours 1,000 a page, first page in under 3 s); nothing was measured on IMAP, Gmail or the store over JNI from this repository, so the three benchmark items of full-mail-index stay open, carried by the joint plan's task 0.

Things to look at once there are numbers:

- A server without QRESYNC answers every pass with a round, so every pass relists the scope's headers; a CONDSTORE-only delta (`CHANGEDSINCE` plus a UID search for expunges) would spare it.
- Gmail reads metadata one request per message (io-gmail has no batch endpoint): about 40 a second, so an unbounded 100k-message account takes about 40 minutes of metadata on its first pass, the newest first.
- A list page read from an offset (a fling far from any loaded page) reads the canonical statement as a subquery and pays for the rows it skips.

## Where a first round's time goes (short-first-sync, 2026-10-07)

The owner's device log (Microsoft 365 test account, about 1 GB, 3,748 messages in 12 mailboxes) had the first mail pass at 65 s, of which Graph's network share was about 20 s at 1,000 a page. The rest is the bridge and the store, so every mail page now logs where its time goes (`PimdirEngine.Clock`, `D/pimalaya: page ...`): the remote call (network and connector), the JSON this side reads and writes, the engine between a reply and its next yield (the Rust parse of the page and the merge included), and the store's loads and writes.

`MailBridgeClockTest` replays a first round of 4,000 named messages in four pages of 1,000 through the real bridge and the real store, the pages read from the string the native call returns, so the clock covers everything but the network. On the build host (Robolectric's SQLite, the bridge built in release; three runs each):

| | JSON (Java) | engine (Rust, JNI) | store (Java SQLite) | total |
|---|---|---|---|---|
| before | 60 ms | 153 ms | 1,220 ms | 1,435 ms |
| after | 55 to 63 ms | 182 to 203 ms | 987 to 1,006 ms | 1,250 ms |

The store dominates, about 85% of the non-network time; the JSON crossing is under 5% and the engine about 10%. Rust writing the page into the store directly is not open on Android, where the store is the platform's SQLite behind Java and the bridge compiles no SQLite of its own (the smallest-binary choice behind `PimdirSql`), so the cut went into the writes: a new message no longer reads back a binding it cannot have (its item did not exist, and a binding cascades with its item), replaces addresses it has none of, or restamps an item its own insert stamped; the single-value reads of a write batch are compiled statements rebound per message rather than a cursor each; the canonical statements' `:name` rewrite is done once per statement rather than per bind; the connection's statement cache holds 100 rather than 25. Per new message the write went from about 13 statements to 7 plus one per address.

The engine went up by 30 ms: the store's load now carries each message's date (see the log entry), which the bridge turns into date-only summaries it never writes back.

Unmeasured on a device: the cursor windows the compiled reads save are an ART cost the host does not reproduce, so the device should gain more than the host's 18%. The bigger saving is the first pass itself: 50 messages a mailbox where it was all of them.

## Graph cost model (graph-unfiltered-delta, 2026-10-07)

Modelled, not measured on a device. A Graph mailbox of `n` messages, the account filling it to `m` (`m <= n`) by chunks:

- **First chunk** (the dialog): one `/messages` page, 50 messages with the summary `$select`, `sentDateTime` filtered and ordered; nothing else. No delta is asked for, so the dialog's cost does not grow with the folder.
- **The pass that makes the link**: the folder's unfiltered delta, its first pass naming all `n` messages by id, `sentDateTime` and markers, 1,000 a page asked for (`ceil(n / 1000)` requests, more if Graph cuts a page shorter than asked, 512 having been reported), one round page each, resumable from Graph's next link. The test tenant answered 1,254 ids in under a second. What lies in scope is bound already and costs no read; an arrival since the chunk is read by id, 20 to a `$batch`.
- **Each widening or fill step**: one `/messages` page of the band alone, `ceil(chunk / 1000)` requests. Filling to `m` lists `m` messages in all, where the delta link made under a filter listed about `m²/1000` (an inbox of 2,000 filled 500 at a time: 500 + 1,000 + 1,500 + 2,000 = 5,000 summaries, now 2,000).
- **Each pass after that**: one delta request (more pages only when more than 1,000 changes wait), reporting changes whatever their date; a change out of scope is dropped, a removal applied, a change to a stored message applied with no read, a new message in scope read by id (one `$batch` per 20).
- **An expired link (410)**: the link-making round again, `ceil(n / 1000)` id pages, and no band relisted.

The cost moved from summaries relisted on every widening to one pass of ids over the whole folder, paid once per link: on a 50,000-message folder about 50 to 100 small requests behind the first dialog, against about 2.5 million summaries listed to fill it by the old filtered link.

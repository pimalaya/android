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

The store dominates, about 85% of the non-network time; the JSON crossing is under 5% and the engine about 10%. Rust writing the page into the store directly is not open on Android, where the store is the bundled SQLite behind Java and the bridge compiles no SQLite of its own (the smallest-binary choice behind `PimdirSql`), so the cut went into the writes: a new message no longer reads back a binding it cannot have (its item did not exist, and a binding cascades with its item), replaces addresses it has none of, or restamps an item its own insert stamped; the single-value reads of a write batch are compiled statements rebound per message rather than a cursor each; the canonical statements' `:name` rewrite is done once per statement rather than per bind; the connection's statement cache holds 100 rather than 25. Per new message the write went from about 13 statements to 7 plus one per address.

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

## Mailboxes side by side (parallel-mailboxes, 2026-10-07)

The owner's device, the first-sync dialog of a Microsoft 365 account (12 mailboxes, 326 messages): remote 6.8 s, one mailbox after another (60 to 400 ms each, 3.7 s for *Deleted Items*), JSON 84 ms, engine 35 ms, store 185 ms. An account's mailboxes now run on a pool of sessions (Graph and JMAP 4, IMAP 3, Gmail 2), the store kept to one writer, so the dialog should take about the longest mailbox, or the remote total over the pool size where that is longer: about 3.7 s here, *Deleted Items* alone, where it was 6.8 s. Each run logs `mail pass <account>: N mailboxes on K sessions in W ms, remote summed R ms`, the line to compare on a device. On the build host (`MailPoolTest`, 120 ms a request, two requests a mailbox): 12 mailboxes in about 0.9 s on 4 sessions against 2.9 s in a row.

## Calendars in a few requests, side by side (calendar-sync-speed, 2026-10-07)

The owner's device, first visit to the Calendar tab of a Microsoft 365 test account: three Graph calendars, 23 events in about 10 s, 5 in 0.9 s, 90 in 11 s, so about 22 s for 118 events. Calendar passes had no timing then; the code says where it went. A pass lists a calendar (one `/events` request, 500 a page, names and `changeKey` only) and names every entry it does not hold at its revision by reading it, and Graph read one `GET /me/events/{id}` an event plus one `/instances` listing per window of each series (windows of at most five years: three for an open series such as a birthday), one calendar after another on one transport. That is one request a lone event and four an open series, about 190 for the owner's 118 events, at roughly 110 to 190 ms each. The JSON, engine and store shares are the mail pass's (a few milliseconds a page of 40), and no calendar is projected into the phone's calendar provider, so there was no provider write to batch.

The cost model now, a Graph calendar of `n` events of which `s` are series with `w` windows each, every one new:

- **Before**: `1 + n + s·w` requests in a row, and the account's calendars one after another.
- **After**: `1 + ceil(n / 20)` (more by one for each read of 64 the engine splits, `named` reading 64 at a time) `+ ceil(s·w / 20)` requests, plus one per extra page of a window; the account's calendars side by side on three transports, so the pass takes about its longest calendar.
- **A later pass**: one listing a calendar, and `ceil(changed / 20)` `$batch` calls for what changed, where it was one request a changed event.
- **Google and JMAP**: the listing they made anyway (Google with `showDeleted`, its pages of 2,500) carries every entry whole, so a pass reads nothing more; Google used to read each event and each series' instances again, JMAP re-listed the calendar for every 64 entries.

On the build host (`CalendarPoolTest`, 20 ms a request, the real calendar driver, engine and store; three runs): 120 events over three calendars took 2.57 s read one by one in a row (123 requests) and 103 to 111 ms batched side by side (9 requests), the network summed 2.49 s against 0.18 s. `graph_calendar_tests` counts the same 120 events at 9 requests against 165 when 15 of them are open series.

Expected on the device, unmeasured: the owner's three calendars in about 2 to 7 requests each (the 23-event calendar, if all series, 7: its listing, two batches of events and four of windows), side by side, so the pass should take about one calendar's 7 round trips where it took 190 in a row. A `$batch` round trip costs more than a single `GET` (Graph serves its inner requests side by side, bounded per mailbox), so a gain of about 4 to 6 times (22 s to 4 or 5 s) is the cautious reading; each run's `calendar pass` line and the per-page lines are the numbers to compare.

Things to look at once there are numbers: a `$batch` inner request throttled (429) is read again alone, where the transport waits as long as Graph asks, so a heavily throttled tenant falls back towards the old request count; `named` asks 64 a time, so a large first pass sends one short batch of 4 per 64 events.

## Graph contacts read 20 to a batch (graph-contact-reads, 2026-10-07)

Found by reading the code, not measured on a device. A Graph contacts delta row carries an id and a `changeKey` and no body (a delta query cannot `$expand` the stash property the vCard projection reads), so a pass reads the body of every contact it does not hold at its revision. A complete round primes those bodies with one full listing (`/contacts`, 100 a page, stash expanded); an incremental round read each changed contact with its own `GET /me/contacts/{id}`, one after another.

The cost model, a Graph book of which `c` contacts changed since the last pass:

- **Before**: one delta request, then `c` requests in a row.
- **After**: one delta request, then `ceil(c / 20)` `$batch` calls, one more for each read of 64 the engine splits (`named` asks 64 at a time, four batches of 20, 20, 20 and 4): 120 changed contacts in 7 batches where they were 120 requests.
- **A complete round**: unchanged, the listing in `ceil(n / 100)` pages, and only a contact created between the delta and the listing read, now in a batch.

Each Graph fetch logs `graph fetch <book>: N asked, H from the listing, M read in B batches, remote T ms, G gone`, and contact pages now log the per-page clock line (network, JSON, engine, store), so a bulk change shows on a device. As with calendars, an inner request throttled (429) is read again alone, the transport waiting as long as Graph asks, so a heavily throttled tenant falls back towards one request a contact. The listing's `$top=100` stays: Graph documents no larger contacts page with an extended-property `$expand`, and nothing here measured one.

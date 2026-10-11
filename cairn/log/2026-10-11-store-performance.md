---
cairn: log
change: store-performance
landed: 2026-10-11
---

# Store performance: reads proportional to what they ask

Capabilities moved: offline-store (added: a scoped load reads what it names, a store write is never read through a cursor, a deep read seeks where it lands), mail (added: a step asks the store which of a page it holds; modified: a message body is read through the bridge, bodies cross the bridge as files), calendar (added: the agenda builds off the main thread). App side of the 2026-10-11 P1/P2 audit; io-pimdir and pimdir untouched, their part proposed in the change's tasks.md (L1 to L6). The change stays active until the library proposals land and `below`/`unjoined` can go.

**Numbers** (host, the audit's cached stores, release bridge, median of 5, before and after on the same host the same day):

| Read | 100k before / after | 1M before / after |
|---|---|---|
| load of a 1000-handle scope (inbox of 27.5k / 275k) | 328 / 8.0 ms | 16 510 / 7.4 ms |
| first round of a 30k mailbox, pages of 1000 | 24.3 s, 164 MiB of load JSON / 6.0 s, 5.7 MiB | |
| first round of a 100k mailbox | | 25.9 s (its last page's 9.8 s is io-pimdir's whole load, L3) |
| keyset page at 98 % | 25.1 / 6.5 ms (raw statement 0.2 ms) | 193.7 / 6.4 ms |
| datesBelow(middle, 50) | 9.8 / 0.22 ms | 94.6 / 0.23 ms |
| bodyRows(all, no window) | 2 107 / 270 ms | 191 062 / 2 700 ms |
| fling to the end (offset / from its day) | 145 / 3.0 ms | 1 534 / 3.3 ms |
| count(all), countByDay, count(unread) | 72 / 15, 115 / 55, 66 / 58 ms | 829 / 149, 1 348 / 587, 735 / 624 ms |
| land's emptiness check | 27.5 / 0.01 ms | 1 084 / 0.01 ms |
| Gmail step: held handles over JNI | 49k handles, 383 KB / one 500-id query per page, 2 ms | 492k handles, 4.3 MB / 3 to 5 ms per page |
| Gmail step wall | 890 / 772 ms | 28.4 / 24.5 s (22 s are io-pimdir's whole loads, L3) |
| bound(account a, 12 months) | 1.5 / 1.5 s | did not finish in 40 min (audit) / 19.2 s, 400k rows |

Body landing (`MailEngine.stored`, 25 / 100 MiB message): Java 26 / 103 MiB to 0, native 73 / 253 to 24 / 176 MiB, 244 / 975 to 142 / 628 ms. Opening a stored body: Java 26 / 103 MiB to 0. `messagePart` of 100 MiB: 251 (audit) to 177 MiB native. Opening one not held now takes the streamed path the audit measured at 37 ms and 0.4 MiB for 25 MiB (was 331 ms, 119 MiB native and 94 MiB Java), and a 100 MiB message opens (was `message is too long`).

**What landed.** `PimdirStorage.scopeClause` binds scope lists through `json_each` (`SCOPE_MAX` gone), a handle scope seeking `bindings_by_handle`; `bound` in one statement; `bindsAny` and `heldOf` replace `handlesOf`. `MailStore.changed` runs `collect_before`, `release_before` and `purge_retained_before` for `changes()` (the old cursor reran the delete for each row past its 87,150-row window: a 250k test hangs the old code, runs in 17 s now). `MailStore.below` states the keyset bound through a flattened `items` common table expression (plan `items_by_sort_global (sort_key>? AND sort_key<?)`, pinned by a test); `MailStore.unjoined` reads the counts without `mail_summary` when the attachment chip is off, a test pinning that the counts read nothing else of it. `MailList.seek` and `MailStore.pageBelow` land a fling from its day. `Native.mailStep` takes the driver; `Client::held` upcalls `serve({op: "held", handles})` per page from the Gmail, JMAP and Graph steps (`GmailSource::held`, `JmapStepReads::held`, `GraphMail::held`); the request lost `held`. `Native.describeMessage` and `Native.parseMessageFile`; `PimdirBlobs.adopt` moves a file into the blob store; `MailEngine.stored` and `MailReader.fetch` (through `MailEngine.sources(session, ..)`) land files that way; `mail::write_part` writes from the parser's buffer; `fetchMessageSource` and the four whole-message reads behind it went. `CalendarList.reload` builds rows on a reader thread, keeping each entry's expansion per week and object.

**Tests.** `StorePerformanceTest` (12), `MailStepTest`/`GraphStepTest` fakes answering `held`, Rust `a_message_is_described_by_one_read` and the step fakes' `held`. R8 keeps `OfflineDriver.serve`, the one upcall the step adds, under the existing rule.

**Left open.** The library proposals (keyset bound, counts and `instr`, delta loads, FTS5 search, owner writes, a canonical scoped load). Audit items 11 to 16 (row overhead, refcount recompute, contacts list, `MailOwed`). The `seen` test still walks `json_each` per row (unread 624 ms at 1M) until L2.

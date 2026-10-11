---
cairn: change
id: store-performance
status: active
created: 2026-10-11
---

# Store performance: reads proportional to what they ask

## Why

The P1/P2 performance audit of 2026-10-11 (synthetic stores of 10k, 100k and 1M messages on the bundled SQLite 3.49, the release bridge) found reads whose cost grows with the store rather than with what they ask for: a listing round quadratic in the mailbox (every page of 500 or 1000 past `SCOPE_MAX` 400 loaded the whole mailbox), a collector reading a `DELETE ... RETURNING` through a cursor (which re-runs the delete on every window refill: narrowing a 425k-message account never finished), keyset pages and the body plan scanning from the newest row down to their own, counts joining a summary no chip asks for, a fling paying for every row it skips, a step carrying every handle the account holds across JNI, a message opened from the server crossing JNI as base64 JSON (119 MiB native for 25 MiB; 100 MiB failing), and the agenda reloading on the main thread.

## What

App-side only; io-pimdir and pimdir are not edited, their part is listed as library proposals in tasks.md.

1. **Scoped loads** (`PimdirStorage.loadCollection`): a scope list binds once as a JSON array through `json_each`, so no length widens it to the whole collection; a handle scope seeks `bindings_by_handle`. `bound` reads the same way, unchunked.
2. **No write through a cursor**: `MailStore.changed` runs a canonical write with its `RETURNING` dropped and counts `changes()`; `collectBefore`, `release` and `DeletedItems.purgeBefore` (whose pins the collector recounts) use it.
3. **Keyset pages seek** (`MailStore.below`): a keyset read states the bound its cursor implies on the sort key through a common table expression named `items` that SQLite flattens into the canonical statement, so `items_by_sort_global` serves `sort_key>? AND sort_key<?`. `page`, `datesBelow` and `bodyRows` use it; `bodyRows(null)` turns linear.
4. **Counts without the summary** (`MailStore.unjoined`): with the attachment chip off, `count_mail`, `count_mail_by_day` and `count_unread` read an empty `mail_summary` in place of the real one, which they only read for that chip (a test pins it).
5. **A fling reads from its day** (`MailList.seek`, `MailStore.pageBelow`): a page with no page above it read starts below the first instant of the day after its first row's, from the per-day counts the layout already holds, skipping only that day's rows ahead of it.
6. **A step asks which of a page the store holds**: `Native.mailStep` takes the driver, and each backend (Gmail, JMAP, Graph) asks it `{op: "held", handles}` per listed page; the request no longer carries `held`. `land`'s emptiness check is an `EXISTS` (`PimdirStorage.bindsAny`), and a Gmail round names its page with the same per-page query.
7. **Opening and landing a body through files**: the reader fetches a message not held through `fetchMessageSources` into a file, like a step; `Native.describeMessage(path)` answers its hash, summary and attachments in one native read; the file is moved into the blob store (`PimdirBlobs.adopt`), never read on the Java side; `Native.parseMessageFile(path)` parses a stored body without a Java copy; `messagePart` writes the decoded part without a second copy. `fetchMessageSource` (base64 JSON) goes. `MailEngine.stored` lands step and body-step files the same way.
8. **Agenda off the main thread** (`CalendarList.reload`): rows built on a reader thread, the last reload winning, each entry's expansion kept while the week and its object stay the same.

## Out of scope

- Search over FTS5 (audit item 5) and the delta load of the whole collection on every delta page and step landing (io-pimdir `sync.rs`, audit item 2): library proposals.
- `instr` for the seen test, the sargable bound and the summary-free counts in the canonical statements themselves: library proposals, after which `below` and `unjoined` go.
- Row overhead (`unsynced` per row), RECOMPUTE_REFCOUNTS after a collector run, the contacts list reading every card, `MailOwed`: audit items 11 to 16, not taken here.

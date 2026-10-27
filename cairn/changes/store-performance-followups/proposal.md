---
cairn: change
id: store-performance-followups
status: active
created: 2026-10-18
---

# Store performance: what store-performance left

## Why

store-performance took the P1/P2 audit's (2026-10-11) items 1, 3, 4, 6, 7, 8, 9 and 12 app-side, and listed 2, 4, 5 and 6 as library proposals; its Out of scope set aside items 11 to 16. Checked against the code on 2026-10-18, these remain, measured in the audit on synthetic stores (host, 16 cores under load; a phone is 3x to 10x slower):

- **#10, the agenda reads every event.** CalendarList.reload is off the main thread now, but still reads and parses every stored event (calendar/CalendarList.java:246, `store.loadEvents()`) on every reload and week change: 10k events cost 171 ms of loading plus 42 ms of expansion, when the event summary already holds dtstart, until and the recurring mark.
- **#11, the roster per page.** MailStore.rows (mail/MailStore.java:890) calls `mailboxes()` (line 1051, `collections.list`, LIST_COLLECTIONS with its coverage aggregate) for every page; at 10k it was most of a 5.3 ms page. The per-row `unsynced` query of the audit is gone.
- **#14, refcounts recomputed whole.** `collectBefore`, `release` and `releaseCrossLinked` (MailStore.java:1416, 1453, 1515) run RECOMPUTE_REFCOUNTS over every object after the collector, in the writer's transaction: 1.3 s at 1M, while the deletes know which objects lost a reference.
- **#15, the contacts list reads every card.** PimdirContacts.list (contact/PimdirContacts.java:95-117) reads each card body from its blob and indexes it over JNI per reload: 330 ms at 10k.
- **#16, MailOwed rewrites its set.** `owe` and `settle` (mail/MailOwed.java:55, 69) copy and rewrite the whole SharedPreferences string set per call: 7 to 15 ms for 50 over 10k owed; it grows with the owed set.
- **PimdirBlobs.get.** `new byte[(int) source.length()]` (pimdir/PimdirBlobs.java:132) overflows past 2 GiB, and its callers (PimdirQueue.java:187, PimdirDb.java:389, PimdirItems.java:80) hold a whole body where `sourceFile` would do.
- **#9 rest, whole-file parses.** `parseMessageFile` and `messagePart` read the stored file whole (rust/src/ffi/mail.rs:341, 396, `fs::read`): a 100 MiB attachment open peaked at 251 MiB native.

## What

- Agenda: prefilter by the event summary (dtstart in the window, or recurring with no until or an until after the window's start) and read only those bodies, through a canonical io-pimdir statement (no SQL in Java).
- Mail list: the roster read once per refresh generation and handed to its pages.
- Collector: refcounts adjusted for the objects the deletes touched (`WHERE hash IN` the touched set), or a canonical statement doing so, instead of RECOMPUTE_REFCOUNTS.
- Contacts list: rows from `contact_summary` and `item_address`, a card parsed only when opened.
- MailOwed: a table in the app's database (or a bounded set), one row written per change.
- PimdirBlobs: `get` refused past `Integer.MAX_VALUE`, readers moved onto `sourceFile` or a stream.
- Bridge: `parseMessageFile` and `messagePart` over a memory map of the file, the part streamed to its target.

## Out of scope

- The pimdir and io-pimdir proposals (sargable keysets, FTS5 search, summary-free counts, delta loads by handle, plan asserts): store-performance's Library proposals, in those repositories.

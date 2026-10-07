---
cairn: change
id: full-mail-index
status: landed
created: 2026-10-07
---

# Every mailbox's metadata is stored whole, and the list loads lazily

## Why

The store holds a window of the newest 50 messages per mailbox, and the list draws one page of 500 rows held in memory, filtered and searched in Java. Older mail is unreachable, and a search or a chip only sees what the page holds.

The obvious alternative, laying the list out from UIDs alone and fetching metadata on scroll, does not fit the merged view: a UID orders messages within one mailbox only, so a list ordered by date across mailboxes and accounts cannot place a row before its date is known. Fetching every message's metadata instead costs once: every pass after the first is a delta (QRESYNC, JMAP state, Graph delta, Gmail history). In exchange there is no viewport hydration, no session held open for scrolling, no skeleton rows, and search, chips and badge cover the whole store offline.

## What

- **Whole mailboxes.** A full round enumerates the whole mailbox, not a window off its end, and a complete round retires only what the mailbox no longer holds. An account option bounds it (all, or the last N months), for Gmail and metered networks.
- **Metadata in the sync.** A new message is filed with its summary and sort key in the same pass, newest first, in chunks committed one transaction each, so the top of the list shows within seconds and an interrupted pass resumes. The probe stage and the meta upgrade after each mailbox go away.
- **A lean bulk fetch.** The bulk IMAP fetch drops `BODYSTRUCTURE`, by far the heaviest item. The attachment mark comes from the `Content-Type` header (`multipart/mixed` reads as carrying an attachment), and is corrected from the body when the message is opened. To be settled by the benchmark below.
- **A lazy list.** The adapter's size is a count; rows load in pages around the scroll position and far pages are evicted. Day sections are placed from one count per day. The filter, the chips and the badge become conditions of the query; search runs in the store (pimdir SEARCH index, `LIKE` as a stopgap). Queries go through io-pimdir over JNI, per the repository convention.
- **Opening a message** is unchanged: store first, fetched with `BODY.PEEK[]` and filed otherwise.

## Where it landed

Built on pimdir's joint plan, `scoped-mail-sync` (io-pimdir 35a1c3f, io-msgraph 00ef069), which won wherever the two differed:

- **The bound is a scope on the `Date` header**, the first day of the month N months back; a provider's received-date filter only narrows a listing, two days below the floor (IMAP `SENTSINCE` one day). Mail outside the bound is never deleted by a sync: narrowing the bound collects what falls below it (`collect_before`), widening it lists the band it lacks (IMAP, Gmail, JMAP) or relists the wider scope (Graph, whose delta link is bound to its filter).
- **Probes are gone from pimdir itself**, not only from this engine: every listed member arrives named, DAV listings carrying the bodies of what is new or changed, 64 a read.
- **The attachment mark** is the source's own flag (Graph, JMAP), else `multipart/mixed`, corrected by the walk of the parts when a message is opened.
- **Graph lists by its filtered message delta** at 1,000 a page with the summary `$select`, not by a plain listing.
- **Pages**: IMAP 500 UIDs per `UID FETCH`, Graph 1,000, Gmail 100 ids per `messages.list` (their metadata read one paced request each, io-gmail having no batch endpoint), JMAP 500 capped by `maxObjectsInGet`; the list reads 50 rows a page, 24 pages cached.

## Benchmark first

Moved to the joint plan's task 0 (pimdir `scoped-mail-sync`), which measured Graph on the test tenant (2026-10-07). Nothing was measured on IMAP, Gmail or the store over JNI for this change: the page sizes are the joint plan's defaults, not numbers from this repository. Before building, the plan was to measure a first pass on a test account (the pimalaya.org Workspace, or a local Dovecot seeded with synthetic mail), never a real account: IMAP throughput with and without `BODYSTRUCTURE`, SQLite insert rate per chunk size, JNI crossing cost. The numbers decide the attachment strategy, the chunk size, and whether Gmail defaults to a bounded window (its `messages.get` costs 5 quota units against 250 per second, about 50 messages a second).

## Out of scope

- A per-mailbox view ordered by UID.
- Server-side search.
- Bodies: still fetched on open only.
